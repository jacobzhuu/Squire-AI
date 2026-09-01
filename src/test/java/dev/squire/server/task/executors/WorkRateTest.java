package dev.squire.server.task.executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.squire.server.profile.Ability;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;

/**
 * 能力和特质对<b>工作节奏</b>的影响。
 *
 * <p>刻意把每 tick 的格数抽成一个纯函数，而不是在执行器循环里现算：节奏是玩家最容易
 * 察觉、也最容易被悄悄改坏的东西，值得被一条断言钉住。同时这里也守住一条设计红线——
 * 能力和特质改的是<b>速度</b>，绝不改<b>每格的成本</b>：材料账不受任何加成影响。</p>
 */
class WorkRateTest {

	private static SquireProfile with(Ability ability, Trait trait) {
		SquireProfile profile = new SquireProfile();
		profile.roleId = Role.BUILDER.id();
		if (ability != null) {
			profile.unlockedAbilities.add(ability.id());
			profile.equippedAbilities.add(ability.id());
		}
		if (trait != null) {
			profile.traits.add(trait.id());
		}
		return profile;
	}

	@Test
	void aPlainCompanionBuildsAtTheBaseRate() {
		int base = BlueprintBuildExecutor.blocksPerTick(null);
		assertEquals(base, BlueprintBuildExecutor.blocksPerTick(new SquireProfile()),
			"没有职业的伙伴按基础速度盖，和以前一样");
		assertTrue(base > 0);
	}

	@Test
	void doubleWorkSpeedDoublesTheBuildRate() {
		int base = BlueprintBuildExecutor.blocksPerTick(null);
		assertEquals(base * 2,
			BlueprintBuildExecutor.blocksPerTick(with(Ability.BUILD_FAST, null)));
	}

	@Test
	void unlockedButUnequippedChangesNothing() {
		SquireProfile profile = new SquireProfile();
		profile.roleId = Role.BUILDER.id();
		profile.unlockedAbilities.add(Ability.BUILD_FAST.id());
		assertEquals(BlueprintBuildExecutor.blocksPerTick(null),
			BlueprintBuildExecutor.blocksPerTick(profile),
			"解锁不等于装备——槽位限制才是取舍的来源");
	}

	@Test
	void diligenceStacksOnTopOfTheAbility() {
		int base = BlueprintBuildExecutor.blocksPerTick(null);
		int diligentOnly = BlueprintBuildExecutor.blocksPerTick(
			with(null, Trait.DILIGENT));
		int both = BlueprintBuildExecutor.blocksPerTick(
			with(Ability.BUILD_FAST, Trait.DILIGENT));
		assertTrue(diligentOnly > base, "勤勉的伙伴干活快一点");
		assertEquals(base * 2 + (diligentOnly - base), both,
			"能力先翻倍，特质再加一个固定量");
	}

	@Test
	void deepDiggingDoublesTheExcavationRate() {
		SquireProfile digger = new SquireProfile();
		digger.roleId = Role.EXCAVATOR.id();
		digger.unlockedAbilities.add(Ability.EXCAVATE_DEEP.id());
		digger.equippedAbilities.add(Ability.EXCAVATE_DEEP.id());
		assertEquals(ExcavateExecutor.blocksPerTick(null) * 2,
			ExcavateExecutor.blocksPerTick(digger));
	}

	@Test
	void diggingIsSlowerThanBuildingSoItLooksLikeDigging() {
		assertTrue(ExcavateExecutor.blocksPerTick(null)
			< BlueprintBuildExecutor.blocksPerTick(null));
	}
}
