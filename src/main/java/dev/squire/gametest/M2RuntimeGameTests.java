package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;

import dev.squire.api.provider.LlmProvider;
import dev.squire.api.provider.ProviderCapabilities;
import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.input.InputGateway;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.provider.ScriptedProvider;
import dev.squire.server.registry.SquireEntities;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.tool.AgentPermission;
import dev.squire.server.tool.CallerIdentity;
import dev.squire.server.tool.RiskLevel;
import dev.squire.server.tool.ToolDefinition;
import dev.squire.server.tool.ToolExposure;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * M2 Agent Runtime GameTests: the "give me 32 torches" command-fulfilment chain
 * (chat → provider → typed function call → gateway → vanilla command),
 * malformed-output safety, gateway refusals, and legacy executor smoke tests.
 *
 * <p>Distinct fake-owner names per test (parallel batches share one runtime);
 * scripted providers route by message marker so interleaved chatter cannot
 * consume another test's script lines.</p>
 */
public final class M2RuntimeGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static void place(net.minecraft.entity.Entity entity, Vec3d feetCenter) {
		entity.refreshPositionAndAngles(feetCenter.x, feetCenter.y, feetCenter.z, 0.0f, 0.0f);
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static int countIn(ServerPlayerEntity player, net.minecraft.item.Item item) {
		int total = 0;
		for (int i = 0; i < player.getInventory().size(); i++) {
			if (player.getInventory().getStack(i).getItem() == item) {
				total += player.getInventory().getStack(i).getCount();
			}
		}
		return total;
	}

