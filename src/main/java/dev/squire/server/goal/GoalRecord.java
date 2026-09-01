package dev.squire.server.goal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent, goal-level ownership over a task DAG. */
public final class GoalRecord {
	public enum Kind {
		ACQUIRE_AND_GIVE, CRAFT_AND_GIVE, GATHER, GUARD, AID_OWNER,
		/** 工程里的一个阶段（第 3 期）。完成/失败由工程自己播报，
		 * 所以这一类目标不再重复发一遍通知。 */
		PROJECT_STAGE
	}
	public enum State { RUNNING, REPLANNING, COMPLETED, FAILED, CANCELLED }

	private final UUID goalId;
	private final UUID ownerId;
	private final UUID agentId;
	private final Kind kind;
	private final String subject;
	private final int requestedCount;
	private final int targetFinalCount;
	private final long createdTick;
	private long updatedTick;
	private State state;
	private int replans;
	private String lastError;
	private final List<UUID> remainingTaskIds;

	public GoalRecord(UUID goalId, UUID ownerId, UUID agentId, Kind kind,
			String subject, int requestedCount, int targetFinalCount, long createdTick,
			long updatedTick, State state, int replans, String lastError,
			List<UUID> remainingTaskIds) {
		this.goalId = goalId;
		this.ownerId = ownerId;
		this.agentId = agentId;
		this.kind = kind;
		this.subject = subject;
		this.requestedCount = requestedCount;
		this.targetFinalCount = targetFinalCount;
		this.createdTick = createdTick;
		this.updatedTick = updatedTick;
		this.state = state;
		this.replans = replans;
		this.lastError = lastError;
		this.remainingTaskIds = new ArrayList<>(remainingTaskIds);
	}

	public UUID goalId() { return goalId; }
	public UUID ownerId() { return ownerId; }
	public UUID agentId() { return agentId; }
	public Kind kind() { return kind; }
	public String subject() { return subject; }
	public int requestedCount() { return requestedCount; }
	public int targetFinalCount() { return targetFinalCount; }
	public long createdTick() { return createdTick; }
	public long updatedTick() { return updatedTick; }
	public State state() { return state; }
	public int replans() { return replans; }
	public String lastError() { return lastError; }
	public List<UUID> remainingTaskIds() { return List.copyOf(remainingTaskIds); }

	void replaceTasks(List<UUID> ids, long tick) {
		remainingTaskIds.clear();
		remainingTaskIds.addAll(ids);
		updatedTick = tick;
		state = State.RUNNING;
	}

	void taskCompleted(UUID id, long tick) {
		remainingTaskIds.remove(id);
		updatedTick = tick;
	}

	void beginReplan(String error, long tick) {
		state = State.REPLANNING;
		replans++;
		lastError = error;
		updatedTick = tick;
	}

	void finish(State terminal, String error, long tick) {
		state = terminal;
		lastError = error;
		remainingTaskIds.clear();
		updatedTick = tick;
	}
}
