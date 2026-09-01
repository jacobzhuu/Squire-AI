package dev.squire.server.registry;

import dev.squire.SquireMod;
import dev.squire.server.item.BellUpgradeRecipe;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialRecipeSerializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** Recipe serializers owned by the ordinary survival lifecycle. */
public final class SquireRecipes {

	public static final RecipeSerializer<BellUpgradeRecipe> BELL_UPGRADE =
		new SpecialRecipeSerializer<>(BellUpgradeRecipe::new);

	public static void register() {
		Registry.register(Registries.RECIPE_SERIALIZER,
			new Identifier(SquireMod.MOD_ID, "bell_upgrade"), BELL_UPGRADE);
	}

	private SquireRecipes() {
	}
}
