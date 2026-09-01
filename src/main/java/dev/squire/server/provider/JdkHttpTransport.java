package dev.squire.server.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * JDK {@link HttpClient}-backed transport. {@code sendAsync} never touches the
 * calling thread, so server-thread non-blocking holds by construction; the
 * client's own connection-pool threads complete the returned futures.
 */
public final class JdkHttpTransport implements HttpTransport {

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@Override
	public CompletableFuture<Response> postJson(String url,
			Map<String, String> headers, String bodyJson, long timeoutMs) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(Duration.ofMillis(timeoutMs))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(bodyJson));
		headers.forEach(builder::header);
		return send(builder.build());
	}

	@Override
	public CompletableFuture<Response> get(String url, Map<String, String> headers,
			long timeoutMs) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(Duration.ofMillis(timeoutMs))
			.GET();
		headers.forEach(builder::header);
		return send(builder.build());
	}

	private CompletableFuture<Response> send(HttpRequest request) {
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(r -> new Response(r.statusCode(), r.body()));
	}
}
