package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 工程师参数化蓝图：<b>每一个等级承诺的参数，都要真的改变盖出来的东西</b>。
 *
 * <p>这份测试守的是设计文档 §13 那一列能力。它不检查美学，只检查
 * 「按这个参数盖出来的房子确实有那个特征」——旋转、镜像、多层、变体、模块、复合，
 * 一条一条对。</p>
 */
class ProjectBlueprintTest {

	private static ProjectSpec house() {
		return ProjectSpec.Template.HOUSE.defaults();
	}

	private static Blueprint compile(ProjectSpec spec) {
		return ProjectBlueprintFactory.compile(spec);
	}

	private static Blueprint.Resolved resolve(ProjectSpec spec) {
		return compile(spec).resolve(BlockPos.ORIGIN, Direction.NORTH);
	}

	// ================================================================== 基础

	@Test
	@DisplayName("盖出来的是一层壳，不是实心盒——内部真的被掏空了")
	void theBuildingIsHollow() {
		Blueprint.Resolved resolved = resolve(house());
		ProjectSpec spec = house();
		BlockPos inside = new BlockPos(spec.width() / 2, 2, spec.depth() / 2);
		assertTrue(resolved.toPlace().stream().noneMatch(c -> c.pos().equals(inside)),
			"房子中间不该被填成实心");
	}

	@Test
	@DisplayName("南墙上有一个能走进去的门洞")
	void thereIsADoorway() {
		ProjectSpec spec = house();
		Blueprint.Resolved resolved = resolve(spec);
		BlockPos door = new BlockPos(spec.width() / 2, 1, spec.depth() - 1);
		assertTrue(resolved.toPlace().stream().noneMatch(c -> c.pos().equals(door)),
			"门口不该被墙堵上");
	}

	@Test
	@DisplayName("每一条消耗材料的步骤都指向一个真实存在的材料槽")
	void everyMaterialStepHasASlot() {
		Blueprint blueprint = compile(house());
		for (BlueprintStep step : blueprint.steps()) {
			if (step.usesMaterial()) {
				assertNotNull(blueprint.materialSlot(step.materialSlot()),
					step.what() + " 用了不存在的材料槽 " + step.materialSlot());
			}
		}
	}

	@Test
	@DisplayName("默认调色板下每一格都能解析出一个具体方块")
	void thePaletteResolves() {
		Blueprint blueprint = compile(house());
		Blueprint.Resolved resolved = blueprint.resolve(BlockPos.ORIGIN, Direction.NORTH);
		assertFalse(resolved.toPlace().isEmpty());
		for (Blueprint.Cell cell : resolved.toPlace()) {
			assertFalse(cell.blockId() == null || cell.blockId().isBlank(),
				cell.what() + " 没有解析出方块");
		}
	}

	@Test
	@DisplayName("单份蓝图不超过 8192 格上限——包括最大的复合营地")
	void everythingFitsInTheCellBudget() {
		for (ProjectSpec.Template template : ProjectSpec.Template.values()) {
			ProjectSpec spec = template.defaults();
			assertTrue(resolve(spec).cellCount() <= Blueprint.MAX_CELLS,
				template.id() + " 默认尺寸就超过了单份蓝图上限");
		}
		ProjectSpec biggest = ProjectSpec.Template.OUTPOST.defaults()
			.withSize(47, 47).withFloors(3);
		assertTrue(resolve(biggest).cellCount() <= Blueprint.MAX_CELLS,
			"Lv.10 承诺的 48×48 必须真的摆得下，实际 " + resolve(biggest).cellCount()
				+ " 格");
	}

	// ================================================================== 各级能力

	@Test
	@DisplayName("Lv.5 结构变体：三种屋顶盖出三种不同的形状")
	void roofVariantsDiffer() {
		// 刻意<b>不</b>比方块数量：9×9 的三种屋顶恰好都是 81 格，
		// 用数量比会得到「三者相同」这个错误结论，而形状其实完全不一样。
		var flat = shape(house().withRoof(ProjectSpec.Roof.FLAT));
		var gable = shape(house().withRoof(ProjectSpec.Roof.GABLE));
		var hip = shape(house().withRoof(ProjectSpec.Roof.HIP));
		assertFalse(flat.equals(gable), "平顶和人字顶不该一模一样");
		assertFalse(gable.equals(hip), "人字顶和四坡顶不该一模一样");
		assertFalse(flat.equals(hip), "平顶和四坡顶不该一模一样");
	}

