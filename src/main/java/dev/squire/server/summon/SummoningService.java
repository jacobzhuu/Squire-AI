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
import net.minecraft.item.Item;
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
	private record Pending(ServerPlayerEntity player, ServerWorld world, BlockPos head,
			BlockState before, Item item, int heldCount, Hand hand) { }

	private static final int MAX_PENDING_ACTIVATIONS = 128;
	private static final Queue<Pending> PENDING = new ArrayDeque<>();

	public static void register() {
		UseBlockCallback.EVENT.register(SummoningService::onUseBlock);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Pending pending;
			while ((pending = PENDING.poll()) != null) {
				ServerPlayerEntity player = pending.player();
				if (!player.isRemoved() && player.getServerWorld() == pending.world()
						&& placementSucceeded(player, pending)) {
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
		if (PENDING.size() >= MAX_PENDING_ACTIVATIONS) {
			return ActionResult.PASS;
		}
		BlockPos head = new ItemPlacementContext(serverPlayer, hand, held, hit).getBlockPos();
		PENDING.add(new Pending(serverPlayer, serverWorld, head.toImmutable(),
			serverWorld.getBlockState(head), held.getItem(), held.getCount(), hand));
		return ActionResult.PASS;
	}

	private static boolean placementSucceeded(ServerPlayerEntity player, Pending pending) {
		if (player.isSpectator()) return false;
		BlockState placed = pending.world().getBlockState(pending.head());
		Block expected = pending.item() == Items.CARVED_PUMPKIN
			? Blocks.CARVED_PUMPKIN : Blocks.JACK_O_LANTERN;
		if (!placed.isOf(expected) || pending.before().isOf(expected)) return false;
		if (player.isCreative()) return true;
		ItemStack held = player.getStackInHand(pending.hand());
		int remaining = held.isOf(pending.item()) ? held.getCount() : 0;
		return remaining < pending.heldCount();
	}

	public static boolean tryActivate(ServerPlayerEntity player, BlockPos head) {
		if (!SquireRuntime.isAlive() || player.isRemoved()
				|| player.isSpectator() || player.getWorld() != player.getServerWorld()) {
			return false;
		}
		ServerWorld world = player.getServerWorld();
		Pattern pattern = findPattern(world, head);
		if (pattern == null) {
			return false;
		}
		var runtime = SquireRuntime.get();
		return consumePattern(player, world, pattern, runtime);
	}

	static boolean tryActivate(ServerPlayerEntity player, BlockPos head,
			dev.squire.server.world.ProtectionAdapter protection) {
		if (!SquireRuntime.isAlive() || player.isRemoved() || player.isSpectator()
				|| player.getWorld() != player.getServerWorld()) return false;
		ServerWorld world = player.getServerWorld();
		Pattern pattern = findPattern(world, head);
		if (pattern == null) return false;
		SquireRuntime runtime = SquireRuntime.get();
		return consumePattern(player, world, pattern, runtime, protection);
	}

	private static boolean consumePattern(ServerPlayerEntity player, ServerWorld world,
			Pattern pattern, SquireRuntime runtime) {
		return consumePattern(player, world, pattern, runtime, runtime.protectionAdapter());
	}

	private static boolean consumePattern(ServerPlayerEntity player, ServerWorld world,
			Pattern pattern, SquireRuntime runtime,
			dev.squire.server.world.ProtectionAdapter protection) {
		var records = runtime.agentStore().recordsOfOwner(player.getUuid());
		if (records.size() >= 2) {
			player.sendMessage(net.minecraft.text.Text.translatable(
				"squire.summon.already_bound"), false);
			return false;
		}
		if (records.size() == 1 && !records.get(0).profile.profession.hasProfession()) {
			player.sendMessage(net.minecraft.text.Text.translatable(
				"squire.summon.choose_first_profession"), false);
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
			var decision = protection.canBreak(world, pos, player.getUuid());
			if (decision == null || !decision.allowed()) {
				player.sendMessage(net.minecraft.text.Text.translatable(
					"squire.summon.protected"), false);
				return false;
			}
			original.put(pos.toImmutable(), world.getBlockState(pos));
		}
		for (BlockPos pos : pattern.blocks()) {
			if (!world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL)) {
				restore(world, original);
				player.sendMessage(net.minecraft.text.Text.translatable(
					"squire.summon.failed"), false);
				return false;
			}
		}
		try {
			var avatar = records.isEmpty()
				? runtime.summonFirstAt(player, pattern.feet())
				: runtime.summonAdditionalAt(player, pattern.feet());
			if (avatar == null) {
				restore(world, original);
				return false;
			}
			// 新铃保持未绑定；玩家手持它右键目标侍从，绑定关系才明确。
			ItemStack bell = new ItemStack(SquireItems.RECALL_BELL);
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
