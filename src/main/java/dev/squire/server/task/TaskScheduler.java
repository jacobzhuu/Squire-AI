package dev.squire.server.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import dev.squire.server.task.TaskExecutor.StepOutcome;

/**
 * Runs tasks for agents on the server thread (spec sections 19/20/21).
 *
 * <p>One RUNNING task per agent at a time; higher priority preempts an interruptible
 * lower-priority task. Timeouts, bounded retries and the VERIFYING hand-off to the
 * {@link GoalVerifier} live here so executors stay simple.</p>
 *
 * <p>{@code RetryPolicy.maxRetries} counts ADDITIONAL attempts after the initial one.</p>
 */
public final class TaskScheduler {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(TaskScheduler.class);

	/**
	 * TOTAL order is mandatory: TreeSet treats compare()==0 as duplicate and would
	 * silently DROP tasks submitted in the same tick at the same priority.
	 */
	private static final Comparator<Task> BY_PRIORITY =
		Comparator.comparing(Task::priority)
			.thenComparing(Task::createdTick)
			.thenComparing(Task::taskId);
	/** Ticks to let the world settle between work finishing and goal verification. */
	private static final int VERIFICATION_GRACE_TICKS = 10;
	/** Absolute floor for the stuck-in-queue safety net (~20 minutes of ticks). */
	private static final long STALE_PENDING_FLOOR_TICKS = 24_000L;

	private final Map<String, TaskExecutor> executors = new HashMap<>();
	private final GoalVerifier verifier = new GoalVerifier();
	private final Map<UUID, Task> allTasks = new HashMap<>();
	private final TreeSet<Task> pending = new TreeSet<>(BY_PRIORITY);
	private final Map<UUID, Task> runningByAgent = new HashMap<>();
	private final List<Task> finished = new ArrayList<>();
	private volatile boolean paused = false;
	/** Last tick handed to {@link #tick}; the start-based timeout budget uses it. */
	private long lastTick;

	/** Killswitch support: while paused no NEW task starts (queued ones wait). */
	public void setPaused(boolean value) {
		this.paused = value;
	}

	public boolean isPaused() {
		return paused;
	}

	/**
	 * Cancel every pending and running task immediately (killswitch activation,
	 * spec section 63). @return how many tasks were cancelled.
	 */
	public synchronized int cancelAll() {
		int cancelled = 0;
		for (Map.Entry<UUID, Task> entry : Map.copyOf(runningByAgent).entrySet()) {
			Task task = entry.getValue();
			try {
				executorFor(task).cancel(task);
			} catch (RuntimeException e) {
				LOG.warn("[task] cancel hook threw on {}", task.taskId(), e);
			}
			if (task.state().isActive()) {
				task.transitionTo(TaskState.CANCELLED);
			}
			runningByAgent.remove(entry.getKey());
			allTasks.remove(task.taskId());
			finished.add(task);
			cancelled++;
		}
		for (Task queued : pending.toArray(Task[]::new)) {
			pending.remove(queued);
			if (queued.state().isActive()) {
				queued.transitionTo(TaskState.CANCELLED);
			}
			allTasks.remove(queued.taskId());
			finished.add(queued);
			cancelled++;
		}
		return cancelled;
	}

	/**
	 * 方案 A3：取消某只伙伴的全部 RUNNING/PENDING/RETRYING 任务（stop 语义）。
	 * 导航句柄由各 executor 的 cancel 钩子释放。
	 *
	 * @return cancelled task count
	 */
	public synchronized int cancelAgent(UUID agentId, String reason) {
		int cancelled = 0;
		Set<UUID> cancelledIds = new HashSet<>();
		Task running = runningByAgent.get(agentId);
		if (running != null) {
			try {
				executorFor(running).cancel(running);
			} catch (RuntimeException e) {
				LOG.warn("[task] cancel hook threw on {}", running.taskId(), e);
			}
			if (running.state().isActive()) {
				running.transitionTo(TaskState.CANCELLED);
			}
			runningByAgent.remove(agentId);
			allTasks.remove(running.taskId());
			finished.add(running);
			cancelledIds.add(running.taskId());
			cancelled++;
		}
		for (Task queued : pending.toArray(Task[]::new)) {
			if (!queued.agentId().equals(agentId)) {
				continue;
			}
			pending.remove(queued);
			if (queued.state().isActive()) {
				queued.transitionTo(TaskState.CANCELLED);
			}
			allTasks.remove(queued.taskId());
			finished.add(queued);
			cancelledIds.add(queued.taskId());
			cancelled++;
		}
		for (UUID cancelledId : cancelledIds) {
			cascadeDependencyFailure(cancelledId);
		}
		if (cancelled > 0 && reason != null) {
			LOG.info("[task] cancelAgent {} dropped {} task(s): {}", agentId, cancelled,
				reason);
		}
		return cancelled;
	}

