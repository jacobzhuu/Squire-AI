package dev.squire.api.body;

import java.util.List;

/**
 * Read-only, module-pure view of a body's inventory (spec section 8).
 * Item ids are {@code namespace:path} strings so the api layer stays Minecraft-free.
 */
public interface InventoryView {
	/** Total count of items matching the given id ({@code namespace:path}). */
	int countOf(String itemId);

	/** Ids currently present, each appearing once, unordered. */
	List<String> distinctItemIds();

	/** Total number of occupied slots (implementation-defined slot model). */
	int occupiedSlots();
}
