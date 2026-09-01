package dev.squire.server.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squire.common.errors.ErrorCode;
import dev.squire.common.errors.ErrorPayload;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.automation.AutomationEngine.Access;
import dev.squire.server.automation.AutomationEngine.NodeDispatcher;
import dev.squire.server.automation.AutomationEngine.Notifier;
import dev.squire.server.automation.AutomationEngine.WorldProbe;
import dev.squire.server.task.TaskExecutor;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.tool.AuditLog;
import dev.squire.server.world.BoundedRegion;

/**
 * AutomationGraph engine tests (spec §49): triggers, conditions, node walking,
 * ownership, TTL, default-off and restart recovery. All server-thread semantics are
 * exercised synchronously through {@link AutomationEngine#tick}.
 */
class AutomationEngineTest {
	private final UUID ownerId = UUID.randomUUID();
	private final UUID agentId = UUID.randomUUID();

	private final List<String> dispatched = new ArrayList<>();
	private final List<String> notifications = new ArrayList<>();
	/** Mutable world facts the tests control directly. */
	private long dayTime = 0;
	private boolean ownerOnline = false;

	private final NodeDispatcher dispatcher = (toolName, args, o, a) -> {
		dispatched.add(toolName + args);
		return ToolResult.success(UUID.randomUUID(), Map.of("ok", true));
	};
	private final Notifier notifier = (o, message) -> notifications.add(message);
	private final WorldProbe probe = new WorldProbe() {
		@Override
		public boolean ownerOnline(UUID id) {
			return ownerOnline;
		}

		@Override
		public Set<UUID> playersInRegion(String dimension, BoundedRegion region) {
			return Set.of(); // covered indirectly via conditions tests below
		}

		@Override
		public long dayTime() {
			return dayTime;
		}
	};

	private final TaskScheduler scheduler = new TaskScheduler();
	private final AuditLog audit = new AuditLog();
	private final Path nullFile = null;

	private AutomationEngine engine() {
		return new AutomationEngine(dispatcher, notifier, probe, scheduler, audit,
			() -> nullFile);
	}

	private AutomationGraph timeGraph(String name, long timeOfDay,
			int conditionCount) {
		var b = AutomationGraph.builder(ownerId, agentId, name)
			.trigger(AutomationTrigger.atTimeOfDay(timeOfDay, 400));
		UUID call = b.node(AutomationNode.toolCall("test.light", Map.of("on", true)));
		UUID note = b.node(AutomationNode.notify("lights on"));
		b.edge(call, note);
		for (int i = 0; i < conditionCount; i++) {
			b.condition(i == 0
				? AutomationCondition.ownerOnline()
				: AutomationCondition.timeOfDayAfter(0));
		}
		return b.build();
	}

	// ------------------------------------------------------------------ §94 default

	@Test
	void disabledByDefaultNothingFires() {
		AutomationEngine engine = engine();
		assertFalse(engine.isEnabled());
		engine.create(timeGraph("night-lights", 13000, 0), 100);
		dayTime = 13000; // dead center of the window
		engine.tick(101);
		assertTrue(dispatched.isEmpty(), "disabled engine must not fire");
		engine.setEnabled(true);
		engine.tick(102);
		assertEquals(1, dispatched.size());
	}

	// ------------------------------------------------------------------ trigger kinds

