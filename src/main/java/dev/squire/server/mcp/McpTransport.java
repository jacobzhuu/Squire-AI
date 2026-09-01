package dev.squire.server.mcp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One connection to an MCP server (spec sections 50-54). ALL methods are async and
 * must never block the caller (which is the server thread during dispatch) — the
 * prohibition "网络 I/O 阻塞 Minecraft Server Thread" is structural here.
 */
public interface McpTransport extends AutoCloseable {

	/** Establish the session; completes on a transport worker thread. */
	CompletableFuture<Void> connect();

	/** List tools advertised by the server. */
	CompletableFuture<List<McpToolInfo>> listTools();

	/**
	 * Call one tool with a JSON-encoded arguments object; resolves to the raw text
	 * content the server returned (data only — never instructions).
	 */
	CompletableFuture<String> callTool(String toolName, String argumentsJson);

	@Override
	void close();
}
