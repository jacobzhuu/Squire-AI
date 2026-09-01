package dev.squire.server.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 I2：服主用一个 JSON 文件部署 MCP 服务器，在飞的调用有归属和期限，
 * 反复失败的服务器会被熔断。
 */
class McpConfigAndCallLifecycleTest {

	@TempDir
	Path dir;

	private Path write(String json) throws Exception {
		Path file = dir.resolve("mcp.json");
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return file;
	}

	// ------------------------------------------------------------------ 配置

	@Test
	void operatorsCanDeployServersWithoutWritingJava() throws Exception {
		Path file = write("""
			{
			  "version": 1,
			  "servers": [
			    {"name": "factory", "transport": "HTTP", "url": "http://127.0.0.1:9/mcp",
			     "timeoutSeconds": 5, "allowlist": ["machine_status"],
			     "trustKey": "factory-trust"},
			    {"name": "local", "transport": "STDIO", "command": ["node", "s.js"]}
			  ]
			}
			""");
		var result = McpConfig.load(file);
		assertFalse(result.hasProblems(), String.valueOf(result.problems()));
		assertEquals(2, result.servers().size());

		var factory = result.servers().get(0);
		assertEquals(McpConfig.TransportKind.HTTP, factory.transport());
		assertEquals(5, factory.timeoutSeconds());
		assertEquals("factory-trust", factory.trustKey());
		assertTrue(factory.allows("machine_status"));
		assertFalse(factory.allows("delete_everything"),
			"the allowlist is what limits which tools get imported");
		assertTrue(factory.enabled(), "servers are enabled unless told otherwise");

		var local = result.servers().get(1);
		assertEquals(McpConfig.TransportKind.STDIO, local.transport());
		assertTrue(local.allows("anything"), "an empty allowlist means 'all tools'");
	}

	@Test
	void oneBadEntryIsSkippedWithAReasonAndTheRestStillLoad() throws Exception {
		Path file = write("""
			{
			  "version": 1,
			  "servers": [
			    {"name": "broken", "transport": "HTTP"},
			    {"name": "good", "transport": "STDIO", "command": ["node", "s.js"]}
			  ]
			}
			""");
		var result = McpConfig.load(file);
		assertEquals(1, result.servers().size(), "the healthy server still loads");
		assertEquals("good", result.servers().get(0).name());
		assertTrue(result.hasProblems());
		assertTrue(result.problems().get(0).contains("url"),
			"the operator is told what was wrong: " + result.problems().get(0));
	}

	@Test
	void duplicateNamesAreRefusedRatherThanShadowingEachOther() throws Exception {
		Path file = write("""
			{"version": 1, "servers": [
			  {"name": "dup", "transport": "STDIO", "command": ["a"]},
			  {"name": "dup", "transport": "STDIO", "command": ["b"]}
			]}
			""");
		var result = McpConfig.load(file);
		assertEquals(1, result.servers().size());
		assertTrue(result.problems().get(0).contains("duplicate"));
	}

	@Test
	void aFutureSchemaLoadsNoServersInsteadOfGuessing() throws Exception {
		var result = McpConfig.load(write("{\"version\": 99, \"servers\": []}"));
		assertTrue(result.servers().isEmpty());
		assertTrue(result.problems().get(0).contains("newer"));
	}

	@Test
	void brokenJsonDegradesTheFeatureInsteadOfThrowing() throws Exception {
		var result = McpConfig.load(write("{ this is not json"));
		assertTrue(result.servers().isEmpty());
		assertTrue(result.hasProblems());
	}

	@Test
	void aMissingFileIsNotAnError() {
		var result = McpConfig.load(dir.resolve("absent.json"));
		assertTrue(result.servers().isEmpty());
		assertFalse(result.hasProblems());
	}

	@Test
	void timeoutsAreClampedIntoASaneRange() throws Exception {
		var result = McpConfig.load(write("""
			{"version": 1, "servers": [
			  {"name": "slow", "transport": "STDIO", "command": ["a"],
			   "timeoutSeconds": 100000}
			]}
			"""));
		assertEquals(McpConfig.MAX_TIMEOUT_SECONDS,
			result.servers().get(0).timeoutSeconds());
	}

	/** 示例文件必须默认全部关闭：装上 mod 不该自动连出去。 */
	@Test
	void theShippedExampleIsDisabledByDefault() {
		assertFalse(McpConfig.exampleJson().contains("\"enabled\": true"));
		assertTrue(McpConfig.exampleJson().contains("\"enabled\": false"));
	}

	// ------------------------------------------------------------------ 在飞调用

