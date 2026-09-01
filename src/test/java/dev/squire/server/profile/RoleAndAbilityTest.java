package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 职业与能力槽的取舍规则。
 *
 * <p>这一层是第 2 期的全部意义：如果槽位不咬人，「专精」就退化成一张说明文字，
 * 玩家没有任何理由召第二只随从。所以这里守的是三条：槽位数由熟练度派生（不存档，
 * 不会漂）、第 4 槽只收本职业能力、以及<b>选职业绝不让他忘掉已经会的事</b>。</p>
 */
class RoleAndAbilityTest {

	private static SquireProfile builderAt(long buildProficiency) {
		SquireProfile profile = new SquireProfile();
		profile.roleId = Role.BUILDER.id();
		profile.proficiency.put(Track.BUILD.id(), buildProficiency);
		profile.refreshUnlocks();
		return profile;
	}

	@Test
	void slotsAreDerivedFromProficiencyNotStored() {
		assertEquals(Role.BASE_SLOTS, builderAt(0).slots());
		assertEquals(Role.BASE_SLOTS, builderAt(159).slots(), "Lv2 还没有第三个槽");
		assertEquals(3, builderAt(500).slots(), "Lv3 给第三个槽");
		assertEquals(3, builderAt(1399).slots());
		assertEquals(4, builderAt(3500).slots(), "满档给第四个槽");
	}

	@Test
	void levelOneAbilitiesAreBasicAndNeverNeedASlot() {
		SquireProfile fresh = builderAt(0);
		assertTrue(fresh.can(Ability.BUILD_BLUEPRINT),
			"按蓝图施工是基础能力，选职业不该让他忘掉已经会的事");
		assertTrue(fresh.equippedAbilities.isEmpty(), "基础能力不占槽");
		assertFalse(fresh.isUnlocked(Ability.BUILD_BLUEPRINT),
			"基础能力不进解锁集，它本来就有");
	}

	@Test
	void aRolelessCompanionStillDoesEverythingBasic() {
		SquireProfile none = new SquireProfile();
		assertTrue(none.can(Ability.BUILD_BLUEPRINT));
		assertTrue(none.can(Ability.LOGISTICS_HAUL));
		assertTrue(none.can(Ability.COMBAT_GUARD));
		assertFalse(none.can(Ability.BUILD_FAST), "进阶能力必须练出来并装上");
		assertEquals(1, none.level(), "没有职业就没有等级");
	}

	@Test
	void advancedAbilitiesUnlockAtTheirLevelAndOnlyForTheirRole() {
		SquireProfile profile = builderAt(40); // 第一档里程碑 = Lv2
		assertTrue(profile.isUnlocked(Ability.BUILD_FAST));
		assertFalse(profile.isUnlocked(Ability.BUILD_SUBSTITUTE), "Lv3 才解锁");
		assertFalse(profile.isUnlocked(Ability.COMBAT_FOCUS_FIRE),
			"护卫的能力要当过护卫才练得出来");
	}

	@Test
	void unlockingIsPermanentAcrossARoleChange() {
		SquireProfile profile = builderAt(500); // Lv3 建筑师
		assertTrue(profile.isUnlocked(Ability.BUILD_FAST));
		profile.roleId = Role.GUARDIAN.id();
		profile.refreshUnlocks();
		assertTrue(profile.isUnlocked(Ability.BUILD_FAST),
			"换职业不该没收练过的东西——那是组小队的杠杆");
		assertEquals(Role.BASE_SLOTS, profile.slots(),
			"但等级归本职业算，护卫从头练");
	}

	@Test
	void equippingIsWhatActuallyGatesBehaviour() {
		SquireProfile profile = builderAt(40);
		assertFalse(profile.can(Ability.BUILD_FAST), "解锁不等于装备");
		profile.equippedAbilities.add(Ability.BUILD_FAST.id());
		assertTrue(profile.can(Ability.BUILD_FAST));
	}

	@Test
	void theFourthSlotOnlyTakesTheCurrentRolesAbilities() {
		assertFalse(Role.slotIsRoleLocked(0));
		assertFalse(Role.slotIsRoleLocked(1));
		assertFalse(Role.slotIsRoleLocked(2), "跨职业能力可以装进前三个槽");
		assertTrue(Role.slotIsRoleLocked(3), "第四个槽是专精真正咬人的地方");
	}

	@Test
	void pruningDropsWhateverStoppedBeingLegal() {
		SquireProfile profile = builderAt(3500); // Lv6，四个槽
		profile.unlockedAbilities.add(Ability.COMBAT_FOCUS_FIRE.id());
		profile.equippedAbilities.addAll(List.of(Ability.BUILD_FAST.id(),
			Ability.BUILD_SUBSTITUTE.id(), Ability.COMBAT_FOCUS_FIRE.id(),
			Ability.BUILD_DEMOLISH.id()));
		assertTrue(profile.pruneEquipped().isEmpty(), "全部合法时什么都不摘");

		profile.roleId = Role.GUARDIAN.id();
		profile.proficiency.put(Track.COMBAT.id(), 0L);
		List<Ability> removed = profile.pruneEquipped();
		assertTrue(removed.contains(Ability.BUILD_DEMOLISH),
			"换成护卫之后，第四槽里的建筑能力必须被摘掉");
		assertTrue(profile.equippedAbilities.size() <= profile.slots(),
			"装备数永远不超过槽位数");
	}

	/**
	 * 未落地的能力就算被塞进槽里也不能生效。
	 *
	 * <p>第 2 期收尾时所有能力都已经接上了行为，所以下面的循环现在是空转的。
	 * 它留在这里是为下一个“先写进清单、后接实现”的条目把门：
	 * 面板上摆一个点了没反应的按钮，比没有这个按钮伤害大得多。</p>
	 */
	@Test
	void anAbilityThatHasNotLandedCanNeverBeUsed() {
		for (Ability ability : Ability.values()) {
			if (ability.available()) {
				continue;
			}
			SquireProfile profile = new SquireProfile();
			profile.roleId = ability.role().id();
			profile.unlockedAbilities.add(ability.id());
			profile.equippedAbilities.add(ability.id());
			assertFalse(profile.can(ability),
				() -> ability.id() + " 没落地，就算被装上也不能当成有效");
			assertTrue(profile.pruneEquipped().contains(ability));
		}
		assertEquals(java.util.Arrays.stream(Ability.values())
				.filter(a -> !a.basic()).count(),
			(long) Ability.equippable().size(),
			"全部进阶能力都已落地时，可装备清单就该等于全部进阶能力");
	}

	/** 职业和自主档位里仍然有“声明了但拒绝”的条目，模式本身仍然有效。 */
	@Test
	void declaredButRefusedIsStillARealState() {
		assertFalse(Role.FARMER.available());
		assertFalse(AutonomyLevel.AUTONOMOUS.available());
	}

	@Test
	void farmerIsAdvertisedButRefused() {
		assertFalse(Role.FARMER.available());
		assertTrue(Role.byId("farmer") != null,
			"常量要留着，玩家找不到会以为坏了");
		assertTrue(Ability.of(Role.FARMER).isEmpty(), "未开放的职业没有能力");
	}
}
