package dev.squire.server.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squire.api.tool.ExternalToolResult;
import dev.squire.api.tool.ToolInvocation;
import dev.squire.server.ext.ExtensionManager;
import dev.squire.server.mcp.mock.MockMcpServers.HugePayloadMcp;
import dev.squire.server.mcp.mock.MockMcpServers.InjectionMcp;
import dev.squire.server.mcp.mock.MockMcpServers.InvalidSchemaMcp;
import dev.squire.server.mcp.mock.MockMcpServers.MaliciousMcp;
import dev.squire.server.mcp.mock.MockMcpServers.SafeMcp;
import dev.squire.server.mcp.mock.MockMcpServers.SlowMcp;
import dev.squire.server.security.ToolTrust;
import dev.squire.server.tool.AgentPermission;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.tool.RiskLevel;
import dev.squire.server.tool.ToolRegistry;

/**
 * MCP stack unit tests (spec sections 50-54, §81 mocks, §88 DoD): sanitize-on-import,
 * fail-closed trust persistence, timeout/disconnect never block or crash, payloads
 * capped, results stay DATA ONLY.
 */
class McpStackTest {

	private final ToolRegistry registry = new ToolRegistry();
	private final ExtensionManager extensions =
		new ExtensionManager(registry, new TaskScheduler());

	private McpClientBridge bridge(int timeoutSeconds) {
		return new McpClientBridge(extensions, new McpTrustStore(Path.of("unused")),
			timeoutSeconds);
	}

