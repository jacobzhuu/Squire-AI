package dev.squire.server.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Append-only audit trail of gateway dispatches (spec sections 27/73).
 * M3 expands this with persistence and kill-switch wiring; the ring keeps memory bounded.
 */
public final class AuditLog {
	/** One audited dispatch attempt, executed or rejected. */
	public record Entry(long tick, String callerKind, String toolName,
			boolean executed, String outcomeCode) {
	}

	private static final int CAPACITY = 2048;
	private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);
	private final Deque<Entry> pendingFlush = new ArrayDeque<>();

	public void record(long tick, String callerKind, String toolName,
			boolean executed, String outcomeCode) {
		Entry entry = new Entry(tick, callerKind, toolName, executed, outcomeCode);
		if (entries.size() == CAPACITY) {
			entries.pollFirst();
		}
		entries.addLast(entry);
		pendingFlush.addLast(entry);
	}

	public List<Entry> snapshot() {
		return List.copyOf(new ArrayList<>(entries));
	}

	/**
	 * Append all not-yet-persisted entries to {@code file} as JSON Lines (spec §93:
	 * audit ON, retention 30 days). Values are enums/numbers/booleans — plain
	 * concatenation keeps business code Gson-free (spec §96.1).
	 *
	 * @return entries written this call
	 */
	public synchronized int appendJsonl(java.nio.file.Path file) throws java.io.IOException {
		if (pendingFlush.isEmpty()) {
			return 0;
		}
		java.nio.file.Files.createDirectories(file.getParent());
		StringBuilder sb = new StringBuilder();
		for (Entry e : pendingFlush) {
			sb.append("{\"tick\":").append(e.tick())
				.append(",\"caller\":\"").append(e.callerKind()).append('"')
				.append(",\"tool\":\"").append(e.toolName()).append('"')
				.append(",\"executed\":").append(e.executed())
				.append(",\"outcome\":\"").append(e.outcomeCode()).append("\"}\n");
		}
		java.nio.file.Files.writeString(file, sb.toString(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		int written = pendingFlush.size();
		pendingFlush.clear();
		return written;
	}

	public int size() {
		return entries.size();
	}
}
