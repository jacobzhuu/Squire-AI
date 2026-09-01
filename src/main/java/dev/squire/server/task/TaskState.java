package dev.squire.server.task;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Task lifecycle states and their legal transitions (spec section 19).
 *
 * <p>The {@code VERIFYING → COMPLETED} edge is special: only the Goal Verifier may take
 * it, enforced through {@link GoalVerifierToken} (ADR-013).</p>
 */
public enum TaskState {
	CREATED,
	PLANNING,
	READY,
	RUNNING,
	WAITING,
	BLOCKED,
	RETRYING,
	REPLANNING,
	INTERRUPTED,
	PAUSED,
	VERIFYING,
	COMPLETED,
	FAILED,
	CANCELLED;

	private static final Map<TaskState, Set<TaskState>> TRANSITIONS;
	static {
		Map<TaskState, Set<TaskState>> m = new HashMap<>();
		m.put(CREATED, Set.of(PLANNING, CANCELLED));
		m.put(PLANNING, Set.of(READY, FAILED, CANCELLED));
		// READY tasks can fail before start when a precondition or dependency fails.
		m.put(READY, Set.of(RUNNING, FAILED, CANCELLED));
		m.put(RUNNING, Set.of(WAITING, BLOCKED, RETRYING, REPLANNING, INTERRUPTED, PAUSED,
			VERIFYING, FAILED, CANCELLED));
		m.put(WAITING, Set.of(RUNNING, BLOCKED, FAILED, CANCELLED));
		m.put(BLOCKED, Set.of(READY, RUNNING, FAILED, CANCELLED));
		m.put(RETRYING, Set.of(RUNNING, READY, FAILED, CANCELLED));
		m.put(REPLANNING, Set.of(PLANNING, READY, FAILED, CANCELLED));
		m.put(INTERRUPTED, Set.of(READY, CANCELLED));
		m.put(PAUSED, Set.of(RUNNING, CANCELLED));
		// VERIFYING 也必须能被<b>取消</b>。ADR-013 管的是「不许不经验证就宣称成功」，
		// 而取消不宣称任何结果——这张表本来就允许 VERIFYING → FAILED，同样是一个
		// 不经验证器的终态。少了 CANCELLED 这条边的后果是真的：验证窗口只有几拍，
		// 但玩家随时可能按遣散、按巡逻、点「不做了」，只要正好落在那几拍里，
		// cancelAgent 就会抛 IllegalStateException——取消没做完，任务还挂在
		// runningByAgent 里，随从从此卡着一个幽灵任务。
		m.put(VERIFYING, Set.of(COMPLETED, RUNNING, RETRYING, FAILED, CANCELLED));
		for (TaskState terminal : Set.of(COMPLETED, FAILED, CANCELLED)) {
			m.put(terminal, Set.of());
		}
		TRANSITIONS = java.util.Collections.unmodifiableMap(m);
	}

	public boolean canTransitionTo(TaskState target) {
		return TRANSITIONS.get(this).contains(target);
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}

	public boolean isActive() {
		return !isTerminal();
	}
}
