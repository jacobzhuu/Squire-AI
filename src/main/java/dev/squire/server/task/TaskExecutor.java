package dev.squire.server.task;

/**
 * Per-task-type behavior driven by the {@link TaskScheduler} on the server thread.
 * Executors do the work through the Tool Gateway and report progress; they never
 * declare the task COMPLETED themselves (ADR-013) — finishing work only moves the
 * task to VERIFYING.
 */
public interface TaskExecutor {
	/** The task {@code type} this executor handles. */
	String type();

	/** Called once when the task first enters RUNNING. */
	void start(Task task);

	/** Called every scheduler tick while RUNNING. */
	StepOutcome tick(Task task, long tick);

	/** Called on cancellation/interruption; release handles, stop navigation, etc. */
	void cancel(Task task);

	/**
	 * 方案 A4（可选检查点）：返回可序列化的恢复点；null 表示"从参数重新开始即幂等"。
	 */
	default String checkpoint(Task task) {
		return null;
	}

	/** 从最近检查点恢复执行状态（重启后 RUNNING→READY 的幂等续跑）。 */
	default void restoreCheckpoint(Task task, String checkpointJson) {
	}

	/**
	 * Rebuild the machine-checkable success condition for a persisted task. Returning
	 * null declares the task non-recoverable; the scheduler fails it explicitly with
	 * SERVER_RESTARTED instead of rearming a task that can never verify.
	 */
	default TaskCondition recoverySuccessCondition(TaskStateStore.Snapshot snapshot) {
		return null;
	}

	/** Optional restart-safe precondition; null means no precondition. */
	default TaskCondition recoveryPrecondition(TaskStateStore.Snapshot snapshot) {
		return null;
	}

	/**
	 * 这个执行器没有伙伴实体就一步也走不了吗？默认 false（纯数据类任务照常起）。
	 *
	 * <p>返回 true 时调度器会把任务继续留在队列里，直到实体真的加载出来，而不是
	 * 硬起一个必然抛 ENTITY_NOT_FOUND 的任务——重启后区块还没就绪的那几十 tick 里，
	 * 恢复回来的任务本来会集体自杀，还会拖垮整条依赖链。等不到就由
	 * expireStalePending 兜底超时，不会永久挂着。</p>
	 */
	default boolean requiresBody() {
		return false;
	}

	/** Progress reported by {@link #tick}. */
	enum StepOutcome { CONTINUE, WORK_DONE, FAILED }
}
