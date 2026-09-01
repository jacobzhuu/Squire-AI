package dev.squire.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.squire.common.errors.ErrorCode;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.Identifier;

/**
 * M3 security foundation: permission nodes (§61), scoped capabilities (§30),
 * quotas (§66), killswitch (§63) — pure-Java paths (dimensions are Identifiers,
 * so no registry bootstrap is needed here).
 */
class SecurityFoundationTest {

	private static final Identifier OVERWORLD =
		new Identifier("minecraft", "overworld");
	private static final Identifier NETHER =
		new Identifier("minecraft", "the_nether");

	// ------------------------------------------------------------------ §61 nodes

	@Test
	void defaultPlayerNodesExcludeAdminPowers() {
		PermissionManager pm = new PermissionManager();
		UUID player = UUID.randomUUID();
		assertTrue(pm.has(player, false, PermissionNodes.TASK_FOLLOW));
		assertTrue(pm.has(player, false, PermissionNodes.WORLD_BREAK));
		assertFalse(pm.has(player, false, PermissionNodes.WORLD_EDIT),
			"§94: worldedit OFF by default");
		assertFalse(pm.has(player, false, PermissionNodes.ADMIN));
		assertFalse(pm.has(player, false, PermissionNodes.MCP_MANAGE));
	}

	@Test
	void adminHoldsEverythingButUnknownNodesNeverPass() {
		PermissionManager pm = new PermissionManager();
		UUID admin = UUID.randomUUID();
		assertTrue(pm.has(admin, true, PermissionNodes.WORLD_EDIT));
		assertTrue(pm.has(admin, true, "squire.admin"));
		assertThrows(IllegalArgumentException.class,
			() -> pm.grant(admin, "not.a.node"));
		assertFalse(pm.has(admin, false, "squire.unknown"));
	}

	@Test
	void grantAndRevokeOverrideDefaults() {
		PermissionManager pm = new PermissionManager();
		UUID player = UUID.randomUUID();
		pm.grant(player, PermissionNodes.WORLD_EDIT);
		assertTrue(pm.has(player, false, PermissionNodes.WORLD_EDIT));
		pm.revoke(player, PermissionNodes.WORLD_BREAK);
		assertFalse(pm.has(player, false, PermissionNodes.WORLD_BREAK),
			"revocation beats defaults");
		pm.reset();
		assertTrue(pm.has(player, false, PermissionNodes.WORLD_BREAK));
	}

	// ------------------------------------------------------------------ §30 capability

	private Capability issueFor(BoundedRegion region, int maxImpact, long now,
			Set<String> tools) {
		return new Capability(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			null, "test.op", tools, OVERWORLD, region, maxImpact, now, now + 1000L,
			UUID.randomUUID());
	}

	@Test
	void capabilityValidatesThenConsumesExactlyOnce() {
		CapabilityStore store = new CapabilityStore();
		BoundedRegion area = BoundedRegion.ofCorners(0, 0, 0, 9, 9, 9);
		var agentId = UUID.randomUUID();
		Capability cap = store.issue(UUID.randomUUID(), agentId, "world.fill",
			Set.of("world.fill"), OVERWORLD, area, 500, 0);

		assertTrue(store.validateForUse(cap.capabilityId(), agentId, "world.fill",
			OVERWORLD, BoundedRegion.ofCorners(1, 1, 1, 5, 5, 5), 100, 10).isEmpty());

		assertTrue(store.consume(cap.capabilityId(), UUID.randomUUID()));
		Optional<String> replay = store.validateForUse(cap.capabilityId(), agentId,
			"w.fill", OVERWORLD, area, 1, 10);
		assertTrue(replay.isPresent()
				&& replay.get().startsWith(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()),
			"replay must be refused (不可重放)");
	}

