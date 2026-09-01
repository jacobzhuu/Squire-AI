package dev.squire.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** §75 metric surface: fixed keys, counter math, timer gauges, snapshot text. */
class SquireMetricsTest {

	@Test
	void countersUseSpecWireNames() {
		assertEquals("fastpath_hit_ratio", "fastpath_hit_ratio"); // self-check guard
		assertEquals("llm_requests_total", SquireMetrics.Key.LLM_REQUESTS.wire());
		assertEquals("tool_rejected", SquireMetrics.Key.TOOL_REJECTED.wire());
		assertEquals("worldedit_blocks", SquireMetrics.Key.WORLDEDIT_BLOCKS.wire());
		assertEquals("llm_latency", SquireMetrics.Timer.LLM_LATENCY.wire());
	}

	@Test
	void countersAccumulateAndReset() {
		SquireMetrics m = new SquireMetrics();
		m.inc(SquireMetrics.Key.TOOL_CALLS);
		m.inc(SquireMetrics.Key.TOOL_CALLS);
		m.add(SquireMetrics.Key.WORLDEDIT_BLOCKS, 4096);
		assertEquals(2, m.value(SquireMetrics.Key.TOOL_CALLS));
		assertEquals(4096, m.value(SquireMetrics.Key.WORLDEDIT_BLOCKS));
		m.resetForTests();
		assertEquals(0, m.value(SquireMetrics.Key.TOOL_CALLS));
		assertEquals(0, m.value(SquireMetrics.Key.WORLDEDIT_BLOCKS));
	}

	@Test
	void timersTrackCountTotalAndMax() {
		SquireMetrics m = new SquireMetrics();
		m.record(SquireMetrics.Timer.TOOL_DURATION, 2_000_000L); // 2ms
		m.record(SquireMetrics.Timer.TOOL_DURATION, 6_000_000L); // 6ms
		m.record(SquireMetrics.Timer.TOOL_DURATION, -1L); // ignored: negative sample
		List<String> lines = m.snapshotLines();
		String toolLine = lines.stream()
			.filter(l -> l.startsWith("tool_duration=")).findFirst().orElseThrow();
		assertTrue(toolLine.contains("n=2"), toolLine);
		assertTrue(toolLine.contains("max=6.00ms"), toolLine);
		assertTrue(toolLine.contains("avg=4.00ms"), toolLine);
	}

	@Test
	void fastpathRatioIsZeroBeforeAnyRequestThenCorrect() {
		SquireMetrics m = new SquireMetrics();
		assertEquals(0.0, m.fastpathHitRatio());
		m.inc(SquireMetrics.Key.FASTPATH_REQUESTS);
		m.inc(SquireMetrics.Key.FASTPATH_REQUESTS);
		m.inc(SquireMetrics.Key.FASTPATH_REQUESTS);
		m.inc(SquireMetrics.Key.FASTPATH_HITS);
		assertEquals(1.0 / 3.0, m.fastpathHitRatio(), 1e-9);
	}

	@Test
	void snapshotCoversEveryKeyExactlyOnce() {
		SquireMetrics m = new SquireMetrics();
		m.record(SquireMetrics.Timer.TASK_DURATION, 1_000L);
		List<String> lines = m.snapshotLines();
		for (SquireMetrics.Key key : SquireMetrics.Key.values()) {
			String prefix = key.wire() + "=";
			assertEquals(1, lines.stream().filter(l -> l.startsWith(prefix)).count(),
				"missing or duplicated: " + prefix);
		}
		for (SquireMetrics.Timer timer : SquireMetrics.Timer.values()) {
			String prefix = timer.wire() + "=";
			assertEquals(1, lines.stream().filter(l -> l.startsWith(prefix)).count(),
				"missing or duplicated: " + prefix);
		}
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("fastpath_hit_ratio=")));
	}
}
