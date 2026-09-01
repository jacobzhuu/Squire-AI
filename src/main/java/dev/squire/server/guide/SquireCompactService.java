package dev.squire.server.guide;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/** First-join delivery for {@link SquireCompact}. */
public final class SquireCompactService {

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			giveOnFirstJoin(handler.getPlayer()));
	}

	/**
	 * Gives one copy if this player has not received it in this world.
	 *
	 * @return true only when this call inserted or dropped a new copy
	 */
	public static boolean giveOnFirstJoin(ServerPlayerEntity player) {
		SquireCompactState state = SquireCompactState.get(player.getServer());
		if (state.hasReceived(player.getUuid())) return false;

		ItemStack compact = SquireCompact.create();
		boolean delivered = player.getInventory().insertStack(compact);
		if (!delivered && !compact.isEmpty()) {
			ItemEntity dropped = player.dropItem(compact, false, true);
			delivered = dropped != null;
		}
		if (delivered) state.markReceived(player.getUuid());
		return delivered;
	}

	private SquireCompactService() {
	}
}
