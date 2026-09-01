package dev.squire.server.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-owned high-risk confirmation flow (spec sections 27/45).
 *
 * <p>When the gateway blocks a HIGH-risk model call with {@code CONFIRMATION_REQUIRED},
 * the orchestrator issues a request here. ONLY the owner who triggered it may confirm,
 * by id, before expiry, exactly once. A forged confirmation (wrong player, expired or
 * replayed id) never executes anything — the gateway re-checks through
 * {@link #matchesAndConsume} at dispatch time.</p>
 */
public final class ConfirmationService {

	private static final Logger LOG = LoggerFactory.getLogger(ConfirmationService.class);

	/** Default validity: 30 seconds of ticks. */
	public static final long DEFAULT_TTL_TICKS = 600L;

	public record Request(UUID confirmId, UUID ownerId, UUID agentId, String toolName,
			String argumentsFingerprint, long issuedAtTick, long expiresAtTick) {
	}

	private final Map<UUID, Request> pending = new ConcurrentHashMap<>();
	private final java.util.Set<UUID> confirmed = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Store a pending request for one blocked call. @return the confirmation id. */
	public Request issue(UUID ownerId, UUID agentId, String toolName,
			String argumentsFingerprint, long nowTick) {
		return issueWithId(UUID.randomUUID(), ownerId, agentId, toolName,
			argumentsFingerprint, nowTick, nowTick + DEFAULT_TTL_TICKS);
	}

	/**
	 * Register a request under an id chosen by the caller (方案 F3). The durable
	 * {@link PendingOperation} owns the id so the player sees ONE id in the preview,
	 * in {@code /squire confirm} and in {@code /squire deny}.
	 */
	public Request issueWithId(UUID confirmId, UUID ownerId, UUID agentId, String toolName,
			String argumentsFingerprint, long nowTick, long expiresAtTick) {
		Request request = new Request(confirmId, ownerId, agentId, toolName,
			argumentsFingerprint, nowTick, expiresAtTick);
		pending.put(request.confirmId(), request);
		LOG.info("[confirm] issued {} for {} by owner {}", request.confirmId(),
			toolName, ownerId);
		return request;
	}

	/** Drop a request without confirming it (owner denied, or superseded). */
	public void cancel(UUID confirmId) {
		pending.remove(confirmId);
		confirmed.remove(confirmId);
	}

	public Optional<Request> get(UUID confirmId) {
		return Optional.ofNullable(pending.get(confirmId));
	}

	/** All requests still pending for one owner (oldest first). */
	public List<Request> pendingFor(UUID ownerId) {
		return pending.values().stream()
			.filter(r -> r.ownerId().equals(ownerId))
			.sorted(java.util.Comparator.comparingLong(Request::issuedAtTick))
			.toList();
	}

	/**
	 * Gateway-side check: is there a CONFIRMED request by THIS player for THIS exact
	 * call? Confirmed ids are consumed on use (single-use), expired ones rejected.
	 */
	public boolean matchesAndConsume(UUID playerId, String toolName,
			String argumentsFingerprint, long nowTick) {
		for (Request request : pendingFor(playerId)) {
			if (!request.toolName().equals(toolName)
					|| !request.argumentsFingerprint().equals(argumentsFingerprint)
					|| request.expiresAtTick() <= nowTick) {
				continue;
			}
			// a stored request only becomes consumable once its owner confirmed the id
			if (!confirmed.contains(request.confirmId())) {
				continue;
			}
			pending.remove(request.confirmId());
			return true;
		}
		return false;
	}

	/**
	 * Owner confirms one request id. Fails closed: unknown id, someone else's id,
	 * expired id.
	 *
	 * @return user-facing message
	 */
	public String confirm(UUID confirmerPlayerId, UUID confirmId, long nowTick) {
		Request request = pending.get(confirmId);
		if (request == null) {
			return "[Squire] Unknown or already-used confirmation id.";
		}
		if (!request.ownerId().equals(confirmerPlayerId)) {
			LOG.warn("[confirm] FORGED attempt: player {} tried to confirm {} owned by {}",
				confirmerPlayerId, confirmId, request.ownerId());
			return "[Squire] That confirmation belongs to another player.";
		}
		if (request.expiresAtTick() <= nowTick) {
			pending.remove(confirmId);
			return "[Squire] That confirmation has expired.";
		}
		confirmed.add(confirmId);
		return "[Squire] Confirmed — executing " + request.toolName() + ".";
	}

	public void expireAllBefore(long nowTick) {
		pending.values().removeIf(r -> r.expiresAtTick() <= nowTick && !confirmed.contains(r.confirmId()));
	}
}