	@Test
	void capabilityRejectsWrongAgentToolDimensionRegionImpact() {
		CapabilityStore store = new CapabilityStore();
		var ownerId = UUID.randomUUID();
		var agentId = UUID.randomUUID();
		BoundedRegion scope = BoundedRegion.ofCorners(-10, -10, -10, 10, 10, 10);
		Capability cap = store.issue(ownerId, agentId, "op",
			Set.of("tool.ok"), OVERWORLD, scope, 64, 0);
		UUID id = cap.capabilityId();

		assertTrue(store.validateForUse(id, UUID.randomUUID(), "tool.ok", OVERWORLD,
			scope, 1, 0).orElse("").contains("different agent"));
		assertTrue(store.validateForUse(id, agentId, "tool.other", OVERWORLD, scope, 1, 0)
			.orElse("").contains("not allowed"));
		assertTrue(store.validateForUse(id, agentId, "tool.ok", NETHER, scope, 1, 0)
			.orElse("").contains("dimension"));
		assertTrue(store.validateForUse(id, agentId, "tool.ok", OVERWORLD,
			BoundedRegion.ofCorners(20, 20, 20, 30, 30, 30), 1, 0)
			.orElse("").contains("outside capability bounds"),
			"越界必须拒绝");
		assertTrue(store.validateForUse(id, agentId, "tool.ok", OVERWORLD, scope, 65, 0)
			.orElse("").contains("exceeds maxImpact"));

		// expiry (default TTL is CapabilityStore.DEFAULT_TTL_TICKS)
		long afterExpiry = cap.expiresAtTick() + 1L;
		assertTrue(store.validateForUse(id, agentId, "tool.ok", OVERWORLD, scope, 1,
			afterExpiry).orElse("").contains("expired"));
	}

	@Test
	void revocationAndBindingAreEnforced() {
		CapabilityStore store = new CapabilityStore();
		var agentId = UUID.randomUUID();
		Capability cap = store.issue(UUID.randomUUID(), agentId, "op",
			Set.of("t"), OVERWORLD, BoundedRegion.ofCorners(0, 0, 0, 1, 1, 1), 8, 0);
		store.revoke(cap.capabilityId());
		assertTrue(store.validateForUse(cap.capabilityId(), agentId, "t", OVERWORLD,
			null, 1, 0).orElse("").contains("no live capability"));

		Capability other = store.issue(UUID.randomUUID(), agentId, "op2", Set.of("t2"),
			OVERWORLD, BoundedRegion.ofCorners(0, 0, 0, 1, 1, 1), 8, 0);
		UUID firstTask = UUID.randomUUID();
		UUID secondTask = UUID.randomUUID();
		Capability bound = other.boundTo(firstTask);
		assertEquals(firstTask, bound.taskId());
		assertThrows(IllegalStateException.class, () -> bound.boundTo(secondTask),
			"capability must not cross tasks (不可跨 Task)");
		// and the STORE enforces the same rule across consume attempts
		assertTrue(store.consume(other.capabilityId(), firstTask));
		assertFalse(store.consume(other.capabilityId(), secondTask),
			"second task cannot consume a capability already bound elsewhere");
	}

	// ------------------------------------------------------------------ §66 quota

	@Test
	void toolCallQuotaIsRateLimitedPerMinute() {
		QuotaLedger quotas = new QuotaLedger(3, 1_000_000);
		assertTrue(quotas.recordToolCall(0).isEmpty());
		assertTrue(quotas.recordToolCall(1).isEmpty());
		assertTrue(quotas.recordToolCall(2).isEmpty());
		assertEquals(ErrorCode.RATE_LIMITED, quotas.recordToolCall(3).orElseThrow());
		// a minute of ticks later the window drains
		assertTrue(quotas.recordToolCall(1201).isEmpty());
	}

	@Test
	void blocksChangedBudgetExceedsAfterHourCeiling() {
		QuotaLedger quotas = new QuotaLedger(1000, 1000);
		assertTrue(quotas.recordBlocksChanged(0, 600).isEmpty());
		assertEquals(ErrorCode.BUDGET_EXCEEDED,
			quotas.recordBlocksChanged(1, 401).orElseThrow());
		assertTrue(quotas.recordBlocksChanged(1, 400).isEmpty()); // exactly at ceiling
		quotas.recordBlocksChanged(72_001, 0); // window rollover resets nothing used
		assertTrue(quotas.recordBlocksChanged(72_002, 600).isEmpty());
	}

	// ------------------------------------------------------------------ §63 killswitch

	@Test
	void killswitchActivatesOnceAndReportsState() {
		Killswitch ks = new Killswitch();
		assertFalse(ks.isActive());
		assertTrue(ks.activate(42));
		assertTrue(ks.isActive());
		assertEquals(42, ks.activatedAtTick());
		assertFalse(ks.activate(43), "second activation is a no-op");
		assertTrue(ks.deactivate());
		assertFalse(ks.isActive());
	}
}
