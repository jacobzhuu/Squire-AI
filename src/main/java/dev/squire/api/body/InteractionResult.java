package dev.squire.api.body;

/**
 * Outcome of a body interaction attempt (attack / useItem / interact).
 * Carries a machine-readable error code from spec section 29 when failed.
 */
public record InteractionResult(boolean success, String errorCode, String detail) {
	public static InteractionResult ok() {
		return new InteractionResult(true, null, null);
	}

	public static InteractionResult fail(String errorCode, String detail) {
		return new InteractionResult(false, errorCode, detail);
	}
}
