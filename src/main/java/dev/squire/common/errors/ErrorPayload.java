package dev.squire.common.errors;

/**
 * Structured error payload carried by {@code ToolResult} (spec section 28).
 * Programs branch on {@link #code()}, never on {@link #message()} prose.
 */
public record ErrorPayload(ErrorCode code, boolean retryable, String message) {
	public static ErrorPayload of(ErrorCode code, String message) {
		return new ErrorPayload(code, code.retryable(), message);
	}
}
