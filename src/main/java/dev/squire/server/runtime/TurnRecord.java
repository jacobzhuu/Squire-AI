package dev.squire.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable state for one bounded conversational planning loop.  The model owns only
 * intent/high-level planning; every observed action is still executed and verified
 * by the server runtime.
 */
public final class TurnRecord {
	public enum State {
		UNDERSTAND, DISPATCH, WAIT_TOOL, OBSERVE, REPLAN,
		CLARIFYING, COMPLETE, ASK_USER, FAILED
	}

	private final UUID turnId;
	private final UUID ownerId;
	private final UUID agentId;
	private final String originalInput;
	private String providerId;
	private final long createdTick;
	private long updatedTick;
	private long deadlineTick;
	private State state;
	private int replans;
	private int toolCalls;
	private int estimatedTokens;
	private int highRiskCalls;
	private String lastError;
	private final List<UUID> waitingTaskIds;
	private final List<UUID> waitingCallIds;
	private final List<String> observations;
	private boolean waitingFailed;
	private int providerRetries;
	private String pendingQuestion;
	private final List<String> planSteps;

	public TurnRecord(UUID turnId, UUID ownerId, UUID agentId, String originalInput,
			String providerId, long createdTick, long updatedTick, long deadlineTick,
			State state, int replans, int toolCalls, int estimatedTokens,
			int highRiskCalls, String lastError,
			List<UUID> waitingTaskIds, List<UUID> waitingCallIds,
			List<String> observations, boolean waitingFailed) {
		this(turnId, ownerId, agentId, originalInput, providerId, createdTick,
			updatedTick, deadlineTick, state, replans, toolCalls, estimatedTokens,
			highRiskCalls, lastError, waitingTaskIds, waitingCallIds, observations,
			waitingFailed, 0, null, List.of());
	}

	public TurnRecord(UUID turnId, UUID ownerId, UUID agentId, String originalInput,
			String providerId, long createdTick, long updatedTick, long deadlineTick,
			State state, int replans, int toolCalls, int estimatedTokens,
			int highRiskCalls, String lastError,
			List<UUID> waitingTaskIds, List<UUID> waitingCallIds,
			List<String> observations, boolean waitingFailed, int providerRetries,
			String pendingQuestion, List<String> planSteps) {
		this.turnId = java.util.Objects.requireNonNull(turnId, "turnId");
		this.ownerId = java.util.Objects.requireNonNull(ownerId, "ownerId");
		this.agentId = agentId;
		this.originalInput = java.util.Objects.requireNonNullElse(originalInput, "");
		this.providerId = java.util.Objects.requireNonNullElse(providerId, "");
		this.createdTick = createdTick;
		this.updatedTick = updatedTick;
		this.deadlineTick = deadlineTick;
		this.state = java.util.Objects.requireNonNullElse(state, State.FAILED);
		this.replans = Math.max(0, replans);
		this.toolCalls = Math.max(0, toolCalls);
		this.estimatedTokens = Math.max(0, estimatedTokens);
		this.highRiskCalls = Math.max(0, highRiskCalls);
		this.lastError = lastError;
		this.waitingTaskIds = new ArrayList<>(waitingTaskIds == null
			? List.of() : waitingTaskIds);
		this.waitingCallIds = new ArrayList<>(waitingCallIds == null
			? List.of() : waitingCallIds);
		this.observations = new ArrayList<>(observations == null
			? List.of() : observations);
		this.waitingFailed = waitingFailed;
		this.providerRetries = Math.max(0, providerRetries);
		this.pendingQuestion = pendingQuestion;
		this.planSteps = new ArrayList<>(planSteps == null ? List.of() : planSteps);
	}

	public UUID turnId() { return turnId; }
	public UUID ownerId() { return ownerId; }
	public UUID agentId() { return agentId; }
	public String originalInput() { return originalInput; }
	public String providerId() { return providerId; }
	public long createdTick() { return createdTick; }
	public long updatedTick() { return updatedTick; }
	public long deadlineTick() { return deadlineTick; }
	public State state() { return state; }
	public int replans() { return replans; }
	public int toolCalls() { return toolCalls; }
	public int estimatedTokens() { return estimatedTokens; }
	public int highRiskCalls() { return highRiskCalls; }
	public String lastError() { return lastError; }
	public List<UUID> waitingTaskIds() { return List.copyOf(waitingTaskIds); }
	public List<UUID> waitingCallIds() { return List.copyOf(waitingCallIds); }
	public List<String> observations() { return List.copyOf(observations); }
	public boolean waitingFailed() { return waitingFailed; }
	public int providerRetries() { return providerRetries; }
	public String pendingQuestion() { return pendingQuestion; }
	public List<String> planSteps() { return List.copyOf(planSteps); }

	public boolean isTerminal() {
		return state == State.COMPLETE || state == State.ASK_USER || state == State.FAILED;
	}

	void switchProvider(String id, long tick) {
		providerId = java.util.Objects.requireNonNullElse(id, "");
		providerRetries = 0;
		updatedTick = tick;
	}

	void incrementProviderRetry(long tick) {
		providerRetries++;
		updatedTick = tick;
	}

	void clearProviderRetries(long tick) {
		providerRetries = 0;
		updatedTick = tick;
	}

	void setPlan(List<String> steps, long tick) {
		planSteps.clear();
		if (steps != null) planSteps.addAll(steps.stream().limit(8).toList());
		updatedTick = tick;
	}

	void awaitClarification(String question, long deadline, long tick) {
		pendingQuestion = question == null ? "" : question;
		deadlineTick = deadline;
		state(State.CLARIFYING, tick);
	}

	void resumeClarification(long deadline, long tick) {
		pendingQuestion = null;
		deadlineTick = deadline;
		state(State.REPLAN, tick);
	}

	void state(State value, long tick) {
		state = value;
		updatedTick = tick;
	}

	void setDeadlineTick(long value) { deadlineTick = value; }

	void addToolCalls(int count, long tick) {
		toolCalls += Math.max(0, count);
		updatedTick = tick;
	}

	boolean consumeEstimatedTokens(int count, int limit, long tick) {
		if (count < 0 || estimatedTokens + count > limit) return false;
		estimatedTokens += count;
		updatedTick = tick;
		return true;
	}

	boolean consumeHighRiskCalls(int count, int limit, long tick) {
		if (count < 0 || highRiskCalls + count > limit) return false;
		highRiskCalls += count;
		updatedTick = tick;
		return true;
	}

	void addObservation(String json, long tick) {
		if (json != null && !json.isBlank()) observations.add(json);
		updatedTick = tick;
	}

	void waitFor(List<UUID> taskIds, List<UUID> callIds, boolean failed, long tick) {
		waitingTaskIds.clear();
		waitingTaskIds.addAll(taskIds);
		waitingCallIds.clear();
		waitingCallIds.addAll(callIds);
		waitingFailed = failed;
		state(State.WAIT_TOOL, tick);
	}

	void clearWaiting(long tick) {
		waitingTaskIds.clear();
		waitingCallIds.clear();
		waitingFailed = false;
		updatedTick = tick;
	}

	void beginReplan(String error, long tick) {
		replans++;
		lastError = error;
		state(State.REPLAN, tick);
	}

	void fail(String error, long tick) {
		lastError = error;
		clearWaiting(tick);
		state(State.FAILED, tick);
		pendingQuestion = null;
	}
}
