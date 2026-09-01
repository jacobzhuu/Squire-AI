package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.squire.server.blueprint.ProjectSpec;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;

/**
 * 「Lv.N 的工程师到底能盖出什么」——设计文档 §13 那一列承诺的可验证版本。
 *
 * <p>这里验的是<b>夹住</b>而不是拒绝：玩家把参数调过头时，工地该退回他这一级能控制的
 * 样子，而不是整份规格作废。一个因为多点了一下就消失的工地，比一个小一号的工地糟得多。</p>
 */
class EngineerLevelGateTest {

	private final ProfessionConfig config = ProfessionConfig.defaults();

	private static ProfessionData engineer(int level) {
		ProfessionData data = new ProfessionData();
		data.setProfession(SquireProfession.ENGINEER);
		data.level = level;
		return data;
	}

	/** 一份「什么都用上了」的规格，用来看每一级会被砍掉哪些。 */
	private static ProjectSpec everything() {
		return new ProjectSpec(ProjectSpec.Template.LARGE_HOUSE, 47, 47, 5, 3,
			ProjectSpec.Roof.HIP, ProjectSpec.Foundation.RAISED,
			ProjectSpec.WindowStyle.TALL, ProjectSpec.Entrance.SIDE,
			Set.of(ProjectSpec.Module.PORCH, ProjectSpec.Module.TOWER,
				ProjectSpec.Module.CHIMNEY),
			true, true);
	}

	private ProjectSpec at(int level) {
		return SquireEngineerService.clampToLevel(config, engineer(level), everything());
	}

	@Test
	@DisplayName("没有工程师职业的随从不受这套上限约束")
	void withoutTheProfessionNothingIsClamped() {
		assertEquals(everything(),
			SquireEngineerService.clampToLevel(config,null, everything()));
		ProfessionData guard = new ProfessionData();
		guard.setProfession(SquireProfession.GUARD);
		guard.level = 1;
		assertEquals(everything(),
			SquireEngineerService.clampToLevel(config,guard, everything()));
	}

	@Test
	@DisplayName("Lv.1 学徒：9×9、一层、默认结构、无模块、无镜像")
	void apprenticeGetsTheBasicsOnly() {
		ProjectSpec spec = at(1);
		assertEquals(9, spec.width());
		assertEquals(9, spec.depth());
		assertEquals(1, spec.floors());
		assertSame(ProjectSpec.Roof.GABLE, spec.roof());
		assertSame(ProjectSpec.Foundation.NONE, spec.foundation());
		assertSame(ProjectSpec.WindowStyle.SMALL, spec.window());
		assertSame(ProjectSpec.Entrance.CENTER, spec.entrance());
		assertTrue(spec.modules().isEmpty());
		assertFalse(spec.mirrored());
	}

	@Test
	@DisplayName("尺寸上限逐级放宽：9 → 13 → 17 → 21 → 32 → 48")
	void thefootprintCeilingClimbs() {
		assertEquals(9, at(1).width());
		assertEquals(13, at(2).width());
		assertEquals(13, at(3).width());
		assertEquals(17, at(4).width());
		assertEquals(21, at(5).width());
		assertEquals(21, at(7).width());
		// 上限是偶数时会被夹成奇数（中轴对称），所以这里比上限少一格。
		assertEquals(31, at(8).width());
		assertEquals(47, at(10).width());
	}

	@Test
	@DisplayName("Lv.5 之前结构变体一律回到默认")
	void structuralVariantsUnlockAtFive() {
		assertSame(ProjectSpec.Roof.GABLE, at(4).roof());
		assertSame(ProjectSpec.Roof.HIP, at(5).roof());
		assertSame(ProjectSpec.Foundation.RAISED, at(5).foundation());
		assertSame(ProjectSpec.WindowStyle.TALL, at(5).window());
		assertSame(ProjectSpec.Entrance.SIDE, at(5).entrance());
	}

	@Test
	@DisplayName("Lv.6 才能盖多层")
	void multiFloorUnlocksAtSix() {
		assertEquals(1, at(5).floors());
		assertEquals(3, at(6).floors());
	}

	@Test
	@DisplayName("Lv.7 才能镜像，也才能挂模块——但一次只挂得住一个")
	void sevenGivesMirrorAndOneModule() {
		assertFalse(at(6).mirrored());
		assertTrue(at(7).mirrored());
		assertTrue(at(6).modules().isEmpty());
		assertEquals(1, at(7).modules().size(),
			"Lv.7 是「附属模块」，自由组合是 Lv.8");
	}

	@Test
	@DisplayName("Lv.8 才能把模块自由组合成一栋大建筑")
	void eightCombinesModulesFreely() {
		assertEquals(3, at(8).modules().size());
	}

	@Test
	@DisplayName("夹过之后的规格仍然是一份能编译的合法蓝图")
	void clampedSpecsStillCompile() {
		for (int level = 1; level <= 10; level++) {
			ProjectSpec spec = at(level);
			assertEquals(spec, ProjectSpec.parse(spec.blueprintId()).orElseThrow(),
				"Lv" + level + " 夹过之后的 id 必须还能解回同一份规格");
			assertTrue(dev.squire.server.blueprint.ProjectBlueprintFactory.compile(spec)
				.steps().size() > 0, "Lv" + level);
		}
	}
}
