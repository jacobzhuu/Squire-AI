package dev.squire.server.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 成长记账。<b>唯一</b>一个把熟练度加上去的地方。
 *
 * <h2>为什么只在任务终态记一次</h2>
 * <p>如果在 tick 里累加，重启恢复的任务会把同一段工作再记一遍，而且任何一个
 * 「开始 → 取消」的循环都能刷。放在任务终态成功这一个出口上，两个问题一起消失：
 * 一次任务恰好记一次账，取消和失败的任务一分不给。所以这里也<b>不该</b>被执行器
 * 直接调用——调用点在 {@code TaskScheduler} 的终态分支上。</p>
 *
 * <h2>撤销回扣</h2>
 * <p>没有回扣，「建 → undo → 建」就是无限刷建筑熟练度。每一笔建造入账都按
 * {@code operationId} 记在这里，玩家撤销那次操作时原样扣回来（当日额度一并扣，
 * 否则撤销就成了清空软上限的手段）。台账有界，旧条目自然淘汰——它只需要覆盖
 * 「刚盖完就后悔」这个窗口。</p>
 *
 * <p>刻意不认识任何 Minecraft 类型：调用方负责把执行器的 Progress 翻译成
 * (track, amount)，这里只做记账。于是这一整套规则可以被普通单测盖住。</p>
 */
public final class ProgressionService {

	/** 撤销台账的容量。够覆盖「刚盖完就后悔」，不够的部分本来也不值得扣。 */
	public static final int UNDO_LEDGER_SIZE = 256;

	/** 一次入账的结果，够调用方决定要不要发聊天提示。 */
	public record Outcome(Track track, long credited, long total, int levelBefore,
			int levelAfter, List<Ability> unlocked, int slotsBefore, int slotsAfter) {

		public boolean anythingHappened() {
			return credited > 0;
		}

		public boolean leveledUp() {
			return levelAfter > levelBefore;
		}

		public boolean gainedSlot() {
			return slotsAfter > slotsBefore;
		}
	}

	/** 一笔可以被撤销扣回的建造入账。 */
	public record Ledger(UUID agentId, Track track, long credited) { }

	private final Map<UUID, Ledger> undoLedger =
		new LinkedHashMap<>(16, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<UUID, Ledger> eldest) {
				return size() > UNDO_LEDGER_SIZE;
			}
		};

	/**
	 * 记一次熟练度并刷新解锁。
	 *
	 * @param raw 原始数量（格数、件数、杀数），调用方从任务的 Progress 里取
	 * @param day 当前 MC 天，用于当日软上限
	 */
	public synchronized Outcome award(SquireProfile profile, Track track, long raw,
			long day) {
		if (profile == null || track == null || raw <= 0) {
			return new Outcome(track, 0L, 0L, 1, 1, List.of(), 0, 0);
		}
		int levelBefore = profile.level();
		int slotsBefore = profile.slots();
		long credited = profile.award(track, raw, day);
		List<Ability> unlocked = credited > 0 ? profile.refreshUnlocks() : List.of();
		return new Outcome(track, credited, profile.proficiencyOf(track), levelBefore,
			profile.level(), unlocked, slotsBefore, profile.slots());
	}

	/** 记下一次建造入账，好在玩家撤销那次操作时扣回来。 */
	public synchronized void rememberForUndo(UUID operationId, UUID agentId, Track track,
			long credited) {
		if (operationId == null || agentId == null || track == null || credited <= 0) {
			return;
		}
		undoLedger.put(operationId, new Ledger(agentId, track, credited));
	}

	/** 取出并移除一笔台账；撤销时调用。台账里没有就返回空（撤销的是别的东西）。 */
	public synchronized Optional<Ledger> takeForUndo(UUID operationId) {
		return Optional.ofNullable(undoLedger.remove(operationId));
	}

	/** 撤销回扣的执行：把这笔账从档案里扣掉。 */
	public synchronized long revoke(SquireProfile profile, Ledger ledger) {
		if (profile == null || ledger == null) {
			return 0L;
		}
		return profile.revoke(ledger.track(), ledger.credited());
	}

	/** 台账里现在有多少笔（诊断/测试用）。 */
	public synchronized int pendingUndoEntries() {
		return undoLedger.size();
	}
}
