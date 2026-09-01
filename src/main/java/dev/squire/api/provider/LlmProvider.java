package dev.squire.api.provider;

import java.util.concurrent.CompletableFuture;

import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;

/**
 * Contract every LLM backend implements (spec section 23).
 *
 * <p>Implementations MUST NOT block the calling thread: {@link #generate} returns a
 * future that completes on the provider's own executor. The runtime decides what the
 * reply means — a provider can never execute actions or declare task completion.</p>
 */
public interface LlmProvider {
	/** Stable provider identity for logs/config (e.g. "mock", "openai"). */
	String id();

	/** Async generation; never blocks the Minecraft server thread. */
	CompletableFuture<AgentResponse> generate(AgentRequest request);

	/** Static capability declaration consulted before features are enabled. */
	ProviderCapabilities capabilities();

	/** Cheap liveness probe; async for the same reason as {@link #generate}. */
	CompletableFuture<Boolean> healthCheck();
}
