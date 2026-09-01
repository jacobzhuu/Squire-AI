package dev.squire.server.security;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.common.errors.ErrorCode;
import net.minecraft.util.Identifier;

/**
 * Issues, validates, consumes and revokes {@link Capability} objects (spec
 * section 30). Validation never throws — every failure maps to a unified error:
 * missing/expired/revoked → {@code CAPABILITY_REQUIRED}, boundary violation →
 * {@code CAPABILITY_SCOPE_VIOLATION}.
 */
public final class CapabilityStore {

	private static final Logger LOG = LoggerFactory.getLogger(CapabilityStore.class);

	/** Default lifetime: 2 minutes of server ticks. */
	public static final long DEFAULT_TTL_TICKS = 2400L;

	private final Map<UUID, Capability> live = new HashMap<>();
	private final Set<UUID> revokedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Set<UUID> consumedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public Capability issue(UUID ownerId, UUID agentId, String type,
			Set<String> allowedTools, Identifier dimension,
			dev.squire.server.world.BoundedRegion region, int maxImpact,
			long nowTick) {
		Capability capability = new Capability(UUID.randomUUID(), ownerId, agentId,
			null, type, allowedTools, dimension, region, maxImpact,
			nowTick, nowTick + DEFAULT_TTL_TICKS, UUID.randomUUID());
		live.put(capability.capabilityId(), capability);
		LOG.info("[capability] issued {} type={} owner={} impact<= {} region={}",
			capability.capabilityId(), type, ownerId, maxImpact, region);
		return capability;
	}

	public Optional<Capability> get(UUID capabilityId) {
		return Optional.ofNullable(live.get(capabilityId));
	}

	/**
	 * Full validation for one intended use. Order: existence → revocation → expiry
	 * → agent binding → tool allowance → dimension → region coverage → impact.
	 *
	 * @param neededRegion the exact region the operation wants to touch
	 * @param impact       blocks the operation intends to change
	 */
	public Optional<String> validateForUse(UUID capabilityId, UUID agentId,
			String toolName, Identifier dimension,
			dev.squire.server.world.BoundedRegion neededRegion, int impact, long nowTick) {
		Capability capability = live.get(capabilityId);
		if (capability == null || revokedIds.contains(capabilityId)) {
			return Optional.of(ErrorCode.CAPABILITY_REQUIRED.wire()
				+ ": no live capability " + capabilityId);
		}
		if (consumedIds.contains(capabilityId)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": capability already used (no replay)");
		}
		if (capability.isExpired(nowTick)) {
			return Optional.of(ErrorCode.CAPABILITY_REQUIRED.wire() + ": capability expired");
		}
		if (!capability.agentId().equals(agentId)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": capability bound to a different agent");
		}
		if (!capability.allowedTools().contains(toolName)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": tool '" + toolName + "' not allowed by this capability");
		}
		if (!capability.dimension().equals(dimension)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": wrong dimension");
		}
		if (neededRegion != null && !capability.region().contains(neededRegion)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": region outside capability bounds");
		}
		if (impact > capability.maxImpact()) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": impact " + impact + " exceeds maxImpact " + capability.maxImpact());
		}
		return Optional.empty();
	}

	/**
	 * Per-batch re-validation for a capability already consumed by ITS OWN task
	 * (方案 F5). A long frame-budgeted edit must keep proving its licence on every
	 * batch — an admin revoking mid-operation, an expiry, or a region that somehow
	 * grew must stop the remaining writes, not just the first one.
	 *
	 * <p>Unlike {@link #validateForUse} this accepts a consumed capability, but only
	 * when the caller is the exact task it was bound to.</p>
	 */
	public Optional<String> validateBoundUse(UUID capabilityId, UUID taskId, UUID agentId,
			String toolName, Identifier dimension,
			dev.squire.server.world.BoundedRegion neededRegion, int impact, long nowTick) {
		Capability capability = live.get(capabilityId);
		if (capability == null || revokedIds.contains(capabilityId)) {
			return Optional.of(ErrorCode.CAPABILITY_REQUIRED.wire()
				+ ": capability " + capabilityId + " is no longer live");
		}
		if (capability.taskId() != null && !capability.taskId().equals(taskId)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": capability bound to a different task");
		}
		if (capability.isExpired(nowTick)) {
			return Optional.of(ErrorCode.CAPABILITY_REQUIRED.wire() + ": capability expired");
		}
		if (!capability.agentId().equals(agentId)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": capability bound to a different agent");
		}
		if (!capability.allowedTools().contains(toolName)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": tool '" + toolName + "' not allowed by this capability");
		}
		if (!capability.dimension().equals(dimension)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": wrong dimension");
		}
		if (neededRegion != null && !capability.region().contains(neededRegion)) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": region outside capability bounds");
		}
		if (impact > capability.maxImpact()) {
			return Optional.of(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
				+ ": impact " + impact + " exceeds maxImpact " + capability.maxImpact());
		}
		return Optional.empty();
	}

	/** Bind to a task and mark used — single use, no replay (spec 不可重放). */
	public boolean consume(UUID capabilityId, UUID taskId) {
		Capability capability = live.get(capabilityId);
		if (capability == null || consumedIds.contains(capabilityId)
				|| revokedIds.contains(capabilityId)) {
			return false;
		}
		try {
			live.put(capabilityId, capability.boundTo(taskId));
		} catch (IllegalStateException e) {
			return false; // bound to a DIFFERENT task earlier
		}
		consumedIds.add(capabilityId);
		LOG.info("[capability] consumed {} by task {}", capabilityId, taskId);
		return true;
	}

	public void revoke(UUID capabilityId) {
		revokedIds.add(capabilityId);
	}

	public void expireAllBefore(long nowTick) {
		live.values().removeIf(c -> c.isExpired(nowTick));
	}
}
