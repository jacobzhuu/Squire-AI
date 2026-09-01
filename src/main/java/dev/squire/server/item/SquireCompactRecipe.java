package dev.squire.server.item;

import dev.squire.server.guide.SquireCompact;
import dev.squire.server.registry.SquireRecipes;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

/** Recipe-book-visible shapeless recipe whose vanilla written-book output carries NBT. */
public final class SquireCompactRecipe extends ShapelessRecipe {

	public SquireCompactRecipe(Identifier id) {
		super(id, "squire_compact", CraftingRecipeCategory.MISC, SquireCompact.create(),
			ingredients());
	}

	private static DefaultedList<Ingredient> ingredients() {
		DefaultedList<Ingredient> ingredients = DefaultedList.of();
		ingredients.add(Ingredient.ofItems(Items.BOOK));
		ingredients.add(Ingredient.ofItems(Items.HAY_BLOCK));
		return ingredients;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return SquireRecipes.SQUIRE_COMPACT;
	}
}