	/**
	 * Cancel only the tasks owned by one workflow. Unlike {@link #cancelAgent}, this
	 * leaves unrelated follow/combat/player-command work alone.
	 */
	public synchronized int cancelTasks(java.util.Collection<UUID> taskIds,
			String reason) {
		if (taskIds == null || taskIds.isEmpty()) return 0;
		Set<UUID> wanted = new HashSet<>(taskIds);
		int cancelled = 0;
		for (Map.Entry<UUID, Task> entry : Map.copyOf(runningByAgent).entrySet()) {
			Task task = entry.getValue();
			if (!wanted.contains(task.taskId())) continue;
			try {
				executorFor(task).cancel(task);
			} catch (RuntimeException e) {
				LOG.warn("[task] cancel hook threw on {}", task.taskId(), e);
			}
			if (task.state().isActive()) task.transitionTo(TaskState.CANCELLED);
			runningByAgent.remove(entry.getKey());
			allTasks.remove(task.taskId());
			finished.add(task);
			cancelled++;
		}
		for (Task queued : pending.toArray(Task[]::new)) {
			if (!wanted.contains(queued.taskId())) continue;
			pending.remove(queued);
			if (queued.state().isActive()) queued.transitionTo(TaskState.CANCELLED);
			allTasks.remove(queued.taskId());
			finished.add(queued);
			cancelled++;
		}
		if (cancelled > 0 && reason != null) {
			LOG.info("[task] cancelled {} workflow task(s): {}", cancelled, reason);
		}
		return cancelled;
	}

	public void register(TaskExecutor executor) {
		executors.put(executor.type(), executor);
	}

	/** True when a task of {@code type} would actually be driven (CREATE_TASK guard). */
	public boolean hasExecutor(String type) {
		return executors.containsKey(type);
	}

	/** Submit a CREATED task; planning is inline in M2, so it becomes READY at once. */
	public void submit(Task task, long currentTick) {
		LOG.info("[task] submit {} type={} agent={} tick={}",
			task.taskId(), task.type(), task.agentId(), currentTick);
		task.markCreated(currentTick);
		task.transitionTo(TaskState.PLANNING);
		task.transitionTo(TaskState.READY);
		if (!pending.add(task)) {
			LOG.error("[task] submit DROPPED by pending set: {}", task.taskId());
		}
		allTasks.put(task.taskId(), task);
		dev.squire.server.metrics.SquireMetrics m = metrics;
		if (m != null) {
			m.inc(dev.squire.server.metrics.SquireMetrics.Key.TASKS_TOTAL);
		}
	}

	/** §75 observability seam (optional; counters stay null-safe). */
	public void setMetrics(dev.squire.server.metrics.SquireMetrics value) {
		this.metrics = value;
	}

	private volatile dev.squire.server.metrics.SquireMetrics metrics;

	public int liveCount() {
		return pending.size() + runningByAgent.size();
	}

	public Optional<Task> current(UUID agentId) {
		return Optional.ofNullable(runningByAgent.get(agentId));
	}

	/**
	 * 任务走到终态时的回调。<b>熟练度记账唯一的入口</b>（第 2 期）。
	 *
	 * <p>放在这里而不是洒到各个执行器里，是因为这是任务变成 COMPLETED 的
	 * <b>只有一条</b>路径：于是一次任务恰好记一次账，重启恢复不会重复计，
	 * 取消和失败的任务一分不给。</p>
	 */
	@FunctionalInterface
	public interface TerminalListener {
		void onTerminal(Task task);
	}

	private volatile TerminalListener terminalListener;

	public void setTerminalListener(TerminalListener listener) {
		this.terminalListener = listener;
	}

