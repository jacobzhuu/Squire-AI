package dev.squire.api.task;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One external task execution: agent, requester, validated parameters and a
 * completion handle for long-running work.
 *
 * <p>Completion semantics (spec section 19): returning
 * {@link dev.squire.api.tool.ExternalToolResult.Status#SUCCESS} or {@code FAILED}
 * from {@link ExternalTaskExecutor#execute} resolves the task immediately; returning
 * {@code RUNNING} keeps it running until the provider calls the handle — the core
 * verifier still owns the final transition, provider results only inform it.</p>
 */
public record ExternalTaskInvocation(UUID taskId, UUID agentId, UUID requesterId,
		Map<String, Object> parameters, Completion completion) {

	/** Resolves a RUNNING external task from any thread; the FIRST resolution wins. */
	public interface Completion {

		void complete(dev.squire.api.tool.ExternalToolResult result);
	}

	private static final Completion IGNORED = result -> {
	};

	public ExternalTaskInvocation {
		Objects.requireNonNull(taskId, "taskId");
		Objects.requireNonNull(agentId, "agentId");
		parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
		completion = completion == null ? IGNORED : completion;
	}

	public ExternalTaskInvocation(UUID taskId, UUID agentId, UUID requesterId,
			Map<String, Object> parameters) {
		this(taskId, agentId, requesterId, parameters, null);
	}
}
