package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 成长与防挂机。
 *
 * <p>四条防挂机规则里有三条能在这里被验证：当日软上限与折算、撤销回扣、
 * 以及「解锁只给能力不给数值」。第四条（只在主人在线时计入）在运行时那一层。</p>
 *
 * <p>为什么这些必须有测试：没有软上限，一座刷怪塔能把战斗轨道刷满；没有撤销回扣，
 * 「建 → undo → 建」是无限刷建筑熟练度。两者都不会报错，只会静静地把「培养一个
 * 伙伴」变成「挂一个宏」。</p>
 */
class ProgressionTest {

	private static SquireProfile builder() {
		SquireProfile profile = new SquireProfile();
		profile.roleId = Role.BUILDER.id();
		return profile;
	}

	@Test
	void milestonesAreReachedInOrderAndNothingIsANumericBuff() {
		assertEquals(1, Track.levelFor(0));
		assertEquals(2, Track.levelFor(40));
		assertEquals(3, Track.levelFor(160));
		assertEquals(4, Track.levelFor(500));
		assertEquals(5, Track.levelFor(1400));
		assertEquals(6, Track.levelFor(3500));
		assertEquals(6, Track.levelFor(999_999), "满档就是满档，不会无限涨");
		assertEquals(40, Track.remainingToNextMilestone(0));
		assertEquals(0, Track.remainingToNextMilestone(3500));
	}

	@Test
	void theDailySoftCapDiscountsOvertimeInsteadOfBlockingIt() {
		Track build = Track.BUILD;
		assertEquals(100, build.applyDailyCap(0, 100), "上限之内原样记");
		assertEquals(build.dailySoftCap(), build.applyDailyCap(0, build.dailySoftCap()));
		// 全部超出：只按 20% 折算
		assertEquals(20, build.applyDailyCap(build.dailySoftCap(), 100),
			"过了软上限只折算，而不是白干——真的盖了一整天的人不该在下午变成零");
		// 跨越上限：前半原样，后半折算
		assertEquals(12 + 17, build.applyDailyCap(build.dailySoftCap() - 12, 100));
	}

	@Test
	void theDailyCounterResetsWithTheMinecraftDay() {
		SquireProfile profile = builder();
		profile.award(Track.BUILD, Track.BUILD.dailySoftCap(), 5L);
		assertEquals(20, profile.award(Track.BUILD, 100, 5L), "同一天，已经满额");
		assertEquals(100, profile.award(Track.BUILD, 100, 6L), "换天清零");
	}

	@Test
	void undoRebateTakesBackBothTheTotalAndTheDailyAllowance() {
		SquireProfile profile = builder();
		profile.award(Track.BUILD, 300, 1L);
		assertEquals(300, profile.proficiencyOf(Track.BUILD));

		assertEquals(300, profile.revoke(Track.BUILD, 300));
		assertEquals(0, profile.proficiencyOf(Track.BUILD));
		// 当日额度也退了回去，否则撤销就成了清空软上限的手段
		assertEquals(300, profile.award(Track.BUILD, 300, 1L));
	}

	@Test
	void revokingNeverGoesNegative() {
		SquireProfile profile = builder();
		profile.award(Track.BUILD, 10, 1L);
		assertEquals(10, profile.revoke(Track.BUILD, 999));
		assertEquals(0, profile.proficiencyOf(Track.BUILD));
	}

	@Test
	void anAwardThatCrossesAMilestoneReportsTheUnlock() {
		ProgressionService progression = new ProgressionService();
		SquireProfile profile = builder();
		var outcome = progression.award(profile, Track.BUILD, 40, 1L);
		assertTrue(outcome.leveledUp());
		assertTrue(outcome.unlocked().contains(Ability.BUILD_FAST),
			"到 Lv2 就该解锁第一个进阶能力，并且有话可说");
		assertFalse(outcome.gainedSlot(), "第三个槽要到 Lv3");

		var toThird = progression.award(profile, Track.BUILD, 500, 1L);
		assertTrue(toThird.gainedSlot(), "Lv3 多一个槽，这是玩家真正在等的东西");
	}

	@Test
	void nothingIsAwardedWithoutARoleOrForANonPositiveAmount() {
		ProgressionService progression = new ProgressionService();
		assertFalse(progression.award(builder(), Track.BUILD, 0, 1L).anythingHappened());
		assertFalse(progression.award(null, Track.BUILD, 10, 1L).anythingHappened());
		assertFalse(progression.award(builder(), null, 10, 1L).anythingHappened());
	}

	@Test
	void theUndoLedgerRemembersOneEntryPerOperationAndIsBounded() {
		ProgressionService progression = new ProgressionService();
		UUID agent = UUID.randomUUID();
		UUID operation = UUID.randomUUID();
		progression.rememberForUndo(operation, agent, Track.BUILD, 26);
		assertEquals(1, progression.pendingUndoEntries());

		var ledger = progression.takeForUndo(operation).orElseThrow();
		assertEquals(26, ledger.credited());
		assertEquals(agent, ledger.agentId());
		assertTrue(progression.takeForUndo(operation).isEmpty(), "一笔账只能扣一次");

		for (int i = 0; i < ProgressionService.UNDO_LEDGER_SIZE + 50; i++) {
			progression.rememberForUndo(UUID.randomUUID(), agent, Track.BUILD, 1);
		}
		assertEquals(ProgressionService.UNDO_LEDGER_SIZE,
			progression.pendingUndoEntries(),
			"台账有界：它只需要覆盖「刚盖完就后悔」那个窗口");
	}

	@Test
	void undoingSomethingElseIsNotAnError() {
		ProgressionService progression = new ProgressionService();
		assertTrue(progression.takeForUndo(UUID.randomUUID()).isEmpty());
		assertEquals(0, progression.revoke(builder(), null));
	}
}
