package dev.squire.server.command;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.ParseResults;

import dev.squire.server.world.ProtectionAdapter;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Single execution path for server-compiled commands.
 *
 * <p>Short commands execute directly through Brigadier. Commands longer than the
 * vanilla chat-command limit execute through a temporary command block. The
 * carrier is created with a short, server-compiled {@code /setblock} command in
 * an allowed air cell, and the previous block state is restored in {@code finally},
 * including block-entity data.</p>
 *
 * <p>This class never accepts model-authored raw commands. Callers must compile
 * typed, registry-validated intents before reaching this boundary.</p>
 */
public final class CommandRunner {

	private static final Logger LOG = LoggerFactory.getLogger(CommandRunner.class);

	/** Vanilla's historical chat command limit; longer commands use a block carrier. */
	public static final int INLINE_LIMIT = 256;

	private static final int SCRATCH_SEARCH_RADIUS = 3;

	private CommandRunner() {
	}

	public record Outcome(boolean success, int affected, String detail,
			boolean viaCommandBlock) {

		public static Outcome ok(int affected, boolean viaCommandBlock) {
			return new Outcome(true, affected, null, viaCommandBlock);
		}

		public static Outcome fail(String detail, boolean viaCommandBlock) {
			return new Outcome(false, 0, detail, viaCommandBlock);
		}
	}

	/**
	 * Execute a command, preserving {@code @s} as the supplied bound entity on both
	 * the inline and command-block paths.
	 */
	public static Outcome run(MinecraftServer server, ServerWorld world,
			BlockPos scratchHint, UUID actor, ProtectionAdapter protection,
			Entity boundEntity, String command) {
		if (server == null || world == null) {
			return Outcome.fail("COMMAND_CONTEXT_MISSING", false);
		}
		if (command == null || command.isBlank()) {
			return Outcome.fail("EMPTY_COMMAND", false);
		}
		String trimmed = command.startsWith("/") ? command.substring(1) : command;
		if (!requiresCommandBlock(trimmed)) {
			return runInline(server, world, scratchHint, boundEntity, trimmed);
		}
		return runViaCommandBlock(server, world, scratchHint, actor, protection,
			boundEntity, trimmed);
	}

	/** Backwards-compatible overload for commands that do not use {@code @s}. */
	public static Outcome run(MinecraftServer server, ServerWorld world,
			BlockPos scratchHint, UUID actor, ProtectionAdapter protection,
			String command) {
		return run(server, world, scratchHint, actor, protection, null, command);
	}

	/** Pure routing predicate, exposed so the boundary can be regression-tested. */
	public static boolean requiresCommandBlock(String command) {
		if (command == null) {
			return false;
		}
		int start = command.startsWith("/") ? 1 : 0;
		return command.length() - start > INLINE_LIMIT;
	}

