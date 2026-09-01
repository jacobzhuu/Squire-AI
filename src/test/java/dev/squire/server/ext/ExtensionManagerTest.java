package dev.squire.server.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.squire.api.task.ExternalTaskInvocation;
import dev.squire.api.task.ExternalTaskExecutor;
import dev.squire.api.task.TaskTypeSpec;
import dev.squire.api.tool.ArgSpec;
import dev.squire.api.tool.ExternalTool;
import dev.squire.api.tool.ExternalToolResult;
import dev.squire.server.security.PermissionNodes;
import dev.squire.server.security.ToolTrust;
import dev.squire.server.tool.AgentPermission;
import dev.squire.server.tool.RiskLevel;
import dev.squire.server.tool.ToolDefinition;
import dev.squire.server.tool.ToolRegistry;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.task.TaskState;

/**
 * Extension import pipeline unit tests (spec sections 53-59): caps, fail-closed
 * classification and refusal-reporting — all without any Minecraft object.
 */
class ExtensionManagerTest {

	private final ToolRegistry registry = new ToolRegistry();
	private final TaskScheduler scheduler = new TaskScheduler();
	private final ExtensionManager ext = new ExtensionManager(registry, scheduler);

	private static ExternalTool readOnlyEcho() {
		return ExternalTool
			.builder("example:echo", inv -> ExternalToolResult.ok(inv.callId(),
				Map.of("echo", String.valueOf(inv.arguments().get("message")))))
			.description("echo")
			.arg(ArgSpec.requiredString("message", "text"))
			.readOnly(true)
			.risk("LOW")
			.build();
	}

	// ------------------------------------------------------------------ tool import

	@Test
	void importMapsFieldsAndClassifiesFailClosed() {
		var report = ext.registerTool(readOnlyEcho(), ToolTrust.UNTRUSTED, "mod:example");
		assertTrue(report.accepted(), report.reason());
		ToolDefinition def = registry.lookup("example:echo").orElseThrow();
		assertEquals(ToolTrust.UNTRUSTED, def.sourceTrust());
		assertEquals("mod:example", def.origin());
		assertEquals(AgentPermission.QUERY, def.permission()); // claimed read-only -> query class
		assertEquals(RiskLevel.LOW, def.risk());
		assertFalse(def.destructive());
	}

	@Test
	void unclaimedWriteShapeDefaultsToWriteClass() {
		ExternalTool tool = ExternalTool
			.builder("example:mystery", inv -> ExternalToolResult.ok(inv.callId()))
			.description("no read-only claim")
			.destructive(true)
			.risk("HIGH")
			.build();
		assertTrue(ext.registerTool(tool, ToolTrust.UNTRUSTED, "mod:x").accepted());
		ToolDefinition def = registry.lookup("example:mystery").orElseThrow();
		assertEquals(AgentPermission.WORLD_PLACE, def.permission());
		assertTrue(def.destructive());
		assertEquals(RiskLevel.HIGH, def.risk());
	}

	@Test
	void duplicateImportRefusedButReportedNotThrown() {
		ext.registerTool(readOnlyEcho(), ToolTrust.UNTRUSTED, "mod:a");
		var second = ext.registerTool(readOnlyEcho(), ToolTrust.TRUSTED_MOD, "mod:b");
		assertFalse(second.accepted());
		assertTrue(second.reason().contains("duplicate"));
		// first import wins; trust of the survivor unchanged
		assertEquals(ToolTrust.UNTRUSTED,
			registry.lookup("example:echo").orElseThrow().sourceTrust());
	}

	@Test
	void oversizedSchemaRefused() {
		ExternalTool.Builder b = ExternalTool.builder("example:huge",
			inv -> ExternalToolResult.ok(inv.callId())).description("huge");
		for (int i = 0; i < ExtensionManager.MAX_TOOL_ARGS + 1; i++) {
			b.arg(ArgSpec.requiredString("arg" + i, "filler"));
		}
		var report = ext.registerTool(b.build(), ToolTrust.TRUSTED_MOD, "mod:x");
		assertFalse(report.accepted());
		assertTrue(report.reason().contains("too many args"));
		assertTrue(registry.lookup("example:huge").isEmpty());
	}

	@Test
	void reservedNodeRefusedAndToolNotImported() {
		ExternalTool tool = ExternalTool
			.builder("example:sneaky", inv -> ExternalToolResult.ok(inv.callId()))
			.description("tries to steal a reserved node")
			.permissionNode("squire.admin")
			.readOnly(true)
			.build();
		var report = ext.registerTool(tool, ToolTrust.TRUSTED_MOD, "mod:x");
		assertFalse(report.accepted());
		assertTrue(registry.lookup("example:sneaky").isEmpty()); // fail-closed: no node, no tool
	}

	@Test
	void customNodeBecomesKnownAndStaysUngrantedByDefault() {
		String node = "exttest.node." + UUID.randomUUID().toString().substring(0, 8);
		ExternalTool tool = ExternalTool
			.builder("example:gated", inv -> ExternalToolResult.ok(inv.callId()))
			.description("gated")
			.permissionNode(node)
			.readOnly(true)
			.build();
		assertTrue(ext.registerTool(tool, ToolTrust.UNTRUSTED, "mod:x").accepted());
		assertTrue(PermissionNodes.isKnown(node));
		assertTrue(PermissionNodes.all().contains(node));
		assertNotEquals(node, "squire.use"); // sanity: distinct from core nodes
	}

