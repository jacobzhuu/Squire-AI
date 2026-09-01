package dev.squire.server.mcp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.api.tool.ExternalToolResult;
import dev.squire.server.ext.ExtensionManager;

/**
 * Owns MCP server connections and imports their tools (spec sections 50-54, §88).
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li>NO network I/O on the server thread: discovery and calls run on transport
 *       threads; the imported handler returns RUNNING immediately.</li>
 *   <li>MCP is never a permission system: trust comes only from the
 *       {@link McpTrustStore}; results are DATA and can never raise permissions.</li>
 *   <li>Timeouts resolve to a FAILED result; disconnects to MCP_DISCONNECTED —
 *       neither crashes nor blocks anything.</li>
 * </ul>
 */
public final class McpClientBridge {
	private static final Logger LOG = LoggerFactory.getLogger(McpClientBridge.class);

	public static final int CALL_TIMEOUT_SECONDS = 10;
	/** Hard cap on remote payload size before it enters any result map (§54). */
	public static final int MAX_RESULT_CHARS = 65_536;

	private final ExtensionManager extensions;
	private final McpTrustStore trusts;
	private final McpToolImporter importer = new McpToolImporter();
	private final Map<String, McpTransport> transports = new ConcurrentHashMap<>();
	/** Settled remote results keyed by the original gateway call id. */
	private final Map<UUID, ExternalToolResult> settledOutcomes =
		new ConcurrentHashMap<>();
	/** Per-call timeout; instance field so tests can shorten it. */
	private final int callTimeoutSeconds;
	/** 方案 I2：在飞调用的归属与期限。 */
	private final PendingExternalCallRegistry pending = new PendingExternalCallRegistry();
	/** 方案 I2：每台服务器的熔断器。 */
	private final McpCircuitBreaker breaker = new McpCircuitBreaker();
	/** Per-server timeout from mcp.json; falls back to the global default. */
	private final Map<String, Integer> serverTimeouts = new ConcurrentHashMap<>();
	/** Server-thread clock, used for deadlines and the breaker's cooldown. */
	private volatile java.util.function.LongSupplier clock = () -> 0L;
	/** Where completed calls are applied. Production wires the AsyncBridge here;
	 *  tests use a direct executor. Never blocks the caller either way. */
	private volatile Executor completionExecutor = Runnable::run;

	public McpClientBridge(ExtensionManager extensions, McpTrustStore trusts) {
		this(extensions, trusts, CALL_TIMEOUT_SECONDS);
	}

	public McpClientBridge(ExtensionManager extensions, McpTrustStore trusts,
			int callTimeoutSeconds) {
		this.extensions = extensions;
		this.trusts = trusts;
		this.callTimeoutSeconds = Math.max(1, callTimeoutSeconds);
	}

	public void setCompletionExecutor(Executor executor) {
		this.completionExecutor = executor;
	}

	/** Server tick source; deadlines and the circuit breaker's cooldown use it. */
	public void setClock(java.util.function.LongSupplier value) {
		this.clock = value == null ? () -> 0L : value;
	}

	public PendingExternalCallRegistry pendingCalls() {
		return pending;
	}

	public McpCircuitBreaker circuitBreaker() {
		return breaker;
	}

	/** Per-server call timeout in seconds, as configured in mcp.json. */
	public void setServerTimeout(String server, int seconds) {
		serverTimeouts.put(server, Math.max(1, seconds));
	}

	private int timeoutFor(String server) {
		return serverTimeouts.getOrDefault(server, callTimeoutSeconds);
	}

	/** Connected server names, for {@code /squire admin mcp list}. */
	public java.util.List<String> connectedServers() {
		return java.util.List.copyOf(transports.keySet());
	}

	public McpTrustStore trusts() {
		return trusts;
	}

	public boolean isConnected(String serverName) {
		return transports.containsKey(serverName);
	}

	public synchronized int connectedCount() {
		return transports.size();
	}

