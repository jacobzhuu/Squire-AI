package dev.squire.server.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One unit of agent work (spec section 18). Confined to the server thread; the
 * scheduler is the only mutator besides the Goal Verifier's completion edge.
 */
public final class Task {
	private final UUID taskId;
	private final UUID agentId;
	private final UUID requesterId;
	private final String type;
	private final TaskPriority priority;
	private final String goalDescription;
	private final List<UUID> dependencies = new ArrayList<>();
	private final TaskCondition preconditions;
	private final TaskCondition successCondition;
	private final long timeoutTicks;
	private final RetryPolicy retryPolicy;
	private final boolean interruptible;
	private final String policySnapshot;
	private final Map<String, Object> parameters;

	private TaskState state = TaskState.CREATED;
	private long createdTick;
	/** -1 until the task first enters RUNNING; the timeout budget starts there. */
	private long startedTick = -1;
	private int attemptsUsed;
	private String lastErrorCode;
	private Object executionState; // opaque per-task-type progress (handles, counters)

	public Task(UUID agentId, UUID requesterId, String type, TaskPriority priority,
			String goalDescription, TaskCondition preconditions,
			TaskCondition successCondition, long timeoutTicks, RetryPolicy retryPolicy,
			boolean interruptible, String policySnapshot) {
		this(agentId, requesterId, type, priority, goalDescription, preconditions,
			successCondition, timeoutTicks, retryPolicy, interruptible, policySnapshot, Map.of());
	}

	public Task(UUID agentId, UUID requesterId, String type, TaskPriority priority,
			String goalDescription, TaskCondition preconditions,
			TaskCondition successCondition, long timeoutTicks, RetryPolicy retryPolicy,
			boolean interruptible, String policySnapshot, Map<String, Object> parameters) {
		this(UUID.randomUUID(), agentId, requesterId, type, priority, goalDescription,
			preconditions, successCondition, timeoutTicks, retryPolicy, interruptible,
			policySnapshot, parameters);
	}

	private Task(UUID taskId, UUID agentId, UUID requesterId, String type,
			TaskPriority priority, String goalDescription, TaskCondition preconditions,
			TaskCondition successCondition, long timeoutTicks, RetryPolicy retryPolicy,
			boolean interruptible, String policySnapshot, Map<String, Object> parameters) {
		this.taskId = java.util.Objects.requireNonNull(taskId, "taskId");
		this.agentId = agentId;
		this.requesterId = requesterId;
		this.type = type;
		this.priority = priority;
		this.goalDescription = goalDescription;
		this.preconditions = preconditions;
		this.successCondition = successCondition;
		this.timeoutTicks = timeoutTicks;
		this.retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
		this.interruptible = interruptible;
		this.policySnapshot = policySnapshot;
		this.parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
	}

	/** Recreate a persisted task with the same id and retry budget. */
	static Task restore(UUID taskId, UUID agentId, UUID requesterId, String type,
			TaskPriority priority, String goalDescription, TaskCondition preconditions,
			TaskCondition successCondition, long timeoutTicks, RetryPolicy retryPolicy,
			boolean interruptible, String policySnapshot, Map<String, Object> parameters,
			int attemptsUsed, String lastErrorCode) {
		Task task = new Task(taskId, agentId, requesterId, type, priority,
			goalDescription, preconditions, successCondition, timeoutTicks, retryPolicy,
			interruptible, policySnapshot, parameters);
		task.attemptsUsed = Math.max(0, attemptsUsed);
		task.lastErrorCode = lastErrorCode;
		return task;
	}

	/** Immutable tool/task arguments carried into the executor (spec section 18). */
	public Map<String, Object> parameters() {
		return parameters;
	}

	public String stringParam(String key) {
		Object v = parameters.get(key);
		return v instanceof String s ? s : null;
	}

	public Integer intParam(String key) {
		Object v = parameters.get(key);
		if (v instanceof Number n) {
			return n.intValue();
		}
		return null;
	}

	// ------------------------------------------------------------------ transitions

