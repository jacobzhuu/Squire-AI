package dev.squire.api.body;

import java.util.UUID;

/**
 * Immutable snapshot of an agent's physical condition (thread-safe read view).
 * Never exposes World/Entity references (spec section 7).
 */
public record AgentPhysicalState(
	UUID agentId,
	double x,
	double y,
	double z,
	String dimension,
	float yaw,
	float pitch,
	float health,
	float maxHealth,
	boolean alive,
	int fireTicks,
	boolean inWater
) {
}
