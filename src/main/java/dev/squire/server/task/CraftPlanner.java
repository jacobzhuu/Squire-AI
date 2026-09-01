package dev.squire.server.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M2 acquisition knowledge base + recursive planner (spec section 36):
 * RESOLVE the goal → CHECK materials → CREATE missing sub-goals → CRAFT → VERIFY.
 *
 * <p>Pure and Minecraft-free: recipes are a small built-in table for common
 * primitives (documented M2 scope); the ToolCompiler feeds it a live inventory
 * snapshot. Unknown items yield {@link #UNKNOWN} so callers answer honestly
 * instead of hallucinating capability.</p>
 */
public final class CraftPlanner {

	/** One planned unit of work for the task compiler. */
	public record Step(Kind kind, String itemId, String blockId, int count) {
		public enum Kind { GATHER, CRAFT, SMELT }
	}

	public static final class UnsupportedItemException extends RuntimeException {
		public UnsupportedItemException(String itemId) {
			super("no gather source or recipe known for " + itemId);
		}
	}

	private static final String ANY_PLANKS = "#minecraft:planks";

	/** item id -> block id to mine for it. */
	private static final Map<String, String> GATHER_SOURCES = Map.ofEntries(
		Map.entry("minecraft:coal", "minecraft:coal_ore"),
		Map.entry("minecraft:raw_iron", "minecraft:iron_ore"),
		Map.entry("minecraft:oak_log", "minecraft:oak_log"),
		Map.entry("minecraft:spruce_log", "minecraft:spruce_log"),
		Map.entry("minecraft:birch_log", "minecraft:birch_log"),
		Map.entry("minecraft:jungle_log", "minecraft:jungle_log"),
		Map.entry("minecraft:acacia_log", "minecraft:acacia_log"),
		Map.entry("minecraft:dark_oak_log", "minecraft:dark_oak_log"),
		Map.entry("minecraft:cherry_log", "minecraft:cherry_log"),
		Map.entry("minecraft:cobblestone", "minecraft:stone"),
		Map.entry("minecraft:dirt", "minecraft:dirt"),
		Map.entry("minecraft:sand", "minecraft:sand"),
		Map.entry("minecraft:gravel", "minecraft:gravel"));

	/** One crafting rule: output item -> yield per craft + ingredient list. */
	public record Rule(int yield, List<String> ingredientIds, List<Integer> counts) {
	}

	private static final Map<String, Rule> RULES = new LinkedHashMap<>();

	static {
		rule("minecraft:oak_planks", 4, "minecraft:oak_log", 1);
		rule("minecraft:spruce_planks", 4, "minecraft:spruce_log", 1);
		rule("minecraft:birch_planks", 4, "minecraft:birch_log", 1);
		rule("minecraft:stick", 4, ANY_PLANKS, 2);
		rule("minecraft:torch", 4, "minecraft:coal", 1, "minecraft:stick", 1);
		rule("minecraft:iron_pickaxe", 1, "minecraft:iron_ingot", 3, "minecraft:stick", 2);
	}

	private static void rule(String out, int yield, Object... pairs) {
		List<String> ids = new ArrayList<>();
		List<Integer> counts = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			ids.add((String) pairs[i]);
			counts.add((Integer) pairs[i + 1]);
		}
		RULES.put(out, new Rule(yield, List.copyOf(ids), List.copyOf(counts)));
	}

	/**
	 * Smelting rules (output -> smeltable input). Vanilla smelting is 1:1; fuel is
	 * the executor's concern (coal/charcoal), not the planner's.
	 */
	private static final Map<String, String> SMELT_RULES = Map.of(
		"minecraft:iron_ingot", "minecraft:raw_iron",
		"minecraft:copper_ingot", "minecraft:raw_copper",
		"minecraft:gold_ingot", "minecraft:raw_gold");

	private CraftPlanner() {
	}

	/**
	 * Plan the steps needed so the inventory ends with ≥ {@code count} of {@code itemId}.
	 * Steps are ordered raw-materials-first; each step's condition threshold must be
	 * computed by the caller against baseline inventory counts.
	 *
	 * @throws UnsupportedItemException when neither gather source nor recipe exists
	 */
	public static List<Step> plan(String itemId, int count, Map<String, Integer> inventory) {
		itemId = namespaced(itemId); // models often send bare "torch" — accept it
		List<Step> steps = new ArrayList<>();
		plan(itemId, count, inventory, steps, new ArrayDeque<>(Set.of(itemId)));
		return List.copyOf(steps);
	}

	/** Bare paths get the implicit {@code minecraft:} namespace (Identifier rule). */
	public static String namespaced(String id) {
		return id != null && !id.contains(":") ? "minecraft:" + id : id;
	}

	/**
	 * Human/model-readable list of everything this planner knows how to obtain —
	 * used in NO_RECIPE payloads so a refusal says what WOULD work.
	 */
	public static String supportedItems() {
		var all = new java.util.TreeSet<String>();
		all.addAll(GATHER_SOURCES.keySet());
		all.addAll(RULES.keySet());
		all.addAll(SMELT_RULES.keySet());
		return String.join(", ", all);
	}

	private static void plan(String itemId, int count, Map<String, Integer> inventory,
			List<Step> steps, Deque<String> path) {
		int owned = inventory.getOrDefault(itemId, 0);
		if (owned >= count) {
			return;
		}
		String source = GATHER_SOURCES.get(itemId);
		if (source != null) {
			steps.add(new Step(Step.Kind.GATHER, itemId, source, count - owned));
			return;
		}
		Rule rule = RULES.get(itemId);
		String smeltInput = SMELT_RULES.get(itemId);
		if (rule == null && smeltInput == null) {
			throw new UnsupportedItemException(itemId);
		}
		if (smeltInput != null) {
			if (path.contains(smeltInput)) {
				throw new UnsupportedItemException(itemId); // cycle guard
			}
			path.push(smeltInput);
			try {
				plan(smeltInput, count - owned, inventory, steps, path);
			} finally {
				path.pop();
			}
			// Smelting consumes real fuel. Plan the minimum coal needed for the
			// missing output instead of letting the executor discover a hidden gap.
			int fuelNeeded = ceilDiv(count - owned, 8);
			if (!itemId.equals("minecraft:coal") && fuelNeeded > 0) {
				plan("minecraft:coal", fuelNeeded, inventory, steps, path);
			}
			steps.add(new Step(Step.Kind.SMELT, itemId, null, count - owned));
			return;
		}
		int craftsNeeded = ceilDiv(count - owned, rule.yield());
		for (int i = 0; i < rule.ingredientIds().size(); i++) {
			String ing = resolvePlaceholder(rule.ingredientIds().get(i), inventory);
			int perCraft = rule.counts().get(i);
			if (path.contains(ing)) {
				throw new UnsupportedItemException(itemId); // cycle guard
			}
			path.push(ing);
			try {
				plan(ing, perCraft * craftsNeeded, inventory, steps, path);
			} finally {
				path.pop();
			}
		}
		steps.add(new Step(Step.Kind.CRAFT, itemId, null, craftsNeeded * rule.yield()));
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}

	/** "#tag" placeholders resolve to an owned concrete member or a sensible default. */
	private static String resolvePlaceholder(String id, Map<String, Integer> inventory) {
		if (!id.startsWith("#")) {
			return id;
		}
		if (ANY_PLANKS.equals(id)) {
			return inventory.keySet().stream()
				.filter(k -> k.endsWith("_planks"))
				.findFirst()
				.orElse("minecraft:oak_planks");
		}
		throw new UnsupportedItemException(id);
	}

	/** True when the runtime knows how to obtain this item at all. */
	public static boolean isSupported(String itemId) {
		return GATHER_SOURCES.containsKey(itemId) || RULES.containsKey(itemId)
			|| SMELT_RULES.containsKey(itemId);
	}

	static Set<String> knownOutputs() {
		return new HashMap<>(RULES).keySet();
	}
}
