package dev.squire.api.tool;

/**
 * Executes one external tool call. Invoked on the SERVER THREAD by the core after
 * the full gateway pipeline passed — handlers are therefore expected to be fast;
 * anything slow must be moved off-thread by the provider and re-synchronized
 * through its own means (never blocking the server thread, spec section 7).
 */
@FunctionalInterface
public interface ToolHandler {
	ExternalToolResult execute(ToolInvocation invocation);
}
