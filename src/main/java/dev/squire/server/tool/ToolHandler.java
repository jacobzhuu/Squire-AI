package dev.squire.server.tool;

import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;

/**
 * Native implementation behind a {@link ToolDefinition}. Handlers run ONLY via the
 * Tool Gateway, always on the server thread.
 */
public interface ToolHandler {
	ToolResult execute(ToolCall call, ToolExecutionContext context);
}
