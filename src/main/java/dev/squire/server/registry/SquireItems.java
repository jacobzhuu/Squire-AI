package dev.squire.server.registry;

import java.util.UUID;

import dev.squire.SquireMod;
import dev.squire.server.item.RecallBellItem;
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

	public static final Item RECALL_BELL = Registry.register(Registries.ITEM,
		new Identifier(SquireMod.MOD_ID, "recall_bell"),
		new RecallBellItem(new Item.Settings().maxCount(1)));

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
			.register(entries -> entries.add(RECALL_BELL));
	}

	public static ItemStack boundRecallBell(UUID ownerId, UUID agentId) {
		ItemStack stack = new ItemStack(RECALL_BELL);
		bind(stack, ownerId, agentId);
		return stack;
	}

	public static void bind(ItemStack stack, UUID ownerId, UUID agentId) {
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putUuid(NBT_OWNER, ownerId);
		nbt.putUuid(NBT_AGENT, agentId);
	}

	private SquireItems() {
	}
}
