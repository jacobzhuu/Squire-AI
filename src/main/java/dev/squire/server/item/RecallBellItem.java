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
	public void onCraft(ItemStack stack, World world, PlayerEntity player) {
		super.onCraft(stack, world, player);
		if (world.isClient || !stack.hasNbt()
				|| !stack.getNbt().contains(SquireItems.NBT_UPGRADE_FROM)) return;
		if (SquireRuntime.isAlive() && player instanceof ServerPlayerEntity serverPlayer) {
			SquireRuntime.get().commitBellUpgrade(serverPlayer, stack);
		}
		stack.getOrCreateNbt().remove(SquireItems.NBT_UPGRADE_FROM);
	}

    @Override
    public void inventoryTick(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean selected) {
        if (!world.isClient && world.getTime() % 40 == 0 && SquireRuntime.isAlive()) {
            SquireRuntime.get().syncRecallBellDisplay(stack, SquireItems.tierOf(stack));
        }
    }

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip,
			TooltipContext context) {
		tooltip.add(Text.translatable("item.squire.recall_bell.tooltip")
			.formatted(Formatting.GRAY));
		BellTier tier = SquireItems.tierOf(stack);
		BellReviveConfig defaults = BellReviveConfig.defaults();
		BellReviveConfig.TierRule rule = defaults.rule(tier);
		var nbt = stack.getNbt();
		double reviveHealth = nbt != null && nbt.contains(SquireItems.NBT_REVIVE_HEALTH)
			? nbt.getDouble(SquireItems.NBT_REVIVE_HEALTH) : rule.reviveHealth();
		long reviveCooldown = nbt != null
			&& nbt.contains(SquireItems.NBT_REVIVE_COOLDOWN)
			? nbt.getLong(SquireItems.NBT_REVIVE_COOLDOWN) : rule.reviveCooldown();
		int levelRequirement = nbt != null
			&& nbt.contains(SquireItems.NBT_REVIVE_LEVEL_REQUIREMENT)
			? nbt.getInt(SquireItems.NBT_REVIVE_LEVEL_REQUIREMENT)
			: rule.levelRequirement();
		boolean hasBuff = nbt != null && nbt.contains(SquireItems.NBT_REVIVE_HAS_BUFF)
			? nbt.getBoolean(SquireItems.NBT_REVIVE_HAS_BUFF)
			: !rule.reviveBuff().empty();
		tooltip.add(Text.translatable("item.squire.recall_bell.quality",
			Text.translatable(tier.translationKey())).formatted(Formatting.AQUA));
		tooltip.add(Text.translatable("item.squire.recall_bell.revive_health",
			Math.round(reviveHealth * 100.0)).formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("item.squire.recall_bell.revive_cooldown",
			reviveCooldown / 1200L).formatted(Formatting.GRAY));
		if (hasBuff) {
			tooltip.add(Text.translatable("item.squire.recall_bell.blessing_requirement",
				levelRequirement).formatted(Formatting.DARK_AQUA));
		}
		if (tier.next() != null) {
			tooltip.add(Text.translatable("item.squire.recall_bell.upgrade_cost."
				+ tier.id()).formatted(Formatting.DARK_GRAY));
		}
        if (nbt != null && nbt.containsUuid(SquireItems.NBT_AGENT)) {
            String name = nbt.getString("SquireDisplayName");
            tooltip.add(Text.translatable(name.isBlank() ? "item.squire.recall_bell.unknown" : "item.squire.recall_bell.bound_name", name).formatted(Formatting.GOLD));
            String profession = nbt.getString("SquireDisplayProfession");
            tooltip.add(Text.translatable("item.squire.recall_bell.profession", Text.translatable(
                profession.isBlank() ? "squire.gui.profession.untrained" : "squire.gui.profession." + profession)).formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("item.squire.recall_bell.unbound").formatted(Formatting.YELLOW));
        }
    }
}