	private static Outcome runInline(MinecraftServer server, ServerWorld world,
			BlockPos at, Entity boundEntity, String command) {
		BlockPos origin = at == null ? BlockPos.ORIGIN : at;
		ServerCommandSource source = server.getCommandSource()
			.withWorld(world)
			.withPosition(Vec3d.ofCenter(origin))
			.withLevel(2)
			.withSilent();
		if (boundEntity != null) {
			source = source.withEntity(boundEntity);
		}
		CommandManager manager = server.getCommandManager();
		ParseResults<ServerCommandSource> parsed =
			manager.getDispatcher().parse(command, source);
		if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()) {
			return Outcome.fail("COMMAND_PARSE_FAILED", false);
		}
		try {
			return Outcome.ok(manager.getDispatcher().execute(parsed), false);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			return Outcome.fail(e.getMessage(), false);
		}
	}

	private static Outcome runViaCommandBlock(MinecraftServer server,
			ServerWorld world, BlockPos hint,
			UUID actor, ProtectionAdapter protection, Entity boundEntity,
			String command) {
		ProtectionAdapter guard = protection == null
			? ProtectionAdapter.ALLOW_ALL : protection;
		BlockPos pos = findScratchPos(world, hint, actor, guard);
		if (pos == null) {
			return Outcome.fail("NO_ALLOWED_SCRATCH_SPACE", true);
		}

		BlockState previousState = world.getBlockState(pos);
		BlockEntity previousEntity = world.getBlockEntity(pos);
		NbtCompound previousNbt = previousEntity == null ? null
			: previousEntity.createNbtWithIdentifyingData();

		try {
			String createCarrier = "setblock " + pos.getX() + " " + pos.getY()
				+ " " + pos.getZ() + " minecraft:command_block";
			Outcome created = runInline(server, world, pos, null, createCarrier);
			if (!created.success()) {
				return Outcome.fail("COMMAND_BLOCK_CREATE_FAILED: " + created.detail(), true);
			}
			if (!(world.getBlockEntity(pos) instanceof CommandBlockBlockEntity block)) {
				return Outcome.fail("COMMAND_BLOCK_MISSING", true);
			}
			String carried = bindEntity(boundEntity, command);
			block.getCommandExecutor().setCommand(carried);
			block.getCommandExecutor().setTrackOutput(true);
			block.setAuto(false);
			block.markDirty();

			boolean ran = block.getCommandExecutor().execute(world);
			int affected = block.getCommandExecutor().getSuccessCount();
			if (!ran || affected <= 0) {
				String output = block.getCommandExecutor().getLastOutput() == null ? ""
					: block.getCommandExecutor().getLastOutput().getString();
				return Outcome.fail(output.isBlank()
					? "COMMAND_BLOCK_FAILED" : output, true);
			}
			return Outcome.ok(affected, true);
		} catch (RuntimeException e) {
			LOG.warn("[command] command-block execution threw at {}", pos, e);
			return Outcome.fail(e.getMessage() == null
				? e.getClass().getSimpleName() : e.getMessage(), true);
		} finally {
			restore(world, pos, previousState, previousNbt);
		}
	}

	/** Command blocks have their own source; wrap the command to preserve {@code @s}. */
	private static String bindEntity(Entity boundEntity, String command) {
		if (boundEntity == null) {
			return command;
		}
		return "execute as " + boundEntity.getUuidAsString()
			+ " at @s run " + command;
	}

	private static void restore(ServerWorld world, BlockPos pos, BlockState state,
			NbtCompound entityNbt) {
		try {
			world.removeBlockEntity(pos);
			world.setBlockState(pos, state, net.minecraft.block.Block.NOTIFY_ALL);
			if (entityNbt != null) {
				BlockEntity restored = world.getBlockEntity(pos);
				if (restored != null) {
					restored.readNbt(entityNbt);
					restored.markDirty();
				}
			}
		} catch (RuntimeException e) {
			LOG.error("[command] failed to restore scratch block at {}", pos, e);
		}
	}

	/** Find the nearest air cell that the shared protection adapter permits. */
	private static BlockPos findScratchPos(ServerWorld world, BlockPos hint,
			UUID actor, ProtectionAdapter protection) {
		BlockPos base = hint == null ? BlockPos.ORIGIN : hint;
		for (int r = 0; r <= SCRATCH_SEARCH_RADIUS; r++) {
			for (int dy = 0; dy <= SCRATCH_SEARCH_RADIUS; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						BlockPos candidate = base.add(dx, dy + 1, dz);
						if (world.isInBuildLimit(candidate)
								&& world.getBlockState(candidate).isAir()
								&& protection.canPlace(world, candidate, actor).allowed()) {
							return candidate;
						}
					}
				}
			}
		}
		BlockPos high = new BlockPos(base.getX(), world.getTopY() - 2, base.getZ());
		return world.isInBuildLimit(high) && world.getBlockState(high).isAir()
			&& protection.canPlace(world, high, actor).allowed() ? high : null;
	}
}