	private static ToolInvocation invocation() {
		return new ToolInvocation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			Map.of());
	}

	/** Waits up to {@code seconds} for the listener to observe a settled result. */
	private static ExternalToolResult await(AtomicReference<ExternalToolResult> ref,
			long seconds) {
		long deadline = System.currentTimeMillis() + seconds * 1000;
		while (System.currentTimeMillis() < deadline) {
			ExternalToolResult r = ref.get();
			if (r != null && r.status() != ExternalToolResult.Status.RUNNING) {
				return r;
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return ref.get();
	}

	// ------------------------------------------------------------------ trust store

	@Test
	void trustStoreRoundTripAndFailClosedDefaults(@TempDir Path dir) {
		Path file = dir.resolve("mcp-trust.json");
		McpTrustStore store = new McpTrustStore(file);
		assertEquals(ToolTrust.UNTRUSTED, store.trustOf("unknown-server"));
		assertTrue(store.setTrust("safe", ToolTrust.ADMIN_APPROVED_LOCAL));
		assertTrue(store.setTrust("evil", ToolTrust.BLOCKED));

		McpTrustStore reloaded = new McpTrustStore(file);
		assertEquals(ToolTrust.ADMIN_APPROVED_LOCAL, reloaded.trustOf("safe"));
		assertEquals(ToolTrust.BLOCKED, reloaded.trustOf("evil"));
		assertNotNull(reloaded.snapshot());
		reloaded.snapshot().put("evil", null); // snapshot is a copy; must not throw
	}

	@Test
	void corruptTrustFileDegradesToUntrusted(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("mcp-trust.json");
		java.nio.file.Files.writeString(file, "{not json at all::::");
		McpTrustStore store = new McpTrustStore(file);
		assertEquals(ToolTrust.UNTRUSTED, store.trustOf("anything"));
	}

	// ------------------------------------------------------------------ import path

	@Test
	void safeServerImportsReadOnlyQueryClassTool() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("safe", new SafeMcp()).join();

		var def = registry.lookup("safe:server_time").orElseThrow();
		assertEquals(ToolTrust.UNTRUSTED, def.sourceTrust()); // not in trust store yet
		assertEquals(AgentPermission.QUERY, def.permission());
		assertEquals(RiskLevel.LOW, def.risk());
		assertFalse(def.destructive());
	}

	@Test
	void maliciousImportsAreSanitizedOrRefused() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("malicious", new MaliciousMcp()).join();

		// oversized schema -> whole tool refused
		assertTrue(registry.lookup("malicious:" + MaliciousMcp.OVERSIZED_TOOL).isEmpty());
		// write tool IS imported (registry presence is inert), classified fail-closed
		var def = registry.lookup("malicious:" + MaliciousMcp.WRITE_TOOL).orElseThrow();
		assertTrue(def.destructive());
		assertEquals(RiskLevel.HIGH, def.risk());
		assertEquals(AgentPermission.WORLD_PLACE, def.permission());
		// huge description truncated to cap
		assertEquals(ExtensionManager.MAX_DESCRIPTION_CHARS, def.description().length());
		// reserved namespace attack: server naming itself "minecraft" imports NOTHING
		bridge.registerServer("minecraft", new MaliciousMcp()).join();
		assertTrue(registry.lookup("minecraft:" + MaliciousMcp.WRITE_TOOL).isEmpty());
	}

	@Test
	void invalidSchemaRefusesTheWholeTool() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("broken-schema", new InvalidSchemaMcp()).join();
		assertTrue(registry.lookup("broken-schema:broken_tool").isEmpty());
	}

	// ------------------------------------------------------------------ call path

	@Test
	void remoteCallReturnsRunningImmediatelyThenTimesOut() {
		McpClientBridge bridge = bridge(1); // 1s so the test stays fast
		bridge.registerServer("slow", new SlowMcp()).join();
		AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
		bridge.setOutcomeListener(observed::set);

		long startNanos = System.nanoTime();
		ExternalToolResult immediate = bridge.handleRemoteCall("slow", "endless_query",
			invocation());
		long millis = (System.nanoTime() - startNanos) / 1_000_000;

		// THE core guarantee: dispatch returns at once, never waiting on network I/O
		assertEquals(ExternalToolResult.Status.RUNNING, immediate.status());
		assertTrue(millis < 500, "handler blocked for " + millis + "ms");

		ExternalToolResult settled = await(observed, 6);
		assertNotNull(settled);
		assertEquals("MCP_TIMEOUT", settled.errorCode());
	}

	@Test
	void hugePayloadIsCappedBeforeEnteringResults() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("huge", new HugePayloadMcp()).join();
		AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
		bridge.setOutcomeListener(observed::set);

		ExternalToolResult immediate = bridge.handleRemoteCall("huge", "firehose",
			invocation());
		assertEquals(ExternalToolResult.Status.RUNNING, immediate.status());
		ExternalToolResult settled = await(observed, 5);
		assertEquals(ExternalToolResult.Status.SUCCESS, settled.status());
		String content = String.valueOf(settled.data().get("content"));
		assertEquals(McpClientBridge.MAX_RESULT_CHARS, content.length());
	}

	@Test
	void injectionPayloadStaysVerbatimInertData() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("inject", new InjectionMcp()).join();
		AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
		bridge.setOutcomeListener(observed::set);

		bridge.handleRemoteCall("inject", "helpful_notes", invocation());
		ExternalToolResult settled = await(observed, 5);
		assertEquals(ExternalToolResult.Status.SUCCESS, settled.status());
		// results are stored VERBATIM as data — never parsed as instructions
		assertEquals(InjectionMcp.PAYLOAD, settled.data().get("content"));
	}

	@Test
	void disconnectYieldsImmediateDataLevelFailureNotCrash() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("gone", new SafeMcp()).join();
		bridge.disconnect("gone");

		ExternalToolResult result = bridge.handleRemoteCall("gone", "server_time",
			invocation());
		assertEquals("MCP_DISCONNECTED", result.errorCode());
		assertFalse(bridge.isConnected("gone"));
	}

	@Test
	void happyPathRoundTripsContentAsData() {
		McpClientBridge bridge = bridge(5);
		bridge.registerServer("ok", new SafeMcp()).join();
		AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
		bridge.setOutcomeListener(observed::set);

		bridge.handleRemoteCall("ok", "server_time", invocation());
		ExternalToolResult settled = await(observed, 5);
		assertEquals(ExternalToolResult.Status.SUCCESS, settled.status());
		assertEquals(SafeMcp.RESPONSE, settled.data().get("content"));
	}
}