	/** 一份形状的可比较指纹：每一格的位置。 */
	private static Set<BlockPos> shape(ProjectSpec spec) {
		return resolve(spec).toPlace().stream().map(Blueprint.Cell::pos)
			.collect(java.util.stream.Collectors.toSet());
	}

	@Test
	@DisplayName("Lv.5 结构变体：地基把整栋楼抬起来")
	void foundationRaisesTheBuilding() {
		int none = compile(house()).height();
		int raised = compile(house()
			.withFoundation(ProjectSpec.Foundation.RAISED)).height();
		assertTrue(raised > none, "抬高地基之后总高应该更高");
	}

	@Test
	@DisplayName("Lv.5 结构变体：窗户样式真的改变开窗数量")
	void windowVariantsDiffer() {
		long small = windowCells(house().withWindow(ProjectSpec.WindowStyle.SMALL));
		long wide = windowCells(house().withWindow(ProjectSpec.WindowStyle.WIDE));
		long tall = windowCells(house().withWindow(ProjectSpec.WindowStyle.TALL));
		assertTrue(wide > small, "宽窗应该比小窗多");
		assertTrue(tall > small, "高窗应该比小窗多");
	}

	private static long windowCells(ProjectSpec spec) {
		return resolve(spec).toPlace().stream()
			.filter(cell -> cell.what().contains("窗")).count();
	}

	@Test
	@DisplayName("Lv.5 结构变体：入口位置真的会挪")
	void entranceVariantMovesTheDoor() {
		ProjectSpec side = house().withEntrance(ProjectSpec.Entrance.SIDE);
		Blueprint.Resolved resolved = resolve(side);
		BlockPos centre = new BlockPos(side.width() / 2, 1, side.depth() - 1);
		BlockPos sideDoor = new BlockPos(1, 1, side.depth() - 1);
		assertTrue(resolved.toPlace().stream().anyMatch(c -> c.pos().equals(centre)),
			"改成侧门之后，正中那一格应该是墙");
		assertTrue(resolved.toPlace().stream().noneMatch(c -> c.pos().equals(sideDoor)),
			"侧门那一格应该是通的");
	}

	@Test
	@DisplayName("Lv.6 多层：层数真的堆上去，而且有楼梯")
	void multipleFloorsStack() {
		int one = compile(house().withFloors(1)).height();
		int two = compile(house().withFloors(2)).height();
		int three = compile(house().withFloors(3)).height();
		assertTrue(two > one && three > two, one + "/" + two + "/" + three);
		assertTrue(resolve(house().withFloors(2)).toPlace().stream()
			.anyMatch(cell -> "楼梯".equals(cell.what())), "两层楼必须有楼梯");
	}

	@Test
	@DisplayName("Lv.6 多层：楼梯口是通的，人上得去")
	void thereIsAStairwell() {
		ProjectSpec spec = house().withFloors(2);
		int ceiling = spec.wallHeight() + 1;
		// 默认 9×9、高 4 的直梯在 x=4 留头部净空，x=5 是最后一级。
		BlockPos hole = new BlockPos(4, ceiling, 1);
		assertTrue(resolve(spec).toPlace().stream().noneMatch(c -> c.pos().equals(hole)),
			"二层楼板上必须留出楼梯口");
	}