	private void notifyTerminal(Task task) {
		TerminalListener listener = this.terminalListener;
		if (listener == null) {
			return;
		}
		try {
			listener.onTerminal(task);
		} catch (RuntimeException e) {
			// 记账出错绝不能拖垂调度器——少记一笔熟练度是小事，
			// 任务循环断掉是大事。
			LOG.warn("[task] terminal listener failed on {}: {}", task.taskId(),
				e.toString());
		}
	}

	public List<Task> finishedTasks() {
		return List.copyOf(finished);
	}

	/** Goal-level correlation by the stable task id, across live and terminal tasks. */
	/** Queued-but-not-started tasks, highest priority first (diagnostics). */
	public synchronized List<Task> pendingTasks() {
		return List.copyOf(pending);
	}

	/** Every live task (pending + running), for the admin inspector. */
	public synchronized List<Task> liveTasks() {
		return List.copyOf(allTasks.values());
	}

	public Optional<Task> findTask(UUID taskId) {
		Task live = allTasks.get(taskId);
		if (live != null) {
			return Optional.of(live);
		}
		return finished.stream().filter(task -> task.taskId().equals(taskId)).findFirst();
	}

	/**
	 * Advance every agent by one tick. The evaluation context is resolved PER TASK so
	 * conditions always judge the owning agent's body; @return tasks whose state changed.
	 */
	public List<Task> tick(long tick,
			java.util.function.Function<UUID, TaskEvaluationContext> contextFactory) {
		List<Task> changed = new ArrayList<>();
		this.lastTick = tick;

		preemptIfNeeded();

		for (Map.Entry<UUID, Task> entry : Map.copyOf(runningByAgent).entrySet()) {
			Task task = entry.getValue();
			if (advanceRunning(task, tick, contextFactory.apply(entry.getKey()))) {
				changed.add(task);
			}
			if (task.state().isTerminal()) {
				LOG.info("[task] terminal {} type={} state={} code={}",
					task.taskId(), task.type(), task.state(),
					task.lastErrorCode().orElse("-"));
				runningByAgent.remove(entry.getKey());
				allTasks.remove(task.taskId());
				finished.add(task);
				notifyTerminal(task);
			}
		}

		expireStalePending(tick, changed);
		startNextReady(contextFactory);
		return changed;
	}

	/**
	 * 排队中的任务也要有尽头。运行中的任务由 advanceRunning 判超时，但一个永远
	 * 等不到身体（或等不到依赖）的任务不在那条路径上，会无声地挂着。
	 */
	private void expireStalePending(long tick, List<Task> changed) {
		for (Task task : List.copyOf(pending)) {
			// 只兜"永远等不到"的底。等一条慢依赖（真熔炉要烧 600 tick）是正常的，
			// 阈值必须远大于任何合理的排队时间，否则会把好好排着队的任务清掉。
			long deadline = Math.max(task.timeoutTicks() * 4, STALE_PENDING_FLOOR_TICKS);
			if (tick - task.createdTick() <= deadline) {
				continue;
			}
			pending.remove(task);
			allTasks.remove(task.taskId());
			task.setLastErrorCode("TIMEOUT");
			fail(task, "TIMEOUT");
			finished.add(task);
			changed.add(task);
			LOG.info("[task] pending {} type={} expired before it could start",
				task.taskId(), task.type());
		}
	}

	private TaskEvaluationContext contextFor(UUID agentId,
			java.util.function.Function<UUID, TaskEvaluationContext> factory) {
		return factory.apply(agentId);
	}

	// ------------------------------------------------------------------ preemption

	private void preemptIfNeeded() {
		if (paused) {
			return;
		}
		for (Map.Entry<UUID, Task> entry : Map.copyOf(runningByAgent).entrySet()) {
			Task current = entry.getValue();
			if (!current.interruptible()) {
				continue;
			}
			Task preemptor = null;
			for (Task candidate : pending) {
				if (candidate.agentId().equals(entry.getKey())
						&& dependenciesMet(candidate)
						&& candidate.priority().preempts(current.priority())) {
					preemptor = candidate;
					break;
				}
			}
			if (preemptor == null) {
				continue;
			}
			pending.remove(preemptor);
			current.transitionTo(TaskState.INTERRUPTED);
			executorFor(current).cancel(current);
			toReady(current);

			startRunning(preemptor);
		}
	}

