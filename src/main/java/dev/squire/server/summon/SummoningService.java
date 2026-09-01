package dev.squire.server.summon;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import dev.squire.SquireMod;
import dev.squire.server.registry.SquireItems;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Detects the training-dummy ritual used to create a player's first level-0 squire. */
public final class SummoningService {

	private record Pattern(List<BlockPos> blocks, BlockPos feet) { }
	private record Pending(UUID playerId, ServerWorld world, BlockPos head) { }

	private static final Queue<Pending> PENDING = new ArrayDeque<>();

	public static void register() {
		UseBlockCallback.EVENT.register(SummoningService::onUseBlock);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Pending pending;
			while ((pending = PENDING.poll()) != null) {
				ServerPlayerEntity player = server.getPlayerManager()
					.getPlayer(pending.playerId());
				if (player != null && player.getServerWorld() == pending.world()) {
					tryActivate(player, pending.head());
				}
			}
		});
	}

	private static ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player,
			net.minecraft.world.World world, Hand hand, BlockHitResult hit) {
		ItemStack held = player.getStackInHand(hand);
		if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)
				|| !(world instanceof ServerWorld serverWorld)
				|| (!held.isOf(Items.CARVED_PUMPKIN)
					&& !held.isOf(Items.JACK_O_LANTERN))) {
			return ActionResult.PASS;
		}
		// Capture the placing player before vanilla places the head, then inspect the
		// resulting world state after the interaction has completed on the server.
		BlockPos head = new ItemPlacementContext(serverPlayer, hand, held, hit).getBlockPos();
		PENDING.add(new Pending(serverPlayer.getUuid(), serverWorld,
			head.toImmutable()));
		return ActionResult.PASS;
	}

	public static boolean tryActivate(ServerPlayerEntity player, BlockPos head) {
		if (!SquireRuntime.isAlive() || player.isRemoved()
				|| player.getWorld() != player.getServerWorld()) {
			return false;
		}
		ServerWorld world = player.getServerWorld();
		Pattern pattern = findPattern(world, head);
		if (pattern == null) {
			return false;
		}
		var runtime = SquireRuntime.get();
		if (runtime.agentStore().recordOfOwner(player.getUuid()).isPresent()) {
			player.sendMessage(net.minecraft.text.Text.translatable(
				"squire.summon.already_bound"), false);
			return false;
		}
		if (!world.getBlockState(pattern.feet().down()).isSolidBlock(world,
				pattern.feet().down())) {
			player.sendMessage(net.minecraft.text.Text.translatable(
				"squire.summon.no_space"), false);
			return false;
		}

		Map<BlockPos, BlockState> original = new LinkedHashMap<>();
		for (BlockPos pos : pattern.blocks()) {
			original.put(pos.toImmutable(), world.getBlockState(pos));
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
		}
		try {
			var avatar = runtime.summonFirstAt(player, pattern.feet());
			if (avatar == null) {
				restore(world, original);
				return false;
			}
			ItemStack bell = SquireItems.boundRecallBell(player.getUuid(), avatar.agentId());
			runtime.syncRecallBellDisplay(bell,
				dev.squire.server.item.BellTier.COMMON);
			if (!player.giveItemStack(bell)) {
				player.dropItem(bell, false);
			}
			world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
				avatar.getX(), avatar.getBodyY(0.5), avatar.getZ(), 24,
				0.55, 0.9, 0.55, 0.08);
			world.playSound(null, avatar.getBlockPos(), SoundEvents.BLOCK_BELL_USE,
				SoundCategory.PLAYERS, 1.0f, 1.15f);
			return true;
		} catch (RuntimeException failure) {
			restore(world, original);
			SquireMod.LOGGER.error("[Squire] training-dummy summon failed", failure);
			player.sendMessage(net.minecraft.text.Text.translatable(
				"squire.summon.failed"), false);
			return false;
		}
	}

	private static Pattern findPattern(ServerWorld world, BlockPos head) {
		BlockState headState = world.getBlockState(head);
		if (!headState.isOf(Blocks.CARVED_PUMPKIN)
				&& !headState.isOf(Blocks.JACK_O_LANTERN)) {
			return null;
		}
		BlockPos centre = head.down();
		if (!world.getBlockState(centre).isOf(Blocks.HAY_BLOCK)) {
			return null;
		}
		BlockPos feet = centre.down();
		if (!world.getBlockState(feet).isIn(BlockTags.FENCES)) {
			return null;
		}
		for (Direction axis : List.of(Direction.EAST, Direction.SOUTH)) {
			BlockPos left = centre.offset(axis);
			BlockPos right = centre.offset(axis.getOpposite());
			if (world.getBlockState(left).isIn(BlockTags.WOOL)
					&& world.getBlockState(right).isIn(BlockTags.WOOL)) {
				return new Pattern(List.of(head.toImmutable(), centre.toImmutable(),
					left.toImmutable(), right.toImmutable(), feet.toImmutable()),
					feet.toImmutable());
			}
		}
		return null;
	}

	private static void restore(ServerWorld world, Map<BlockPos, BlockState> blocks) {
		blocks.forEach((pos, state) -> world.setBlockState(pos, state, Block.NOTIFY_ALL));
	}

	private SummoningService() {
	}
}
