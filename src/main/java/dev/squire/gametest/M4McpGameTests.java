package dev.squire.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;

import dev.squire.api.tool.ExternalToolResult;
import dev.squire.common.errors.ErrorCode;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.mcp.mock.MockMcpServers.InjectionMcp;
import dev.squire.server.mcp.mock.MockMcpServers.MaliciousMcp;
import dev.squire.server.mcp.mock.MockMcpServers.SafeMcp;
import dev.squire.server.mcp.mock.MockMcpServers.SlowMcp;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.tool.CallerIdentity;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * M4 MCP GameTests against the REAL runtime wiring (spec §88 DoD): an untrusted MCP
 * write tool is denied by the same gateway pipeline; a timing-out remote call never
 * blocks the server thread and disconnects degrade to data-level failures; injected
 * result text stays inert DATA and cannot change permissions.
 */
public final class M4McpGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

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

	private interface Body {
		void run(SquireRuntime rt, ServerWorld world, FakePlayer owner,
				AvatarEntity avatar);
	}

	private static void withOwner(TestContext context, int atTick, String name,
			Body body) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, name);
		context.runAtTick(atTick, () -> {
			TestSupport.clearHostilesNear(world,
				context.getAbsolutePos(net.minecraft.util.math.BlockPos.ORIGIN), 48.0);
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			avatar.setStayMode();
			body.run(rt, world, owner, avatar);
		});
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(
				message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	// ---------------------------------------------------------- §88: untrusted write

	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-m4-mcp-write")
	public void untrustedMcpWriteToolDeniedThroughGateway(TestContext context) {
		withOwner(context, 5, "m4-mcp-write-owner", (rt, world, owner, avatar) -> {
			rt.mcp().registerServer("malicious-mcp", new MaliciousMcp());
			AtomicBoolean checked = new AtomicBoolean(false);
			pollUntil(context,
				() -> rt.toolRegistry().lookup("malicious-mcp:"
					+ MaliciousMcp.WRITE_TOOL).isPresent(),
				30, 10, () -> checked.set(true), checked);
			// hard stop with diagnostics
			context.runAtTick(200, () -> {
				if (!checked.get()) {
					context.assertTrue(false, "write tool was never imported");
					return;
				}
				// oversized schema must have been refused entirely
				context.assertTrue(rt.toolRegistry()
					.lookup("malicious-mcp:" + MaliciousMcp.OVERSIZED_TOOL).isEmpty(),
					"oversized MCP schema must refuse the whole tool");
				// the ownership check (stage 5c) resolves the avatar through the
				// registry — if concurrent batches left a hostile mob that killed our
				// body, re-summon so we test POLICY gating, not environment noise.
				AvatarEntity live = avatar.alive() ? avatar : rt.summonFor(owner);
				ToolResult blocked = dispatch(rt, world, live, owner.getUuid(),
					"malicious-mcp:" + MaliciousMcp.WRITE_TOOL, Map.of());
				context.assertTrue(blocked.errorOrNull().isPresent()
					&& blocked.errorOrNull().get().code() == ErrorCode.POLICY_DENIED,
					"untrusted MCP write must be POLICY_DENIED, got " + blocked);
				assertEquals(ToolResult.Status.FAILED, blocked.status(), "status");
				context.complete();
			});
		});
	}

	// --------------------------------------------- §88: timeout non-blocking + close

	/**
	 * The gametest server ticks far faster than wall-clock 20tps, so the production
	 * 10s real-time orTimeout cannot be awaited in game ticks (flaky by design).
	 * Settlement of the TIMEOUT path is proven by
	 * {@code McpStackTest#remoteCallReturnsRunningImmediatelyThenTimesOut} with a
	 * shortened bridge timeout; here we prove the SAME deferred-application pipeline
	 * deterministically: dispatch returns RUNNING on the same tick, the remote future
	 * fails out-of-band, the outcome lands later via AsyncBridge as pure DATA, and a
	 * disconnected server degrades to an immediate data-level failure.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-m4-mcp-timeout")
	public void mcpTimeoutNeverBlocksThreadAndDisconnectDegrades(TestContext context) {
		withOwner(context, 5, "m4-mcp-slow-owner", (rt, world, owner, avatar) -> {
			SlowMcp slow = new SlowMcp();
			rt.mcp().registerServer("slow-mcp", slow);
			AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
			rt.mcp().setOutcomeListener(observed::set);
			AtomicBoolean settled = new AtomicBoolean(false);

			context.runAtTick(30, () -> {
				context.assertTrue(
					rt.toolRegistry().lookup("slow-mcp:endless_query").isPresent(),
					"slow tool imported");
				long before = world.getTime();
				ToolResult running = dispatch(rt, world, avatar, owner.getUuid(),
					"slow-mcp:endless_query", Map.of());
				long after = world.getTime();
				// THE guarantee: the call returned RUNNING on the SAME server tick
				assertEquals(ToolResult.Status.RUNNING, running.status(), "dispatch");
				assertEquals(before, after, "server tick advanced during dispatch");
				// settle the pending remote future from outside — exactly what a
				// timeout/transport failure does, minus the wall clock
				assertTrue(slow.lastCall != null, "pending future captured");
				slow.lastCall.completeExceptionally(
					new java.io.IOException("remote died mid-call"));
			});

			pollUntil(context,
				() -> observed.get() != null && observed.get().status()
					== ExternalToolResult.Status.FAILED,
				50, 10, () -> settled.set(true), settled);

			context.runAtTick(200, () -> {
				assertTrue(settled.get(),
					"deferred failure never applied, got " + observed.get());
				// the guarantee under test needs A live body, not this exact entity:
				// long-running suites can lose an avatar to environment noise
				dev.squire.server.body.avatar.AvatarEntity live =
					avatar.alive() ? avatar : rt.summonFor(owner);
				// disconnect mid-life: subsequent calls fail as DATA, nothing crashes
				rt.mcp().disconnect("slow-mcp");
				ToolResult afterClose = dispatch(rt, world, live, owner.getUuid(),
					"slow-mcp:endless_query", Map.of());
				assertEquals(ToolResult.Status.FAILED, afterClose.status(), "post-close");
				String msg = afterClose.errorOrNull().map(Object::toString).orElse("");
				assertTrue(msg.contains("not connected"),
					"post-close failure should name the disconnected server, got " + msg);
				context.complete();
			});
		});
	}

	// --------------------------------------------------- §88: injection changes nada

	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-m4-mcp-inject")
	public void injectionResultCannotChangePermissions(TestContext context) {
		withOwner(context, 5, "m4-mcp-inject-owner", (rt, world, owner, avatar) -> {
			rt.setWorldEditEnabled(false); // explicit baseline for this test
			rt.mcp().registerServer("inject-mcp", new InjectionMcp());
			AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
			rt.mcp().setOutcomeListener(observed::set);

			context.runAtTick(30, () -> dispatch(rt, world, avatar, owner.getUuid(),
				"inject-mcp:helpful_notes", Map.of()));

			AtomicBoolean settled = new AtomicBoolean(false);
			pollUntil(context,
				() -> observed.get() != null
					&& observed.get().status() == ExternalToolResult.Status.SUCCESS,
				50, 10, () -> settled.set(true), settled);

			context.runAtTick(150, () -> {
				context.assertTrue(settled.get(), "injection call never settled");
				Object content = observed.get() == null ? null
					: observed.get().data().get("content");
				// the hostile text landed VERBATIM as inert data
				assertEquals(InjectionMcp.PAYLOAD, content == null ? null
					: String.valueOf(content), "payload stored as data");
				// ...and changed NOTHING about permissions
				context.assertTrue(!rt.isWorldEditEnabled(),
					"injected text must not enable worldedit");
				context.assertTrue(!rt.permissions().has(owner,
					dev.squire.server.security.PermissionNodes.WORLD_EDIT),
					"injected text must not grant nodes");
				context.assertTrue(!rt.killswitch().isActive(), "killswitch intact");
				context.complete();
			});
		});
	}

	// ------------------------------------------------------------ safe round trip

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m4-mcp-safe")
	public void safeMcpToolRoundTripsThroughGateway(TestContext context) {
		withOwner(context, 5, "m4-mcp-safe-owner", (rt, world, owner, avatar) -> {
			rt.mcp().registerServer("safe-mcp", new SafeMcp());
			AtomicReference<ExternalToolResult> observed = new AtomicReference<>();
			rt.mcp().setOutcomeListener(observed::set);

			context.runAtTick(30, () -> {
				context.assertTrue(
					rt.toolRegistry().lookup("safe-mcp:server_time").isPresent(),
					"safe tool imported");
				dispatch(rt, world, avatar, owner.getUuid(), "safe-mcp:server_time",
					Map.of());
			});
			AtomicBoolean done = new AtomicBoolean(false);
			pollUntil(context,
				() -> observed.get() != null
					&& observed.get().status() == ExternalToolResult.Status.SUCCESS,
				50, 10, () -> done.set(true), done);
			context.runAtTick(150, () -> {
				context.assertTrue(done.get(), "call never settled");
				assertEquals(SafeMcp.RESPONSE,
					observed.get() == null ? null : observed.get().data().get("content"),
					"content round-tripped");
				context.complete();
			});
		});
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}
}
