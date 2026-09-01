package dev.squire.server.ext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import dev.squire.api.sensor.SensorContext;
import dev.squire.api.sensor.SensorResult;
import dev.squire.api.sensor.SquireSensor;
import dev.squire.server.security.ToolTrust;

/**
 * Holds third-party sensors and runs them each perception cycle (M4). Every sensor
 * gets a hard serialization budget; a throwing sensor degrades to one bounded line
 * instead of breaking the turn.
 */
public final class SensorRegistry {

	/** One registered sensor plus its provider origin for the inspector. */
	public record Entry(String origin, ToolTrust trust, SquireSensor sensor) {
	}

	/** Hard cap so a misbehaving provider fleet can never flood perception. */
	public static final int MAX_SENSORS = 32;

	private final List<Entry> entries = new ArrayList<>();

	public synchronized boolean register(SquireSensor sensor, ToolTrust trust, String origin) {
		if (sensor == null || origin == null || origin.isBlank()) {
			return false;
		}
		if (entries.size() >= MAX_SENSORS) {
			return false;
		}
		for (Entry e : entries) {
			if (e.sensor().definition().id().equals(sensor.definition().id())) {
				return false; // duplicate sensor id
			}
		}
		return entries.add(new Entry(origin, trust, sensor));
	}

	public synchronized List<Entry> all() {
		return List.copyOf(entries);
	}

	/**
	 * Observe every sensor for one agent. Output lines are
	 * {@code [sensor <id>] k=v k2=v2} sorted by key and truncated to the sensor's
	 * declared budget — prompt growth is bounded no matter what providers return.
	 */
	public synchronized List<String> observe(UUID agentId, UUID ownerId, long tick) {
		List<String> lines = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			lines.add(render(entry, agentId, ownerId, tick));
		}
		return lines;
	}

	private String render(Entry entry, UUID agentId, UUID ownerId, long tick) {
		String id = entry.sensor().definition().id();
		SensorResult result;
		try {
			result = entry.sensor().observe(
				new SensorContext(agentId, ownerId, tick));
		} catch (RuntimeException e) {
			return "[sensor " + id + "] degraded: " + e.getClass().getSimpleName();
		}
		if (result == null) {
			return "[sensor " + id + "] degraded: null result";
		}
		Map<String, Object> sorted = new TreeMap<>();
		if (result.data() != null) {
			sorted.putAll(result.data());
		}
		StringBuilder sb = new StringBuilder("[sensor ").append(id)
			.append(result.healthy() ? "]" : "!]");
		for (Map.Entry<String, Object> kv : sorted.entrySet()) {
			sb.append(' ').append(kv.getKey()).append('=')
				.append(kv.getValue() == null ? "null" : kv.getValue());
		}
		if (!result.healthy() && result.statusMessage() != null
				&& !result.statusMessage().isBlank()) {
			sb.append(" status=").append(result.statusMessage());
		}
		String line = sb.toString();
		int budget = Math.max(16, entry.sensor().definition().serializationBudget());
		if (line.length() > budget) {
			line = line.substring(0, budget - 1) + "…";
		}
		return line.toLowerCase(Locale.ROOT);
	}
}
