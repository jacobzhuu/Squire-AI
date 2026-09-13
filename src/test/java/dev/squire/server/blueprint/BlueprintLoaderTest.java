package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

class BlueprintLoaderTest {
	@Test
	void materialBindingsOnlyChangeTheDeclaredBlockAndRegion() throws Exception {
		Blueprint b = new BlueprintLoader().load("bound", """
			{"schemaVersion":1,"format":"squire:steps","displayName":"Bound","size":[1,8,1],
			 "materialSlots":[{"id":"roof","displayName":"Roof","type":"ROOF","defaultFamily":"squire:spruce","requiredVariants":["stairs"]}],
			 "materialBindings":[{"block":"minecraft:spruce_stairs","slot":"roof","variant":"stairs","from":[0,5,0],"to":[8,8,8]}],
			 "steps":[{"block":"minecraft:spruce_stairs","from":[0,2,0]},
			          {"block":"minecraft:spruce_stairs","from":[0,6,0],"properties":{"facing":"east","half":"top"}}]}
			""", resource -> null);
		assertTrue(!b.steps().get(0).usesMaterial());
		assertEquals("roof", b.steps().get(1).materialSlot());
		assertEquals("top", b.steps().get(1).properties().get("half"));
		assertEquals("east", b.steps().get(1).properties().get("facing"));
	}
	@Test
	void descriptorMetadataAndStepsJoinTheSharedResolvedPipeline() throws Exception {
		Blueprint blueprint = new BlueprintLoader().load("fallback", """
			{
			  "schemaVersion":1,"id":"loaded_house","format":"squire:steps",
			  "displayName":"Loaded house","category":"SHELTER","size":[2,2,1],
			  "metadata":{"author":"Test builder","license":"CC0-1.0","style":"test"},
			  "steps":[
			    {"block":"minecraft:oak_stairs","from":[0,0,0],"properties":{"facing":"north"}},
			    {"dig":true,"from":[1,0,0]}
			  ]
			}
			""", resource -> null);
		Blueprint.Resolved resolved = blueprint.resolve(BlockPos.ORIGIN, Direction.EAST);
		assertEquals("Test builder", blueprint.metadata().author());
		assertEquals("east", resolved.toPlace().get(0).properties().get("facing"));
		ConstructionPlan plan = ConstructionPlan.create(resolved, resolved.toPlace());
		assertEquals(new HashSet<>(resolved.toPlace()), new HashSet<>(plan.cells()),
			"preview/resolution and construction must consume the same final cells");
	}

	@Test
	void unknownFormatsFailWithTheDescriptorId() {
		IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
			() -> new BlueprintLoader().load("demo", """
				{"schemaVersion":1,"format":"worldedit:schem"}
				""", resource -> null));
		assertTrue(bad.getMessage().contains("demo"));
		assertTrue(bad.getMessage().contains("worldedit:schem"));
	}
}