	@Test
	void timeTriggerFiresOncePerDayInsideWindow() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		engine.create(timeGraph("night-lights", 13000, 0), 0);
		dayTime = 12000;
		engine.tick(10);
		assertTrue(dispatched.isEmpty(), "outside window");
		dayTime = 13100;
		engine.tick(11);
		assertEquals(1, dispatched.size());
		// second tick walks the NEXT node (notify), not a new firing
		engine.tick(12);
		assertEquals(1, dispatched.size());
		assertEquals(List.of("[squire:night-lights] lights on"), notifications);
		// still same day/window: no re-fire
		engine.tick(13);
		assertEquals(1, dispatched.size());
		// next day fires again
		dayTime += 24000;
		engine.tick(24010);
		assertEquals(2, dispatched.size());
	}

	@Test
	void intervalTriggerRespectsSpacing() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var b = AutomationGraph.builder(ownerId, agentId, "heartbeat")
			.trigger(AutomationTrigger.every(100));
		UUID call = b.node(AutomationNode.notify("beat"));
		b.edge(call, b.node(AutomationNode.waitTicks(1)));
		engine.create(b.build(), 0);
		engine.tick(99);
		assertTrue(notifications.isEmpty());
		engine.tick(100);
		assertEquals(List.of("[squire:heartbeat] beat"), notifications);
		engine.tick(150);
		assertEquals(1, notifications.size(), "interval not yet elapsed");
		engine.tick(200);
		assertEquals(2, notifications.size());
	}

	@Test
	void ownerOnlineEdgeTriggerFiresOnTransitionOnly() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var b = AutomationGraph.builder(ownerId, agentId, "greeting")
			.trigger(AutomationTrigger.ownerOnline());
		UUID note = b.node(AutomationNode.notify("welcome back"));
		b.edge(note, b.node(AutomationNode.waitTicks(1)));
		engine.create(b.build(), 0);
		engine.tick(1);
		assertTrue(notifications.isEmpty(), "offline -> nothing");
		ownerOnline = true;
		engine.tick(2);
		assertEquals(1, notifications.size(), "online transition fires");
		engine.tick(3);
		assertEquals(1, notifications.size(), "still online: no re-fire");
	}

	// ------------------------------------------------------------------ conditions

	@Test
	void conditionsGateTheFiring() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		engine.create(timeGraph("guarded", 13000, 1), 0); // requires owner online
		dayTime = 13100;
		engine.tick(10);
		assertTrue(dispatched.isEmpty(), "condition false: no fire");
		ownerOnline = true;
		engine.tick(11);
		assertEquals(1, dispatched.size());
	}

	// ------------------------------------------------------------------ node walk

	@Test
	void branchPicksEdgesByConditionVerdict() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var b = AutomationGraph.builder(ownerId, agentId, "router")
			.trigger(AutomationTrigger.manual())
			.condition(AutomationCondition.ownerOnline());
		UUID branch = b.node(AutomationNode.branch(0));
		UUID onlinePath = b.node(AutomationNode.notify("online-path"));
		UUID offlinePath = b.node(AutomationNode.notify("offline-path"));
		b.edgeBranchTrue(branch, onlinePath);
		b.edgeBranchFalse(branch, offlinePath);
		var g = engine.create(b.build(), 0);

		ownerOnline = true;
		engine.fire(g.id(), ownerId, 5);
		engine.tick(6);
		engine.tick(7); // second tick delivers the chosen branch node
		assertEquals(List.of("[squire:router] online-path"), notifications);

		ownerOnline = false;
		engine.fire(g.id(), ownerId, 7);
		engine.tick(8);
		engine.tick(9);
		assertEquals(2, notifications.size());
		assertTrue(notifications.get(1).endsWith("offline-path"));
	}

	@Test
	void waitNodeParksTheCursorUntilTick() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var b = AutomationGraph.builder(ownerId, agentId, "delayed")
			.trigger(AutomationTrigger.manual());
		UUID wait = b.node(AutomationNode.waitTicks(50));
		UUID note = b.node(AutomationNode.notify("after-wait"));
		b.edge(wait, note);
		var g = engine.create(b.build(), 0);
		engine.fire(g.id(), ownerId, 10);
		engine.tick(11);
		assertTrue(notifications.isEmpty(), "must still be waiting");
		engine.tick(60);
		assertTrue(notifications.isEmpty(), "wait started at 11 -> done at 61");
		engine.tick(61);
		assertEquals(1, notifications.size());
	}

	@Test
	void failedToolCallErrorsTheGraphAndResumeReactivates() {
		AutomationEngine engine = engine();
		NodeDispatcher failing = (name, args, o, a) -> ToolResult.failed(
			UUID.randomUUID(), ErrorPayload.of(ErrorCode.INTERNAL_ERROR, "boom"));
		AutomationEngine failEngine =
			new AutomationEngine(failing, notifier, probe, scheduler, audit,
				() -> nullFile);
		failEngine.setEnabled(true);
		var g = failEngine.create(timeGraph("breaker", 13000, 0), 0);
		dayTime = 13100;
		failEngine.tick(10);
		assertEquals(dev.squire.server.automation.AutomationGraph.State.ERROR,
			g.state());
		assertNotNull(g.errorCode());
		assertTrue(notifications.isEmpty(), "chain stopped at the failure");

		Access resumed = failEngine.resume(g.id(), ownerId, false);
		assertTrue(resumed.ok());
		assertNull(g.errorCode());
		assertEquals(dev.squire.server.automation.AutomationGraph.State.ACTIVE,
			g.state());
	}

	@Test
	void createTaskNodeSubmitsThroughScheduler() {
		List<String> started = new ArrayList<>();
		scheduler.register(new TaskExecutor() {
			@Override
			public String type() {
				return "test:auto";
			}

			@Override
			public void start(dev.squire.server.task.Task task) {
				started.add(task.goalDescription());
			}

			@Override
			public StepOutcome tick(dev.squire.server.task.Task task, long tick) {
				return StepOutcome.WORK_DONE;
			}

			@Override
			public void cancel(dev.squire.server.task.Task task) {
			}
		});
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var b = AutomationGraph.builder(ownerId, agentId, "spawner")
			.trigger(AutomationTrigger.manual())
			.ttlTicks(0);
		UUID create = b.node(AutomationNode.createTask("test:auto", Map.of()));
		b.edge(create, b.node(AutomationNode.notify("spawned")));
		var g = engine.create(b.build(), 0);
		engine.fire(g.id(), ownerId, 3);
		engine.tick(4);
		assertTrue(scheduler.liveCount() >= 1 || !started.isEmpty(),
			"task submitted");
		engine.tick(5); // next node after CREATE_TASK
		assertEquals(1, notifications.size(), "chain continued past CREATE_TASK");

		// unknown task type errors instead of submitting into the void
		var bad = AutomationGraph.builder(ownerId, agentId, "bad-spawner")
			.trigger(AutomationTrigger.manual());
		UUID badCreate = bad.node(AutomationNode.createTask("no:such", Map.of()));
		bad.edge(badCreate, bad.node(AutomationNode.notify("never")));
		var bg = engine.create(bad.build(), 5);
		engine.fire(bg.id(), ownerId, 6);
		engine.tick(7);
		assertEquals(dev.squire.server.automation.AutomationGraph.State.ERROR,
			bg.state());
		assertEquals(1, notifications.size(), "no extra notification");
	}

	// ------------------------------------------------------------------ TTL & caps

	@Test
	void ttlExpiryStopsTriggers() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var b = AutomationGraph.builder(ownerId, agentId, "short-lived")
			.trigger(AutomationTrigger.every(20))
			.ttlTicks(50);
		UUID note = b.node(AutomationNode.notify("tick"));
		b.edge(note, b.node(AutomationNode.waitTicks(1)));
		var g = engine.create(b.build(), 0);
		engine.tick(30);
		assertEquals(1, notifications.size());
		assertEquals(dev.squire.server.automation.AutomationGraph.State.ACTIVE,
			g.state());
		engine.tick(51); // created 0 + ttl 50 < 51
		assertEquals(dev.squire.server.automation.AutomationGraph.State.EXPIRED,
			g.state());
		engine.tick(80);
		assertEquals(1, notifications.size(), "expired graphs stay quiet");
	}

	@Test
	void perOwnerCapRefusesExtraGraphs() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		for (int i = 0; i < AutomationEngine.MAX_PER_OWNER; i++) {
			assertNotNull(engine.create(timeGraph("cap" + i, 1000 + i, 0), i));
		}
		assertNull(engine.create(timeGraph("over-cap", 9999, 0),
			AutomationEngine.MAX_PER_OWNER));
	}

	// ------------------------------------------------------------------ ownership

	@Test
	void nonOwnersCannotControlAutomations() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var g = engine.create(timeGraph("mine", 13000, 0), 0);
		UUID stranger = UUID.randomUUID();

		assertFalse(engine.pause(g.id(), stranger, false).ok());
		assertFalse(engine.resume(g.id(), stranger, false).ok());
		assertFalse(engine.remove(g.id(), stranger, false).ok());

		assertTrue(engine.pause(g.id(), ownerId, false).ok());
		assertEquals(dev.squire.server.automation.AutomationGraph.State.PAUSED,
			g.state());
		dayTime = 13100;
		engine.tick(20);
		assertTrue(dispatched.isEmpty(), "paused graphs don't fire");

		assertTrue(engine.resume(g.id(), ownerId, false).ok());
		engine.tick(21);
		assertEquals(1, dispatched.size());

		assertTrue(engine.remove(g.id(), stranger, true).ok(), "admin may remove");
		assertTrue(engine.get(g.id()).isEmpty());
	}

	@Test
	void manualFireIsOwnerOnly() {
		AutomationEngine engine = engine();
		engine.setEnabled(true);
		var g = engine.create(timeGraph("manual-only", 13000, 0), 0);
		assertFalse(engine.fire(g.id(), UUID.randomUUID(), 1).ok());
		assertTrue(dispatched.isEmpty());
		assertTrue(engine.fire(g.id(), ownerId, 1).ok());
		engine.tick(2);
		assertEquals(1, dispatched.size());
	}

	// ------------------------------------------------------------------ recovery

	@Test
	void restartRecoveryRestoresGraphsAndCursor(@TempDir Path dir) {
		AutomationEngine first = engine(dir);
		first.setEnabled(true);
		var active = first.create(timeGraph("recover-active", 13000, 0), 0);
		var pb = AutomationGraph.builder(ownerId, agentId, "recover-paused")
			.trigger(AutomationTrigger.every(100))
			.ttlTicks(0);
		UUID beat = pb.node(AutomationNode.notify("beat"));
		pb.edge(beat, pb.node(AutomationNode.waitTicks(1)));
		var paused = first.create(pb.build(), 0);
		first.pause(paused.id(), ownerId, false);
		dayTime = 13100;
		first.tick(1); // ACTIVE fires once (dispatch recorded, cursor advances)
		int dispatchedBefore = dispatched.size();
		assertTrue(dispatchedBefore >= 1);
		first.save();

		// fresh engine over the same file: restart recovery (§49)
		dispatched.clear();
		AutomationEngine recovered = engine(dir);
		recovered.setEnabled(true);
		assertEquals(2, recovered.load(1000));
		assertTrue(recovered.get(active.id()).isPresent());
		var loadedPaused = recovered.get(paused.id()).orElseThrow();
		assertEquals(dev.squire.server.automation.AutomationGraph.State.PAUSED,
			loadedPaused.state(), "PAUSED stays paused across restarts");

		dayTime = 13100;
		recovered.tick(1001);
		assertTrue(dispatched.isEmpty(),
			"already-fired today: lastFiredDay survives the restart");
		dayTime += 24000;
		recovered.tick(24001);
		assertEquals(1, dispatched.size(),
			"AUTOMATION resumes triggering the next day after recovery");
	}

	private AutomationEngine engine(Path dir) {
		return new AutomationEngine(dispatcher, notifier, probe, scheduler, audit,
			() -> dir.resolve("automations.json"));
	}
}
