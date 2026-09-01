package dev.squire.server.tool;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-planning-turn loop protection (spec section 31): bounds total calls, same-tool
 * calls and EQUIVALENT (same tool + same argument) calls. Thresholds are hard caps.
 */
public final class LoopProtector {
	public static final int MAX_TOOL_CALLS = 32;
	public static final int MAX_SAME_TOOL_CALLS = 6;
	public static final int MAX_EQUIVALENT_CALLS = 3;
	public static final int MAX_REPLANS = 2;

	private long totalCalls;
	private int replans;
	private final Map<String, Integer> perTool = new HashMap<>();
	private final Map<String, Integer> equivalent = new HashMap<>();

	/** @return empty when allowed, present = LOOP_DETECTED reason code detail. */
	public Optional<String> checkAndCount(String toolName, String argsFingerprint) {
		if (totalCalls + 1 > MAX_TOOL_CALLS) {
			return Optional.of("maxToolCalls=" + MAX_TOOL_CALLS + " exceeded");
		}
		int sameTool = perTool.merge(toolName, 1, Integer::sum);
		if (sameTool > MAX_SAME_TOOL_CALLS) {
			return Optional.of("maxSameToolCalls=" + MAX_SAME_TOOL_CALLS
				+ " exceeded for " + toolName);
		}
		String eqKey = toolName + "|" + argsFingerprint;
		int sameArgs = equivalent.merge(eqKey, 1, Integer::sum);
		if (sameArgs > MAX_EQUIVALENT_CALLS) {
			return Optional.of("maxEquivalentCalls=" + MAX_EQUIVALENT_CALLS
				+ " exceeded for " + eqKey);
		}
		totalCalls++;
		return Optional.empty();
	}

	public Optional<String> checkAndCountReplan() {
		if (replans + 1 > MAX_REPLANS) {
			return Optional.of("maxReplans=" + MAX_REPLANS + " exceeded");
		}
		replans++;
		return Optional.empty();
	}

	/** New turn: counters reset (world may have changed through task execution). */
	public void reset() {
		totalCalls = 0;
		replans = 0;
		perTool.clear();
		equivalent.clear();
	}

	public boolean isExhausted() {
		return totalCalls >= MAX_TOOL_CALLS;
	}
}
