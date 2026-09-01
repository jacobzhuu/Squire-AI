package dev.squire.server.item;

import com.google.gson.JsonObject;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.util.Identifier;

/** Stateless serializer: ingredients and localized output are fixed by the compact edition. */
public final class SquireCompactRecipeSerializer
		implements RecipeSerializer<SquireCompactRecipe> {

	@Override
	public SquireCompactRecipe read(Identifier id, JsonObject json) {
		return new SquireCompactRecipe(id);
	}

	@Override
	public SquireCompactRecipe read(Identifier id, PacketByteBuf buf) {
		return new SquireCompactRecipe(id);
	}

	@Override
	public void write(PacketByteBuf buf, SquireCompactRecipe recipe) {
		// The recipe has no data-driven fields, so the identifier is sufficient.
	}
}
