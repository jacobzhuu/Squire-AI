package dev.squire.server.metrics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每 tick 分段计时，用来回答一个以前只能靠读代码猜的问题：<b>到底是哪一段在卡</b>。
 *
 * <p>只在服务器线程上使用（{@code tickScheduler()} 是唯一写入点），所以内部没有任何
 * 同步——刻意和 {@link SquireMetrics} 区分开：那个要跨线程累加，这个不用，于是可以用
 * 裸数组做环形缓冲，单次记录的开销就是一次 {@code nanoTime} 加一次数组写。</p>
 *
 * <p>窗口是滚动的（最近 {@link #WINDOW} 拍）。用滚动均值而不是累计均值，是因为要回答的
 * 是「<i>现在</i>卡不卡」——一段代码开服时慢过一次，不该在半小时后还把均值拖高。另外
 * 保留一个从不重置的历史峰值，用来抓那种偶发的、窗口早已滑过去的尖刺。</p>
 */
public final class TickProfiler {

	/** 滚动窗口长度：200 拍 = 10 秒，够平滑掉抖动，又不至于迟钝到看不出变化。 */
	public static final int WINDOW = 200;

	/**
	 * {@code tickScheduler()} 里的分段。顺序即调用顺序，打印时照此排列，读起来就是
	 * 一拍的执行流程。
	 */
	public enum Section {
		AUTOMATION("automation"),
		CBP("cbp"),
		GUARDS("guards"),
		AUTONOMY("autonomy"),
		FOLLOW_DIMENSION("follow_dim"),
		BLUEPRINTS("blueprints"),
		PROJECT("project"),
		MCP_SWEEP("mcp_sweep"),
		EXPIRY("expiry"),
		SCHEDULER("scheduler"),
		GOALS("goals"),
		CONVERSATIONS("conversations"),
		ACTIVITY("activity");

		private final String wire;

		Section(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** 一个分段的滚动样本。 */
	private static final class Window {
		final long[] samples = new long[WINDOW];
		int cursor;
		int filled;
		long sum;
		/** 历史峰值，永不随窗口滑出而丢失。 */
		long peakNanos;

		void add(long nanos) {
			if (filled == WINDOW) {
				sum -= samples[cursor];
			} else {
				filled++;
			}
			samples[cursor] = nanos;
			sum += nanos;
			cursor = (cursor + 1) % WINDOW;
			if (nanos > peakNanos) {
				peakNanos = nanos;
			}
		}

		double avgNanos() {
			return filled == 0 ? 0.0 : (double) sum / filled;
		}

		long windowMaxNanos() {
			long max = 0;
			for (int i = 0; i < filled; i++) {
				if (samples[i] > max) {
					max = samples[i];
				}
			}
			return max;
		}

		void reset() {
			java.util.Arrays.fill(samples, 0L);
			cursor = 0;
			filled = 0;
			sum = 0L;
			peakNanos = 0L;
		}
	}

	private final Map<Section, Window> windows = new EnumMap<>(Section.class);
	private long ticksObserved;

	public TickProfiler() {
		for (Section section : Section.values()) {
			windows.put(section, new Window());
		}
	}

	/** 一拍开始时取一次时间戳，之后交给 {@link #mark} 接力。 */
	public long now() {
		return System.nanoTime();
	}

	/**
	 * 记下 {@code since} 到现在这段属于 {@code section} 的耗时，并返回<b>新的</b>时间戳
	 * 供下一段接力。
	 *
	 * <p>这样串起来每段只花一次 {@code nanoTime}，而不是首尾各一次——十三个分段一拍
	 * 十四次调用，量级在半微秒上下，相对 50ms 的一拍可以忽略，所以这个计时器常开、
	 * 不做开关。一个需要先打开才能用的性能开关，等你想起来打开时现场已经过去了。</p>
	 */
	public long mark(Section section, long since) {
		long now = System.nanoTime();
		record(section, now - since);
		return now;
	}

	/** 直接记一个样本。测试用它注入确定的数值，生产路径走 {@link #mark}。 */
	public void record(Section section, long nanos) {
		if (nanos < 0) {
			return; // 时钟回拨；宁可丢一个样本，也不要把均值和峰值污染掉
		}
		windows.get(section).add(nanos);
	}

	/** 一拍结束：仅用于统计观测到的拍数。 */
	public void endTick() {
		ticksObserved++;
	}

	public long ticksObserved() {
		return ticksObserved;
	}

	/** 供命令打印的快照；单位统一用微秒，这些分段正常都在个位数微秒。 */
	public List<String> snapshotLines() {
		List<String> lines = new ArrayList<>();
		int filled = windows.get(Section.AUTOMATION).filled;
		lines.add(String.format(Locale.ROOT,
			"window=%d/%d ticks observed=%d", filled, WINDOW, ticksObserved));
		double totalAvg = 0.0;
		for (Section section : Section.values()) {
			Window w = windows.get(section);
			totalAvg += w.avgNanos();
			lines.add(String.format(Locale.ROOT,
				"%-14s avg=%8.1fus  max=%9.1fus  peak=%9.1fus",
				section.wire(),
				w.avgNanos() / 1000.0,
				w.windowMaxNanos() / 1000.0,
				w.peakNanos / 1000.0));
		}
		lines.add(String.format(Locale.ROOT,
			"%-14s avg=%8.1fus  (一拍 50000us，占 %.4f%%)",
			"TOTAL", totalAvg / 1000.0, totalAvg / 50_000_000.0 * 100.0));
		return lines;
	}

	/** 清空窗口与历史峰值——量一段新场景之前先归零。 */
	public void reset() {
		for (Window window : windows.values()) {
			window.reset();
		}
		ticksObserved = 0L;
	}
}
