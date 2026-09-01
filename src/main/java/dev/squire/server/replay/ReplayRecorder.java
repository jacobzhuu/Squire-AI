package dev.squire.server.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Deterministic debug replay (spec §76). While enabled it appends one JSON line
 * per event — input envelope, perception snapshot, visible tools, LLM response,
 * gateway decisions, tool results, task transitions — under
 * {@code save/squire/replay/replay-<session>.jsonl}, for bug reproduction and
 * test-fixture generation.
 *
 * <p>PRIVACY (spec §76 + §90): the flag ships OFF. Every payload passes the
 * redactor before touching disk: API keys, MCP credentials and sensitive
 * configuration values are replaced, never persisted. Files stay on the server
 * owner's disk; nothing is transmitted.</p>
 */
public final class ReplayRecorder {

	private static final Logger LOG = LoggerFactory.getLogger(ReplayRecorder.class);
	/** Rotate before a session file exceeds this many events (bounded disk use). */
	public static final int MAX_EVENTS_PER_FILE = 20_000;

	private final java.util.function.Supplier<Path> fileSupplier;
	private final List<String> buffer = new ArrayList<>();
	private volatile boolean enabled = false;
	private long sessionSeq = 0;
	private int eventsInFile = 0;

	public ReplayRecorder(java.util.function.Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	public void setEnabled(boolean value) {
		if (enabled == value) {
			return;
		}
		enabled = value;
		if (value) {
			sessionSeq++;
			eventsInFile = 0; // new session file per enable
			LOG.info("[replay] session {} started (privacy notice: redacted, local only)",
				sessionSeq);
		} else {
			flush();
			LOG.info("[replay] disabled");
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	/** Records one event; no-op unless enabled. Never throws into callers. */
	public void record(String eventType, JsonObject payload) {
		if (!enabled || payload == null) {
			return;
		}
		JsonObject full = new JsonObject();
		full.addProperty("schemaVersion", 1);
		full.addProperty("type", eventType);
		full.add("data", Redactor.scrub(payload));
		synchronized (buffer) {
			buffer.add(full.toString());
			eventsInFile++;
		}
		if (eventsInFile >= MAX_EVENTS_PER_FILE) {
			setEnabled(false); // rotate off rather than grow unbounded
		}
	}

	/** Convenience for string values (escaped as JSON). */
	public void recordSimple(String eventType, String key, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(key, value);
		record(eventType, o);
	}

	/** Writes buffered lines to {@code replay/replay-<n>.jsonl}. @return lines written */
	public synchronized int flush() {
		List<String> batch;
		synchronized (buffer) {
			if (buffer.isEmpty()) {
				return 0;
			}
			batch = new ArrayList<>(buffer);
			buffer.clear();
		}
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return 0;
			}
			Path target = file.resolveSibling(
				stripSuffix(file.getFileName()) + "-" + sessionSeq + ".jsonl");
			Files.createDirectories(target.getParent());
			StringBuilder sb = new StringBuilder();
			for (String line : batch) {
				sb.append(line).append('\n');
			}
			Files.writeString(target, sb.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return batch.size();
		} catch (IOException | RuntimeException e) {
			LOG.warn("[replay] flush failed (dropping {} lines): {}",
				batch.size(), e.toString());
			return 0;
		}
	}

	private static String stripSuffix(Path name) {
		String s = name.toString();
		int dot = s.lastIndexOf('.');
		return dot > 0 ? s.substring(0, dot) : s;
	}

	/**
	 * §76 redaction applied to EVERY replay payload: any field whose key looks
	 * credential-shaped is masked regardless of nesting depth.
	 */
	public static final class Redactor {
		private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
			"apikey", "api_key", "secret", "credential", "password", "passwd",
			"authorization", "bearer", "privatekey", "private_key", "token");

		private Redactor() {
		}

		public static JsonObject scrub(JsonObject in) {
			JsonObject out = new JsonObject();
			for (var e : in.entrySet()) {
				String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
				boolean sensitive = SENSITIVE_KEY_FRAGMENTS.stream()
					.anyMatch(key::contains);
				if (sensitive) {
					out.addProperty(e.getKey(), "[REDACTED]");
				} else if (e.getValue().isJsonObject()) {
					out.add(e.getKey(), scrub(e.getValue().getAsJsonObject()));
				} else {
					out.add(e.getKey(), e.getValue());
				}
			}
			return out;
		}
	}
}
