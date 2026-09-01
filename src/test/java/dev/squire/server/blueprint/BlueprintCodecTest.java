package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 蓝图 JSON 是玩家和整合包作者会手写的东西，所以解析失败必须给出说得清的原因。
 *
 * <p>这条测试盯的是「写错了会怎样」：缺字段、坏 JSON、用 air 当方块。前两种要带上
 * 蓝图 id 抛出来（调用方直接读给玩家），第三种要被<b>当成挖除</b>而不是变成一条
 * 消耗 {@code minecraft:air} 物品的施工步骤——那样玩家会看到「还差 12 个 air」。</p>
 */
class BlueprintCodecTest {

	@Test
	void parsesShapeSizeAndStepFlags() {
		Blueprint bp = BlueprintCodec.parse("hut", """
			{
			  "displayName": "小屋", "tier": 2, "category": "STORAGE",
			  "size": [5, 4, 5],
			  "requiredAbilities": ["build.blueprint"],
			  "steps": [
			    {"block": "minecraft:oak_planks", "from": [0,0,0], "to": [4,3,4],
			     "what": "墙体"},
			    {"dig": true, "from": [1,1,1], "to": [3,3,3], "what": "掏空"},
			    {"block": "minecraft:glass_pane", "from": [0,2,2], "optional": true}
			  ]
			}
			""");
		assertEquals("小屋", bp.displayName());
		assertEquals(2, bp.tier());
		assertEquals(Blueprint.Category.STORAGE, bp.category());
		assertEquals(5, bp.width());
		assertEquals(4, bp.height());
		assertEquals(3, bp.steps().size());
		assertTrue(bp.steps().get(1).negative(), "dig:true 是挖除步骤");
		assertTrue(bp.steps().get(2).optional(), "optional 缺料时跳过");
		assertFalse(bp.steps().get(0).optional(), "墙不是可选的");
		assertTrue(bp.requiredAbilities().contains("build.blueprint"));
	}

	@Test
	void omittingToMakesASingleCellStep() {
		Blueprint bp = BlueprintCodec.parse("one", """
			{"size": [1,1,1], "steps": [{"block": "minecraft:torch", "from": [0,0,0]}]}
			""");
		var step = bp.steps().get(0);
		assertEquals(0, step.x2());
		assertEquals(0, step.z2());
	}

	@Test
	void stepOrderDefaultsToArrayOrder() {
		Blueprint bp = BlueprintCodec.parse("ordered", """
			{"size": [2,2,2], "steps": [
			  {"block": "minecraft:stone", "from": [0,0,0], "to": [1,1,1]},
			  {"dig": true, "from": [0,0,0], "to": [0,0,0]}
			]}
			""");
		assertEquals(0, bp.steps().get(0).order());
		assertEquals(1, bp.steps().get(1).order(),
			"作者写的顺序就是施工顺序，不该逼他再手填一遍序号");
	}

	/** 用 air 当方块表达的一定是「挖掉」，绝不能变成一条消耗空气物品的施工步骤。 */
	@Test
	void airBlockIsTreatedAsADigStep() {
		Blueprint bp = BlueprintCodec.parse("air", """
			{"size": [2,2,2], "steps": [
			  {"block": "minecraft:stone", "from": [0,0,0], "to": [1,1,1]},
			  {"block": "minecraft:air", "from": [1,1,1]}
			]}
			""");
		assertTrue(bp.steps().get(1).negative());
		assertTrue(bp.resolve(net.minecraft.util.math.BlockPos.ORIGIN,
			net.minecraft.util.math.Direction.NORTH).toClear().size() == 1);
	}

	@Test
	void configurableStepsResolveOneFamilyAndRotateTheirBlockState() {
		Blueprint bp = BlueprintCodec.parse("roof_piece", """
			{
			  "size": [2,1,1],
			  "materialSlots": [{
			    "id": "roof", "displayName": "屋顶", "type": "ROOF",
			    "defaultFamily": "squire:oak", "requiredVariants": ["stairs"]
			  }],
			  "steps": [
			    {"material": {"slot": "roof", "variant": "stairs"},
			     "from": [0,0,0], "properties": {"facing": "north"}},
			    {"block": "minecraft:oak_log", "from": [1,0,0],
			     "properties": {"axis": "x"}}
			  ]
			}
			""");

		Blueprint.Resolved resolved = bp.resolve(BlockPos.ORIGIN, Direction.EAST,
			Map.of("roof", "squire:stone_bricks"), MaterialFamilyRegistry.defaults());
		Blueprint.Cell stair = resolved.toPlace().stream()
			.filter(cell -> cell.blockId().contains("stairs")).findFirst().orElseThrow();
		Blueprint.Cell log = resolved.toPlace().stream()
			.filter(cell -> cell.blockId().endsWith("oak_log")).findFirst().orElseThrow();
		assertEquals("minecraft:stone_brick_stairs", stair.blockId());
		assertEquals("east", stair.properties().get("facing"));
		assertEquals("z", log.properties().get("axis"));
	}

	@Test
	void everyFailureNamesTheBlueprintAndTheField() {
		assertTrue(assertThrows(IllegalArgumentException.class,
				() -> BlueprintCodec.parse("nosize", "{\"steps\": []}"))
			.getMessage().contains("nosize"));
		assertTrue(assertThrows(IllegalArgumentException.class,
				() -> BlueprintCodec.parse("nosteps", "{\"size\": [1,1,1]}"))
			.getMessage().contains("steps"));
		assertTrue(assertThrows(IllegalArgumentException.class,
				() -> BlueprintCodec.parse("broken", "not json at all"))
			.getMessage().contains("broken"));
		assertTrue(assertThrows(IllegalArgumentException.class,
				() -> BlueprintCodec.parse("noblock", """
					{"size": [1,1,1], "steps": [{"from": [0,0,0]}]}
					"""))
			.getMessage().contains("dig"),
			"既没有 block 也没有 dig：错误信息要直接说出两个选项");
	}
}
