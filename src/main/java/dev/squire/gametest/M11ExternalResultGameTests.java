package dev.squire.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.mcp.mock.MockMcpServers.DeferredMcp;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.security.ToolTrust;
import dev.squire.server.tool.CallerIdentity;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * 工作包 I 黑盒验收：外部/MCP 结果真的回到 Turn 和玩家。
 *
 * <p>重点是"晚到的结果不能丢"：远端返回 RUNNING 之后，Turn 必须停在 WAIT_TOOL，
 * 结果回来时切回服务器线程唤醒它；永远不回来时由期限扫描合成结构化超时，而不是
 * 让这轮对话永远挂着。</p>
 */
public final class M11ExternalResultGameTests implements FabricGameTest {
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

	private static dev.squire.server.tool.ToolExecutionContext execContext(
			AvatarEntity avatar, FakePlayer owner, SquireRuntime rt) {
		return new dev.squire.server.tool.ToolExecutionContext() {
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
				return avatar.getWorld().getServer();
			}

			@Override
			public UUID requesterId() {
				return owner.getUuid();
			}

			@Override
			public long tick() {
				return rt.tickNow();
			}
		};
	}

	// ================================================== 晚到的结果不能丢

	/**
	 * I3：远端先返回 RUNNING。结果晚一点才来，必须切回服务器线程被 Turn 关联到，
	 * 玩家看到可读摘要，而不是这次调用被静静丢掉。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-i-deferred")
	public void aLateMcpResultStillReachesTheWaitingCall(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "i-deferred-owner");
		DeferredMcp remote = new DeferredMcp();
		String server = "deferred-mcp";
		String toolId = server + ":" + DeferredMcp.TOOL;
		AtomicBoolean done = new AtomicBoolean(false);
		final UUID[] callId = new UUID[1];

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			rt.mcp().trusts().setTrust(server, ToolTrust.UNTRUSTED);
			rt.mcp().registerServer(server, remote).join();
			context.assertTrue(rt.toolRegistry().lookup(toolId).isPresent(),
				"the read-only remote tool was imported");

			callId[0] = UUID.randomUUID();
			var result = rt.gateway().dispatch(
				new dev.squire.common.protocol.ToolCall(callId[0], toolId, Map.of()),
				CallerIdentity.model(owner.getUuid(), avatar.agentId()),
				execContext(avatar, owner, rt), rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(result.status()
					== dev.squire.common.protocol.ToolResult.Status.RUNNING,
				"a remote call returns RUNNING at once, got " + result.status());
			// 调用被登记了归属和期限，才可能有人去唤醒它
			context.assertTrue(rt.mcp().pendingCalls().get(callId[0]).isPresent(),
				"the in-flight call is registered");
			context.assertTrue(rt.mcp().outcomeOf(callId[0]).isEmpty(),
				"and has NOT settled yet");
		});

		context.runAtTick(30, () -> {
			context.assertTrue(remote.hasPendingCall(), "the remote is still thinking");
			context.assertTrue(rt.mcp().outcomeOf(callId[0]).isEmpty(),
				"still nothing to observe");
			// 结果现在才回来
			context.assertTrue(remote.release("{\"status\":\"running\",\"energy\":812}"),
				"released the deferred answer");
		});

		context.runAtTick(60, () -> {
			var outcome = rt.mcp().outcomeOf(callId[0]).orElse(null);
			context.assertTrue(outcome != null,
				"the late result really landed back in the runtime");
			context.assertTrue(outcome.isSuccess(), "and it is a success");
			context.assertTrue(String.valueOf(outcome.data()).contains("812"),
				"carrying the actual value: " + outcome.data());
			context.assertTrue(rt.mcp().pendingCalls().get(callId[0]).isEmpty(),
				"the call left the in-flight registry exactly once");
			done.set(true);
			rt.mcp().disconnect(server);
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(560, () -> {
			if (!done.get()) {
				rt.mcp().disconnect(server);
				context.throwGameTestException("deferred result never arrived");
			}
		});
	}

	/**
	 * I2/I3：结果永远不来时，期限扫描把它变成结构化超时，Turn 不会永远挂着。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-i-timeout")
	public void aCallThatNeverAnswersBecomesAStructuredTimeout(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "i-timeout-owner");
		DeferredMcp remote = new DeferredMcp();
		String server = "silent-mcp";
		AtomicBoolean done = new AtomicBoolean(false);
		final UUID[] callId = new UUID[1];

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			rt.mcp().trusts().setTrust(server, ToolTrust.UNTRUSTED);
			rt.mcp().registerServer(server, remote).join();

			callId[0] = UUID.randomUUID();
			rt.gateway().dispatch(
				new dev.squire.common.protocol.ToolCall(callId[0],
					server + ":" + DeferredMcp.TOOL, Map.of()),
				CallerIdentity.model(owner.getUuid(), avatar.agentId()),
				execContext(avatar, owner, rt), rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(rt.mcp().pendingCalls().get(callId[0]).isPresent(),
				"the call is in flight");

			// 直接把期限推到过去：真实超时要等十几秒，测试不该靠等
			var pending = rt.mcp().pendingCalls().complete(callId[0]).orElseThrow();
			rt.mcp().pendingCalls().register(
				new dev.squire.server.mcp.PendingExternalCallRegistry.PendingCall(
					pending.callId(), pending.ownerId(), pending.agentId(), null, null,
					pending.server(), pending.toolName(), pending.issuedAtTick(),
					rt.tickNow() - 1));
		});

		context.runAtTick(60, () -> {
			var outcome = rt.mcp().outcomeOf(callId[0]).orElse(null);
			context.assertTrue(outcome != null,
				"an unanswered call must not stay pending forever");
			context.assertTrue(!outcome.isSuccess(), "it is a failure");
			context.assertTrue("MCP_TIMEOUT".equals(outcome.errorCode()),
				"with a structured code, got " + outcome.errorCode());
			context.assertTrue(rt.mcp().pendingCalls().get(callId[0]).isEmpty(),
				"and it left the registry");
			done.set(true);
			rt.mcp().disconnect(server);
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(560, () -> {
			if (!done.get()) {
				rt.mcp().disconnect(server);
				context.throwGameTestException("timeout sweep never fired");
			}
		});
	}

	/** I2：断线时在飞的调用立刻得到结构化失败，而不是挂在那里等一个不会来的结果。 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-i-disconnect")
	public void disconnectingReleasesInFlightCallsImmediately(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "i-disconnect-owner");
		DeferredMcp remote = new DeferredMcp();
		String server = "flaky-mcp";
		final UUID[] callId = new UUID[1];

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			rt.mcp().trusts().setTrust(server, ToolTrust.UNTRUSTED);
			rt.mcp().registerServer(server, remote).join();

			callId[0] = UUID.randomUUID();
			rt.gateway().dispatch(
				new dev.squire.common.protocol.ToolCall(callId[0],
					server + ":" + DeferredMcp.TOOL, Map.of()),
				CallerIdentity.model(owner.getUuid(), avatar.agentId()),
				execContext(avatar, owner, rt), rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(rt.mcp().pendingCalls().forServer(server).size() == 1,
				"one call is in flight");

			rt.mcp().disconnect(server);

			var outcome = rt.mcp().outcomeOf(callId[0]).orElse(null);
			context.assertTrue(outcome != null && !outcome.isSuccess(),
				"the in-flight call failed immediately on disconnect");
			context.assertTrue("MCP_DISCONNECTED".equals(outcome.errorCode()),
				"with a structured code, got "
					+ (outcome == null ? "null" : outcome.errorCode()));
			context.assertTrue(rt.mcp().pendingCalls().forServer(server).isEmpty(),
				"nothing is left dangling for that server");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** I2：mcp.json 是唯一的服务器来源，服主可以在不写 Java 的情况下查看和重载。 */
	@GameTest(templateName = FLOOR, batchId = "squire-i-config")
	public void operatorsCanInspectAndReloadMcpConfiguration(TestContext context) {
		SquireRuntime rt = runtime(context);

		context.runAtTick(5, () -> {
			String report = rt.reloadMcpServers();
			context.assertTrue(report.contains("mcp.json"),
				"the report names the config file: " + report);
			// 第一次运行会留下一份全部禁用的示例；再次加载不应该连出去
			var config = rt.mcpConfig();
			for (var entry : config.servers()) {
				context.assertFalse(entry.enabled(),
					"the shipped example must not auto-connect: " + entry.name());
			}
			String status = rt.mcpStatus("example-http");
			context.assertTrue(status.contains("熔断"),
				"status reports the circuit state: " + status);
			context.assertTrue(status.contains("信任"),
				"and where trust comes from: " + status);
			context.complete();
		});
	}
}
