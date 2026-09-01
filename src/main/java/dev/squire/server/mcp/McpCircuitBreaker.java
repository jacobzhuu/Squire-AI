package dev.squire.server.mcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每台 MCP 服务器一个熔断器（方案 I2）。
 *
 * <p>一台挂掉的远端服务器不该让每一次对话都先卡满超时时间再失败。连续失败到阈值
 * 后短路一段时间，期间调用立刻得到结构化的 {@code MCP_CIRCUIT_OPEN}，模型可以据此
 * 换个方案，而不是反复撞墙。</p>
 *
 * <p>半开：冷却期过后放一次调用过去探路，成功就完全恢复，失败就重新熔断。</p>
 */
public final class McpCircuitBreaker {

	private static final Logger LOG = LoggerFactory.getLogger(McpCircuitBreaker.class);

	/** 连续失败多少次后熔断。 */
	public static final int FAILURE_THRESHOLD = 5;
	/** 熔断后多久允许一次探路调用（服务器 tick）。 */
	public static final long COOLDOWN_TICKS = 600L;

	public enum State { CLOSED, OPEN, HALF_OPEN }

	private static final class Entry {
		int consecutiveFailures;
		long openedAtTick = -1;
		boolean probeInFlight;
	}

	private final Map<String, Entry> byServer = new ConcurrentHashMap<>();

	/** Current state, resolving the cooldown into HALF_OPEN when it has elapsed. */
	public synchronized State stateOf(String server, long nowTick) {
		Entry entry = byServer.get(server);
		if (entry == null || entry.openedAtTick < 0) {
			return State.CLOSED;
		}
		return nowTick - entry.openedAtTick >= COOLDOWN_TICKS ? State.HALF_OPEN : State.OPEN;
	}

	/**
	 * Ask permission to call.
	 *
	 * @return true when the call may proceed (CLOSED, or one probe while HALF_OPEN)
	 */
	public synchronized boolean allowCall(String server, long nowTick) {
		State state = stateOf(server, nowTick);
		if (state == State.CLOSED) {
			return true;
		}
		if (state == State.OPEN) {
			return false;
		}
		Entry entry = byServer.get(server);
		if (entry.probeInFlight) {
			return false; // one probe at a time
		}
		entry.probeInFlight = true;
		return true;
	}

	public synchronized void recordSuccess(String server) {
		Entry entry = byServer.get(server);
		if (entry == null) {
			return;
		}
		if (entry.openedAtTick >= 0) {
			LOG.info("[mcp] circuit for '{}' closed again after a successful probe",
				server);
		}
		byServer.remove(server);
	}

	public synchronized void recordFailure(String server, long nowTick) {
		Entry entry = byServer.computeIfAbsent(server, key -> new Entry());
		entry.probeInFlight = false;
		if (entry.openedAtTick >= 0) {
			entry.openedAtTick = nowTick; // probe failed: restart the cooldown
			return;
		}
		entry.consecutiveFailures++;
		if (entry.consecutiveFailures >= FAILURE_THRESHOLD) {
			entry.openedAtTick = nowTick;
			LOG.warn("[mcp] circuit for '{}' OPEN after {} consecutive failures",
				server, entry.consecutiveFailures);
		}
	}

	public synchronized int consecutiveFailures(String server) {
		Entry entry = byServer.get(server);
		return entry == null ? 0 : entry.consecutiveFailures;
	}

	/** Admin reset (e.g. after fixing the remote server). */
	public synchronized void reset(String server) {
		byServer.remove(server);
	}

	public synchronized void clear() {
		byServer.clear();
	}
}
