package dev.squire.server.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在飞的外部调用登记表（方案 I2）：callId → owner / agent / turn / task / tool /
 * deadline。
 *
 * <p>没有这张表，一个 RUNNING 的远端调用就是个孤儿：结果回来了不知道该唤醒谁，
 * 结果永远不回来也没人发现。登记表让每一次异步调用都有归属和期限——超时由
 * 服务器 tick 主动发现并合成一个结构化失败，而不是让 Turn 永远等下去。</p>
 */
public final class PendingExternalCallRegistry {

	private static final Logger LOG =
		LoggerFactory.getLogger(PendingExternalCallRegistry.class);

	/** 同时在飞的调用上限，防止一个坏掉的服务器把内存吃满。 */
	public static final int MAX_IN_FLIGHT = 256;

	/** 一次在飞的外部调用。 */
	public record PendingCall(UUID callId, UUID ownerId, UUID agentId, UUID turnId,
			UUID taskId, String server, String toolName, long issuedAtTick,
			long deadlineTick) {

		public boolean isOverdue(long nowTick) {
			return deadlineTick > 0 && nowTick >= deadlineTick;
		}
	}

	private final Map<UUID, PendingCall> inFlight = new ConcurrentHashMap<>();

	/**
	 * Register a call that just went out.
	 *
	 * @return false when the in-flight cap is already reached (caller must fail fast)
	 */
	public boolean register(PendingCall call) {
		if (call == null) {
			return false;
		}
		if (inFlight.size() >= MAX_IN_FLIGHT && !inFlight.containsKey(call.callId())) {
			LOG.warn("[mcp] refusing call {} to {}: {} already in flight",
				call.callId(), call.server(), inFlight.size());
			return false;
		}
		inFlight.put(call.callId(), call);
		return true;
	}

	public Optional<PendingCall> get(UUID callId) {
		return Optional.ofNullable(inFlight.get(callId));
	}

	/** Settle one call; returns what it was, so the caller knows whom to notify. */
	public Optional<PendingCall> complete(UUID callId) {
		return Optional.ofNullable(inFlight.remove(callId));
	}

	public int inFlightCount() {
		return inFlight.size();
	}

	public List<PendingCall> all() {
		return List.copyOf(inFlight.values());
	}

	public List<PendingCall> forServer(String server) {
		return inFlight.values().stream()
			.filter(call -> call.server().equals(server)).toList();
	}

	/**
	 * Calls whose deadline has passed. They are REMOVED here, so each overdue call is
	 * reported exactly once and the caller can synthesise a structured timeout.
	 */
	public List<PendingCall> takeOverdue(long nowTick) {
		List<PendingCall> overdue = inFlight.values().stream()
			.filter(call -> call.isOverdue(nowTick)).toList();
		overdue.forEach(call -> inFlight.remove(call.callId()));
		if (!overdue.isEmpty()) {
			LOG.info("[mcp] {} external call(s) passed their deadline", overdue.size());
		}
		return overdue;
	}

	/** Drop everything for a server that just disconnected. */
	public List<PendingCall> takeAllForServer(String server) {
		List<PendingCall> affected = forServer(server);
		affected.forEach(call -> inFlight.remove(call.callId()));
		return affected;
	}

	public void clear() {
		inFlight.clear();
	}
}