	@Test
	@DisplayName("层间楼梯逐级相邻、逐级上升，而且每一级都有明确朝向")
	void stairsFormOneOrderedWalkablePath() {
		for (int size : new int[] {5, 7, 13}) {
			ProjectSpec spec = house().withSize(size, size).withWallHeight(6).withFloors(2);
			var stairs = resolve(spec).toPlace().stream()
				.filter(cell -> cell.what().equals("楼梯"))
				.sorted(java.util.Comparator.comparingInt(cell -> cell.pos().getY()))
				.toList();
			assertEquals(spec.wallHeight() + 1, stairs.size(), "尺寸 " + size);
			for (int i = 0; i < stairs.size(); i++) {
				assertTrue(stairs.get(i).properties().containsKey("facing"),
					"楼梯方向不能交给方块默认值");
				if (i == 0) continue;
				BlockPos before = stairs.get(i - 1).pos();
				BlockPos current = stairs.get(i).pos();
				assertEquals(1, current.getY() - before.getY());
				assertEquals(1, Math.abs(current.getX() - before.getX())
					+ Math.abs(current.getZ() - before.getZ()),
					"相邻两级必须在水平方向接壤");
			}
		}
	}

	@Test
	@DisplayName("三种屋顶都有完整密封层，人字顶楼梯随建筑旋转")
	void roofsAreSealedAndDirectional() {
		for (ProjectSpec.Roof roof : ProjectSpec.Roof.values()) {
			ProjectSpec spec = house().withRoof(roof);
			long sealed = resolve(spec).toPlace().stream()
				.filter(cell -> cell.what().equals("屋顶密封层")).count();
			assertEquals(spec.width() * spec.depth(), sealed, roof.id());
		}
		ProjectSpec gable = house().withRoof(ProjectSpec.Roof.GABLE);
		var north = compile(gable).resolve(BlockPos.ORIGIN, Direction.NORTH).toPlace()
			.stream().filter(cell -> cell.what().equals("斜屋顶")).findFirst().orElseThrow();
		var east = compile(gable).resolve(BlockPos.ORIGIN, Direction.EAST).toPlace()
			.stream().filter(cell -> cell.what().equals("斜屋顶")).findFirst().orElseThrow();
		assertFalse(north.properties().get("facing").equals(east.properties().get("facing")),
			"建筑转向后屋顶楼梯也必须跟着转");
	}

	@Test
	@DisplayName("Lv.7 镜像：沿 X 翻转之后侧门跑到另一边，方块总数不变")
	void mirroringFlipsTheLayout() {
		ProjectSpec side = house().withEntrance(ProjectSpec.Entrance.SIDE);
		ProjectSpec mirrored = side.withMirror(true, false);
		assertEquals(resolve(side).toPlace().size(), resolve(mirrored).toPlace().size(),
			"镜像只是翻转，不该多出或少掉方块");
		BlockPos leftDoor = new BlockPos(1, 1, side.depth() - 1);
		BlockPos rightDoor = new BlockPos(side.width() - 2, 1, side.depth() - 1);
		assertTrue(resolve(side).toPlace().stream()
			.noneMatch(c -> c.pos().equals(leftDoor)));
		assertTrue(resolve(mirrored).toPlace().stream()
			.noneMatch(c -> c.pos().equals(rightDoor)), "翻转之后门应该在另一侧");
	}

	@Test
	@DisplayName("Lv.7 附属模块：每一个模块都真的往世界里加东西")
	void everyModuleAddsSomething() {
		int plain = resolve(house()).toPlace().size();
		for (ProjectSpec.Module module : ProjectSpec.Module.values()) {
			ProjectSpec withModule = house().toggleModule(module);
			assertTrue(resolve(withModule).toPlace().size() > plain,
				module.id() + " 装上去什么都没变，等于这个按钮是假的");
		}
	}

	@Test
	@DisplayName("Lv.8 模块化：模块可以叠加，而且互不覆盖")
	void modulesCombine() {
		ProjectSpec one = house().toggleModule(ProjectSpec.Module.PORCH);
		ProjectSpec two = one.toggleModule(ProjectSpec.Module.STORAGE_WING);
		ProjectSpec three = two.toggleModule(ProjectSpec.Module.TOWER);
		assertTrue(resolve(three).toPlace().size() > resolve(two).toPlace().size());
		assertTrue(resolve(two).toPlace().size() > resolve(one).toPlace().size());
	}

