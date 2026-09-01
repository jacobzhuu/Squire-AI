package dev.squire.server.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure planner tests for the acquisition pipeline (spec section 36). */
class CraftPlannerTest {

	@Test
	void torchesFromEmptyInventory() {
		List<CraftPlanner.Step> steps = CraftPlanner.plan("minecraft:torch", 32, Map.of());
		assertEquals(5, steps.size());
		// raw materials first: coal and a log
		assertEquals(CraftPlanner.Step.Kind.GATHER, steps.get(0).kind());
		assertEquals("minecraft:coal", steps.get(0).itemId());
		assertEquals(8, steps.get(0).count());
		assertEquals("minecraft:coal_ore", steps.get(0).blockId());
		assertEquals(CraftPlanner.Step.Kind.GATHER, steps.get(1).kind());
		assertEquals("minecraft:oak_log", steps.get(1).itemId());
		// then intermediates: planks -> sticks -> torches
		assertEquals("minecraft:oak_planks", steps.get(2).itemId());
		assertEquals(CraftPlanner.Step.Kind.CRAFT, steps.get(2).kind());
		assertEquals("minecraft:stick", steps.get(3).itemId());
		assertEquals(8, steps.get(3).count());
		assertEquals(CraftPlanner.Step.Kind.CRAFT, steps.get(4).kind());
		assertEquals(32, steps.get(4).count());
	}

	@Test
	void alreadySatisfiedGoalIsEmptyPlan() {
		assertTrue(CraftPlanner.plan("minecraft:torch", 8,
			Map.of("minecraft:torch", 10)).isEmpty());
	}

	@Test
	void partialStockReducesDeficit() {
		// 4 torches owned, want 32 -> 28 more -> 7 crafts -> 7 coal (have 2) -> gather 5
		List<CraftPlanner.Step> steps = CraftPlanner.plan("minecraft:torch", 32,
			Map.of("minecraft:torch", 4, "minecraft:coal", 2));
		CraftPlanner.Step coal = steps.stream()
			.filter(s -> "minecraft:coal".equals(s.itemId())).findFirst().orElseThrow();
		assertEquals(5, coal.count());
	}

	@Test
	void placeholderResolvesToOwnedPlanksSkippingCraftStep() {
		List<CraftPlanner.Step> steps = CraftPlanner.plan("minecraft:stick", 8,
			Map.of("minecraft:birch_planks", 10));
		assertEquals(1, steps.size());
		assertEquals(CraftPlanner.Step.Kind.CRAFT, steps.get(0).kind());
		assertEquals("minecraft:stick", steps.get(0).itemId());
	}

	@Test
	void unknownItemThrowsHonestUnsupported() {
		assertThrows(CraftPlanner.UnsupportedItemException.class,
			() -> CraftPlanner.plan("minecraft:diamond_sword", 1, Map.of()));
	}

	@Test
	void supportedCheckMatchesTables() {
		assertTrue(CraftPlanner.isSupported("minecraft:torch"));
		assertTrue(CraftPlanner.isSupported("minecraft:cobblestone"));
		assertEquals(false, CraftPlanner.isSupported("minecraft:enchanted_book"));
	}

	@Test
	void woodCuttingIsASingleGatherStep() {
		// B03 “帮我砍 20 个橡木”: logs are a direct gather source
		List<CraftPlanner.Step> steps = CraftPlanner.plan("minecraft:oak_log", 20, Map.of());
		assertEquals(1, steps.size());
		assertEquals(CraftPlanner.Step.Kind.GATHER, steps.get(0).kind());
		assertEquals(20, steps.get(0).count());
		assertEquals("minecraft:oak_log", steps.get(0).blockId());
	}

	@Test
	void ironPickaxePlansThroughSmelting() {
		// B04: ore -> raw_iron -> smelt ingots -> craft pickaxe (sticks need wood)
		List<CraftPlanner.Step> steps = CraftPlanner.plan("minecraft:iron_pickaxe", 1,
			Map.of("minecraft:coal", 8));
		var kinds = steps.stream().map(CraftPlanner.Step::kind).toList();
		assertTrue(kinds.contains(CraftPlanner.Step.Kind.SMELT), "must plan smelting: " + kinds);
		assertTrue(kinds.contains(CraftPlanner.Step.Kind.CRAFT), "must plan crafting");
		CraftPlanner.Step smelt = steps.stream()
			.filter(s -> s.kind() == CraftPlanner.Step.Kind.SMELT).findFirst().orElseThrow();
		assertEquals("minecraft:iron_ingot", smelt.itemId());
		assertEquals(3, smelt.count());
		CraftPlanner.Step gatherOre = steps.stream()
			.filter(s -> "minecraft:raw_iron".equals(s.itemId())).findFirst().orElseThrow();
		assertEquals(3, gatherOre.count());
		// sticks/planks come after the smelting step in execution order
		int smeltIdx = kinds.indexOf(CraftPlanner.Step.Kind.SMELT);
		assertTrue(kinds.subList(0, smeltIdx).stream()
			.allMatch(k -> k == CraftPlanner.Step.Kind.GATHER),
			"raw gathering precedes smelting");
	}

	@Test
	void bareItemIdsAreNamespacedAutomatically() {
		// models frequently send "torch" instead of "minecraft:torch"; both must plan
		List<CraftPlanner.Step> bare = CraftPlanner.plan("torch", 16, Map.of());
		List<CraftPlanner.Step> full = CraftPlanner.plan("minecraft:torch", 16, Map.of());
		assertEquals(full.size(), bare.size(), "bare id must plan identically");
		for (int i = 0; i < full.size(); i++) {
			assertEquals(full.get(i).itemId(), bare.get(i).itemId());
			assertEquals(full.get(i).kind(), bare.get(i).kind());
			assertEquals(full.get(i).count(), bare.get(i).count());
		}
	}

	@Test
	void bareGatherSourcesAndSmeltRulesResolveToo() {
		assertEquals(CraftPlanner.plan("coal", 4, Map.of()),
			CraftPlanner.plan("minecraft:coal", 4, Map.of()));
		assertEquals(CraftPlanner.plan("iron_ingot", 3, Map.of()),
			CraftPlanner.plan("minecraft:iron_ingot", 3, Map.of()));
	}

	@Test
	void unsupportedStillRefusedAndSupportedListIsHelpful() {
		assertThrows(CraftPlanner.UnsupportedItemException.class,
			() -> CraftPlanner.plan("minecraft:ender_pearl", 1, Map.of()));
		String supported = CraftPlanner.supportedItems();
		assertTrue(supported.contains("minecraft:torch"), supported);
		assertTrue(supported.contains("minecraft:oak_log"), supported);
		assertTrue(supported.contains("minecraft:iron_ingot"), supported);
	}
}
