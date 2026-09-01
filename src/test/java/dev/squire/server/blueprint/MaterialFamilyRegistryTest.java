package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class MaterialFamilyRegistryTest {

	@Test
	void candidatesAreWhitelistedBySlotTypeAndCompleteVariantSet() {
		var slot = new Blueprint.MaterialSlot("roof", "屋顶",
			MaterialFamily.SlotType.ROOF, "squire:spruce",
			Set.of("block", "stairs", "slab"));
		var ids = new MaterialFamilyRegistry().candidates(slot).stream()
			.map(MaterialFamily::id).toList();

		assertTrue(ids.contains("squire:oak"));
		assertTrue(ids.contains("squire:stone_bricks"));
		assertFalse(ids.contains("squire:glass"),
			"玻璃族不能因为恰好有 block 变体就进入屋顶白名单");
	}

	@Test
	void aDatapackFamilyCarriesEveryDerivativeExplicitly() {
		MaterialFamily copper = MaterialFamilyRegistry.parse("example:copper_roof", """
			{
			  "displayName": "铜屋顶",
			  "types": ["roof"],
			  "variants": {
			    "block": "example:copper_tiles",
			    "stairs": "example:copper_tile_stairs",
			    "slab": "example:copper_tile_slab"
			  }
			}
			""");
		var slot = new Blueprint.MaterialSlot("roof", "屋顶",
			MaterialFamily.SlotType.ROOF, copper.id(),
			Set.of("block", "stairs", "slab"));

		assertTrue(copper.supports(slot));
		assertEquals("example:copper_tile_stairs", copper.block("stairs"));
		assertEquals("example:copper_tile_stairs",
			copper.representative(MaterialFamily.SlotType.ROOF));
	}
}
