package dev.squire.server.body.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import net.fabricmc.fabric.api.entity.FakePlayer;

/**
 * Short-lived {@link FakePlayer} interaction proxy (ADR-004).
 *
 * <p>The avatar's inventory is authoritative: before acting, the tool/stack is copied onto
 * the proxy ("prepare"); after acting, counts/durability/results are synced back ("sync").
 * The proxy is fetched per call and never cached by callers.</p>
 *
 * <p>M0-03 spike scope: break / place / durability / inventory sync semantics.
 * Full mining-tick simulation is deliberately NOT attempted — fake players do not tick.</p>
 */
public final class FakePlayerInteractionProxy {
	private FakePlayerInteractionProxy() {
	}

	/**
	 * @param harvested whether the tool actually satisfied the block's tool requirement.
	 *                  {@code broken && !harvested} means the block was removed WITHOUT
	 *                  drops — runtime gathering must pre-check and never end up here.
	 */
	public record BreakResult(boolean broken, List<ItemStack> drops, boolean harvested) {
		static final BreakResult FAIL = new BreakResult(false, List.of(), false);
	}

	/** True when breaking this state with this stack would actually yield its drops. */
	public static boolean canHarvest(BlockState state, ItemStack tool) {
		return !state.isToolRequired() || (tool != null && tool.isSuitableFor(state));
	}

	/**
	 * Break a block using the avatar's tool stack.
	 *
	 * @param tool the avatar-owned tool stack (copied onto the proxy for loot context)
	 * @param onToolUsed consumer receiving the post-break tool stack (durability applied)
	 */
	public static BreakResult breakBlock(ServerWorld world, BlockPos pos, ItemStack tool,
			Consumer<ItemStack> onToolUsed) {
		return breakBlock(world, pos, tool, onToolUsed, true);
	}

	/**
	 * Variant for runtime-owned gathering: drops are returned to the caller
	 * (typically inserted into the avatar inventory, ADR-004) instead of spawned
	 * as item entities nobody would pick up.
	 */
	public static BreakResult breakAndCollect(ServerWorld world, BlockPos pos, ItemStack tool,
			Consumer<ItemStack> onToolUsed) {
		return breakBlock(world, pos, tool, onToolUsed, false);
	}

	private static BreakResult breakBlock(ServerWorld world, BlockPos pos, ItemStack tool,
			Consumer<ItemStack> onToolUsed, boolean spawnDrops) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || state.getBlock() == Blocks.VOID_AIR || state.getBlock() == Blocks.CAVE_AIR) {
			return BreakResult.FAIL;
		}

		FakePlayer fake = FakePlayer.get(world);
		// prepare: the loot context must see a player holding the tool
		fake.getInventory().setStack(0, tool.copy());
		fake.getInventory().selectedSlot = 0;

		// vanilla gates drops on player.canHarvest(state) inside tryBreakBlock; since we
		// drive the loot table directly we must replicate that gate or bare hands mine ore.
		boolean canHarvest = canHarvest(state, tool);

		List<ItemStack> drops = new ArrayList<>();
		if (canHarvest) {
			var lootBuilder = new LootContextParameterSet.Builder(world)
				.add(LootContextParameters.ORIGIN, Vec3d.ofCenter(pos))
				.add(LootContextParameters.THIS_ENTITY, fake)
				.add(LootContextParameters.TOOL, tool);
			drops.addAll(state.getDroppedStacks(lootBuilder));
		}

		world.removeBlock(pos, false);

		// durability: authentic damage path (respects Unbreaking enchant)
		if (!tool.isEmpty() && tool.isDamageable()) {
			tool.damage(1, world.random, null);
			if (tool.getDamage() >= tool.getMaxDamage()) {
				tool.setCount(0);
			}
		}
		onToolUsed.accept(tool.copy());

		if (spawnDrops) {
			drops.forEach(d -> Block.dropStack(world, pos, d));
		}
		return new BreakResult(true, drops, canHarvest);
	}

	public record PlaceResult(boolean placed, ItemStack remaining) {
	}

	/**
	 * Place a block from an avatar-owned item stack via a real BlockItem placement
	 * through the proxy (so modded placement hooks see a PlayerEntity).
	 *
	 * <p>Like a real player click, the target needs support: the cell below must be
	 * solid (non-replaceable), because the synthetic hit result points at it. Placing
	 * against side faces / mid-air is out of M0 scope.</p>
	 */
	public static PlaceResult placeBlock(ServerWorld world, BlockPos pos, ItemStack blockItem) {
		if (!(blockItem.getItem() instanceof BlockItem blockItemItem)) {
			return new PlaceResult(false, blockItem);
		}
		BlockPos target = pos.toImmutable();
		if (!world.getBlockState(target).isAir() && !world.getBlockState(target).isReplaceable()) {
			return new PlaceResult(false, blockItem);
		}
		// vanilla rays never end inside air: require solid support under the target
		if (world.getBlockState(target.down()).isReplaceable()) {
			return new PlaceResult(false, blockItem);
		}

		FakePlayer fake = FakePlayer.get(world);
		ItemStack held = blockItem.copy();

		var hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target.down(), false);
		var context = new ItemPlacementContext(fake, Hand.MAIN_HAND, held, hit);
		// prepare done above (context holds the copy); act:
		// NOTE: ActionResult.isAccepted() is (result != PASS) — FAIL counts as "accepted".
		// Success must be checked explicitly against SUCCESS/CONSUME.
		ActionResult result = blockItemItem.place(context);
		boolean success = result == ActionResult.SUCCESS || result == ActionResult.CONSUME
			|| result == ActionResult.CONSUME_PARTIAL;

		if (success) {
			return new PlaceResult(true, held); // caller syncs decremented count back
		}
		return new PlaceResult(false, blockItem);
	}

	/** Utility: total count of an item across a player's main inventory (spike helper). */
	public static int countItem(PlayerEntity entity, ItemStack matcher) {
		int total = 0;
		for (int i = 0; i < entity.getInventory().size(); i++) {
			ItemStack s = entity.getInventory().getStack(i);
			if (ItemStack.areItemsEqual(s, matcher)) {
				total += s.getCount();
			}
		}
		return total;
	}
}