	/**
	 * Connect + discover + import asynchronously. Failures are logged and reported —
	 * never thrown, so a dead server cannot break startup or ticking.
	 *
	 * @return the future completing when the import attempt finished (success OR error)
	 */
	public CompletableFuture<Void> registerServer(String name, McpTransport transport) {
		String safe = McpToolImporter.sanitizeServerName(name);
		if (safe.isEmpty()) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("unsanitary server name: " + name));
		}
		transports.put(safe, transport);
		return transport.connect()
			.thenCompose(v -> transport.listTools())
			.thenAccept(tools -> {
				int accepted = 0;
				for (McpToolInfo info : tools) {
					try {
						var report = extensions.registerTool(
							importer.build(this, safe, info),
							trusts.trustOf(safe), "mcp:" + safe);
						if (report.accepted()) {
							accepted++;
						}
					} catch (RuntimeException e) {
						LOG.warn("[mcp] refused tool '{}' from {}: {}",
							info == null ? "?" : info.name(), safe, e.getMessage());
					}
				}
				LOG.info("[mcp] server '{}' ready: {} tools imported", safe, accepted);
			})
			.whenComplete((v, err) -> {
				if (err != null) {
					LOG.warn("[mcp] server '{}' unavailable: {}", safe,
						rootMessage(err));
					transports.remove(safe, transport); // failed handshake = not present
				}
			});
	}

	/** Admin/disconnect path: closes the transport and forgets the server. */
	public void disconnect(String name) {
		// 在飞的调用必须立刻得到结构化失败，不能挂在那里等一个不会来的结果
		for (var call : pending.takeAllForServer(name)) {
			ExternalToolResult result = ExternalToolResult.failed(call.callId(),
				"MCP_DISCONNECTED", "server '" + name + "' disconnected mid-call");
			settledOutcomes.put(call.callId(), result);
			listener.accept(result);
		}
		McpTransport transport = transports.remove(name);
		if (transport != null) {
			try {
				transport.close(); // in-flight calls fail through their own futures
			} catch (RuntimeException e) {
				LOG.warn("[mcp] close of '{}' threw (ignored): {}", name, e.getMessage());
			}
		}
	}

	/**
	 * The handler bound into every imported MCP tool: validates nothing (the gateway
	 * already did), fires the remote call off-thread and returns RUNNING at once.
	 */
	public ExternalToolResult handleRemoteCall(String server, String toolName,
			dev.squire.api.tool.ToolInvocation call) {
		UUID callId = call.callId();
		long now = clock.getAsLong();
		McpTransport transport = transports.get(server);
		if (transport == null) {
			noteFailure();
			return ExternalToolResult.failed(callId, "MCP_DISCONNECTED",
				"server '" + server + "' is not connected");
		}
		// 方案 I2：连续失败的服务器先短路，别让每次对话都卡满超时才失败
		if (!breaker.allowCall(server, now)) {
			noteFailure();
			return ExternalToolResult.failed(callId, "MCP_CIRCUIT_OPEN",
				"server '" + server + "' is temporarily circuit-broken after repeated "
					+ "failures");
		}
		int timeoutSeconds = timeoutFor(server);
		// 登记归属和期限：结果回来知道唤醒谁，永远不回来也有人发现
		boolean registered = pending.register(new PendingExternalCallRegistry.PendingCall(
			callId, call.requesterId(), call.agentId(), null, null, server, toolName,
			now, now + Math.max(20L, timeoutSeconds * 20L + 40L)));
		if (!registered) {
			noteFailure();
			return ExternalToolResult.failed(callId, "RATE_LIMITED",
				"too many external calls in flight");
		}
		noteCall();
		Map<String, Object> arguments = call.arguments();
		transport.callTool(toolName, GsonHolder.GSON.toJson(arguments))
			.orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
			.handle((text, err) -> err == null
				? ExternalToolResult.ok(callId, Map.of("content", cap(text)))
				: failureFor(callId, err, timeoutSeconds))
			.thenAccept(result -> completionExecutor.execute(() ->
				onRemoteOutcome(server, toolName, result)));
		return ExternalToolResult.running(callId,
			Map.of("server", server, "tool", toolName));
	}

	/**
	 * 服务器 tick 调用：把过了期限却还没有任何结果的调用变成结构化超时。
	 * 没有这一步，一次永远不返回的调用会让 Turn 无限等待。
	 *
	 * @return 本次合成的超时结果
	 */
	public java.util.List<ExternalToolResult> sweepOverdue(long nowTick) {
		var overdue = pending.takeOverdue(nowTick);
		java.util.List<ExternalToolResult> synthesised = new java.util.ArrayList<>();
		for (var call : overdue) {
			ExternalToolResult result = ExternalToolResult.failed(call.callId(),
				"MCP_TIMEOUT", "no response from '" + call.server() + "' before the "
					+ "deadline");
			breaker.recordFailure(call.server(), nowTick);
			settledOutcomes.put(call.callId(), result);
			noteFailure();
			listener.accept(result);
			synthesised.add(result);
		}
		return synthesised;
	}

	/** Completion sink: data lands as DATA ONLY — permissions are never touched here. */
	private void onRemoteOutcome(String server, String toolName,
			ExternalToolResult result) {
		if (pending.complete(result.callId()).isEmpty()) {
			// 已经被 sweepOverdue 判过超时：迟到的结果不能再翻案
			LOG.info("[mcp] late result for {} ignored (already timed out)",
				result.callId());
			return;
		}
		settledOutcomes.put(result.callId(), result);
		if (result.status() == ExternalToolResult.Status.FAILED) {
			LOG.info("[mcp] call {}/{} failed: {}", server, toolName, result.message());
			noteFailure();
			breaker.recordFailure(server, clock.getAsLong());
		} else {
			breaker.recordSuccess(server);
		}
		listener.accept(result);
	}

	/** Durable-turn polling seam for RUNNING MCP calls. Results remain immutable data. */
	public java.util.Optional<ExternalToolResult> outcomeOf(UUID callId) {
		return java.util.Optional.ofNullable(settledOutcomes.get(callId));
	}

	/** Turn correlation consumes a settled result exactly once. */
	public java.util.Optional<ExternalToolResult> takeOutcome(UUID callId) {
		return java.util.Optional.ofNullable(settledOutcomes.remove(callId));
	}

	// ---- §75 observability seams -------------------------------------------

	private volatile dev.squire.server.metrics.SquireMetrics metrics;

	public void setMetrics(dev.squire.server.metrics.SquireMetrics value) {
		this.metrics = value;
	}

	private void noteCall() {
		dev.squire.server.metrics.SquireMetrics m = metrics;
		if (m != null) {
			m.inc(dev.squire.server.metrics.SquireMetrics.Key.MCP_CALLS);
		}
	}

	private void noteFailure() {
		dev.squire.server.metrics.SquireMetrics m = metrics;
		if (m != null) {
			m.inc(dev.squire.server.metrics.SquireMetrics.Key.MCP_FAILURES);
		}
	}

	private volatile java.util.function.Consumer<ExternalToolResult> listener =
		result -> {
		};

	/** Observation seam for tests/diagnostics; receives every settled remote result. */
	public void setOutcomeListener(java.util.function.Consumer<ExternalToolResult> l) {
		this.listener = l;
	}

	private static ExternalToolResult failureFor(UUID callId, Throwable err,
			int timeoutSeconds) {
		Throwable root = err;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		boolean timeout = root instanceof TimeoutException
			|| String.valueOf(root).toLowerCase(java.util.Locale.ROOT)
				.contains("timeout");
		return timeout
			? ExternalToolResult.failed(callId, "MCP_TIMEOUT",
				"remote call exceeded " + timeoutSeconds + "s")
			: ExternalToolResult.failed(callId, "MCP_PROTOCOL_ERROR",
				String.valueOf(root.getMessage()));
	}

	private static String cap(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= MAX_RESULT_CHARS ? text
			: text.substring(0, MAX_RESULT_CHARS - 1) + "…";
	}

	private static String rootMessage(Throwable err) {
		Throwable root = err;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return String.valueOf(root.getMessage());
	}

	/** Lazily-initialized Gson holder (keeps this class import-light). */
	private static final class GsonHolder {
		static final com.google.gson.Gson GSON = new com.google.gson.Gson();
	}
}
