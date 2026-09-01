package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 蓝图的两个核心不变量：旋转是双射，展平只留每格的最终目标。
 *
 * <p>为什么这两条重要：旋转错一格，门就开在墙里，玩家进不去而且看不出为什么；
 * 展平如果照字面执行「先砌实心盒再掏空」，一栋 7×7 的房子会先花掉一百多块木板
 * 再挖掉，材料账当场变成假的——而「资源真实流动」正是这一期存在的理由。</p>
 */
class BlueprintTest {

	/** 一份 3×3×3 的小壳：实心盒 + 掏空正中一格。 */
	private static Blueprint shell() {
		return new Blueprint("test_shell", "测试壳", 1, Blueprint.Category.SHELTER,
			3, 3, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 2, 2, "minecraft:stone",
					"壳", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空")),
			Set.of());
	}

	@Test
	void flatteningKeepsOnlyTheLastStepThatCoversACell() {
		Blueprint.Resolved resolved = shell().resolve(BlockPos.ORIGIN, Direction.NORTH);
		assertEquals(26, resolved.toPlace().size(),
			"实心 27 格减掉掏空的 1 格 = 只砌 26 格的壳，而不是先砌 27 再挖 1");
		assertEquals(List.of(new BlockPos(1, 1, 1)), resolved.toClear(),
			"被后一步覆盖成空气的那一格进挖除清单");
	}

	@Test
	void placementOrderRisesAndDiggingOrderDescends() {
		Blueprint.Resolved resolved = new Blueprint("t", "t", 1,
			Blueprint.Category.SHELTER, 3, 3, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 2, 2, "minecraft:stone", "壳",
					false),
				BlueprintStep.dig(1, 1, 0, 1, 1, 2, 1, "竖井")),
			Set.of()).resolve(BlockPos.ORIGIN, Direction.NORTH);
		List<Blueprint.Cell> place = resolved.toPlace();
		for (int i = 1; i < place.size(); i++) {
			assertTrue(place.get(i - 1).pos().getY() <= place.get(i).pos().getY(),
				"建造自下而上，看起来才像在盖房子");
		}
		List<BlockPos> clear = resolved.toClear();
		for (int i = 1; i < clear.size(); i++) {
			assertTrue(clear.get(i - 1).getY() >= clear.get(i).getY(),
				"挖除自上而下，伙伴不会把自己埋了");
		}
	}

	@Test
	void cellsNoStepCoversAreNeverTouched() {
		Blueprint.Resolved resolved = shell().resolve(BlockPos.ORIGIN, Direction.NORTH);
		Set<BlockPos> touched = new HashSet<>(resolved.toClear());
		resolved.toPlace().forEach(cell -> touched.add(cell.pos()));
		assertFalse(touched.contains(new BlockPos(3, 0, 0)),
			"足印以外一格都不碰");
		assertEquals(27, touched.size(), "恰好是 3×3×3 的足印");
	}

	@Test
	void rotationIsABijectionOfTheFootprint() {
		for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH,
				Direction.EAST, Direction.WEST)) {
			Set<BlockPos> seen = new HashSet<>();
			for (int x = 0; x < 7; x++) {
				for (int z = 0; z < 5; z++) {
					BlockPos rotated = Blueprint.rotate(x, 0, z, facing, 7, 5);
					assertTrue(seen.add(rotated),
						() -> "旋转把两格映到了同一处：" + facing);
				}
			}
			assertEquals(35, seen.size(), "旋转不能吞掉格子：" + facing);
		}
	}

	@Test
	void eastAndWestSwapTheFootprintSpan() {
		Blueprint wide = new Blueprint("wide", "wide", 1, Blueprint.Category.SHELTER,
			7, 3, 5,
			List.of(BlueprintStep.place(0, 0, 0, 0, 6, 2, 4, "minecraft:stone", "壳",
				false)),
			Set.of());
		assertEquals(7, wide.placedWidth(Direction.NORTH));
		assertEquals(5, wide.placedDepth(Direction.NORTH));
		assertEquals(5, wide.placedWidth(Direction.EAST), "东西朝向宽深互换");
		assertEquals(7, wide.placedDepth(Direction.EAST));
	}

	/**
	 * 旋转约定必须和 {@code HouseTemplate.doorPos} 逐个方向对齐，否则从模板转过来的
	 * 房子门会开错面——这正是当初「模型盖出没有门的盒子」要避免的那类问题。
	 */
	@Test
	void rotationAgreesWithTheHouseTemplateDoorSide() {
		int w = 7;
		int d = 7;
		BlockPos doorLocal = new BlockPos(w / 2, 1, d - 1); // 模板在正北时的门位
		assertEquals(new BlockPos(w / 2, 1, 0),
			Blueprint.rotate(doorLocal.getX(), 1, doorLocal.getZ(), Direction.SOUTH, w, d),
			"朝南时门开在 z=0 那面");
		assertEquals(new BlockPos(0, 1, d / 2),
			Blueprint.rotate(doorLocal.getX(), 1, doorLocal.getZ(), Direction.EAST, w, d),
			"朝东时门开在 x=0 那面");
		assertEquals(new BlockPos(w - 1, 1, d / 2),
			Blueprint.rotate(doorLocal.getX(), 1, doorLocal.getZ(), Direction.WEST, w, d),
			"朝西时门开在 x=w-1 那面");
	}

	/**
	 * 包围盒取步骤并集，不取声明尺寸。矿井前哨站的竖井 y 是负的，照声明尺寸算会把
	 * 地下部分漏在保护判定和幽灵预览之外——也就是「没经过判定就动了土」。
	 */
	@Test
	void boundsCoverStepsThatReachBelowTheOrigin() {
		Blueprint withShaft = new Blueprint("shaft", "shaft", 1,
			Blueprint.Category.MINE, 3, 2, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 1, 2, "minecraft:stone", "壳",
					false),
				BlueprintStep.dig(1, 1, -4, 1, 1, 0, 1, "竖井")),
			Set.of());
		var bounds = withShaft.bounds(BlockPos.ORIGIN, Direction.NORTH);
		assertEquals(-4, bounds.min().getY(), "包围盒必须含住竖井底");
		assertEquals(1, bounds.max().getY());
	}

	@Test
	void aNegativeStepMustTargetAir() {
		assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> new BlueprintStep(0, 0, 0, 0, 1, 1, 1, "minecraft:stone",
					"坏的", false, true))
			.getMessage().contains("air"),
			"挖除步骤只能是空气，否则执行器不知道该放还是该挖");
	}
}
