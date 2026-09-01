package dev.squire.api.sensor;

import java.util.Map;

/** Plain-data sensor output. Oversized payloads are truncated by the CORE, not the sensor. */
public record SensorResult(Map<String, Object> data, boolean healthy,
		String statusMessage) {

	public static SensorResult of(Map<String, Object> data) {
		return new SensorResult(data == null ? Map.of() : Map.copyOf(data), true, null);
	}

	public static SensorResult degraded(String message) {
		return new SensorResult(Map.of(), false,
			message == null ? "degraded" : message);
	}
}