	// ------------------------------------------------------------------ running

	private boolean advanceRunning(Task task, long tick, TaskEvaluationContext context) {
		if (tick - task.timeoutBaseTick() > task.timeoutTicks() && task.state().isActive()) {
			fail(task, "TIMEOUT");
			return true;
		}
		switch (task.state()) {
			case RUNNING -> {
				StepOutcome outcome;
				try {
					outcome = executorFor(task).tick(task, tick);
				} catch (RuntimeException e) {
					outcome = StepOutcome.FAILED; // never crash the server loop
					LOG.error("[task] executor {} threw on {}",
						task.type(), task.taskId(), e);
					task.setLastErrorCode(describeThrow(task, e));
				}
				switch (outcome) {
					case CONTINUE -> {
						return false;
					}
					case WORK_DONE -> {
						task.setLastErrorCode(null); // transient retry errors must not survive a successful execution
						task.setExecutionState(tick); // remember when verification began
						task.transitionTo(TaskState.VERIFYING);
						return true;
					}
					case FAILED -> {
						retryOrFail(task, tick);
						return true;
					}
				}
				return false;
			}
			case VERIFYING -> {
				Long beganAt = (Long) task.executionState();
				if (beganAt != null && tick - beganAt < VERIFICATION_GRACE_TICKS) {
					return false; // let pickups/state settle before judging
				}
				GoalVerifier.Verdict verdict = verifier.verify(task, context);
				switch (verdict) {
					case MET -> {
						return true;
					}
					case NOT_MET_YET -> {
                            task.setLastErrorCode("POSTCONDITION_NOT_MET");
                            retryOrFail(task, tick);
                        }
					case FAILED -> fail(task, "POSTCONDITION_FAILED");
				}
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private void retryOrFail(Task task, long tick) {
		if (task.attemptsUsed() <= task.retryPolicy().maxRetries()) {
			if (task.lastErrorCode().isEmpty()) task.setLastErrorCode("EXECUTION_FAILED");
            LOG.info("[task] retry {} type={} cause={}", task.taskId(), task.type(), task.lastErrorCode().orElse("EXECUTION_FAILED"));
			task.transitionTo(TaskState.RETRYING);
			toReady(task);
			pending.add(task);
			runningByAgent.remove(task.agentId(), task);
		} else {
			fail(task, task.lastErrorCode().orElse("INTERNAL_ERROR"));
		}
	}

	private void fail(Task task, String code) {
		LOG.info("[task] fail {} type={} code={}", task.taskId(), task.type(), code);
		task.setLastErrorCode(code);
		if (task.state().isActive()) {
			task.transitionTo(TaskState.FAILED);
		}
		cascadeDependencyFailure(task.taskId());
	}

	/**
	 * 方案 A4：依赖任务失败时，所有依赖它的任务转为 FAILED(DEPENDENCY_FAILED)，
	 * 不能永久留在 pending。级联是传递闭包（依赖链逐层下沉）。
	 */
	private void cascadeDependencyFailure(UUID failedTaskId) {
		java.util.ArrayDeque<UUID> failures = new java.util.ArrayDeque<>();
		failures.add(failedTaskId);
		while (!failures.isEmpty()) {
			UUID source = failures.removeFirst();
			for (Task queued : pending.toArray(Task[]::new)) {
				if (queued.dependencies().stream().noneMatch(id -> id.equals(source))) {
					continue;
				}
				pending.remove(queued);
				allTasks.remove(queued.taskId());
				if (queued.state().isActive()) {
					queued.setLastErrorCode("DEPENDENCY_FAILED");
					queued.transitionTo(TaskState.FAILED);
				}
				finished.add(queued);
				LOG.info("[task] dependency-cascade failed {} (dep {})",
					queued.taskId(), source);
				failures.addLast(queued.taskId());
			}
		}
	}

	// ------------------------------------------------------------------ scheduling

	private void startNextReady(
			java.util.function.Function<UUID, TaskEvaluationContext> contextFactory) {
		if (paused) {
			return;
		}
		// 每个空闲的 agent 各起一个任务。以前这里 break 掉了整个循环，于是全服每
		// tick 只有一个任务能开始——注释写着"其它伙伴仍可启动"，代码却做不到。
		// 多个伙伴（或测试并行跑）时，任务会莫名其妙排队好几 tick 才动。
		List<Task> starting = new ArrayList<>();
		Set<UUID> claimed = new java.util.HashSet<>(runningByAgent.keySet());
		for (Task candidate : pending) {
			if (!dependenciesMet(candidate) || claimed.contains(candidate.agentId())) {
				continue;
			}
			TaskExecutor executor = executors.get(candidate.type());
			if (executor != null && executor.requiresBody()
					&& contextFor(candidate.agentId(), contextFactory).body() == null) {
				continue; // 身体还没加载回来，让它继续排队而不是立刻失败
			}
			claimed.add(candidate.agentId());
			starting.add(candidate);
		}
		for (Task next : starting) {
			pending.remove(next);
			TaskEvaluationContext ctx = contextFor(next.agentId(), contextFactory);
			if (next.preconditions() != null && !next.preconditions().evaluate(ctx)) {
				fail(next, "PRECONDITION_FAILED");
				finished.add(next);
				allTasks.remove(next.taskId());
				continue;
			}
			startRunning(next);
		}
	}

	/** Preserve the executor's own error code, adding what actually blew up. */
	private static String describeThrow(Task task, RuntimeException e) {
		String existing = task.lastErrorCode().orElse(null);
		String detail = e.getMessage() == null ? e.getClass().getSimpleName()
			: e.getMessage();
		return existing == null ? detail : existing + ": " + detail;
	}

	private void startRunning(Task task) {
		LOG.info("[task] start {} type={} agent={}", task.taskId(), task.type(), task.agentId());
		task.transitionTo(TaskState.RUNNING);
		task.markStarted(lastTick);
		runningByAgent.put(task.agentId(), task);
		try {
			executorFor(task).start(task);
		} catch (RuntimeException e) {
			LOG.error("[task] executor {} start threw on {}",
				task.type(), task.taskId(), e);
			// 执行器抛异常前通常已经写好了真正的错误码（ENTITY_NOT_FOUND 之类）。
			// 以前这里用异常类名覆盖它，玩家最后只看到一个 IllegalStateException，
			// 完全不知道发生了什么。保留原因，把异常信息附在后面。
			task.setLastErrorCode(describeThrow(task, e));
			fail(task, task.lastErrorCode().orElse("INTERNAL_ERROR"));
		}
	}

	private boolean dependenciesMet(Task candidate) {
		for (UUID depId : candidate.dependencies()) {
			boolean completed = finished.stream()
				.anyMatch(f -> f.taskId().equals(depId) && f.state() == TaskState.COMPLETED);
			if (!completed) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ restart recovery (方案 A4)

	/** Export the declarative state of every live (pending/running) task. */
	public synchronized List<TaskStateStore.Snapshot> exportActive(long nowTick) {
		List<TaskStateStore.Snapshot> out = new ArrayList<>();
		for (Task task : allTasks.values()) {
			long remainingTimeout = Math.max(100L,
				task.timeoutTicks() - Math.max(0L, nowTick - task.timeoutBaseTick()));
			out.add(new TaskStateStore.Snapshot(task.taskId(), task.agentId(),
				task.requesterId(), task.type(), task.priority().name(),
				task.state().name(), task.attemptsUsed(), task.goalDescription(),
				task.parameters(),
				task.dependencies().stream().filter(allTasks::containsKey)
					.map(UUID::toString).toList(),
				nowTick, remainingTimeout,
				task.lastErrorCode().orElse(null),
				executorCheckpoint(task), task.retryPolicy().maxRetries(),
				task.retryPolicy().backoffTicks(), task.interruptible(),
				task.policySnapshot()));
		}
		return out;
	}

	private String executorCheckpoint(Task task) {
		TaskExecutor executor = executors.get(task.type());
		return executor == null ? null : executor.checkpoint(task);
	}

	/**
	 * Restart recovery: RUNNING → READY and re-run idempotently from parameters +
	 * checkpoint. Tasks whose type has no registered executor are handed to
	 * {@code unrecoverable} (FAILED(SERVER_RESTARTED) + owner notification upstream).
	 *
	 * @return how many tasks were re-armed
	 */
	public synchronized int restoreFromSnapshots(List<TaskStateStore.Snapshot> snapshots,
			long nowTick,
			java.util.function.Consumer<TaskStateStore.Snapshot> unrecoverable) {
		Map<UUID, TaskStateStore.Snapshot> candidates = new HashMap<>();
		Map<UUID, TaskCondition> successConditions = new HashMap<>();
		for (TaskStateStore.Snapshot s : snapshots) {
			TaskExecutor executor = executors.get(s.type());
			TaskCondition success = null;
			try {
				success = executor == null ? null : executor.recoverySuccessCondition(s);
			} catch (RuntimeException e) {
				LOG.warn("[task] could not rebuild recovery condition for {}: {}",
					s.taskId(), e.toString());
			}
			if (success == null || candidates.containsKey(s.taskId())) {
				if (unrecoverable != null) {
					unrecoverable.accept(s);
				}
				continue;
			}
			candidates.put(s.taskId(), s);
			successConditions.put(s.taskId(), success);
		}

		boolean removed;
		do {
			removed = false;
			for (TaskStateStore.Snapshot s : List.copyOf(candidates.values())) {
				if (dependenciesSurvive(s, candidates.keySet())) {
					continue;
				}
				candidates.remove(s.taskId());
				successConditions.remove(s.taskId());
				if (unrecoverable != null) {
					unrecoverable.accept(s);
				}
				removed = true;
			}
		} while (removed);

		Map<UUID, Task> restoredTasks = new HashMap<>();
		for (TaskStateStore.Snapshot s : candidates.values()) {
			TaskExecutor executor = executors.get(s.type());
			TaskPriority priority;
			try {
				priority = TaskPriority.valueOf(s.priority());
			} catch (IllegalArgumentException | NullPointerException e) {
				priority = TaskPriority.P3_USER_TASK;
			}
			Task restored = Task.restore(s.taskId(), s.agentId(), s.ownerId(), s.type(),
				priority, s.goalDescription() == null ? "restored" : s.goalDescription(),
				executor.recoveryPrecondition(s), successConditions.get(s.taskId()),
				Math.max(100L, s.timeoutTicks()),
				new RetryPolicy(Math.max(0, s.maxRetries()), Math.max(0, s.backoffTicks())),
				s.interruptible(), s.policySnapshot(), s.parameters(), s.attemptsUsed(),
				s.lastErrorCode());
			restoredTasks.put(s.taskId(), restored);
			allTasks.put(s.taskId(), restored);
		}
		// second pass rewires dependencies through the old→new id map
		for (TaskStateStore.Snapshot s : candidates.values()) {
			Task restored = restoredTasks.get(s.taskId());
			for (String oldDep : s.dependencies()) {
				restored.dependsOnId(UUID.fromString(oldDep));
				// a dependency that did not survive counts as failed → cascade below
			}
			if (s.checkpoint() != null) {
				try {
					executorFor(restored).restoreCheckpoint(restored, s.checkpoint());
				} catch (RuntimeException e) {
					LOG.warn("[task] checkpoint restore failed on {}: {}", s.taskId(),
						e.toString());
				}
			}
		}
		// queue every restored task; missing dependencies cascade to DEPENDENCY_FAILED
		for (Task restored : restoredTasks.values()) {
			restored.markCreated(nowTick);
			restored.transitionTo(TaskState.PLANNING);
			restored.transitionTo(TaskState.READY);
			pending.add(restored);
		}
		return restoredTasks.size();
	}

	private static boolean dependenciesSurvive(TaskStateStore.Snapshot snapshot,
			Set<UUID> candidates) {
		for (String raw : snapshot.dependencies()) {
			try {
				if (!candidates.contains(UUID.fromString(raw))) {
					return false;
				}
			} catch (IllegalArgumentException e) {
				return false;
			}
		}
		return true;
	}

	private void toReady(Task task) {
		if (task.state().canTransitionTo(TaskState.READY)) {
			task.transitionTo(TaskState.READY);
		}
	}

	private TaskExecutor executorFor(Task task) {
		TaskExecutor executor = executors.get(task.type());
		if (executor == null) {
			throw new IllegalStateException("no executor registered for " + task.type());
		}
		return executor;
	}
}
