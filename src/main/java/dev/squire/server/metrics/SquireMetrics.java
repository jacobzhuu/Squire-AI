package dev.squire.server.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded observability counters (spec §75). Every metric name is one of the
 * spec's fixed keys — nothing dynamic can be registered, so the set cannot grow
 * unbounded. Counters are {@link LongAdder}s (safe from both the server thread
 * and async settlement executors); durations accumulate nanoseconds plus a max.
 */
public final class SquireMetrics {

	/** Fixed metric identities (spec §75). */
	public enum Key {
		FASTPATH_REQUESTS("fastpath_requests"),
		FASTPATH_HITS("fastpath_hits"),
		LLM_REQUESTS("llm_requests_total"),
		LLM_FAILURES("llm_failures"),
		LLM_EMPTY_OUTPUTS("llm_empty_outputs"),
		LLM_TRUNCATED("llm_truncated_outputs"),
		LLM_FAILOVERS("llm_failovers"),
		LLM_CLARIFICATIONS("llm_clarifications"),
		LLM_TOKENS("llm_tokens"),
		TOOL_CALLS("tool_calls"),
		TOOL_REJECTED("tool_rejected"),
		TASKS_TOTAL("tasks_total"),
		MCP_CALLS("mcp_calls"),
		MCP_FAILURES("mcp_failures"),
		WORLDEDIT_BLOCKS("worldedit_blocks"),
		UNDO_ENTRIES("undo_entries");

		private final String wire;

		Key(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** Duration metric identities (count + total-nanos + max-nanos). */
	public enum Timer {
		LLM_LATENCY("llm_latency"),
		TOOL_DURATION("tool_duration"),
		TASK_DURATION("task_duration");

		private final String wire;

		Timer(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	private static final class TimerSample {
		final LongAdder count = new LongAdder();
		final LongAdder totalNanos = new LongAdder();
		volatile long maxNanos;
	}

	private final Map<Key, LongAdder> counters = new ConcurrentHashMap<>();
	private final Map<Timer, TimerSample> timers = new ConcurrentHashMap<>();

	public SquireMetrics() {
		for (Key key : Key.values()) {
			counters.put(key, new LongAdder());
		}
		for (Timer timer : Timer.values()) {
			timers.put(timer, new TimerSample());
		}
	}

	/** Increments one of the fixed counters. */
	public void inc(Key key) {
		counters.get(key).increment();
	}

	public void add(Key key, long delta) {
		counters.get(key).add(delta);
	}

	public long value(Key key) {
		return counters.get(key).sum();
	}

	/** Records one duration sample in nanoseconds. */
	public void record(Timer timer, long nanos) {
		if (nanos < 0) {
			return;
		}
		TimerSample sample = timers.get(timer);
		sample.count.increment();
		sample.totalNanos.add(nanos);
		long prevMax = sample.maxNanos;
		if (nanos > prevMax) {
			sample.maxNanos = nanos; // benign race: close enough for a gauge
		}
	}

	/** FastPath hit ratio in [0,1]; 0 when nothing was requested yet. */
	public double fastpathHitRatio() {
		long requests = value(Key.FASTPATH_REQUESTS);
		return requests == 0 ? 0.0 : (double) value(Key.FASTPATH_HITS) / requests;
	}

	/** Human-readable snapshot lines (sorted by name) for the admin command. */
	public List<String> snapshotLines() {
		List<String> lines = new ArrayList<>();
		for (Key key : Key.values()) {
			lines.add(key.wire() + "=" + value(key));
		}
		for (Timer timer : Timer.values()) {
			TimerSample s = timers.get(timer);
			long count = s.count.sum();
			if (count == 0) {
				lines.add(timer.wire() + "=n/a");
				continue;
			}
			double avgMs = s.totalNanos.sum() / count / 1_000_000.0;
			double maxMs = s.maxNanos / 1_000_000.0;
			lines.add(String.format(java.util.Locale.ROOT,
				"%s=n=%d avg=%.2fms max=%.2fms", timer.wire(), count, avgMs, maxMs));
		}
		lines.add(String.format(java.util.Locale.ROOT, "fastpath_hit_ratio=%.3f",
			fastpathHitRatio()));
		return lines;
	}

	/** Test seam: zeroes everything. */
	public void resetForTests() {
		for (LongAdder counter : counters.values()) {
			counter.reset();
		}
		for (TimerSample sample : timers.values()) {
			sample.count.reset();
			sample.totalNanos.reset();
			sample.maxNanos = 0;
		}
	}
}
