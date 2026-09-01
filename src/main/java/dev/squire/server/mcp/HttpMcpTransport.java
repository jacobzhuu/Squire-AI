package dev.squire.server.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * MCP over Streamable HTTP (spec section 53): JSON-RPC POSTs to the server's MCP
 * endpoint using only the JDK HttpClient — no new dependencies (§97). Every call is
 * async; nothing here may run on the server thread.
 */
public final class HttpMcpTransport implements McpTransport {
	private static final Gson GSON = new Gson();
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private final URI endpoint;
	private final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(REQUEST_TIMEOUT)
		.build();
	private final AtomicLong nextId = new AtomicLong(1);

	public HttpMcpTransport(String url) {
		this.endpoint = URI.create(url);
	}

	@Override
	public CompletableFuture<Void> connect() {
		return rpc("initialize", initializeParams()).thenAccept(r -> {
		});
	}

	private static JsonObject initializeParams() {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", "2025-03-26");
		params.add("capabilities", new JsonObject());
		JsonObject info = new JsonObject();
		info.addProperty("name", "squire");
		info.addProperty("version", "1.0");
		params.add("clientInfo", info);
		return params;
	}

	@Override
	public CompletableFuture<List<McpToolInfo>> listTools() {
		return rpc("tools/list", new JsonObject()).thenApply(response -> {
			List<McpToolInfo> out = new ArrayList<>();
			JsonElement result = response.get("result");
			if (result == null || !result.isJsonObject()) {
				return out;
			}
			JsonElement tools = result.getAsJsonObject().get("tools");
			if (tools == null || !tools.isJsonArray()) {
				return out;
			}
			for (JsonElement element : tools.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject tool = element.getAsJsonObject();
				out.add(new McpToolInfo(
					tool.has("name") ? tool.get("name").getAsString() : "",
					tool.has("description") ? tool.get("description").getAsString() : "",
					tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()
						? GSON.toJson(tool.get("inputSchema")) : "{}",
					bool(tool, "readOnlyHint"), bool(tool, "destructiveHint")));
				if (out.size() >= 128) { // hard cap per server (§54 sanitize)
					break;
				}
			}
			return out;
		});
	}

	private static boolean bool(JsonObject tool, String member) {
		return tool.has(member) && tool.get(member).isJsonPrimitive()
			&& tool.get(member).getAsBoolean();
	}

	@Override
	public CompletableFuture<String> callTool(String toolName, String argumentsJson) {
		JsonObject params = new JsonObject();
		params.addProperty("name", toolName);
		JsonElement args = JsonParser.parseString(
			argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
		params.add("arguments", args.isJsonObject() ? args.getAsJsonObject() : new JsonObject());
		return rpc("tools/call", params).thenApply(response -> {
			if (response.has("error") && response.get("error").isJsonObject()) {
				throw new IllegalStateException("mcp error: "
					+ response.get("error").toString());
			}
			JsonElement result = response.get("result");
			if (result == null || !result.isJsonObject()) {
				return "";
			}
			StringBuilder sb = new StringBuilder();
			JsonElement content = result.getAsJsonObject().get("content");
			if (content != null && content.isJsonArray()) {
				for (JsonElement element : content.getAsJsonArray()) {
					if (element.isJsonObject()
							&& element.getAsJsonObject().has("text")) {
						sb.append(element.getAsJsonObject().get("text").getAsString());
					}
				}
			}
			return sb.toString();
		});
	}

	private CompletableFuture<JsonObject> rpc(String method, JsonObject params) {
		long id = nextId.getAndIncrement();
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.addProperty("id", id);
		message.addProperty("method", method);
		message.add("params", params);
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(message)))
			.build();
		CompletableFuture<HttpResponse<String>> response = client.sendAsync(request,
			HttpResponse.BodyHandlers.ofString());
		return response.thenApply(res -> {
			if (res.statusCode() / 100 != 2) {
				throw new IllegalStateException("mcp http status " + res.statusCode());
			}
			String body = res.body().trim();
			// Streamable HTTP may answer as SSE: take the first data: line
			if (body.startsWith("event:") || body.startsWith("data:")) {
				for (String line : body.split("\n")) {
					line = line.trim();
					if (line.startsWith("data:")) {
						body = line.substring(5).trim();
						break;
					}
				}
			}
			int start = body.indexOf('{');
			if (start < 0) {
				throw new IllegalStateException("mcp http body without json object");
			}
			return JsonParser.parseString(body.substring(start)).getAsJsonObject();
		});
	}

	@Override
	public void close() {
		// JDK client has no lifecycle to tear down; connections time out naturally
	}
}
