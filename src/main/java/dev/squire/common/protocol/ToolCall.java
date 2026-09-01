package dev.squire.common.protocol;

import java.util.Map;
import java.util.UUID;

/**
 * A model-emitted request to invoke one tool (spec section 22).
 *
 * <p>Pure DTO: arguments are raw decoded JSON values. Every validation happens later,
 * inside the Tool Gateway — this type never executes anything itself.</p>
 */
public record ToolCall(UUID callId, String toolName, Map<String, Object> arguments) {
	public ToolCall {
		if (callId == null) {
			callId = UUID.randomUUID();
		}
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName must not be blank");
		}
		arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
	}

	public static ToolCall of(String toolName, Map<String, Object> arguments) {
		return new ToolCall(UUID.randomUUID(), toolName, arguments);
	}
}
