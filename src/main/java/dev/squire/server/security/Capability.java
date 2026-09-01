package dev.squire.server.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.Identifier;

/**
 * Scoped capability (spec section 30): answers "what may THIS operation change,
 * where, until when". High-risk world edits execute only under a live capability.
 *
 * <p>Capabilities are short-lived, bound to at most one task, single-use,
 * region-bounded and revocable — all enforced by {@link CapabilityStore}.</p>
 */
public final class Capability {

	private final UUID capabilityId;
	private final UUID ownerId;
	private final UUID agentId;
	private final UUID taskId; // null = not yet bound; binds on first use
	private final String type; // e.g. "world.fill"
	private final Set<String> allowedTools;
	private final Identifier dimension; // e.g. minecraft:overworld
	private final BoundedRegion region;
	private final int maxImpact; // max blocks this capability may change
	private final long issuedAtTick;
	private final long expiresAtTick;
	private final UUID nonce;

	public Capability(UUID capabilityId, UUID ownerId, UUID agentId, UUID taskId,
			String type, Set<String> allowedTools, Identifier dimension,
			BoundedRegion region, int maxImpact, long issuedAtTick, long expiresAtTick,
			UUID nonce) {
		this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.agentId = Objects.requireNonNull(agentId, "agentId");
		this.taskId = taskId;
		this.type = Objects.requireNonNull(type, "type");
		this.allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools));
		if (this.allowedTools.isEmpty()) {
			throw new IllegalArgumentException("capability must allow at least one tool");
		}
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		this.region = Objects.requireNonNull(region, "region");
		if (maxImpact < 1) {
			throw new IllegalArgumentException("maxImpact must be positive");
		}
		this.maxImpact = maxImpact;
		if (expiresAtTick <= issuedAtTick) {
			throw new IllegalArgumentException("capability must expire after issue");
		}
		this.issuedAtTick = issuedAtTick;
		this.expiresAtTick = expiresAtTick;
		this.nonce = Objects.requireNonNull(nonce, "nonce");
	}

	public UUID capabilityId() {
		return capabilityId;
	}

	public UUID ownerId() {
		return ownerId;
	}

	public UUID agentId() {
		return agentId;
	}

	public UUID taskId() {
		return taskId;
	}

	public String type() {
		return type;
	}

	public Set<String> allowedTools() {
		return allowedTools;
	}

	public Identifier dimension() {
		return dimension;
	}

	public BoundedRegion region() {
		return region;
	}

	public int maxImpact() {
		return maxImpact;
	}

	public long issuedAtTick() {
		return issuedAtTick;
	}

	public long expiresAtTick() {
		return expiresAtTick;
	}

	public UUID nonce() {
		return nonce;
	}

	public boolean isExpired(long nowTick) {
		return nowTick > expiresAtTick;
	}

	/** Bind to a task exactly once (spec: 不可跨 Task 使用). */
	public Capability boundTo(UUID taskId) {
		if (this.taskId != null && !this.taskId.equals(taskId)) {
			throw new IllegalStateException("capability already bound to another task");
		}
		if (this.taskId != null) {
			return this;
		}
		return new Capability(capabilityId, ownerId, agentId, taskId, type, allowedTools,
			dimension, region, maxImpact, issuedAtTick, expiresAtTick, nonce);
	}
}
