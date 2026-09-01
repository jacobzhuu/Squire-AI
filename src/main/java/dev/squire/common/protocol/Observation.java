package dev.squire.common.protocol;

import java.util.List;

/**
 * Sanitized world observation handed to the LLM (spec section 16).
 *
 * <p>Built by the perception layer on the server thread; carries ONLY serializable
 * values — never live World/Entity/NBT references (spec section 16 preamble).</p>
 */
public record Observation(
	String level,
	int tick,
	String agentSummary,
	String ownerSummary,
	String currentTask,
	List<String> threats,
	List<String> inventorySummary,
	List<String> nearbyEntities,
	long serializationBudgetTokens
) {
	public Observation {
		threats = List.copyOf(threats == null ? List.of() : threats);
		inventorySummary = List.copyOf(inventorySummary == null ? List.of() : inventorySummary);
		nearbyEntities = List.copyOf(nearbyEntities == null ? List.of() : nearbyEntities);
	}
}
