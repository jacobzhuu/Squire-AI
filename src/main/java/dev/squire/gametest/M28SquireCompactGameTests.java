package dev.squire.gametest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.guide.SquireCompact;
import dev.squire.server.guide.SquireCompactService;
import dev.squire.server.guide.SquireCompactState;
import dev.squire.server.item.SquireCompactRecipe;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.WrittenBookItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** End-to-end contracts for the first-join compact and its survival replacement recipe. */
public final class M28SquireCompactGameTests implements FabricGameTest {

	private static final String FLOOR = M0SpikeGameTests.FLOOR;
	private static final ScreenHandler NOOP_HANDLER = new ScreenHandler(null, 0) {
		@Override public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity player,
				int slot) { return ItemStack.EMPTY; }
		@Override public boolean canUse(net.minecraft.entity.player.PlayerEntity player) {
			return true;
		}
	};

	private static FakePlayer player(ServerWorld world, String name, BlockPos pos) {
		FakePlayer player = FakePlayer.get(world, new GameProfile(
			UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
		if (!player.isAlive()) world.spawnEntity(player);
		player.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
			0.0f, 0.0f);
		return player;
	}

	@GameTest(templateName = FLOOR, tickLimit = 80, batchId = "squire-compact-book")
	public void compactIsValidLocalizedAndCraftable(TestContext context) {
		context.runAtTick(5, () -> {
			ItemStack compact = SquireCompact.create();
			context.assertTrue(SquireCompact.isCurrent(compact),
				"the generated book carries the current Squire edition marker");
			context.assertTrue(WrittenBookItem.isValid(compact.getNbt()),
				"the compact is valid vanilla written-book NBT");
			context.assertTrue(SquireCompact.TITLE.equals(compact.getNbt().getString("title")),
				"the bilingual title is stable across client languages");
			context.assertTrue(SquireCompact.pages(compact).size() == 7,
				"the compact contains the seven promised pages");
			context.assertTrue(SquireCompact.pages(compact).getString(0)
				.contains("book.squire.compact.page.1"),
				"page JSON keeps a translation key for client-side localization");

			SquireCompactRecipe recipe = new SquireCompactRecipe(
				new Identifier("squire", "compact_test"));
			CraftingInventory grid = new CraftingInventory(NOOP_HANDLER, 2, 2);
			grid.setStack(0, new ItemStack(Items.BOOK));
			grid.setStack(3, new ItemStack(Items.HAY_BLOCK));
			context.assertTrue(recipe.matches(grid, context.getWorld()),
				"one book plus one hay bale matches in any positions");
			context.assertFalse(recipe.isIgnoredInRecipeBook(),
				"the replacement recipe remains visible in the vanilla recipe book");
			ItemStack crafted = recipe.craft(grid,
				context.getWorld().getRegistryManager());
			context.assertTrue(SquireCompact.isCurrent(crafted),
				"the crafted replacement is identical to the join gift edition");
			grid.setStack(1, new ItemStack(Items.STRING));
			context.assertFalse(recipe.matches(grid, context.getWorld()),
				"extra ingredients do not match the compact recipe");
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-compact-gift")
	public void firstJoinGiftPersistsAndFullInventoryDropsSafely(TestContext context) {
		context.runAtTick(5, () -> {
			ServerWorld world = context.getWorld();
			BlockPos firstPos = context.getAbsolutePos(new BlockPos(2, 2, 2));
			FakePlayer first = player(world, "compact-first-recipient", firstPos);
			SquireCompactState state = SquireCompactState.get(world.getServer());
			context.assertFalse(state.hasReceived(first.getUuid()),
				"a player absent from this world's state is eligible");
			context.assertTrue(SquireCompactService.giveOnFirstJoin(first),
				"the first delivery succeeds");
			context.assertTrue(first.getInventory().count(Items.WRITTEN_BOOK) == 1,
				"the compact goes into an available inventory slot");
			context.assertFalse(SquireCompactService.giveOnFirstJoin(first),
				"a repeat join never creates a second copy");

			NbtCompound persisted = state.writeNbt(new NbtCompound());
			context.assertTrue(SquireCompactState.createFromNbtPublic(persisted)
				.hasReceived(first.getUuid()),
				"the recipient survives an NBT round trip");

			BlockPos fullPos = context.getAbsolutePos(new BlockPos(6, 2, 2));
			FakePlayer full = player(world, "compact-full-recipient", fullPos);
			for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
				full.getInventory().setStack(slot, new ItemStack(Items.STONE, 64));
			}
			context.assertTrue(SquireCompactService.giveOnFirstJoin(full),
				"a full inventory falls back to a retained ground drop");
			context.assertTrue(full.getInventory().count(Items.WRITTEN_BOOK) == 0,
				"the full inventory is not silently overwritten");
			boolean dropped = !world.getEntitiesByClass(ItemEntity.class,
				full.getBoundingBox().expand(2.0),
				entity -> SquireCompact.isCurrent(entity.getStack())).isEmpty();
			context.assertTrue(dropped, "the compact exists at the player's feet");
			context.assertTrue(state.hasReceived(full.getUuid()),
				"a successful fallback delivery is marked exactly once");
			first.discard();
			full.discard();
			context.complete();
		});
	}
}
