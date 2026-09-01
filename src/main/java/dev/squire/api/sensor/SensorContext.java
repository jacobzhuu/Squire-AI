package dev.squire.api.sensor;

import java.util.UUID;

/** Minimal observation context; the core resolves world state on its side. */
public record SensorContext(UUID agentId, UUID ownerId, long tick) {

	public SensorContext {
		java.util.Objects.requireNonNull(agentId, "agentId");
	}
}
