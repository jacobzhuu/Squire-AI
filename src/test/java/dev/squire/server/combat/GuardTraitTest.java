package dev.squire.server.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.squire.server.profile.Ability;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;

/**
 * 性格与能力对战斗的影响。
 *
 * <p>性格只改撤退线、警戒/追击范围等<b>行为参数</b>，
 * 不改伤害，也不解锁职业能力。这条区别是第 2 期反复守的红线：护卫升级绝不给攻击力 +2。
 * 所以这里除了验这些阈值，也验它们是从 {@link SquireProfile} 派生出来的纯函数——
 * 可以被一条断言钉住，而不是「他今天好像更谨慎了」。</p>
 */
class GuardTraitTest {

	private static SquireProfile withTrait(Trait trait) {
		SquireProfile profile = new SquireProfile();
		profile.roleId = Role.GUARDIAN.id();
		if (trait != null) {
			profile.traits.add(trait.id());
		}
		return profile;
	}

	@Test
	void theDefaultRetreatLineIsUsedWhenThereIsNoProfile() {
		float base = GuardRuntime.retreatFraction(null);
		assertEquals(base, GuardRuntime.retreatFraction(withTrait(null)));
		assertTrue(base > 0f && base < 1f);
	}

	@Test
	void aCautiousCompanionRetreatsEarlierAndARecklessOneLater() {
		float base = GuardRuntime.retreatFraction(null);
		assertTrue(GuardRuntime.retreatFraction(withTrait(Trait.CAUTIOUS)) > base,
			"谨慎的更早收手");
		assertTrue(GuardRuntime.retreatFraction(withTrait(Trait.RECKLESS)) < base,
			"莽撞的更晚收手");
	}

	@Test
	void escortWidensTheGuardRadiusOnlyWhenEquipped() {
		GuardPolicy policy = GuardPolicy.enabled(UUID.randomUUID(), UUID.randomUUID(),
			16, 0L);
		SquireProfile plain = withTrait(null);
		assertEquals(16.0, GuardRuntime.guardRadius(policy, plain));
		assertEquals(16.0, GuardRuntime.guardRadius(policy, null),
			"读不到档案时按基础行为走，不该悄悄变强");

		plain.unlockedAbilities.add(Ability.COMBAT_ESCORT.id());
		assertEquals(16.0, GuardRuntime.guardRadius(policy, plain),
			"解锁不等于装备");
		plain.equippedAbilities.add(Ability.COMBAT_ESCORT.id());
		assertTrue(GuardRuntime.guardRadius(policy, plain) > 16.0,
			"装上「护送」之后他才敢跟你走远一点");
	}

	@Test
	void recklessWidensAggroAndPreStanceChaseWithoutOpeningAbilities() {
		GuardPolicy policy = GuardPolicy.enabled(UUID.randomUUID(), UUID.randomUUID(),
			16, 0L);
		SquireProfile reckless = withTrait(Trait.RECKLESS);
		assertEquals(20.0, GuardRuntime.guardRadius(policy, reckless));

		var config = dev.squire.server.profession.ProfessionConfig.defaults();
		var guard = new dev.squire.server.profession.ProfessionData();
		guard.setProfession(dev.squire.server.profession.SquireProfession.GUARD);
		guard.level = 1;
		double plain = GuardRuntime.chaseLimit(guard, config, 16,
			dev.squire.server.profession.CombatStance.BALANCED, false, null);
		double inclined = GuardRuntime.chaseLimit(guard, config, 16,
			dev.squire.server.profession.CombatStance.BALANCED, false, reckless);
		assertEquals(plain * Trait.RECKLESS_CHASE_FACTOR, inclined);
		assertFalse(CombatStyle.gatesFor(guard).bow(),
			"莽撞不能让 Lv.1 守卫提前使用弓");

		guard.level = 8;
		double playerStance = GuardRuntime.chaseLimit(guard, config, 16,
			dev.squire.server.profession.CombatStance.AGGRESSIVE, false, null);
		assertEquals(playerStance, GuardRuntime.chaseLimit(guard, config, 16,
			dev.squire.server.profession.CombatStance.AGGRESSIVE, false, reckless),
			"解锁后玩家明确选择的战斗姿态不能被性格覆盖");

		var engineer = new dev.squire.server.profession.ProfessionData();
		engineer.setProfession(dev.squire.server.profession.SquireProfession.ENGINEER);
		engineer.level = 10;
		assertFalse(engineer.can(dev.squire.server.profession.ProfessionAbility
			.GUARD_BOW_PROFICIENCY), "工程师的战斗性格不能获得守卫专属能力");
	}
}
