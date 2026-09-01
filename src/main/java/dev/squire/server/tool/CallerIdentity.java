package dev.squire.server.tool;

import java.util.UUID;

/** Who is invoking a tool through the Gateway — checked BEFORE execution (spec §27). */
public record CallerIdentity(UUID senderId, UUID agentId, CallerKind kind) {

	public enum CallerKind { MODEL, FAST_PATH, PLANNER, ADMIN, RUNTIME }

	public static CallerIdentity model(UUID senderId, UUID agentId) {
		return new CallerIdentity(senderId, agentId, CallerKind.MODEL);
	}

	/**
	 * A deterministic action selected from an explicit player utterance. Unlike a
	 * MODEL call, this utterance itself is the owner's confirmation; every other
	 * gateway check (schema, capability, profession, node, ownership and policy)
	 * still applies.
	 */
	public static CallerIdentity fastPath(UUID senderId, UUID agentId) {
		return new CallerIdentity(senderId, agentId, CallerKind.FAST_PATH);
	}

	public static CallerIdentity planner(UUID agentId) {
		return new CallerIdentity(agentId, agentId, CallerKind.PLANNER);
	}
}