	/** All edges except VERIFYING→COMPLETED, which requires {@link #completeVerified}. */
	public void transitionTo(TaskState target) {
		if (state == TaskState.VERIFYING && target == TaskState.COMPLETED) {
			throw new IllegalStateException(
				"VERIFYING -> COMPLETED requires the Goal Verifier (ADR-013)");
		}
		if (!state.canTransitionTo(target)) {
			throw new IllegalStateException("illegal transition " + state + " -> " + target);
		}
		if (target == TaskState.RUNNING && state == TaskState.RETRYING
				|| target == TaskState.RUNNING && state == TaskState.READY) {
			attemptsUsed++;
		}
		state = target;
	}

	/** The single legal path to COMPLETED — callable only within server.task. */
	void completeVerified() {
		if (!state.canTransitionTo(TaskState.COMPLETED)) {
			throw new IllegalStateException("illegal transition " + state + " -> COMPLETED");
		}
		this.state = TaskState.COMPLETED;
	}

	// ------------------------------------------------------------------ getters/setters

	public UUID taskId() {
		return taskId;
	}

	public UUID agentId() {
		return agentId;
	}

	public UUID requesterId() {
		return requesterId;
	}

	public String type() {
		return type;
	}

	public TaskPriority priority() {
		return priority;
	}

	public TaskState state() {
		return state;
	}

	public String goalDescription() {
		return goalDescription;
	}

	public List<UUID> dependencies() {
		return List.copyOf(dependencies);
	}

	public void dependsOn(Task other) {
		dependencies.add(other.taskId());
	}

	void dependsOnId(UUID taskId) {
		dependencies.add(java.util.Objects.requireNonNull(taskId, "taskId"));
	}

	public TaskCondition preconditions() {
		return preconditions;
	}

	public TaskCondition successCondition() {
		return successCondition;
	}

	public long timeoutTicks() {
		return timeoutTicks;
	}

	public RetryPolicy retryPolicy() {
		return retryPolicy;
	}

	public boolean interruptible() {
		return interruptible;
	}

	public String policySnapshot() {
		return policySnapshot;
	}

	public int attemptsUsed() {
		return attemptsUsed;
	}

	public Optional<String> lastErrorCode() {
		return Optional.ofNullable(lastErrorCode);
	}

	public void setLastErrorCode(String code) {
		this.lastErrorCode = code;
	}

	public long createdTick() {
		return createdTick;
	}

	public void markCreated(long tick) {
		this.createdTick = tick;
	}

	/**
	 * Tick at which this task first got the agent and started executing, or -1 while
	 * it is still queued.
	 */
	public long startedTick() {
		return startedTick;
	}

	public void markStarted(long tick) {
		if (this.startedTick < 0) {
			this.startedTick = tick;
		}
	}

	/**
	 * The tick the timeout budget counts from. A task blocked behind a dependency
	 * must NOT burn its own budget while waiting — a long real-furnace smelt used to
	 * time out every craft queued behind it before those crafts ever ran.
	 */
	public long timeoutBaseTick() {
		return startedTick >= 0 ? startedTick : createdTick;
	}
	/**
	 * 执行器收尾时公布的<b>工作量摘要</b>。
	 *
	 * <p>为什么不直接读 {@link #executionState()}：调度器在任务进入 VERIFYING 时
	 * 会把那个字段改成「开始验证的 tick」（看 {@code TaskScheduler}），所以任务真正
	 * 走到 COMPLETED 的时候，执行器的计数已经被覆盖掉了。熟练度记账恰好就在那一刻
	 * 发生，所以它需要一个自己的、不会被任何人复用的字段。</p>
	 *
	 * @param kind        工作种类（与 {@code Track} 的 id 对应）；认不出就不记账
	 * @param amount      真实完成的量（格数/件数）
	 * @param operationId 对应的撤销操作；非空时这笔账可以被撤销回扣
	 */
	public record WorkReport(String kind, long amount, java.util.UUID operationId) { }

	private volatile WorkReport workReport;

	/** 执行器收尾时调一次。 */
	public void setWorkReport(WorkReport report) {
		this.workReport = report;
	}

	/** {@code null} 表示这类任务不产生可计量的工作量。 */
	public WorkReport workReport() {
		return workReport;
	}



	public Object executionState() {
		return executionState;
	}

	public void setExecutionState(Object executionState) {
		this.executionState = executionState;
	}

	@Override
	public String toString() {
		return "Task[" + type + " " + state + " prio=" + priority + "]";
	}
}
