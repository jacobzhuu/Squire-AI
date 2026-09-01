package dev.squire.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;

import dev.squire.common.errors.ErrorCode;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;
import dev.squire.example.ExampleSquireProvider;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.ext.SensorRegistry;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.security.PermissionNodes;
import dev.squire.server.tool.CallerIdentity;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

/**
 * M4 extension-platform GameTests (spec §88 DoD): third-party example tools register
 * through the {@code squire} entrypoint and dispatch WITHOUT any core change; the
 * untrusted privileged example tool is DENIED; a custom permission node gates an
 * extension tool fail-closed until granted; extension sensors emit bounded lines;
 * an externally-contributed task type runs through the real scheduler to COMPLETED.
 */
public final class M4ExtensionGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	// gametest code lives in the MAIN source set where JUnit is absent: assert with
	// plain checks that throw AssertionError into the framework (TestContext has no
	// assertEquals)
	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(
				message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		SquireRuntime rt = SquireRuntime.get();
		if (rt.killswitch().isActive()) {
			rt.setKillswitch(false);
		}
		return rt;
	}

	private static ToolResult dispatch(SquireRuntime rt, ServerWorld world,
			AvatarEntity avatar, UUID senderId, String toolName,
			Map<String, Object> args) {
		var ctx = new dev.squire.server.tool.ToolExecutionContext() {
			@Override
			public dev.squire.api.body.AgentBody body() {
				return avatar;
			}

			@Override
			public AvatarEntity avatar() {
				return avatar;
			}

			@Override
			public net.minecraft.server.MinecraftServer server() {
				return world.getServer();
			}

			@Override
			public UUID requesterId() {
				return senderId;
			}

			@Override
			public long tick() {
				return world.getTime();
			}
		};
		return rt.gateway().dispatch(new ToolCall(UUID.randomUUID(), toolName, args),
			CallerIdentity.model(senderId, avatar.agentId()), ctx,
			rt.capabilitiesOf(avatar.agentId()));
	}

	private static void pollUntil(TestContext context, BooleanSupplier condition,
			int startTick, int intervalTicks, Runnable onSuccess, AtomicBoolean done) {
		if (done.get()) {
			return;
		}
		context.runAtTick(startTick, () -> {
			if (done.get()) {
				return;
			}
			if (condition.getAsBoolean()) {
				done.set(true);
				onSuccess.run();
			} else if (startTick + intervalTicks < 5900) {
				pollUntil(context, condition, startTick + intervalTicks, intervalTicks,
					onSuccess, done);
			}
		});
	}

	private interface OwnerSetup {
		void run(SquireRuntime rt, ServerWorld world, ServerPlayerEntity owner,
				AvatarEntity avatar);
	}

	/** Shared arrange step: spawn owner, summon avatar, park it. */
	private static void withOwner(TestContext context, int atTick, String name,
			OwnerSetup body) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, name);
		context.runAtTick(atTick, () -> {
			TestSupport.clearHostilesNear(world,
				context.getAbsolutePos(net.minecraft.util.math.BlockPos.ORIGIN), 48.0);
			world.spawnEntity(owner); // NodeChecker/OwnerVerifier resolve spawned players
			AvatarEntity avatar = rt.summonFor(owner);
			avatar.setStayMode();
			body.run(rt, world, owner, avatar);
		});
	}

	// ------------------------------------------------------------------ §88 line 1

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m4-echo")
	public void exampleModToolCallableWithoutCoreChanges(TestContext context) {
		withOwner(context, 5, "m4-echo-owner", (rt, world, owner, avatar) -> {
			context.assertTrue(
				rt.toolRegistry().lookup("example:echo").isPresent(),
				"example:echo must be imported from the squire entrypoint");
			ToolResult result = dispatch(rt, world, avatar, owner.getUuid(),
				"example:echo", Map.of("message", "hi"));
			context.assertTrue(result.isSuccess(),
				"read-only external tool must execute: " + result.errorOrNull());
			assertEquals("hi", result.data().get("echo"), "echo payload");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ §55/§88 trust

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m4-trust")
	public void untrustedPrivilegedExampleToolDenied(TestContext context) {
		ExampleSquireProvider.DANGEROUS_CALLS.set(0);
		withOwner(context, 5, "m4-trust-owner", (rt, world, owner, avatar) -> {
			context.assertTrue(
				rt.toolRegistry().lookup("example:dangerous_write").isPresent(),
				"tool is registered and model-visible");
			ToolResult result = dispatch(rt, world, avatar, owner.getUuid(),
				"example:dangerous_write", Map.of());
			context.assertTrue(result.errorOrNull().isPresent()
				&& result.errorOrNull().get().code() == ErrorCode.POLICY_DENIED,
				"untrusted privileged tool must be POLICY_DENIED, got " + result);
			assertEquals(0, ExampleSquireProvider.DANGEROUS_CALLS.get(),
				"handler must never run while its provider is untrusted");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ custom node

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m4-node")
	public void customNodeGatesExtensionToolUntilGranted(TestContext context) {
		String node = "exampletools.gated.echo";
		withOwner(context, 5, "m4-node-owner", (rt, world, owner, avatar) -> {
			context.assertTrue(PermissionNodes.isKnown(node),
				"extension node auto-registered known");
			ToolResult denied = dispatch(rt, world, avatar, owner.getUuid(),
				"example:gated_echo", Map.of("message", "x"));
			context.assertTrue(denied.errorOrNull().orElseThrow().code()
				== ErrorCode.PERMISSION_DENIED,
				"custom nodes start UNGRANTED for everyone (§94)");
			rt.permissions().grant(owner.getUuid(), node);
			ToolResult allowed = dispatch(rt, world, avatar, owner.getUuid(),
				"example:gated_echo", Map.of("message", "granted"));
			context.assertTrue(allowed.isSuccess()
				&& "granted".equals(allowed.data().get("echo")),
				"granted node unlocks the tool: " + allowed.errorOrNull());
			rt.permissions().revoke(owner.getUuid(), node);
			context.complete();
		});
	}

	// ------------------------------------------------------------------ sensors

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m4-sensor")
	public void extensionSensorProducesBoundedLine(TestContext context) {
		withOwner(context, 5, "m4-sensor-owner", (rt, world, owner, avatar) -> {
			SensorRegistry sensors = rt.extensions().sensors();
			boolean found = sensors.all().stream()
				.anyMatch(e -> e.sensor().definition().id().equals("example:heartbeat"));
			context.assertTrue(found, "entrypoint sensor must be registered");

			var lines = sensors.observe(avatar.agentId(), owner.getUuid(),
				world.getTime());
			String line = lines.stream()
				.filter(l -> l.startsWith("[sensor example:heartbeat]"))
				.findFirst().orElse(null);
			context.assertTrue(line != null, "heartbeat line present: " + lines);
			context.assertTrue(line.contains("tick="), line);
			int budget = sensors.all().stream()
				.filter(e -> e.sensor().definition().id().equals("example:heartbeat"))
				.findFirst().orElseThrow()
				.sensor().definition().serializationBudget();
			context.assertTrue(line.length() <= budget,
				"line " + line.length() + " exceeds budget " + budget);
			context.complete();
		});
	}

	// ------------------------------------------------------------------ task types

	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-m4-task")
	public void externalTaskTypeRunsToCompletion(TestContext context) {
		SquireRuntime rtHolder = runtime(context);
		AtomicBoolean checked = new AtomicBoolean(false);
		withOwner(context, 5, "m4-task-owner", (rt, world, owner, avatar) -> {
			context.assertTrue(rt.extensions().taskType("example:pause").isPresent(),
				"external task type registered via entrypoint");
			var submitted = rt.extensions().submitExternalTask(avatar.agentId(),
				owner.getUuid(), "example:pause", Map.of(), world.getTime());
			context.assertTrue(submitted.isPresent(), "submission accepted");

			pollUntil(context,
				() -> rt.scheduler().finishedTasks().stream().anyMatch(t ->
					t.taskId().equals(submitted.get().taskId())
						&& t.state() == dev.squire.server.task.TaskState.COMPLETED),
				40, 10,
				() -> {
					var task = rt.scheduler().finishedTasks().stream()
						.filter(t -> t.taskId().equals(submitted.get().taskId()))
						.findFirst().orElseThrow();
					assertEquals(dev.squire.server.task.TaskState.COMPLETED,
						task.state(), "verifier completed the external task");
					context.assertTrue(rt.extensions()
						.outcomeOf(task.taskId())
						.map(dev.squire.api.tool.ExternalToolResult::isSuccess)
						.orElse(false), "bridge recorded the provider outcome");
					checked.set(true);
					context.complete();
				}, checked);
		});

		// hard diagnostic stop: if the poll never succeeded, say WHY before failing
		context.runAtTick(560, () -> {
			if (checked.get()) {
				return;
			}
			context.assertTrue(false, "external task never completed; finished="
				+ rtHolder.scheduler().finishedTasks());
		});
	}
}
