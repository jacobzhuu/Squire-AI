package dev.squire.api.body;

import java.util.Set;

/**
 * Declares what a concrete {@link AgentBody} implementation can do (ADR-002).
 * Upper layers must consult capabilities instead of instanceof checks.
 */
public record BodyCapabilities(
	boolean canMove,
	boolean canAttack,
	boolean canUseItems,
	boolean canInteractBlocks,
	boolean hasInventory,
	Set<String> traits
) {
	public static BodyCapabilities none() {
		return new BodyCapabilities(false, false, false, false, false, Set.of());
	}

	public static BodyCapabilities avatarLike() {
		return new BodyCapabilities(true, true, true, true, true,
			Set.of("navigation", "combat", "equipment"));
	}
}
