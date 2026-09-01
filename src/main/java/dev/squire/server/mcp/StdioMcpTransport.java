package dev.squire.server.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * MCP over STDIO: launches the server process and speaks newline-delimited JSON-RPC
 * on stdin/stdout (spec section 53). All protocol I/O runs on a dedicated daemon
 * executor — the server thread only ever hands off futures.
 */
public final class StdioMcpTransport implements McpTransport {
	private static final Gson GSON = new Gson();
	private static final long REQUEST_TIMEOUT_SECONDS = 15;

	private final List<String> command;
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "squire-mcp-stdio");
		t.setDaemon(true);
		return t;
	});
	private final AtomicLong nextId = new AtomicLong(1);

	private volatile Process process;
	private volatile BufferedReader reader;
	private volatile OutputStreamWriter writer;

	public StdioMcpTransport(List<String> command) {
		this.command = List.copyOf(command);
	}

	@Override
	public CompletableFuture<Void> connect() {
		return CompletableFuture.runAsync(() -> {
			try {
				ProcessBuilder pb = new ProcessBuilder(command);
				pb.redirectErrorStream(false);
				process = pb.start();
				reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
					StandardCharsets.UTF_8));
				writer = new OutputStreamWriter(process.getOutputStream(),
					StandardCharsets.UTF_8);
				// initialize handshake: best effort; failures surface on first use
				request("initialize", initializeParams()).get(REQUEST_TIMEOUT_SECONDS,
					TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException("stdio connect failed: " + e.getMessage(), e);
			}
		}, io);
	}

	private static JsonObject initializeParams() {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", "2025-03-26");
		JsonObject caps = new JsonObject();
		params.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", "squire");
		info.addProperty("version", "1.0");
		params.add("clientInfo", info);
		return params;
	}

	@Override
	public CompletableFuture<List<McpToolInfo>> listTools() {
		return request("tools/list", new JsonObject()).thenApply(this::parseTools);
	}

	private List<McpToolInfo> parseTools(JsonObject response) {
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
			String name = tool.has("name") ? tool.get("name").getAsString() : "";
			String description = tool.has("description")
				? tool.get("description").getAsString() : "";
			String schema = tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()
				? GSON.toJson(tool.get("inputSchema")) : "{}";
			boolean readOnly = annotation(tool, "readOnlyHint");
			boolean destructive = annotation(tool, "destructiveHint");
			out.add(new McpToolInfo(name, description, schema, readOnly, destructive));
			if (out.size() >= 128) { // hard cap per server (§54 sanitize)
				break;
			}
		}
		return out;
	}

	private static boolean annotation(JsonObject tool, String member) {
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
		return request("tools/call", params).thenApply(response -> {
			if (response.has("error") && response.get("error").isJsonObject()) {
				throw new IllegalStateException("mcp error: "
					+ response.get("error").toString());
			}
			JsonElement result = response.get("result");
			if (result == null || !result.isJsonObject()) {
				return "";
			}
			return extractText(result.getAsJsonObject());
		});
	}

	private static String extractText(JsonObject result) {
		StringBuilder sb = new StringBuilder();
		JsonElement content = result.get("content");
		if (content != null && content.isJsonArray()) {
			for (JsonElement element : content.getAsJsonArray()) {
				if (element.isJsonObject() && element.getAsJsonObject().has("text")) {
					sb.append(element.getAsJsonObject().get("text").getAsString());
				}
			}
		}
		return sb.toString();
	}

	private CompletableFuture<JsonObject> request(String method, JsonObject params) {
		long id = nextId.getAndIncrement();
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.addProperty("id", id);
		message.addProperty("method", method);
		message.add("params", params);
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return CompletableFuture.supplyAsync(() -> {
			try {
				writer.write(GSON.toJson(message) + "\n");
				writer.flush();
				String line = reader.readLine(); // single-flight executor: reply matches
				if (line == null) {
					throw new IllegalStateException("mcp server closed the stream");
				}
				JsonObject response = JsonParser.parseString(line).getAsJsonObject();
				future.complete(response); // satisfy the timeout guard
				return response;
			} catch (IOException e) {
				throw new IllegalStateException("stdio io failed: " + e.getMessage(), e);
			}
		}, io).applyToEither(future, r -> r);
	}

	@Override
	public void close() {
		io.shutdownNow();
		Process p = process;
		if (p != null && p.isAlive()) {
			p.destroy(); // graceful SIGTERM; never block the caller on exit()
		}
	}

	/** Visible for tests/diagnostics: did the child process die? */
	public boolean isAlive() {
		Process p = process;
		return p != null && p.isAlive();
	}
}
