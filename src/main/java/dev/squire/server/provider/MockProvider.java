package dev.squire.server.provider;

import java.util.concurrent.CompletableFuture;

import dev.squire.api.provider.LlmProvider;
import dev.squire.api.provider.ProviderCapabilities;
import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;

/**
 * Deterministic offline provider: no network, fixed echo behavior, completes on its
 * own single-thread executor so the server-thread rule stays honest even in tests.
 */
public final class MockProvider implements LlmProvider {
	private final java.util.concurrent.ExecutorService executor =
		java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "squire-mock-provider");
			t.setDaemon(true);
			return t;
		});

	@Override
	public String id() {
		return "mock";
	}

	@Override
	public CompletableFuture<AgentResponse> generate(AgentRequest request) {
		return CompletableFuture.supplyAsync(() ->
			AgentResponse.of("[mock] you said: " + request.message()), executor);
	}

	@Override
	public ProviderCapabilities capabilities() {
		return ProviderCapabilities.minimal();
	}

	@Override
	public CompletableFuture<Boolean> healthCheck() {
		return CompletableFuture.completedFuture(true);
	}
}
