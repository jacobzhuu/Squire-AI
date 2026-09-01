package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HouseSpecTest {

	@Test
	void legacyHouseRequestsMapToTheTwoFixedShelters() {
		assertEquals("shelter_wood", HouseSpec.defaults().blueprintId());
		assertEquals("shelter_wood", new HouseSpec(9, 5, 5,
			HouseSpec.Material.SPRUCE, HouseSpec.Roof.FLAT).blueprintId());
		assertEquals("shelter_stone", HouseSpec.stoneDefaults().blueprintId());
		assertEquals("shelter_stone", new HouseSpec(5, 9, 3,
			HouseSpec.Material.COBBLESTONE, HouseSpec.Roof.FLAT).blueprintId());
		assertEquals(HouseSpec.defaults(),
			HouseSpec.parseBlueprintId("shelter_wood").orElseThrow());
		assertEquals(HouseSpec.stoneDefaults(),
			HouseSpec.parseBlueprintId("shelter_stone").orElseThrow());
	}

	@Test
	void generatedBlueprintStaysInsideItsDeclaredShape() {
		var spec = new HouseSpec(9, 5, 5, HouseSpec.Material.SPRUCE,
			HouseSpec.Roof.GABLE);
		Blueprint blueprint = HouseBlueprintFactory.compile(spec);
		assertEquals(9, blueprint.width());
		assertEquals(5, blueprint.depth());
		assertTrue(blueprint.resolve(net.minecraft.util.math.BlockPos.ORIGIN,
			net.minecraft.util.math.Direction.NORTH).cellCount() <= Blueprint.MAX_CELLS);
	}

	@Test
	void malformedOrUnsupportedIdsFailClosed() {
		assertFalse(HouseSpec.parseBlueprintId("generated_house/oak/8x7x4/gable")
			.isPresent());
		assertFalse(HouseSpec.parseBlueprintId("generated_house/diamond/7x7x4/flat")
			.isPresent());
	}
}
