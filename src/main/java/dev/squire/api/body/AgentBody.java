package dev.squire.api.body;

import java.util.UUID;

/**
 * The stable body abstraction every agent backend implements (ADR-002, spec section 8).
 *
 * <p>Upper layers must program against this interface and consult
 * {@link #capabilities()} — never {@code instanceof} a concrete entity.</p>
 *
 * <p>M1 scope: movement, look, emote, inventory view. Combat/item-use interactions
 * are declared here but only become functional in M2 via the Tool Gateway.</p>
 */
public interface AgentBody {
	UUID agentId();

	UUID ownerId();

	BodyCapabilities capabilities();

	AgentPhysicalState snapshotState();

	MoveHandle moveTo(TargetPosition target, MoveOptions options);

	void stopMoving();

	default InteractionResult attack(java.util.UUID targetEntityId) {
		return InteractionResult.fail("UNSUPPORTED", "body cannot attack");
	}

	default InteractionResult useItem() {
		return InteractionResult.fail("UNSUPPORTED", "body cannot use items");
	}

	default InteractionResult interactBlock(String dimension, double x, double y, double z) {
		return InteractionResult.fail("UNSUPPORTED", "body cannot interact with blocks");
	}

	InventoryView inventory();

	void lookAt(TargetPosition target);

	void emote(EmoteType type);

	boolean alive();
}