	private static PendingExternalCallRegistry.PendingCall call(UUID id, String server,
			long issued, long deadline) {
		return new PendingExternalCallRegistry.PendingCall(id, UUID.randomUUID(),
			UUID.randomUUID(), null, null, server, "tool", issued, deadline);
	}

	@Test
	void anOverdueCallIsReportedExactlyOnce() {
		var registry = new PendingExternalCallRegistry();
		UUID id = UUID.randomUUID();
		registry.register(call(id, "s", 0, 100));

		assertTrue(registry.takeOverdue(50).isEmpty(), "not yet past the deadline");
		assertEquals(1, registry.takeOverdue(150).size());
		assertTrue(registry.takeOverdue(200).isEmpty(),
			"a timed-out call must not be reported twice");
		assertEquals(0, registry.inFlightCount());
	}

	@Test
	void completingACallTellsTheCallerWhoWasWaiting() {
		var registry = new PendingExternalCallRegistry();
		UUID id = UUID.randomUUID();
		registry.register(call(id, "factory", 0, 1000));

		var settled = registry.complete(id).orElseThrow();
		assertEquals("factory", settled.server());
		assertTrue(registry.complete(id).isEmpty(), "settling twice is not possible");
	}

	@Test
	void aDisconnectingServerReleasesAllItsInFlightCalls() {
		var registry = new PendingExternalCallRegistry();
		registry.register(call(UUID.randomUUID(), "a", 0, 1000));
		registry.register(call(UUID.randomUUID(), "a", 0, 1000));
		registry.register(call(UUID.randomUUID(), "b", 0, 1000));

		assertEquals(2, registry.takeAllForServer("a").size());
		assertEquals(1, registry.inFlightCount(), "server b is untouched");
	}

	@Test
	void theInFlightCapStopsARunawayServerFromEatingMemory() {
		var registry = new PendingExternalCallRegistry();
		for (int i = 0; i < PendingExternalCallRegistry.MAX_IN_FLIGHT; i++) {
			assertTrue(registry.register(call(UUID.randomUUID(), "s", 0, 10_000)));
		}
		assertFalse(registry.register(call(UUID.randomUUID(), "s", 0, 10_000)),
			"the cap must refuse rather than grow without bound");
	}

	// ------------------------------------------------------------------ 熔断

	@Test
	void repeatedFailuresOpenTheCircuitSoCallsFailFast() {
		var breaker = new McpCircuitBreaker();
		for (int i = 0; i < McpCircuitBreaker.FAILURE_THRESHOLD; i++) {
			assertTrue(breaker.allowCall("s", 0), "still closed at failure " + i);
			breaker.recordFailure("s", 0);
		}
		assertEquals(McpCircuitBreaker.State.OPEN, breaker.stateOf("s", 0));
		assertFalse(breaker.allowCall("s", 0),
			"a dead server must not cost a full timeout on every turn");
	}

	@Test
	void afterTheCooldownOneProbeIsAllowedAndSuccessClosesTheCircuit() {
		var breaker = new McpCircuitBreaker();
		for (int i = 0; i < McpCircuitBreaker.FAILURE_THRESHOLD; i++) {
			breaker.recordFailure("s", 0);
		}
		long later = McpCircuitBreaker.COOLDOWN_TICKS + 1;
		assertEquals(McpCircuitBreaker.State.HALF_OPEN, breaker.stateOf("s", later));
		assertTrue(breaker.allowCall("s", later), "one probe gets through");
		assertFalse(breaker.allowCall("s", later), "but only one at a time");

		breaker.recordSuccess("s");
		assertEquals(McpCircuitBreaker.State.CLOSED, breaker.stateOf("s", later));
		assertTrue(breaker.allowCall("s", later));
	}

	@Test
	void aFailedProbeRestartsTheCooldownInsteadOfReopeningTheFloodgates() {
		var breaker = new McpCircuitBreaker();
		for (int i = 0; i < McpCircuitBreaker.FAILURE_THRESHOLD; i++) {
			breaker.recordFailure("s", 0);
		}
		long probeTick = McpCircuitBreaker.COOLDOWN_TICKS + 1;
		assertTrue(breaker.allowCall("s", probeTick));
		breaker.recordFailure("s", probeTick);
		assertEquals(McpCircuitBreaker.State.OPEN, breaker.stateOf("s", probeTick + 1));
	}

	@Test
	void oneServersFailuresDoNotBreakAnother() {
		var breaker = new McpCircuitBreaker();
		for (int i = 0; i < McpCircuitBreaker.FAILURE_THRESHOLD; i++) {
			breaker.recordFailure("bad", 0);
		}
		assertFalse(breaker.allowCall("bad", 0));
		assertTrue(breaker.allowCall("good", 0), "circuits are per server");
	}
}
