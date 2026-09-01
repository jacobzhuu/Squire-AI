package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.NbtCompound;

/**
 * 档案跨重启存活。
 *
 * <p>玩家花了几个小时练出来的职业、槽位和性格，如果重启后回到默认值，这一整期就等于
 * 没做。所以这里逐字段验往返，并且守住 additive-optional 的两半：默认值<b>不写</b>
 * （档案不会因为一堆零而膨胀），缺字段<b>能读</b>（旧存档照常打开）。</p>
 *
 * <p>巡逻点位的往返不在这里：{@code GlobalPos} 需要注册表引导，而 headless 单测的
 * 类加载器起不来 Bootstrap。那两条在 {@code M14ProfileGameTests} 里。</p>
 */
class SquireProfileNbtTest {

	private static SquireProfile roundTrip(SquireProfile original) {
		SquireProfile restored = new SquireProfile();
		restored.readNbt(original.writeNbt(new NbtCompound()));
		return restored;
	}

	@Test
	void afreshProfileWritesAlmostNothing() {
		NbtCompound nbt = new SquireProfile().writeNbt(new NbtCompound());
		assertTrue(nbt.isEmpty(),
			"默认值一个都不写：一份没配置过的档案不该占任何空间");
	}

	@Test
	void anEmptyCompoundReadsBackAsDefaults() {
		SquireProfile profile = new SquireProfile();
		profile.readNbt(new NbtCompound());
		assertEquals(null, profile.roleId);
		assertEquals(AutonomyLevel.STANDARD, profile.autonomyLevel());
		assertEquals(SquireProfile.DEFAULT_STAY_RADIUS, profile.stayRadius);
		assertTrue(profile.traits.isEmpty());
		assertEquals(Long.MIN_VALUE, profile.lastRespecTick);
	}

	@Test
	void everyFieldSurvivesARestart() {
		SquireProfile original = new SquireProfile();
		original.roleId = Role.EXCAVATOR.id();
		original.autonomy = AutonomyLevel.PROACTIVE.id();
		original.unlockedAbilities.add(Ability.EXCAVATE_DEEP.id());
		original.unlockedAbilities.add(Ability.BUILD_FAST.id());
		original.equippedAbilities.add(Ability.EXCAVATE_DEEP.id());
		original.proficiency.put(Track.EXCAVATE.id(), 640L);
		original.dailyEarned.put(Track.EXCAVATE.id(), 120L);
		original.dailyResetDay = 42L;
		original.traits.add(Trait.DILIGENT.id());
		original.unlockedBlueprints.add("mine_outpost");
		original.patrolCursor = 2;
		original.stayRadius = 12;
		original.lastRespecTick = 987L;

		SquireProfile back = roundTrip(original);
		assertEquals(Role.EXCAVATOR, back.role());
		assertEquals(AutonomyLevel.PROACTIVE, back.autonomyLevel());
		assertEquals(List.of(Ability.EXCAVATE_DEEP), back.equippedList());
		assertTrue(back.isUnlocked(Ability.BUILD_FAST),
			"别的职业练过的东西也要跟着走");
		assertEquals(640L, back.proficiencyOf(Track.EXCAVATE));
		assertEquals(120L, back.dailyEarned.get(Track.EXCAVATE.id()));
		assertEquals(42L, back.dailyResetDay);
		assertTrue(back.hasTrait(Trait.DILIGENT));
		assertTrue(back.unlockedBlueprints.contains("mine_outpost"));
		assertEquals(2, back.patrolCursor);
		assertEquals(SquireProfile.DEFAULT_STAY_RADIUS, back.stayRadius,
			"old roaming stay radii migrate to the exact anchor command");
		assertEquals(987L, back.lastRespecTick);
		assertEquals(4, back.level(), "等级是派生的，不存档，但必须一致");
		assertEquals(3, back.slots());
	}

	@Test
	void anUnknownRoleOrAbilityIsSimplyIgnoredRatherThanFatal() {
		SquireProfile profile = new SquireProfile();
		profile.roleId = "no_such_role";
		profile.equippedAbilities.add("no.such.ability");
		SquireProfile back = roundTrip(profile);
		assertEquals(null, back.role());
		assertEquals(1, back.level());
		assertTrue(back.equippedList().isEmpty(),
			"认不出的能力 id 不出现在装备清单里");
		back.pruneEquipped();
		assertTrue(back.equippedAbilities.isEmpty(),
			"整理一遍之后认不出的能力 id 也被摘掉了");
	}
}