	private static void setBlock(TestContext context, int x, int y, int z,
			net.minecraft.block.Block block) {
		context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)),
			block.getDefaultState());
	}

	/** Poll every 10 ticks; on first success run onSuccess; fail via tickLimit otherwise. */
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

	// ------------------------------------------------------------------ e2e

	/**
	 * EXCLUSIVE batch: the scripted provider registers into the global single-slot
	 * ProviderRegistry; batches run sequentially so no sibling test's registration
	 * can shadow it mid-run (root-caused after a whole-batch false-failure).
	 */
	@GameTest(templateName = FLOOR, tickLimit = 4000, batchId = "squire-convo-e2e")
	public void acquireAndDeliverTorchesEndToEnd(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-torch-owner");

		// Deliberately repeat the same non-idempotent tool call on every observation
		// round. The orchestrator must journal-deduplicate it after the first success.
		ScriptedProvider provider = new ScriptedProvider("scripted-torch").respondTo(
			"请直接兑现三十二个火把", """
				{"say":"好的","tool_calls":[{"name":"minecraft.command.give",
				  "arguments":{"itemId":"minecraft:torch","count":32}}]}
				""");
		ProviderRegistry.register(provider);

		context.runAtTick(5, () -> {
			// fake players are not registered in the PlayerManager — spawning the owner
			// into the world lets RuntimeServices.requester resolve them for delivery
			world.spawnEntity(owner);
			owner.getInventory().clear();
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
			owner.refreshPositionAndAngles(
				context.getAbsolutePos(new BlockPos(0, 2, 0)).getX() + 0.5,
				context.getAbsolutePos(new BlockPos(0, 2, 0)).getY(),
				context.getAbsolutePos(new BlockPos(0, 2, 0)).getZ() + 0.5, 0.0f, 0.0f);
			avatar.setStayMode();
		});

		context.runAtTick(15, () ->
			InputGateway.acceptChat(owner, "请直接兑现三十二个火把"));

		for (int t = 40; t <= 300; t += 20) {
			final int tickNo = t;
			context.runAtTick(tickNo, () -> {
				var ownTurn = rt.conversations().records().stream()
					.filter(turn -> owner.getUuid().equals(turn.ownerId()))
					.reduce((first, second) -> second).orElse(null);
				if (countIn(owner, Items.TORCH) == 32
						&& provider.receivedRequests().size() >= 2
						&& ownTurn != null
						&& ownTurn.state()
							== dev.squire.server.runtime.TurnRecord.State.COMPLETE) {
					context.assertTrue(rt.resolveAvatarFor(owner.getUuid()).orElseThrow()
						.inventory().countOf("minecraft:torch") == 0,
						"the avatar never gathers or carries the requested item");
					ProviderRegistry.clear();
					rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
					context.complete();
				}
			});
		}
	}

	/**
	 * B3/B12 black-box path: chat -> model-selected third-party query tool ->
	 * structured data shown to the player and fed into the model's next answer.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-turn-observe")
	public void thirdPartyQueryResultReturnsToPlayerAndModel(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "phase-b-query-owner");
		String toolId = "phaseb:machine_status";
		if (rt.toolRegistry().lookup(toolId).isEmpty()) {
			rt.extensions().registerTool(dev.squire.api.tool.ExternalTool
				.builder(toolId, invocation -> dev.squire.api.tool.ExternalToolResult.ok(
					invocation.callId(), java.util.Map.of("machine", "crusher",
						"state", "RUNNING", "energy", 4200)))
				.description("Returns the current machine state.")
				.readOnly(true).risk("LOW").build(),
				dev.squire.server.security.ToolTrust.UNTRUSTED, "mod:phase-b-test");
		}
		ScriptedProvider provider = new ScriptedProvider("phase-b-observer",
			"""
			{"say":"我来查询","tool_calls":[{"name":"phaseb:machine_status","arguments":{}}]}
			""",
			"机器 crusher 正在运行，当前能量 4200。");
		ProviderRegistry.register(provider);

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			rt.summonFor(owner);
			InputGateway.acceptChat(owner, "使用第三方 Tool 查询机器状态");
		});
		context.forEachRemainingTick(() -> {
			var ownTurns = rt.conversations().records().stream()
				.filter(turn -> owner.getUuid().equals(turn.ownerId())).toList();
			if (provider.receivedRequests().size() < 2 || ownTurns.isEmpty()
					|| ownTurns.get(ownTurns.size() - 1).state()
						!= dev.squire.server.runtime.TurnRecord.State.COMPLETE) {
				return;
			}
			String observationPrompt = provider.receivedRequests().get(1).message();
			context.assertTrue(observationPrompt.contains("structured_observations")
					&& observationPrompt.contains("\"energy\":4200")
					&& observationPrompt.contains("\"status\":\"SUCCESS\""),
				"structured query result must be returned to the model");
			String visible = String.join("\n", rt.notifier().queuedFor(owner.getUuid()));
			context.assertTrue(visible.contains("4200") && visible.contains("crusher"),
				"query data and final answer must be visible to the player");
			ProviderRegistry.clear();
			provider.shutdown();
			context.complete();
		});
	}

	// ------------------------------------------------------------------ malformed output

	/** EXCLUSIVE batch for the same ProviderRegistry-isolation reason as the e2e test. */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-convo-malformed")
	public void malformedModelOutputNeverExecutes(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-malformed-owner");

		ScriptedProvider brokenProvider = new ScriptedProvider("scripted-broken")
			.respondTo("坏输出", "{\"tool_calls\": [ {\"name\": }");
		ProviderRegistry.register(brokenProvider);
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
		});
		final long tasksBefore = rt.scheduler().liveCount(); // global count is shared across parallel tests — informational only

		context.runAtTick(15, () -> InputGateway.acceptChat(owner, "坏输出"));
		final UUID[] agentIdHolder = new UUID[1];
		context.runAtTick(10, () -> agentIdHolder[0] =
			rt.resolveAvatarFor(owner.getUuid()).map(AvatarEntity::agentId).orElse(null));
		context.runAtTick(80, () -> {
			UUID agentId = agentIdHolder[0];
			boolean ownCurrentTask = agentId != null && rt.scheduler().current(agentId).isPresent();
			boolean ownFinishedTask = rt.scheduler().finishedTasks().stream()
				.anyMatch(t -> agentId != null && agentId.equals(t.agentId()));
			context.assertTrue(!ownCurrentTask && !ownFinishedTask,
				"malformed model output must not create any task for this agent");
			boolean audited = rt.gateway().audit().snapshot().stream()
				.anyMatch(e -> "malformed_model_output".equals(e.outcomeCode()));
			context.assertTrue(audited, "rejection must appear in the audit trail");
			var turn = rt.conversations().records().stream()
				.filter(value -> owner.getUuid().equals(value.ownerId()))
				.reduce((first, second) -> second).orElseThrow();
			context.assertTrue(turn.state() == dev.squire.server.runtime.TurnRecord.State.FAILED
					&& turn.replans() == 2 && brokenProvider.receivedRequests().size() == 3,
				"malformed output must stop after exactly two bounded replans");
			ProviderRegistry.clear();
			brokenProvider.shutdown();
			context.complete();
		});
	}

	/** A hot reload affects new turns only; an in-flight turn keeps one provider. */
	@GameTest(templateName = FLOOR, tickLimit = 240, batchId = "squire-convo-provider-snapshot")
	public void providerReloadDoesNotSplitOneTurnAcrossProviders(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-provider-snapshot-owner");
		CompletableFuture<AgentResponse> firstResponse = new CompletableFuture<>();
		AtomicInteger firstProviderCalls = new AtomicInteger();
		AtomicInteger replacementCalls = new AtomicInteger();

		LlmProvider firstProvider = new LlmProvider() {
			@Override public String id() { return "snapshot-a"; }
			@Override public ProviderCapabilities capabilities() {
				return ProviderCapabilities.full(32_768);
			}
			@Override public CompletableFuture<AgentResponse> generate(AgentRequest request) {
				return firstProviderCalls.incrementAndGet() == 1
					? firstResponse
					: CompletableFuture.completedFuture(new AgentResponse(
						"{\"say\":\"provider-a-finished\",\"tool_calls\":[]}"));
			}
			@Override public CompletableFuture<Boolean> healthCheck() {
				return CompletableFuture.completedFuture(true);
			}
		};
		LlmProvider replacement = new LlmProvider() {
			@Override public String id() { return "snapshot-b"; }
			@Override public ProviderCapabilities capabilities() {
				return ProviderCapabilities.full(32_768);
			}
			@Override public CompletableFuture<AgentResponse> generate(AgentRequest request) {
				replacementCalls.incrementAndGet();
				return CompletableFuture.completedFuture(new AgentResponse("provider-b-used"));
			}
			@Override public CompletableFuture<Boolean> healthCheck() {
				return CompletableFuture.completedFuture(true);
			}
		};

		ProviderRegistry.register(firstProvider);
		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			rt.summonFor(owner);
			InputGateway.acceptChat(owner, "provider snapshot probe");
			context.assertTrue(firstProviderCalls.get() == 1,
				"the first provider owns the initial request");
		});
		context.runAtTick(10, () -> {
			ProviderRegistry.register(replacement);
			firstResponse.complete(new AgentResponse(
				"{\"say\":\"checking\",\"tool_calls\":[{\"name\":\"query.status\",\"arguments\":{}}]}"));
		});

		AtomicBoolean checked = new AtomicBoolean(false);
		context.forEachRemainingTick(() -> {
			if (checked.get() || firstProviderCalls.get() < 2) {
				return;
			}
			var turn = rt.conversations().records().stream()
				.filter(value -> owner.getUuid().equals(value.ownerId()))
				.reduce((first, second) -> second).orElse(null);
			if (turn == null || !turn.isTerminal()) {
				return;
			}
			checked.set(true);
			ProviderRegistry.clear();
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.assertTrue(replacementCalls.get() == 0,
				"a replacement provider must not receive the second half of an existing turn");
			context.assertTrue("snapshot-a".equals(turn.providerId()),
				"the durable turn keeps the provider it started with");
			context.complete();
		});
		context.runAtTick(220, () -> {
			if (!checked.get()) {
				ProviderRegistry.clear();
				context.assertTrue(false, "provider snapshot turn did not finish");
			}
		});
	}

	/** Requests remain bounded when two owners spam a provider that never completes. */
	@GameTest(templateName = FLOOR, tickLimit = 180, batchId = "squire-convo-cap")
	public void providerRequestsAndActiveTurnsAreBoundedPerPlayerAndServer(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer alice = fakeOwner(world, "m2-concurrency-alice");
		FakePlayer bob = fakeOwner(world, "m2-concurrency-bob");
		AtomicInteger requests = new AtomicInteger();
		LlmProvider neverCompletes = new LlmProvider() {
			@Override public String id() { return "bounded-never-completes"; }
			@Override public ProviderCapabilities capabilities() {
				return ProviderCapabilities.full(32_768);
			}
			@Override public CompletableFuture<AgentResponse> generate(AgentRequest request) {
				requests.incrementAndGet();
				return new CompletableFuture<>();
			}
			@Override public CompletableFuture<Boolean> healthCheck() {
				return CompletableFuture.completedFuture(true);
			}
		};
		ProviderRegistry.clear();
		ProviderRegistry.register(neverCompletes);

		context.runAtTick(5, () -> {
			world.spawnEntity(alice);
			world.spawnEntity(bob);
			for (int i = 0; i < 20; i++) {
				rt.conversations().beginTurn(alice, "alice request " + i);
			}
			for (int i = 0; i < 20; i++) {
				rt.conversations().beginTurn(bob, "bob request " + i);
			}
			long aliceActive = rt.conversations().records().stream()
				.filter(turn -> alice.getUuid().equals(turn.ownerId()) && !turn.isTerminal()).count();
			long bobActive = rt.conversations().records().stream()
				.filter(turn -> bob.getUuid().equals(turn.ownerId()) && !turn.isTerminal()).count();
			context.assertTrue(aliceActive == 3 && bobActive == 3,
				"each player can hold at most three active turns: " + aliceActive + ", " + bobActive);
			context.assertTrue(requests.get() == 5
					&& rt.conversations().activeProviderRequestCount() == 5,
				"only five provider calls may run at once: " + requests.get());
			context.assertTrue(rt.conversations().queuedProviderRequestCount() == 1,
				"the sixth accepted provider request waits in the bounded FIFO");

			rt.conversations().cancelFor(alice.getUuid(), null);
			rt.conversations().cancelFor(bob.getUuid(), null);
			context.assertTrue(rt.conversations().activeProviderRequestCount() == 0
					&& rt.conversations().queuedProviderRequestCount() == 0,
				"cancellation releases provider slots and queued turns");
			ProviderRegistry.clear();
			context.complete();
		});
	}

	// ------------------------------------------------------------------ gateway security

	@GameTest(templateName = FLOOR, tickLimit = 300)
	public void gatewayRefusesUnsafeModelCalls(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-security-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
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
					return owner.getUuid();
				}

				@Override
				public long tick() {
					return world.getTime();
				}
			};
			CallerIdentity model = CallerIdentity.model(owner.getUuid(), avatar.agentId());

			// 1. no such tool: a model saying "done" cannot touch task state
			ToolResult unknown = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), "task.complete", java.util.Map.of()),
				model, ctx, rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(!unknown.isSuccess(), "task.complete must not exist for models");
			context.assertTrue(unknown.errorOrNull().isPresent()
				&& unknown.errorOrNull().get().code()
					== dev.squire.common.errors.ErrorCode.TOOL_NOT_FOUND,
				"expected TOOL_NOT_FOUND");

			// 2. capability denial stops everything before execution
			ToolResult denied = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), "query.status", java.util.Map.of()),
				model, ctx, p -> false);
			context.assertTrue(denied.errorOrNull().isPresent()
				&& denied.errorOrNull().get().code()
					== dev.squire.common.errors.ErrorCode.PERMISSION_DENIED,
				"expected PERMISSION_DENIED");

			// 3. high-risk tool needs owner confirmation when called by the model
			String dangerName = "test.danger." + UUID.randomUUID();
			rt.toolRegistry().register(ToolDefinition.builder(dangerName)
					.risk(RiskLevel.HIGH)
					.permission(AgentPermission.WORLD_BREAK)
					.exposure(ToolExposure.MODEL_PUBLIC)
					.build(),
				(call, c) -> ToolResult.success(call.callId(), java.util.Map.of()));
			ToolResult blocked = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), dangerName, java.util.Map.of()),
				model, ctx, rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(blocked.status() == ToolResult.Status.BLOCKED
				&& blocked.errorOrNull().isPresent()
				&& blocked.errorOrNull().get().code()
					== dev.squire.common.errors.ErrorCode.CONFIRMATION_REQUIRED,
				"high-risk model calls must be BLOCKED");

			// 4. planner-internal tools are invisible to the model layer
			String internalName = "test.internal." + UUID.randomUUID();
			rt.toolRegistry().register(ToolDefinition.builder(internalName)
					.exposure(ToolExposure.PLANNER_INTERNAL)
					.build(),
				(call, c) -> ToolResult.success(call.callId(), java.util.Map.of()));
			ToolResult hidden = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), internalName, java.util.Map.of()),
				model, ctx, rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(hidden.errorOrNull().isPresent()
				&& hidden.errorOrNull().get().code()
					== dev.squire.common.errors.ErrorCode.TOOL_NOT_VISIBLE,
				"planner internals must be TOOL_NOT_VISIBLE for models");

			// 5. legit query passes and reflects real state
			ToolResult ok = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), "query.status", java.util.Map.of()),
				model, ctx, rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(ok.isSuccess(), "legit query.status must pass the gateway");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ executor smoke

	@GameTest(templateName = FLOOR, tickLimit = 1200)
	public void gatherExecutorMinesCoalOre(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-gather-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
			// 这里原来是 setStayMode()。待命现在是「就站这一格」
			// （DEFAULT_STAY_RADIUS = 0），而 AvatarEntity#moveTo 会把任何离开这一格的
			// 移动直接判 OUT_OF_STAY_AREA——于是采集执行器一步也走不出去，
			// 最后报 NO_REACHABLE_TARGET。真实路径上是 beginOrderedWork 先解开待命
			// （releaseStayFor），而这条测试是直接往调度器塞任务、绕过了那道门。
			// 本条要验的是采集执行器本身，所以让他空闲着就好。
			avatar.setIdleMode();
			avatar.insertStack(new ItemStack(Items.IRON_PICKAXE));
			setBlock(context, 4, 2, 4, Blocks.COAL_ORE);
			setBlock(context, 6, 2, 6, Blocks.COAL_ORE);
		});
		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			dev.squire.server.task.Task task =
				new dev.squire.server.task.Task(avatar.agentId(), owner.getUuid(),
					"gather.block", dev.squire.server.task.TaskPriority.P3_USER_TASK,
					"gather 2 coal", null,
					dev.squire.server.task.executors.GatherBlockExecutor.hasItems(
						"minecraft:coal", 2),
					1000L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "m2",
					java.util.Map.of("blockId", "minecraft:coal_ore",
						"itemId", "minecraft:coal", "count", 2));
			rt.scheduler().submit(task, world.getTime());
		});
		AtomicBoolean checked = new AtomicBoolean(false);
		pollUntil(context,
			() -> rt.resolveAvatarFor(owner.getUuid())
				.map(a -> a.inventory().countOf("minecraft:coal") >= 2).orElse(false),
			60, 20, () -> {
				checked.set(true);
				boolean completed = rt.scheduler().finishedTasks().stream()
					.anyMatch(t -> t.type().equals("gather.block")
						&& t.state() == dev.squire.server.task.TaskState.COMPLETED);
				context.assertTrue(completed, "gather task must reach COMPLETED via verifier");
				context.complete();
			}, checked);
		// diagnostic hard stop with executor error codes instead of a silent timeout
		context.runAtTick(1150, () -> {
			if (!checked.get()) {
				int coal = rt.resolveAvatarFor(owner.getUuid())
					.map(a -> a.inventory().countOf("minecraft:coal")).orElse(-1);
				var diag = new java.util.ArrayList<String>();
				rt.scheduler().current(rt.resolveAvatarFor(owner.getUuid())
					.map(AvatarEntity::agentId).orElse(null))
					.ifPresent(t -> diag.add(t.type() + ":" + t.state()));
				rt.scheduler().finishedTasks()
					.forEach(t -> diag.add(t.type() + ":" + t.state() + ":"
						+ t.lastErrorCode().orElse("-")));
				context.assertTrue(false,
					"gather stalled coal=" + coal + " tasks=" + diag);
			}
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 600)
	public void craftExecutorProducesPlanksFromLogs(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-craft-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
			avatar.setStayMode();
			avatar.insertStack(new ItemStack(Items.OAK_LOG, 2));
		});
		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			dev.squire.server.task.Task task =
				new dev.squire.server.task.Task(avatar.agentId(), owner.getUuid(),
					"craft.recipe", dev.squire.server.task.TaskPriority.P3_USER_TASK,
					"craft 8 oak planks", null,
					dev.squire.server.task.executors.CraftRecipeExecutor.hasProduced(
						"minecraft:oak_planks", 8),
					400L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "m2",
					java.util.Map.of("itemId", "minecraft:oak_planks", "count", 8));
			rt.scheduler().submit(task, world.getTime());
		});
		context.runAtTick(60, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(
				avatar.inventory().countOf("minecraft:oak_planks") >= 8,
				"crafting must produce 8 planks from 2 logs");
			context.assertTrue(avatar.inventory().countOf("minecraft:oak_log") == 0,
				"logs must be consumed");
			boolean completed = rt.scheduler().finishedTasks().stream()
				.anyMatch(t -> t.type().equals("craft.recipe")
					&& t.state() == dev.squire.server.task.TaskState.COMPLETED);
			context.assertTrue(completed, "craft task must be COMPLETED by the verifier");
			context.complete();
		});
	}

	/**
	 * B04's middle link: raw_iron + coal → iron_ingot in a REAL furnace (方案 C4).
	 * The ingots are produced by the vanilla {@code FurnaceBlockEntity}, so the test
	 * has to stand a furnace next to the avatar — no furnace, no smelting.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 1500)
	public void smeltExecutorProducesIngotsFromRawIron(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-smelt-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
			avatar.setStayMode();
			context.setBlockState(new BlockPos(3, 2, 2), net.minecraft.block.Blocks.FURNACE);
			avatar.insertStack(new ItemStack(Items.RAW_IRON, 3));
			avatar.insertStack(new ItemStack(Items.COAL));
		});
		AtomicReference<UUID> smeltTaskId = new AtomicReference<>();
		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			dev.squire.server.task.Task task =
				new dev.squire.server.task.Task(avatar.agentId(), owner.getUuid(),
					dev.squire.server.task.executors.SmeltTaskExecutor.TYPE,
					dev.squire.server.task.TaskPriority.P3_USER_TASK,
					"smelt 3 iron_ingot", null,
					dev.squire.server.task.executors.SmeltTaskExecutor.hasProduced(
						"minecraft:iron_ingot", 3),
					1400L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "m2",
					java.util.Map.of("itemId", "minecraft:iron_ingot", "count", 3));
			smeltTaskId.set(task.taskId());
			rt.scheduler().submit(task, world.getTime());
		});
		AtomicBoolean checked = new AtomicBoolean(false);
		// 产物由炉子在自己的 tick 里烧出来，执行器要到下一次调度才收走并交给校验器；
		// 因此轮询条件是"任务已 COMPLETED"，再去检查世界状态，避免抢在校验器前面。
		pollUntil(context,
			() -> taskFinished(rt, smeltTaskId.get()),
			70, 20, () -> {
				context.assertTrue(taskCompleted(rt, smeltTaskId.get()),
					"this test's smelt task must reach COMPLETED via the verifier");
				checked.set(true);
				AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
				context.assertTrue(
					avatar.inventory().countOf("minecraft:iron_ingot") >= 3,
					"three real ingots came out of the furnace");
				context.assertTrue(
					avatar.inventory().countOf("minecraft:raw_iron") == 0,
					"raw iron must be consumed");
				context.assertTrue(avatar.inventory().countOf("minecraft:coal") == 0,
					"fuel share must be consumed from the real inventory");
				context.complete();
			}, checked);
		context.runAtTick(1450, () -> {
			if (!checked.get()) {
				AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElse(null);
				int ingots = avatar == null ? -2
					: avatar.inventory().countOf("minecraft:iron_ingot");
				String inv = avatar == null ? "no-avatar"
					: "raw=" + avatar.inventory().countOf("minecraft:raw_iron")
						+ " ore=" + avatar.inventory().countOf("minecraft:iron_ore")
						+ " coal=" + avatar.inventory().countOf("minecraft:coal");
				var diag = new java.util.ArrayList<String>();
				if (avatar != null) {
					rt.scheduler().finishedTasks().stream()
						.filter(t -> t.agentId().equals(avatar.agentId()))
						.forEach(t -> diag.add(t.type() + ":" + t.state() + ":"
							+ t.lastErrorCode().orElse("-") + ":"
							+ (t.executionState()
								instanceof dev.squire.server.task.executors.SmeltTaskExecutor.Progress p
									? p.inputId() : "-")));
					rt.scheduler().current(avatar.agentId())
						.ifPresent(t -> diag.add("CURRENT:" + t.type() + ":" + t.state()));
				}
				context.assertTrue(false,
					"smelt stalled ingots=" + ingots + " " + inv
						+ " ownTasks=" + diag);
			}
		});
	}

	// ------------------------------------------------------------------ combat & healing

	private static dev.squire.server.tool.ToolExecutionContext execContext(
			SquireRuntime rt, AvatarEntity avatar, ServerWorld world,
			FakePlayer owner) {
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
				return world.getServer();
			}

			@Override
			public UUID requesterId() {
				return owner.getUuid();
			}

			@Override
			public long tick() {
				return world.getTime();
			}
		};
	}

	/**
	 * Batches run concurrently in one shared runtime, so matching a finished task by
	 * TYPE alone can pick up a sibling test's task. Identity-scoped checks use this.
	 */
	private static boolean taskCompleted(SquireRuntime rt, UUID taskId) {
		return taskId != null && rt.scheduler().finishedTasks().stream()
			.anyMatch(t -> t.taskId().equals(taskId)
				&& t.state() == dev.squire.server.task.TaskState.COMPLETED);
	}

	private static boolean taskFinished(SquireRuntime rt, UUID taskId) {
		return taskId != null && rt.scheduler().finishedTasks().stream()
			.anyMatch(t -> t.taskId().equals(taskId));
	}

	private static boolean finishedWith(SquireRuntime rt, String type) {
		return rt.scheduler().finishedTasks().stream()
			.anyMatch(t -> t.type().equals(type)
				&& t.state() == dev.squire.server.task.TaskState.COMPLETED);
	}

	/** “保护我”: the runtime fights for the owner — the LLM is never in the loop. */
	@GameTest(templateName = FLOOR, tickLimit = 1600, batchId = "squire-runtime-guard-owner")
	public void guardTaskDefendsOwnerFromZombie(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-guard-owner");
		AtomicReference<ZombieEntity> threat = new AtomicReference<>();

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			owner.refreshPositionAndAngles(
				context.getAbsolutePos(new BlockPos(1, 2, 1)).getX() + 0.5,
				context.getAbsolutePos(new BlockPos(1, 2, 1)).getY(),
				context.getAbsolutePos(new BlockPos(1, 2, 1)).getZ() + 0.5, 0.0f, 0.0f);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(3, 2, 3))));
			avatar.setStayMode();
			avatar.insertStack(new ItemStack(Items.DIAMOND_SWORD)); // quick, decisive kill
			ZombieEntity zombie = (ZombieEntity) EntityType.ZOMBIE.create(world);
			threat.set(zombie);
			zombie.refreshPositionAndAngles(
				context.getAbsolutePos(new BlockPos(2, 2, 2)).getX() + 0.5,
				context.getAbsolutePos(new BlockPos(2, 2, 2)).getY(),
				context.getAbsolutePos(new BlockPos(2, 2, 2)).getZ() + 0.5, 0.0f, 0.0f);
			world.spawnEntity(zombie);
		});

		// guard.start goes through the GATEWAY as a model call — the only legal path
		final boolean[] started = {false};
		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			// §23.1：主动发起护卫现在是<b>守卫这条成长线</b>的工具，不再人人都有。
			// 玩家自己说「保护我」走的是 FastPath，不经过这道闸（M7 验那一条）；
			// 这里验的是模型走网关的那条路，所以这只随从得真的是个守卫。
			for (var milestone
					: dev.squire.server.profession.TrainingMilestone.values()) {
				rt.noteTraining(avatar, milestone);
			}
			rt.professionOf(avatar).setProfession(
				dev.squire.server.profession.SquireProfession.GUARD);
			ToolResult result = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), "guard.start",
					java.util.Map.of("radius", 12, "durationTicks", 600)),
				CallerIdentity.model(owner.getUuid(), avatar.agentId()),
				execContext(rt, avatar, world, owner),
				rt.capabilitiesOf(avatar.agentId()));
			started[0] = result.status() == ToolResult.Status.RUNNING;
			context.assertTrue(started[0], "guard.start must start a task: "
				+ result.status());
		});

		AtomicBoolean done = new AtomicBoolean(false);
		pollUntil(context,
			() -> threat.get() != null && !threat.get().isAlive(),
			120, 20, () -> {
				done.set(true);
				context.assertTrue(finishedWith(rt, "guard.owner"),
					"guard task must complete after the threat dies");
				context.assertTrue(owner.isAlive(), "owner must survive the attack");
				rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS); owner.discard();
				context.complete();
			}, done);
		context.runAtTick(1550, () -> {
			if (!done.get()) {
				context.assertTrue(false, "guard stalled; zombie still alive="
					+ (threat.get() != null && threat.get().isAlive())
					+ " ownerHp=" + owner.getHealth());
			}
		});
	}

	/**
	 * heal.now 消耗真实食物并真的把血补回来（方案 D2）。金苹果不再是"固定 4.0 HP"的
	 * flat heal——它走香草 finishUsing，给出 Regeneration II + Absorption，因此回血是
	 * 持续的，执行器也会等上一件生效完再吃下一件。测试因此按真实节奏轮询。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 900)
	public void healNowRestoresHealthFromInventory(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m2-heal-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
			avatar.setStayMode();
			// 直接设血而不是打一下：伤害要过护甲与「皮实」特质（第 2 期随机抽到的），
			// 这条测试验的是「受伤之后能吃东西回血」，不该把伤害公式一并绑进来。
			avatar.setHealth(6.0f);
			context.assertTrue(avatar.getHealth() <= 7.0f, "avatar must start hurt");
			for (int i = 0; i < 4; i++) {
				avatar.insertStack(new ItemStack(Items.GOLDEN_APPLE));
			}
		});
		AtomicReference<UUID> healTaskId = new AtomicReference<>();
		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			ToolResult result = rt.gateway().dispatch(
				new ToolCall(UUID.randomUUID(), "heal.now", java.util.Map.of()),
				CallerIdentity.model(owner.getUuid(), avatar.agentId()),
				execContext(rt, avatar, world, owner),
				rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(result.status() == ToolResult.Status.RUNNING,
				"heal.now must start a P1 survival task");
			healTaskId.set(UUID.fromString(
				String.valueOf(result.data().get("taskId"))));
		});
		AtomicBoolean checked = new AtomicBoolean(false);
		pollUntil(context,
			() -> taskFinished(rt, healTaskId.get()),
			80, 10, () -> {
				checked.set(true);
				context.assertTrue(taskCompleted(rt, healTaskId.get()),
					"this test's heal task must reach COMPLETED via the verifier");
				AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
				context.assertTrue(avatar.getHealth() >= 19.0f,
					"real regeneration brought the body back up, hp=" + avatar.getHealth());
				context.assertTrue(avatar.inventory().countOf("minecraft:golden_apple") == 0,
					"healing food must be consumed from the real inventory");
				context.assertTrue(avatar.hasStatusEffect(
						net.minecraft.entity.effect.StatusEffects.ABSORPTION),
					"the golden apple's OWN effects must have been applied, not a flat heal");
				context.complete();
			}, checked);
		context.runAtTick(870, () -> {
			if (!checked.get()) {
				float hp = rt.resolveAvatarFor(owner.getUuid())
					.map(AvatarEntity::getHealth).orElse(-1f);
				context.assertTrue(false, "heal stalled hp=" + hp);
			}
		});
	}
}