	@Test
	void descriptionTruncatedToCap() {
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 3000; i++) {
			big.append('x');
		}
		ExternalTool tool = ExternalTool
			.builder("example:longdesc", inv -> ExternalToolResult.ok(inv.callId()))
			.description(big.toString())
			.readOnly(true)
			.build();
		assertTrue(ext.registerTool(tool, ToolTrust.UNTRUSTED, "mod:x").accepted());
		assertEquals(ExtensionManager.MAX_DESCRIPTION_CHARS,
			registry.lookup("example:longdesc").orElseThrow().description().length());
	}

	@Test
	void nullInputsProduceRefusalsWithoutThrowing() {
		assertFalse(ext.registerTool(null, ToolTrust.UNTRUSTED, "mod:x").accepted());
		assertFalse(ext.registerTool(readOnlyEcho(), ToolTrust.UNTRUSTED, null).accepted());
		assertNotNull(ext.reports());
		long refused = ext.reports().stream().filter(r -> !r.accepted()).count();
		assertTrue(refused >= 2);
	}

	// ------------------------------------------------------------------ task types

	@Test
	void externalTaskCompletesThroughVerifier() {
		var spec = new TaskTypeSpec("extt:ok", List.of(), 200L, "instant success");
		ExternalTaskExecutor executor = inv ->
			ExternalToolResult.ok(inv.taskId(), Map.of("done", true));
		assertTrue(ext.registerTaskType(spec, executor, "mod:t").accepted());

		UUID agentId = UUID.randomUUID();
		var taskOpt = ext.submitExternalTask(agentId, UUID.randomUUID(), "extt:ok",
			Map.of(), 1000L);
		assertTrue(taskOpt.isPresent());
		var task = taskOpt.get();

		tick(scheduler, 1000L, agentId); // READY -> RUNNING (provider runs in start)
		assertEquals(TaskState.RUNNING, task.state());
		tick(scheduler, 1001L, agentId); // WORK_DONE -> VERIFYING
		assertEquals(TaskState.VERIFYING, task.state());
		for (long t = 1002L; t <= 1015L && task.state() != TaskState.COMPLETED; t++) {
			tick(scheduler, t, agentId); // grace ticks then the verifier judges
		}
		assertEquals(TaskState.COMPLETED, task.state());
		assertTrue(ext.outcomeOf(task.taskId()).map(ExternalToolResult::isSuccess)
			.orElse(false));
	}

	@Test
	void runningTaskResolvesViaCompletionHandleFromAnotherThread()
			throws InterruptedException {
		AtomicReference<ExternalTaskInvocation> captured = new AtomicReference<>();
		var spec = new TaskTypeSpec("extt:async", List.of(), 400L, "async");
		ExternalTaskExecutor executor = inv -> {
			captured.set(inv);
			return ExternalToolResult.running(inv.taskId(), Map.of("started", true));
		};
		assertTrue(ext.registerTaskType(spec, executor, "mod:t").accepted());

		UUID agentId = UUID.randomUUID();
		var task = ext.submitExternalTask(agentId, UUID.randomUUID(), "extt:async",
			Map.of(), 2000L).orElseThrow();
		tick(scheduler, 2000L, agentId); // start -> RUNNING stored result
		assertEquals(TaskState.RUNNING, task.state());

		Thread worker = new Thread(() -> captured.get().completion()
			.complete(ExternalToolResult.ok(captured.get().taskId())));
		worker.start();
		worker.join(5000);

		boolean completed = false;
		for (long t = 2001L; t <= 2100L && !completed; t++) {
			tick(scheduler, t, agentId);
			completed = task.state() == TaskState.COMPLETED;
		}
		assertTrue(completed, "task should complete after async resolution");
	}

	@Test
	void failedExternalTaskFailsWithZeroRetries() {
		var spec = new TaskTypeSpec("extt:bad", List.of(), 200L, "always fails");
		ExternalTaskExecutor executor = inv ->
			ExternalToolResult.failed(inv.taskId(), "CUSTOM_FAIL", "nope");
		assertTrue(ext.registerTaskType(spec, executor, "mod:t").accepted());

		UUID agentId = UUID.randomUUID();
		var task = ext.submitExternalTask(agentId, UUID.randomUUID(), "extt:bad",
			Map.of(), 3000L).orElseThrow();
		tick(scheduler, 3000L, agentId);
		tick(scheduler, 3001L, agentId); // bridge reports FAILED -> no retries -> FAILED
		assertEquals(TaskState.FAILED, task.state());
		assertEquals("CUSTOM_FAIL", task.lastErrorCode().orElse("-"));
	}

	@Test
	void duplicateTaskTypeRefused() {
		var spec = new TaskTypeSpec("extt:dup", List.of(), 50L + 20, "dup");
		assertTrue(ext.registerTaskType(spec, inv -> ExternalToolResult.ok(inv.taskId()),
			"mod:a").accepted());
		var second = ext.registerTaskType(spec,
			inv -> ExternalToolResult.ok(inv.taskId()), "mod:b");
		assertFalse(second.accepted());
		assertTrue(second.reason().contains("duplicate"));
	}

	// ------------------------------------------------------------------ helpers

	private static void tick(TaskScheduler scheduler, long tick, UUID... agentIds) {
		for (UUID agentId : agentIds) {
			scheduler.tick(tick, id -> new dev.squire.server.task.TaskEvaluationContext() {
				@Override
				public long tick() {
					return tick;
				}

				@Override
				public UUID agentId() {
					return id;
				}

				@Override
				public dev.squire.api.body.AgentBody body() {
					return null;
				}
			});
		}
	}
}
