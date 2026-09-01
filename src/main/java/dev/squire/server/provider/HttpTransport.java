package dev.squire.server.provider;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound HTTP seam for LLM providers. Implementations MUST be async: the
 * returned future completes on the transport's own threads, never on the
 * Minecraft server thread (spec section 3 prohibition). The seam exists so
 * tests can inject a fake transport and so the wire format stays swappable.
 */
public interface HttpTransport {

	/** One completed HTTP exchange: status code plus body (any status). */
	record Response(int status, String body) {
	}

	/**
	 * POSTs {@code bodyJson} with the given headers, resolving to the response
	 * for any completed HTTP exchange (any status code — callers decide what
	 * non-2xx means) and completing exceptionally on connect/timeout/IO
	 * failure.
	 */
	CompletableFuture<Response> postJson(String url, Map<String, String> headers,
			String bodyJson, long timeoutMs);

	/** GET with headers; same contract as {@link #postJson}. */
	CompletableFuture<Response> get(String url, Map<String, String> headers,
			long timeoutMs);
}
