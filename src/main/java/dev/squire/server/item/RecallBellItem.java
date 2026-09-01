package dev.squire.server.item;

import java.util.List;

import dev.squire.server.registry.SquireItems;
import dev.squire.server.runtime.SquireRuntime;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** A persistent, owner-bound way to recall an existing squire without commands. */
public final class RecallBellItem extends Item {

	private static final int COOLDOWN_TICKS = 100;

	public RecallBellItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) {
			return TypedActionResult.success(stack);
		}
		if (!(user instanceof ServerPlayerEntity player) || !SquireRuntime.isAlive()) {
			return TypedActionResult.fail(stack);
		}
		var result = SquireRuntime.get().recallWithBell(player, stack);
		player.sendMessage(Text.literal(result.message()), false);
		if (result.success()) {
			player.getItemCooldownManager().set(this, COOLDOWN_TICKS);
			return TypedActionResult.consume(stack);
		}
		return TypedActionResult.fail(stack);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip,
			TooltipContext context) {
		tooltip.add(Text.translatable("item.squire.recall_bell.tooltip")
			.formatted(Formatting.GRAY));
		if (stack.hasNbt() && stack.getNbt().containsUuid(SquireItems.NBT_OWNER)) {
			tooltip.add(Text.translatable("item.squire.recall_bell.bound")
				.formatted(Formatting.GOLD));
		}
	}
}
