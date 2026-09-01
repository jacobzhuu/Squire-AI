package dev.squire.api.tool;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything an external tool handler may know about one invocation. Handlers never
 * see Minecraft types — results flow back as plain data and the CORE applies every
 * world effect through its own validated paths (spec sections 7/27/57).
 */
public record ToolInvocation(UUID callId, UUID agentId, UUID requesterId,
		Map<String, Object> arguments) {

	public ToolInvocation {
		Objects.requireNonNull(callId, "callId");
		Objects.requireNonNull(agentId, "agentId");
		arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
	}
}
