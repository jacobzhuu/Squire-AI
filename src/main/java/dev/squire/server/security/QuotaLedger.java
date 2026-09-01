package dev.squire.server.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import dev.squire.common.errors.ErrorCode;

/**
 * Rolling-window quota ledger for the gateway's Quota stage (spec section 66).
 * Counters are tick-based; every check-and-record is atomic on the server thread.
 */
public final class QuotaLedger {

	private final int toolCallsPerMinute;
	private final long blocksChangedPerHour;

	private final Deque<Long> toolCallTicks = new ArrayDeque<>();
	private long blocksChangedThisHour = 0;
	private long hourWindowStartTick = 0;

	public static final int DEFAULT_TOOL_CALLS_PER_MINUTE = 120;
	public static final long DEFAULT_BLOCKS_PER_HOUR = 100_000L;

	public QuotaLedger(int toolCallsPerMinute, long blocksChangedPerHour) {
		this.toolCallsPerMinute = Math.max(1, toolCallsPerMinute);
		this.blocksChangedPerHour = Math.max(1, blocksChangedPerHour);
	}

	public QuotaLedger() {
		this(DEFAULT_TOOL_CALLS_PER_MINUTE, DEFAULT_BLOCKS_PER_HOUR);
	}

	/**
	 * Record one tool dispatch.
	 *
	 * @return empty when allowed, or the rejection code (RATE_LIMITED)
	 */
	public Optional<ErrorCode> recordToolCall(long nowTick) {
		while (!toolCallTicks.isEmpty() && nowTick - toolCallTicks.peekFirst() >= 1200L) {
			toolCallTicks.pollFirst();
		}
		if (toolCallTicks.size() >= toolCallsPerMinute) {
			return Optional.of(ErrorCode.RATE_LIMITED);
		}
		toolCallTicks.addLast(nowTick);
		return Optional.empty();
	}

	/**
	 * Record {@code blocks} world changes in the trailing hour window.
	 *
	 * @return empty when allowed, or BUDGET_EXCEEDED
	 */
	public Optional<ErrorCode> recordBlocksChanged(long nowTick, long blocks) {
		if (nowTick - hourWindowStartTick >= 72_000L) { // one hour of ticks
			hourWindowStartTick = nowTick;
			blocksChangedThisHour = 0;
		}
		if (blocks > 0 && blocksChangedThisHour + blocks > blocksChangedPerHour) {
			return Optional.of(ErrorCode.BUDGET_EXCEEDED);
		}
		blocksChangedThisHour += Math.max(0, blocks);
		return Optional.empty();
	}

	public int toolCallsInWindow() {
		return toolCallTicks.size();
	}

	public long blocksChangedThisWindow() {
		return blocksChangedThisHour;
	}
}
