package dev.squire.api.sensor;

import java.util.Objects;

/**
 * Sensor contract with mandatory budgets (spec sections 16.4/59): unbounded scans
 * are forbidden — every sensor declares its radius, item cap, priority and
 * serialization budget up front.
 */
public record SensorDefinition(String id, int radius, int maxItems, int priority,
		int serializationBudget) {

	public SensorDefinition {
		Objects.requireNonNull(id, "id");
		if (id.isBlank()) {
			throw new IllegalArgumentException("sensor id required");
		}
		if (radius < 1 || radius > 64) {
			throw new IllegalArgumentException("radius must be within 1..64: " + radius);
		}
		if (maxItems < 1 || maxItems > 4096) {
			throw new IllegalArgumentException("maxItems must be within 1..4096");
		}
		if (serializationBudget < 16 || serializationBudget > 65_536) {
			throw new IllegalArgumentException("serializationBudget out of range");
		}
	}
}
