package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 内置蓝图是玩家开箱就能用的东西，形状写错了没人会发现——直到他凑齐材料盖出一个
 * 没有门的盒子。这里守的是每一份内置蓝图的「能不能住人」级别的基本性质。
 */
class BlueprintRegistryTest {

	private final BlueprintRegistry registry = new BlueprintRegistry();

	@Test
	void shipsBundledBlueprintsFromTheResourceCatalog() {
		assertEquals(List.of("shelter_wood", "shelter_stone", "storage_shed",
			"watchtower", "mine_outpost", "field_smithy", "roadside_inn",
			"keepitlevel_residence", "keepitlevel_fountain",
			"keepitlevel_builder_lodge", "keepitlevel_guardtower",
			"keepitlevel_library", "keepitlevel_warehouse"), registry.ids());
		assertEquals(2, registry.byId("watchtower").orElseThrow().tier());
		assertEquals(2, registry.byId("mine_outpost").orElseThrow().tier());
	}

	@Test
	void lookupIsCaseAndWhitespaceForgiving() {
		assertTrue(registry.byId("  WATCHTOWER ").isPresent(),
			"玩家打命令时不该因为大小写落空");
		assertFalse(registry.byId("no_such_thing").isPresent());
	}

	@Test
	void everyBuiltinHasAWayIn() {
		for (Blueprint blueprint : registry.all()) {
			Blueprint.Resolved resolved = blueprint.resolve(BlockPos.ORIGIN,
				Direction.NORTH);
			assertFalse(resolved.toClear().isEmpty(),
				() -> blueprint.id() + " 没有任何负空间——那是个实心盒子，进不去");
			assertFalse(resolved.toPlace().isEmpty(),
				() -> blueprint.id() + " 一格都不用放，那它不是建筑");
		}
	}

	@Test
	void everyBuiltinFitsUnderTheCellCeiling() {
		for (Blueprint blueprint : registry.all()) {
			int cells = blueprint.resolve(BlockPos.ORIGIN, Direction.NORTH).cellCount();
			assertTrue(cells <= Blueprint.MAX_CELLS,
				() -> blueprint.id() + " has " + cells + " cells");
		}
	}

	/** 住宅现在是固定、带梁柱与坡屋顶的成品结构，材料由语义槽位独立替换。 */
	@Test
	void theTimberCottageHasAFixedDetailedShellAndMaterialSlots() {
		Blueprint wood = registry.byId("shelter_wood").orElseThrow();
		assertEquals(9, wood.width());
		assertEquals(9, wood.height());
		assertEquals(7, wood.depth());
		assertEquals(List.of("foundation", "frame", "wall", "floor", "roof", "window"),
			wood.materialSlots().stream().map(Blueprint.MaterialSlot::id).toList());
		assertTrue(wood.steps().stream().anyMatch(BlueprintStep::negative),
			"固定模板仍要清理室内负空间");
		assertTrue(wood.steps().stream().anyMatch(BlueprintStep::optional),
			"窗和装饰可选：缺少装饰材料不应卡住主体");
	}

	/** 矿井前哨站的竖井打到原点以下，包围盒必须含住它——否则地下部分不过保护判定。 */
	@Test
	void theMineOutpostDigsBelowItsOrigin() {
		Blueprint outpost = registry.byId("mine_outpost").orElseThrow();
		var bounds = outpost.bounds(BlockPos.ORIGIN, Direction.NORTH);
		assertTrue(bounds.min().getY() < 0, "竖井在原点以下");
		assertTrue(outpost.requiredAbilities().contains(BlueprintRegistry.ABILITY_EXCAVATE),
			"要挖负空间的蓝图必须声明掘进能力");
	}

	@Test
	void registeringOverwritesByIdSoDatapacksCanRetuneABuiltin() {
		Blueprint replacement = new Blueprint("watchtower", "矮塔", 1,
			Blueprint.Category.DEFENCE, 3, 3, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 2, 2, "minecraft:dirt", "土堆",
					false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空")),
			Set.of());
		registry.register(replacement);
		assertEquals("矮塔", registry.byId("watchtower").orElseThrow().displayName());
		assertEquals(13, registry.size(), "覆盖同名 id，不是多出一份");
	}

	@Test
	void bundledMetadataIsAvailableToUiAndFutureCatalogFilters() {
		Blueprint smithy = registry.byId("field_smithy").orElseThrow();
		assertEquals("Apache-2.0", smithy.metadata().license());
		assertEquals("medieval_frontier", smithy.metadata().style());
		assertTrue(smithy.metadata().tags().contains("workshop"));
		assertEquals("squire:steps", smithy.metadata().format());
		assertTrue(registry.supportedFormats().contains("minecraft:structure_nbt"));
		assertTrue(registry.supportedFormats().contains("structurize:blueprint_v1"));
	}

	@Test
	void redistributedKeepItLevelBuildingsKeepAttributionAndVanillaAdaptation() {
		List<String> imported = List.of("keepitlevel_residence", "keepitlevel_fountain",
			"keepitlevel_builder_lodge", "keepitlevel_guardtower",
			"keepitlevel_library", "keepitlevel_warehouse");
		for (String id : imported) {
			Blueprint blueprint = registry.byId(id).orElseThrow();
			assertEquals("MIT", blueprint.metadata().license());
			assertTrue(blueprint.metadata().author().contains("alt_bier"));
			assertEquals("structurize:blueprint_v1", blueprint.metadata().format());
			assertTrue(blueprint.metadata().source().contains(
				"74259e3da775f467275adb57b076caefdea84d99"));
			assertEquals(registry.catalog().variant(id).orElseThrow().requiredEngineerLevel(), blueprint.minEngineerLevel());
			for (Blueprint.Cell cell : blueprint.resolve(BlockPos.ORIGIN,
					Direction.NORTH).toPlace()) {
				assertTrue(cell.blockId().startsWith("minecraft:"),
					() -> id + " still requires a third-party block: " + cell.blockId());
			}
		}
	}

	@Test
	void categoriesAndEngineerUnlocksComeFromDescriptorMetadata() {
		assertEquals(Blueprint.Category.HOUSING,
			registry.byId("shelter_wood").orElseThrow().category());
		assertEquals(Blueprint.Category.PRODUCTION,
			registry.byId("field_smithy").orElseThrow().category());
		assertEquals(Blueprint.Category.CIVIC,
			registry.byId("roadside_inn").orElseThrow().category());
		assertEquals(1, registry.byId("shelter_wood").orElseThrow().minEngineerLevel());
		assertEquals(registry.catalog().variant("keepitlevel_warehouse").orElseThrow().requiredEngineerLevel(), registry.byId("keepitlevel_warehouse").orElseThrow()
			.minEngineerLevel());
	}
}
