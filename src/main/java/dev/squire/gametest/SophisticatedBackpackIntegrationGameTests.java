package dev.squire.gametest;

import dev.squire.server.compat.BackpackCompat;
import dev.squire.server.compat.BackpackView;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;

/** Optional smoke test for the real Sophisticated Backpacks Fabric item-storage provider. */
public final class SophisticatedBackpackIntegrationGameTests implements FabricGameTest {

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void realSophisticatedStorageRoundTripsThroughBackpackItem(TestContext context) {
		if (!BackpackCompat.available()) {
			context.complete(); // Sophisticated Backpacks is optional for other installations.
			return;
		}

		var backpackItem = Registries.ITEM.get(new Identifier("sophisticatedbackpacks", "backpack"));
		context.assertTrue(backpackItem != Items.AIR,
			"Sophisticated's registered backpack item must be present when its provider is loaded");

		SimpleInventory backingSlot = new SimpleInventory(1);
		backingSlot.setStack(0, new ItemStack(backpackItem));
		BackpackView view = BackpackCompat.open(backingSlot);
		context.assertTrue(view != null && view.slotCount() > 0,
			"the real item lookup must expose slotted backpack storage");

		ItemStack remainder = view.insert(new ItemStack(Items.DIAMOND, 32));
		context.assertTrue(remainder.isEmpty(), "the real backpack must accept the inserted stack");
		view = BackpackCompat.open(backingSlot);
		context.assertTrue(view != null && view.stackAt(0).isOf(Items.DIAMOND)
				&& view.stackAt(0).getCount() == 32,
			"committed contents must be visible after reopening storage from the item stack");

		ItemStack extracted = view.extract(0, 12);
		context.assertTrue(extracted.isOf(Items.DIAMOND) && extracted.getCount() == 12,
			"the real storage must extract the requested amount");
		view = BackpackCompat.open(backingSlot);
		context.assertTrue(view != null && view.stackAt(0).getCount() == 20,
			"the remainder must survive reopening storage from the updated item stack");
		context.complete();
	}
}
