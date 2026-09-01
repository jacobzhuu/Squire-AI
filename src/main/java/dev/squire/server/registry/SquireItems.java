package dev.squire.server.registry;

import java.util.UUID;

import dev.squire.SquireMod;
import dev.squire.server.item.BellReviveConfig;
import dev.squire.server.item.RecallBellItem;
import dev.squire.server.item.BellTier;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** Items that form part of the ordinary, command-free companion lifecycle. */
public final class SquireItems {

	public static final String NBT_OWNER = "SquireOwner";
	public static final String NBT_AGENT = "SquireAgent";
	public static final String NBT_BELL_TIER = "BellTier";
	/** Display-only server config snapshot. Revival never trusts these values. */
	public static final String NBT_REVIVE_HEALTH = "BellReviveHealth";
	public static final String NBT_REVIVE_COOLDOWN = "BellReviveCooldown";
	public static final String NBT_REVIVE_LEVEL_REQUIREMENT =
		"BellReviveLevelRequirement";
	public static final String NBT_REVIVE_HAS_BUFF = "BellReviveHasBuff";
	/** Transient recipe proof, removed by the server crafting callback. */
	public static final String NBT_UPGRADE_FROM = "SquireBellUpgradeFrom";

	public static final Item RECALL_BELL = Registry.register(Registries.ITEM,
		new Identifier(SquireMod.MOD_ID, "recall_bell"),
		new RecallBellItem(new Item.Settings().maxCount(1)));

	public static void register() {
		SquireRecipes.register();
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
			.register(entries -> entries.add(RECALL_BELL));
	}

	public static ItemStack boundRecallBell(UUID ownerId, UUID agentId) {
		ItemStack stack = new ItemStack(RECALL_BELL);
		bind(stack, ownerId, agentId);
		return stack;
	}

	public static void bind(ItemStack stack, UUID ownerId, UUID agentId) {
		bind(stack, ownerId, agentId, tierOf(stack));
	}

	public static void bind(ItemStack stack, UUID ownerId, UUID agentId,
			BellTier tier) {
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putUuid(NBT_OWNER, ownerId);
		nbt.putUuid(NBT_AGENT, agentId);
		BellTier actualTier = tier == null ? BellTier.COMMON : tier;
		nbt.putString(NBT_BELL_TIER, actualTier.id());
		writeDisplayRule(stack, BellReviveConfig.defaults().rule(actualTier));
	}

	public static void writeDisplayRule(ItemStack stack,
			BellReviveConfig.TierRule rule) {
		if (stack == null || rule == null) return;
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putDouble(NBT_REVIVE_HEALTH, rule.reviveHealth());
		nbt.putLong(NBT_REVIVE_COOLDOWN, rule.reviveCooldown());
		nbt.putInt(NBT_REVIVE_LEVEL_REQUIREMENT, rule.levelRequirement());
		nbt.putBoolean(NBT_REVIVE_HAS_BUFF, !rule.reviveBuff().empty());
	}

	public static BellTier tierOf(ItemStack stack) {
		return stack != null && stack.hasNbt()
			? BellTier.byId(stack.getNbt().getString(NBT_BELL_TIER))
			: BellTier.COMMON;
	}

	private SquireItems() {
	}
}
