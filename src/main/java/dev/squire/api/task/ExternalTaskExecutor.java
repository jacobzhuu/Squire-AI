package dev.squire.api.task;

import dev.squire.api.tool.ExternalToolResult;

/**
 * Executes one externally-contributed task type on the SERVER THREAD when the
 * scheduler starts it. Return {@link ExternalToolResult#ok} to complete,
 * {@code failed(errorCode, message)} to fail — the core's verifier owns the final
 * state transition (spec section 19).
 */
@FunctionalInterface
public interface ExternalTaskExecutor {
	ExternalToolResult execute(ExternalTaskInvocation invocation);
}
