package dev.squire.server.automation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squire.common.protocol.ToolResult;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.tool.AuditLog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 G1/G3：长期自动化的生命周期与结构化编译。
 *
 * <p>"每天晚上开灯"必须是真正长期的——旧版默认两周后静默过期，那不是有效期而是
 * 一个会让玩家莫名其妙的 bug。启用开关也必须跨重启保留。</p>
 */
class AutomationLifecycleTest {

	private final UUID ownerId = UUID.randomUUID();
	private final UUID agentId = UUID.randomUUID();

	private AutomationEngine engine(Path dir) {
		return new AutomationEngine(
			(tool, args, owner, agent) -> ToolResult.success(UUID.randomUUID(), Map.of()),
			(owner, message) -> { },
			new AutomationEngine.WorldProbe() {
				@Override
				public boolean ownerOnline(UUID id) {
					return true;
				}

				@Override
				public java.util.Set<UUID> playersInRegion(String dimension,
						dev.squire.server.world.BoundedRegion region) {
					return java.util.Set.of();
				}

				@Override
				public long dayTime() {
					return 0;
				}
			},
			new TaskScheduler(), new AuditLog(),
			() -> dir.resolve("automations.json"));
	}

	private AutomationGraph nightlyGraph(String name) {
		var builder = AutomationGraph.builder(ownerId, agentId, name)
			.trigger(AutomationTrigger.atTimeOfDay(13000, 600));
		builder.node(AutomationNode.notify("lights"));
		return builder.build();
	}

	// ------------------------------------------------------------------ G3 生命周期

	@Test
	void recurringAutomationsDefaultToRunningIndefinitely() {
		AutomationGraph graph = nightlyGraph("nightly");
		assertEquals(AutomationGraph.NO_EXPIRY, graph.ttlTicks(),
			"'every night' must not quietly stop after two weeks");
		assertTrue(graph.runsIndefinitely());
		assertTrue(graph.isRecurring());
	}

	@Test
	void oneShotAutomationsMayStillCarryAnExplicitTtl() {
		var builder = AutomationGraph.builder(ownerId, agentId, "one-shot")
			.trigger(AutomationTrigger.manual())
			.ttlTicks(1200);
		builder.node(AutomationNode.notify("once"));
		AutomationGraph graph = builder.build();
		assertEquals(1200, graph.ttlTicks());
		assertFalse(graph.runsIndefinitely());
	}

	@Test
	void theEnabledSwitchSurvivesARestart(@TempDir Path dir) {
		AutomationEngine first = engine(dir);
		first.create(nightlyGraph("nightly"), 0);
		first.setEnabled(true);

		AutomationEngine second = engine(dir);
		assertEquals(1, second.load(100));
		assertTrue(second.isEnabled(),
			"an operator enables automation once, not after every restart");
	}

	/**
	 * 回归：{@code setEnabled} 会 save，而 save 在 load 之前会把一个空的图列表
	 * 写到文件上——也就是说"重启后先打开开关"会删光所有自动化。
	 */
	@Test
	void togglingTheSwitchBeforeRecoveryNeverWipesTheFile(@TempDir Path dir) {
		AutomationEngine first = engine(dir);
		first.setEnabled(true);
		first.create(nightlyGraph("keep-me-a"), 0);
		first.create(nightlyGraph("keep-me-b"), 0);

		AutomationEngine second = engine(dir);
		second.setEnabled(true); // BEFORE load — must not clobber
		assertEquals(2, second.load(100),
			"both automations survive an enable-before-load sequence");
	}

	@Test
	void legacyTwoWeekTtlIsMigratedToIndefiniteOnce(@TempDir Path dir) throws Exception {
		AutomationEngine first = engine(dir);
		var builder = AutomationGraph.builder(ownerId, agentId, "legacy-nightly")
			.trigger(AutomationTrigger.atTimeOfDay(13000, 600))
			.ttlTicks(AutomationGraph.LEGACY_DEFAULT_TTL_TICKS);
		builder.node(AutomationNode.notify("lights"));
		first.create(builder.build(), 0);
		first.save();

		// rewrite the file as the pre-G3 schema so load() takes the migration path
		Path file = dir.resolve("automations.json");
		String legacy = Files.readString(file, StandardCharsets.UTF_8)
			.replace("\"version\":2", "\"version\":1");
		Files.writeString(file, legacy, StandardCharsets.UTF_8);

		AutomationEngine second = engine(dir);
		assertEquals(1, second.load(100));
		AutomationGraph migrated = second.all().get(0);
		assertEquals(AutomationGraph.NO_EXPIRY, migrated.ttlTicks(),
			"an existing 'every night' order must not expire two weeks in");
	}

	@Test
	void aFutureSchemaStartsEmptyRatherThanDowngradingTheFile(@TempDir Path dir)
			throws Exception {
		Files.writeString(dir.resolve("automations.json"),
			"{\"version\":99,\"automations\":[]}", StandardCharsets.UTF_8);
		assertEquals(0, engine(dir).load(0));
	}

	// ------------------------------------------------------------------ G1 编译与白名单

