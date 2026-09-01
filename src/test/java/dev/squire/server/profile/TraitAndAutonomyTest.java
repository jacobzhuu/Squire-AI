package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

/**
 * 性格与自主档位。
 *
 * <p>性格<b>只影响机制</b>，所以它必须是可测的：这里用固定种子把「随机」钉住，
 * 并守住唯一一条会毁掉设计的性质——绝不同时抽到一对相反的特质（一个既谨慎又莽撞的
 * 伙伴，撤退线到底听谁的？）。</p>
 *
 * <p>自主档位守的是另一件事：认不出的值必须回落到<b>标准</b>，绝不能因为一个坏字段
 * 让随从变成完全自动——那一档还没开放，而它会自己消耗玩家的材料。</p>
 */
class TraitAndAutonomyTest {

	private static RandomGenerator seeded(long seed) {
		java.util.Random random = new java.util.Random(seed);
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return random.nextLong();
			}

			@Override
			public int nextInt(int bound) {
				return random.nextInt(bound);
			}
		};
	}

	@Test
	void aRollAlwaysGivesOneOrTwoTraits() {
		for (long seed = 0; seed < 200; seed++) {
			List<Trait> rolled = Trait.roll(seeded(seed));
			assertTrue(rolled.size() >= 1 && rolled.size() <= 2,
				() -> "seed " + rolled);
		}
	}

	@Test
	void aRollNeverContainsAContradictoryPair() {
		for (long seed = 0; seed < 500; seed++) {
			List<Trait> rolled = Trait.roll(seeded(seed));
			for (Trait trait : rolled) {
				Trait opposite = trait.opposite();
				assertFalse(opposite != null && rolled.contains(opposite),
					() -> "既谨慎又莽撞的伙伴，撤退线该听谁的？" + rolled);
			}
			assertEquals(rolled.size(), rolled.stream().distinct().count(),
				"同一个特质不该抽到两次");
		}
	}

	@Test
	void rerollKeepsTheTraitCountAndActuallyChangesTheSet() {
		List<Trait> previous = List.of(Trait.STURDY, Trait.RECKLESS);
		for (long seed = 0; seed < 100; seed++) {
			List<Trait> rolled = Trait.reroll(seeded(seed), 2, previous);
			assertEquals(2, rolled.size());
			assertEquals(2, rolled.stream().distinct().count());
			assertFalse(rolled.containsAll(previous), "洗练不能原样返回同一组");
		}
	}

	@Test
	void everyTraitHasAtLeastOneVisibleRealEffect() {
		for (Trait trait : Trait.values()) {
			assertTrue(trait.effectLineCount() > 0, trait.id() + " 不能是无效果性格");
			assertFalse(trait.summary().isBlank());
		}
	}

	@Test
	void onlyTheCautiousPairIsMutuallyExclusive() {
		assertEquals(Trait.RECKLESS, Trait.CAUTIOUS.opposite());
		assertEquals(Trait.CAUTIOUS, Trait.RECKLESS.opposite());
		assertEquals(null, Trait.DILIGENT.opposite(),
			"勤勉和别的特质并不冲突，没必要人为拆开");
	}

	@Test
	void aProfileReportsItsTraitsBackAsEnums() {
		SquireProfile profile = new SquireProfile();
		profile.traits.add(Trait.SWIFT.id());
		profile.traits.add("no-such-trait");
		assertEquals(List.of(Trait.SWIFT), profile.traitList(),
			"认不出的特质 id 直接忽略，不该让整份档案读不出来");
		assertTrue(profile.hasTrait(Trait.SWIFT));
		assertFalse(profile.hasTrait(Trait.STURDY));
	}

	@Test
	void autonomyOrderingIsWhatGatesProactiveBehaviour() {
		assertTrue(AutonomyLevel.STANDARD.atLeast(AutonomyLevel.CONSERVATIVE));
		assertTrue(AutonomyLevel.STANDARD.atLeast(AutonomyLevel.STANDARD));
		assertFalse(AutonomyLevel.CONSERVATIVE.atLeast(AutonomyLevel.STANDARD),
			"保守档不该主动施救");
		assertTrue(AutonomyLevel.PROACTIVE.atLeast(AutonomyLevel.STANDARD));
	}

	@Test
	void anUnknownOrLockedLevelFallsBackToStandard() {
		assertEquals(AutonomyLevel.STANDARD, AutonomyLevel.byIdOrDefault(null));
		assertEquals(AutonomyLevel.STANDARD, AutonomyLevel.byIdOrDefault("garbage"));
		assertEquals(AutonomyLevel.STANDARD,
			AutonomyLevel.byIdOrDefault(AutonomyLevel.AUTONOMOUS.id()),
			"一个坏字段绝不能把随从变成还没开放的完全自动档");
		assertEquals(AutonomyLevel.PROACTIVE, AutonomyLevel.byIdOrDefault("PROACTIVE"));
	}

	@Test
	void fullAutoIsAdvertisedButRefused() {
		assertFalse(AutonomyLevel.AUTONOMOUS.available());
		assertTrue(AutonomyLevel.byId("autonomous") != null,
			"常量留着，设置时如实拒绝并说明原因");
	}
}
