package dev.squire.api.body;

/**
 * Handle to an in-flight movement. Cancelling is idempotent. Handles are never persisted
 * (spec section 72): after restart, tasks re-resolve targets and request new handles.
 */
public interface MoveHandle {
	enum State {
		MOVING, ARRIVED, FAILED, CANCELLED
	}

	State state();

	void cancel();

	/** True when the navigation reports arrival at the requested target. */
	default boolean arrived() {
		return state() == State.ARRIVED;
	}

	/**
	 * Why this move failed, or {@code null} while it has not.
	 *
	 * <p>The caller needs the specific reason, not just FAILED: a refusal because the
	 * target lies outside the companion's stay area is a thing the player can fix by
	 * moving the anchor, and telling them "path blocked" instead sends them looking for
	 * a wall that is not there.</p>
	 */
	default String errorCode() {
		return null;
	}
}