	@Test
	void nightLightsTemplateCompilesToTwoIndefiniteRecurringGraphs() {
		var compiled = AutomationCompiler.compileNightLights(ownerId, agentId, "base",
			"minecraft:overworld", new net.minecraft.util.math.BlockPos(10, 64, 10), 8);

		assertTrue(compiled.ok(), compiled.rejection());
		assertEquals(2, compiled.graphs().size(), "one graph lights up, one darkens");
		assertTrue(compiled.writesWorld(), "flipping levers writes the world");
		for (AutomationGraph graph : compiled.graphs()) {
			assertEquals(AutomationGraph.NO_EXPIRY, graph.ttlTicks());
			assertEquals(AutomationTrigger.Kind.TIME, graph.trigger().kind());
			assertEquals("base.lights.set", graph.entryNode().text());
		}
		assertTrue(compiled.preview().contains("无限期"),
			"the preview states the validity: " + compiled.preview());
		assertTrue(compiled.preview().contains("命令方块"),
			"the preview promises no command blocks: " + compiled.preview());
	}

	@Test
	void aTemplateWithoutABaseIsRefusedInsteadOfGuessing() {
		var compiled = AutomationCompiler.compileNightLights(ownerId, agentId, "base",
			"minecraft:overworld", null, 8);
		assertFalse(compiled.ok());
		assertTrue(compiled.rejection().startsWith("NO_BASE_MEMORY"));
	}

	@Test
	void validationAcceptsTheTemplateItJustCompiled() {
		var compiled = AutomationCompiler.compileNightLights(ownerId, agentId, "base",
			"minecraft:overworld", new net.minecraft.util.math.BlockPos(0, 64, 0), 8);
		for (AutomationGraph graph : compiled.graphs()) {
			assertNull(AutomationCompiler.validate(graph, name -> true));
		}
	}

	@Test
	void automationsMayNotDriveToolsOutsideTheWhitelist() {
		var builder = AutomationGraph.builder(ownerId, agentId, "sneaky")
			.trigger(AutomationTrigger.atTimeOfDay(13000, 600));
		builder.node(AutomationNode.toolCall("minecraft.command.fill",
			Map.of("blockId", "minecraft:stone")));
		String problem = AutomationCompiler.validate(builder.build(), name -> true);
		assertNotNull(problem, "a long-term graph must not become a confirmation bypass");
		assertTrue(problem.startsWith("POLICY_DENIED"), problem);
	}

	@Test
	void automationsMayNotCallToolsThatDoNotExist() {
		var builder = AutomationGraph.builder(ownerId, agentId, "typo")
			.trigger(AutomationTrigger.atTimeOfDay(13000, 600));
		builder.node(AutomationNode.toolCall("base.lights.set", Map.of("on", true)));
		String problem = AutomationCompiler.validate(builder.build(), name -> false);
		assertNotNull(problem);
		assertTrue(problem.startsWith("TOOL_NOT_FOUND"), problem);
	}

	@Test
	void tooFrequentIntervalsAreRefused() {
		var builder = AutomationGraph.builder(ownerId, agentId, "spam")
			.trigger(AutomationTrigger.every(20));
		builder.node(AutomationNode.notify("tick"));
		String problem = AutomationCompiler.validate(builder.build(), name -> true);
		assertNotNull(problem);
		assertTrue(problem.contains("interval"), problem);
	}

	@Test
	void aCycleWithoutAWaitNodeIsRefusedButAPacedLoopIsFine() {
		var spin = AutomationGraph.builder(ownerId, agentId, "spin")
			.trigger(AutomationTrigger.atTimeOfDay(13000, 600));
		UUID a = spin.node(AutomationNode.notify("a"));
		UUID b = spin.node(AutomationNode.notify("b"));
		spin.edge(a, b);
		spin.edge(b, a);
		String problem = AutomationCompiler.validate(spin.build(), name -> true);
		assertNotNull(problem, "an unpaced loop burns the node budget every firing");
		assertTrue(problem.contains("cycle"), problem);

		var paced = AutomationGraph.builder(ownerId, agentId, "paced")
			.trigger(AutomationTrigger.atTimeOfDay(13000, 600));
		UUID first = paced.node(AutomationNode.notify("a"));
		UUID wait = paced.node(AutomationNode.waitTicks(100));
		paced.edge(first, wait);
		paced.edge(wait, first);
		assertNull(AutomationCompiler.validate(paced.build(), name -> true),
			"a loop paced by a WAIT node is a controlled loop");
	}

	@Test
	void onlyWhitelistedTriggerKindsAreAccepted() {
		assertTrue(AutomationCompiler.ALLOWED_TRIGGERS
			.contains(AutomationTrigger.Kind.TIME));
		assertFalse(AutomationCompiler.ALLOWED_TRIGGERS
			.contains(AutomationTrigger.Kind.TASK_EVENT),
			"task-event chaining is not exposed to player-created automations yet");
		List<String> writeTools = AutomationCompiler.ALLOWED_TOOLS.stream()
			.filter(name -> name.startsWith("minecraft.command.")
				|| name.startsWith("cbp.") || name.startsWith("worldedit."))
			.toList();
		assertTrue(writeTools.isEmpty(),
			"no high-risk world/command tool may be whitelisted: " + writeTools);
	}
}