	@Test
	@DisplayName("Lv.9 复合蓝图：一份蓝图里真的有好几栋，还有围栏和大门")
	void compoundHoldsSeveralBuildings() {
		Blueprint.Resolved resolved = resolve(ProjectSpec.Template.OUTPOST.defaults());
		assertTrue(resolved.toPlace().stream()
			.anyMatch(cell -> cell.what().contains("围栏")), "营地要有围栏");
		assertTrue(resolved.toPlace().stream()
			.anyMatch(cell -> cell.what().contains("塔")), "营地要有哨塔");
		BlockPos gate = new BlockPos(
			ProjectSpec.Template.OUTPOST.defaultWidth() / 2, 1,
			ProjectSpec.Template.OUTPOST.defaultDepth() - 1);
		assertTrue(resolved.toPlace().stream().noneMatch(c -> c.pos().equals(gate)),
			"大门那一格应该是通的");
	}

	// ================================================================== id 往返

	@Test
	@DisplayName("规格编码进 id 之后能原样解回来——摆放只存一个字符串就够了")
	void idRoundTrips() {
		ProjectSpec spec = new ProjectSpec(ProjectSpec.Template.LARGE_HOUSE, 13, 11, 5, 2,
			ProjectSpec.Roof.HIP, ProjectSpec.Foundation.RAISED,
			ProjectSpec.WindowStyle.TALL, ProjectSpec.Entrance.SIDE,
			Set.of(ProjectSpec.Module.PORCH, ProjectSpec.Module.TOWER), true, false);
		assertEquals(spec, ProjectSpec.parse(spec.blueprintId()).orElseThrow());
	}

	@Test
	@DisplayName("每个模板的默认规格都能往返")
	void everyTemplateRoundTrips() {
		for (ProjectSpec.Template template : ProjectSpec.Template.values()) {
			ProjectSpec spec = template.defaults();
			assertEquals(spec, ProjectSpec.parse(spec.blueprintId()).orElseThrow(),
				template.id());
		}
	}

	@Test
	@DisplayName("认不出的 id 一律返回空，绝不猜一个形状出来")
	void unknownIdsAreRefused() {
		assertTrue(ProjectSpec.parse(null).isEmpty());
		assertTrue(ProjectSpec.parse("shelter_wood").isEmpty());
		assertTrue(ProjectSpec.parse("project/house/9x9x4").isEmpty());
		assertTrue(ProjectSpec.parse("project/nope/9x9x4/f1/gable-none-small-center/none/-")
			.isEmpty());
		assertTrue(ProjectSpec.parse("project/house/9x9x4/f1/gable-none-small-center/"
			+ "spaceship/-").isEmpty());
	}

	@Test
	@DisplayName("尺寸被夹进合法区间，而不是把工地炸掉")
	void sizesAreClampedNotRejected() {
		ProjectSpec tiny = house().withSize(-5, 0);
		assertEquals(ProjectSpec.MIN_FOOTPRINT, tiny.width());
		assertEquals(ProjectSpec.MIN_FOOTPRINT, tiny.depth());
		ProjectSpec huge = house().withSize(9999, 9999);
		assertTrue(huge.width() <= ProjectBlueprintFactory.MAX_FOOTPRINT + 1);
		assertEquals(3, house().withFloors(99).floors());
	}

	@Test
	@DisplayName("足印一律是奇数，而且向下取——绝不越过刚夹好的上限")
	void footprintsAreOdd() {
		assertEquals(9, house().withSize(10, 10).width());
		assertEquals(9, house().withSize(10, 10).depth());
		assertEquals(ProjectSpec.MIN_FOOTPRINT, house().withSize(5, 5).width(),
			"下限本身是奇数，不该被再砍一格");
	}

	@Test
	@DisplayName("注册表按 id 就能拿到参数化蓝图，重启后形状完全一致")
	void theRegistryResolvesGeneratedIds() {
		ProjectSpec spec = house().withFloors(2).withRoof(ProjectSpec.Roof.HIP);
		BlueprintRegistry registry = new BlueprintRegistry();
		Blueprint first = registry.byId(spec.blueprintId()).orElseThrow();
		Blueprint second = registry.byId(spec.blueprintId()).orElseThrow();
		assertEquals(first.steps().size(), second.steps().size());
		assertEquals(spec.blueprintId(), first.id());
	}
}
