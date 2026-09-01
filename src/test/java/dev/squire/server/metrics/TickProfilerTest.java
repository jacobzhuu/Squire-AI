package dev.squire.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.squire.server.metrics.TickProfiler.Section;

/**
 * 滚动窗口的算术。这份统计的用法是「改动前后各拍一张逐行对比」，所以均值算错、
 * 旧样本没被挤出去、峰值被窗口滑掉，都会直接导致看错结论——必须有测试守着。
 */
class TickProfilerTest {

	@Test
	void freshProfilerReportsZeroWithoutBlowingUp() {
		TickProfiler p = new TickProfiler();
		List<String> lines = p.snapshotLines();
		// 表头 + 每个分段一行 + TOTAL
		assertEquals(Section.values().length + 2, lines.size());
		assertEquals(0L, p.ticksObserved());
		assertTrue(lines.get(0).contains("window=0/" + TickProfiler.WINDOW));
	}

	@Test
	void averageIsOverObservedSamplesNotTheWholeWindow() {
		TickProfiler p = new TickProfiler();
		p.record(Section.GUARDS, 1_000L);
		p.record(Section.GUARDS, 3_000L);
		// 只记了两拍，均值就该是这两拍的，不能被 198 个没发生过的 0 拉低
		assertTrue(lineFor(p, "guards").contains("avg=     2.0us"),
			lineFor(p, "guards"));
	}

	@Test
	void windowSlidesAndEvictsOldSamples() {
		TickProfiler p = new TickProfiler();
		// 先灌满一窗的 10us，再灌满一窗的 20us：旧的应该被完全挤出去
		for (int i = 0; i < TickProfiler.WINDOW; i++) {
			p.record(Section.SCHEDULER, 10_000L);
		}
		assertTrue(lineFor(p, "scheduler").contains("avg=    10.0us"));
		for (int i = 0; i < TickProfiler.WINDOW; i++) {
			p.record(Section.SCHEDULER, 20_000L);
		}
		assertTrue(lineFor(p, "scheduler").contains("avg=    20.0us"),
			lineFor(p, "scheduler"));
	}

	@Test
	void peakSurvivesTheWindowSlidingPastIt() {
		TickProfiler p = new TickProfiler();
		p.record(Section.BLUEPRINTS, 900_000L); // 一次 900us 的尖刺
		for (int i = 0; i < TickProfiler.WINDOW; i++) {
			p.record(Section.BLUEPRINTS, 1_000L);
		}
		String line = lineFor(p, "blueprints");
		// 窗口里已经看不到那一拍了……
		assertTrue(line.contains("max=      1.0us"), line);
		// ……但历史峰值必须还记得，否则偶发尖刺永远抓不到
		assertTrue(line.contains("peak=    900.0us"), line);
	}

	@Test
	void negativeSampleIsDroppedRatherThanPoisoningTheStats() {
		TickProfiler p = new TickProfiler();
		p.record(Section.GOALS, 2_000L);
		p.record(Section.GOALS, -5_000L); // 时钟回拨
		assertTrue(lineFor(p, "goals").contains("avg=     2.0us"),
			lineFor(p, "goals"));
	}

	@Test
	void markChainsTimestampsAndCountsOneSamplePerSection() {
		TickProfiler p = new TickProfiler();
		long mark = p.now();
		mark = p.mark(Section.AUTOMATION, mark);
		p.mark(Section.CBP, mark);
		p.endTick();
		assertEquals(1L, p.ticksObserved());
		// 真实耗时无法断言具体数值，但两段都该有样本，窗口计数才会是 1
		assertTrue(p.snapshotLines().get(0).contains("window=1/"));
	}

	@Test
	void resetZeroesWindowsPeaksAndTickCount() {
		TickProfiler p = new TickProfiler();
		p.record(Section.CONVERSATIONS, 500_000L);
		p.endTick();
		p.reset();
		assertEquals(0L, p.ticksObserved());
		String line = lineFor(p, "conversations");
		assertTrue(line.contains("peak=      0.0us"), line);
		assertFalse(line.contains("500.0us"), line);
	}

	private static String lineFor(TickProfiler profiler, String sectionWire) {
		return profiler.snapshotLines().stream()
			.filter(l -> l.startsWith(sectionWire))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no line for " + sectionWire));
	}
}
