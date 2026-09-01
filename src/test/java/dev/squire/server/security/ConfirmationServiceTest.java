package dev.squire.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Confirmation flow (§45 + §82 "Forged client confirmation"): only the owning
 * player, with the right id, before expiry, exactly once, gets a call through.
 */
class ConfirmationServiceTest {

	private static final String TOOL = "minecraft.command.fill";
	private static final String FINGERPRINT = "blockId=minecraft:stone;x1=0;x2=1";

	@Test
	void ownerConfirmEnablesExactlyOneDispatch() {
		ConfirmationService service = new ConfirmationService();
		UUID owner = UUID.randomUUID();
		var request = service.issue(owner, UUID.randomUUID(), TOOL, FINGERPRINT, 0);

		assertFalse(service.matchesAndConsume(owner, TOOL, FINGERPRINT, 10),
			"not confirmed yet — must stay blocked");

		String reply = service.confirm(owner, request.confirmId(), 20);
		assertTrue(reply.contains("Confirmed"));

		assertTrue(service.matchesAndConsume(owner, TOOL, FINGERPRINT, 30),
			"confirmed dispatch goes through");
		assertFalse(service.matchesAndConsume(owner, TOOL, FINGERPRINT, 31),
			"single use — no replay");
	}

	@Test
	void forgedConfirmationsFailClosed() {
		ConfirmationService service = new ConfirmationService();
		UUID owner = UUID.randomUUID();
		UUID attacker = UUID.randomUUID();
		var request = service.issue(owner, UUID.randomUUID(), TOOL, FINGERPRINT, 0);

		String forged = service.confirm(attacker, request.confirmId(), 5);
		assertTrue(forged.contains("another player"), "wrong player must be refused");
		assertFalse(service.matchesAndConsume(attacker, TOOL, FINGERPRINT, 6),
			"attacker must never pass the gateway check");
		assertFalse(service.matchesAndConsume(owner, TOOL, FINGERPRINT, 6),
			"an unconfirmed id still blocks the real owner too");

		assertEquals("[Squire] Unknown or already-used confirmation id.",
			service.confirm(owner, UUID.randomUUID(), 7));
	}

	@Test
	void expiredRequestsCannotBeConfirmedOrUsed() {
		ConfirmationService service = new ConfirmationService();
		UUID owner = UUID.randomUUID();
		var request = service.issue(owner, UUID.randomUUID(), TOOL, FINGERPRINT, 0);
		long expiresAt = request.expiresAtTick();

		assertTrue(service.confirm(owner, request.confirmId(), expiresAt).contains("expired"));
		service.expireAllBefore(expiresAt);
		assertTrue(service.pendingFor(owner).isEmpty());
	}

	@Test
	void differentToolOrArgumentsDoNotMatchAConfirmation() {
		ConfirmationService service = new ConfirmationService();
		UUID owner = UUID.randomUUID();
		var request = service.issue(owner, UUID.randomUUID(), TOOL, FINGERPRINT, 0);
		service.confirm(owner, request.confirmId(), 1);

		assertNotEquals(TOOL, "minecraft.command.setblock");
		assertFalse(service.matchesAndConsume(owner, "minecraft.command.setblock",
			FINGERPRINT, 2), "tool mismatch");
		assertFalse(service.matchesAndConsume(owner, TOOL,
			"blockId=minecraft:lava;x1=0;x2=999", 2), "argument mismatch");
		assertTrue(service.matchesAndConsume(owner, TOOL, FINGERPRINT, 2));
	}
}
