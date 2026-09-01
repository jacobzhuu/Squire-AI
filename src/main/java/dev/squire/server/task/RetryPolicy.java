package dev.squire.server.task;

import java.util.Optional;

/** Bounded retry behavior for one task (spec section 18). */
public record RetryPolicy(int maxRetries, int backoffTicks) {
	public static final RetryPolicy NONE = new RetryPolicy(0, 0);
	public static final RetryPolicy DEFAULT = new RetryPolicy(3, 20);

	public Optional<Integer> bounded() {
		return Optional.of(maxRetries);
	}
}
