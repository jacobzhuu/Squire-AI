package dev.squire.server.provider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dev.squire.api.provider.LlmProvider;
import dev.squire.api.provider.ProviderCapabilities;
import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;

/**
 * Deterministic provider for tests and GameTests: replays queued responses off the
 * server thread (same threading contract as real providers, spec section 7).
 * When the script runs dry it degrades to an echo so late turns never hang.
 */
public final class ScriptedProvider implements LlmProvider {
	private final String id;
	private final Deque<String> script = new ArrayDeque<>();
	/** Marker-routed responses: first entry whose marker is contained in the message wins. */
	private final java.util.Map<String, String> routedResponses =
		new java.util.LinkedHashMap<>();
	private final List<AgentRequest> received = new ArrayList<>();
	private final AtomicInteger index = new AtomicInteger();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "squire-scripted-provider");
		t.setDaemon(true);
		return t;
	});

	public ScriptedProvider(String id, String... responses) {
		this.id = id;
		for (String response : responses) {
			script.add(response);
		}
	}

	/** Route by substring of the player turn — immune to interleaved test chatter. */
	public ScriptedProvider respondTo(String messageMarker, String response) {
		routedResponses.put(messageMarker, response);
		return this;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public CompletableFuture<AgentResponse> generate(AgentRequest request) {
		synchronized (received) {
			received.add(request);
		}
		String responseText = null;
		synchronized (routedResponses) {
			for (var entry : routedResponses.entrySet()) {
				if (request.message().contains(entry.getKey())) {
					responseText = entry.getValue();
					break;
				}
			}
		}
		if (responseText == null) {
			synchronized (script) {
				responseText = script.isEmpty()
					? "[scripted] no scripted line left for turn " + index.incrementAndGet()
					: script.poll();
			}
		}
		final String text = responseText;
		return CompletableFuture.supplyAsync(() -> new AgentResponse(text), worker);
	}

	@Override
	public ProviderCapabilities capabilities() {
		return ProviderCapabilities.full(32_768);
	}

	@Override
	public CompletableFuture<Boolean> healthCheck() {
		return CompletableFuture.completedFuture(true);
	}

	public List<AgentRequest> receivedRequests() {
		synchronized (received) {
			return List.copyOf(received);
		}
	}

	public void shutdown() {
		worker.shutdownNow();
	}
}
