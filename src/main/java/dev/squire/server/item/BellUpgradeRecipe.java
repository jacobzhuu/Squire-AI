package dev.squire.server.item;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.squire.server.registry.SquireItems;
import dev.squire.server.registry.SquireRecipes;
import dev.squire.server.runtime.SquireRuntime;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** One NBT-preserving workbench recipe that advances a bound bell exactly one tier. */
public final class BellUpgradeRecipe extends SpecialCraftingRecipe {

	private static final Map<BellTier, Map<Item, Integer>> COSTS = Map.of(
		BellTier.COMMON, cost(Items.AMETHYST_SHARD, 4, Items.GOLD_INGOT, 4),
		BellTier.ENHANCED, cost(Items.ECHO_SHARD, 2, Items.DIAMOND, 2),
		BellTier.RESONANT, cost(Items.NETHER_STAR, 1, Items.NETHERITE_INGOT, 1));

	public BellUpgradeRecipe(Identifier id, CraftingRecipeCategory category) {
		super(id, category);
	}

	private static Map<Item, Integer> cost(Item first, int firstCount,
			Item second, int secondCount) {
		Map<Item, Integer> cost = new LinkedHashMap<>();
		cost.put(first, firstCount);
		cost.put(second, secondCount);
		return Map.copyOf(cost);
	}

	public static Map<Item, Integer> costFor(BellTier from) {
		return COSTS.getOrDefault(from, Map.of());
	}

	@Override
	public boolean matches(RecipeInputInventory inventory, World world) {
		ItemStack bell = ItemStack.EMPTY;
		Map<Item, Integer> found = new LinkedHashMap<>();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack.isEmpty()) continue;
			if (stack.isOf(SquireItems.RECALL_BELL)) {
				if (!bell.isEmpty() || stack.getCount() != 1 || !isBound(stack)) return false;
				bell = stack;
			} else {
				found.merge(stack.getItem(), 1, Integer::sum);
			}
		}
		if (bell.isEmpty()) return false;
		BellTier from = SquireItems.tierOf(bell);
		if (from.next() == null || !found.equals(costFor(from))) return false;
		if (!world.isClient && SquireRuntime.isAlive()) {
			var nbt = bell.getNbt();
			var record = SquireRuntime.get().agentStore()
				.recordOfAgent(nbt.getUuid(SquireItems.NBT_AGENT)).orElse(null);
			if (record == null || !record.ownerId.equals(nbt.getUuid(SquireItems.NBT_OWNER))
					|| BellTier.byId(record.bellTier) != from) return false;
		}
		return true;
	}

	private static boolean isBound(ItemStack bell) {
		return bell.hasNbt() && bell.getNbt().containsUuid(SquireItems.NBT_OWNER)
			&& bell.getNbt().containsUuid(SquireItems.NBT_AGENT);
	}

	@Override
	public ItemStack craft(RecipeInputInventory inventory,
			DynamicRegistryManager registryManager) {
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack input = inventory.getStack(slot);
			if (!input.isOf(SquireItems.RECALL_BELL)) continue;
			BellTier from = SquireItems.tierOf(input);
			BellTier next = from.next();
			if (next == null) return ItemStack.EMPTY;
			ItemStack output = input.copy();
			output.setCount(1);
			output.getOrCreateNbt().putString(SquireItems.NBT_UPGRADE_FROM, from.id());
			output.getOrCreateNbt().putString(SquireItems.NBT_BELL_TIER, next.id());
			return output;
		}
		return ItemStack.EMPTY;
	}

	@Override
	public boolean fits(int width, int height) {
		return width * height >= 9;
	}

	@Override
	public net.minecraft.recipe.RecipeSerializer<?> getSerializer() {
		return SquireRecipes.BELL_UPGRADE;
	}
}
