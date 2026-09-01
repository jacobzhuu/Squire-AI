package dev.squire.common.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.squire.common.errors.ErrorPayload;

/**
 * Outcome of one gateway-approved tool invocation (spec section 28).
 *
 * @param callId   echoes the originating {@link ToolCall#callId()}
 * @param status   terminal or in-progress classification
 * @param data     tool-specific payload (immutable)
 * @param error    structured error when status is FAILED/BLOCKED
 */
public record ToolResult(UUID callId, Status status, Map<String, Object> data,
		ErrorPayload error) {

	public enum Status { SUCCESS, PARTIAL, RUNNING, BLOCKED, FAILED, CANCELLED }

	public ToolResult {
		data = data == null ? Map.of() : Map.copyOf(data);
	}

	public static ToolResult success(UUID callId, Map<String, Object> data) {
		return new ToolResult(callId, Status.SUCCESS, data, null);
	}

	public static ToolResult failed(UUID callId, ErrorPayload error) {
		return new ToolResult(callId, Status.FAILED, Map.of(), error);
	}

	/** Work was accepted and continues asynchronously as task(s). */
	public static ToolResult running(UUID callId, Map<String, Object> data) {
		return new ToolResult(callId, Status.RUNNING, data, null);
	}

	public static ToolResult blocked(UUID callId, ErrorPayload error) {
		return new ToolResult(callId, Status.BLOCKED, Map.of(), error);
	}

	public boolean isSuccess() {
		return status == Status.SUCCESS;
	}

	public Optional<ErrorPayload> errorOrNull() {
		return Optional.ofNullable(error);
	}
}
