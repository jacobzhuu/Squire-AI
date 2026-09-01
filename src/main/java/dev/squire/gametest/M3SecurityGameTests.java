package dev.squire.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;

import dev.squire.common.errors.ErrorCode;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.security.CapabilityStore;
import dev.squire.server.security.PermissionNodes;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.tool.CallerIdentity;
import dev.squire.server.tool.ToolGateway;
import dev.squire.server.world.BoundedRegion;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * M3 security GameTests (spec section 82 checklist): confirmation-gated world
 * editing end-to-end, forged confirmations fail closed, killswitch same-tick,
 * capability scope violations change ZERO blocks, undo restores BlockState +
 * BlockEntity NBT, non-owner model writes are rejected, and defaults-off.
 *
 * <p>Every test that flips a global flag (worldEditEnabled, killswitch) restores it
 * on both the success and diagnostic paths — suites run sequentially against ONE
 * shared runtime.</p>
 */
public final class M3SecurityGameTests implements FabricGameTest {
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
		SquireRuntime rt = SquireRuntime.get();
		// defensive isolation: a previously-failed test must never leak a live
		// killswitch / paused scheduler into this test
		if (rt.killswitch().isActive()) {
			rt.setKillswitch(false);
		}
		return rt;
	}

	private static BlockPos abs(TestContext context, int x, int y, int z) {
		return context.getAbsolutePos(new BlockPos(x, y, z));
	}

	private static void setBlock(TestContext context, int x, int y, int z,
			net.minecraft.block.Block block) {
		context.getWorld().setBlockState(abs(context, x, y, z), block.getDefaultState());
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

	private static Map<String, Object> fillArgs(BlockPos a, BlockPos b, String blockId) {
		return new java.util.HashMap<>(Map.of(
			"x1", a.getX(), "y1", a.getY(), "z1", a.getZ(),
			"x2", b.getX(), "y2", b.getY(), "z2", b.getZ(),
			"blockId", blockId));
	}

	private static boolean regionIsAll(ServerWorld world, BoundedRegion region,
			net.minecraft.block.Block block) {
		for (BlockPos pos : region.cells()) {
			if (!world.getBlockState(pos).isOf(block)) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ §45/§27 e2e

	/**
	 * THE high-risk chain: model asks for a fill → gateway BLOCKS with
	 * CONFIRMATION_REQUIRED → forged confirmation by another player fails → owner
	 * confirms by id → re-dispatch compiles and executes bounded vanilla /fill.
	 * §82: Forged client confirmation 失败.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 2000, batchId = "squire-m3-fill")
	public void fillNeedsOwnerConfirmationThenExecutesFramed(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m3-fill-owner");
		FakePlayer attacker = fakeOwner(world, "m3-fill-attacker");

		context.runAtTick(5, () -> {
			rt.setWorldEditEnabled(true); // restored below
			rt.permissions().grant(owner.getUuid(), PermissionNodes.WORLD_EDIT);
			world.spawnEntity(owner); // NodeChecker/OwnerVerifier resolve spawned players
			world.spawnEntity(attacker);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(abs(context, 2, 2, 2)));
			avatar.setStayMode();
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			Map<String, Object> args = fillArgs(abs(context, 3, 2, 3),
				abs(context, 5, 4, 5), "minecraft:glowstone");

			// 1. first model call MUST be blocked pending owner confirmation
			ToolResult blocked = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.fill", new java.util.HashMap<>(args));
			context.assertTrue(blocked.status() == ToolResult.Status.BLOCKED
				&& blocked.errorOrNull().orElseThrow().code()
					== ErrorCode.CONFIRMATION_REQUIRED,
				"high-risk fill must be BLOCKED before owner confirmation");

			// 2. server owns the confirmation; a FORGED player cannot confirm it
			String fingerprint = ToolGateway.fingerprint(args);
			var request = rt.confirmations().issue(owner.getUuid(), avatar.agentId(),
				"minecraft.command.fill", fingerprint, world.getTime());
			String forgedReply = rt.confirmRequest(attacker, request.confirmId());
			context.assertTrue(forgedReply.contains("another player"),
				"wrong player must be refused, got: " + forgedReply);

			// 3. ...and the forged attempt unlocks NOTHING for anyone
			ToolResult afterForged = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.fill", new java.util.HashMap<>(args));
			context.assertTrue(afterForged.status() == ToolResult.Status.BLOCKED,
				"forged confirmation must not unlock the call");

			// 4. the real owner confirms BY ID → exactly one dispatch passes
			String okReply = rt.confirmRequest(owner, request.confirmId());
			context.assertTrue(okReply.contains("Confirmed"), okReply);
			ToolResult executed = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.fill", new java.util.HashMap<>(args));
			context.assertTrue(executed.status() == ToolResult.Status.SUCCESS,
				"confirmed fill must execute directly, got " + executed.status()
					+ " " + executed.errorOrNull());
			BoundedRegion target = BoundedRegion.ofCorners(
				abs(context, 3, 2, 3).getX(), abs(context, 3, 2, 3).getY(),
				abs(context, 3, 2, 3).getZ(), abs(context, 5, 4, 5).getX(),
				abs(context, 5, 4, 5).getY(), abs(context, 5, 4, 5).getZ());
			context.assertTrue(regionIsAll(world, target, Blocks.GLOWSTONE),
				"/fill must have changed the confirmed region synchronously");
			context.assertTrue(Boolean.FALSE.equals(executed.data().get("viaCommandBlock")),
				"this short /fill should stay on the inline Brigadier path");
			boolean audited = rt.gateway().audit().snapshot().stream().anyMatch(e ->
				"minecraft.command.fill".equals(e.toolName()) && e.executed());
			context.assertTrue(audited, "fill must appear in the audit trail");
			cleanup(rt, owner);
			context.complete();
		});
	}

	private static void cleanup(SquireRuntime rt, FakePlayer owner) {
		rt.setWorldEditEnabled(false);
		rt.permissions().revoke(owner.getUuid(), PermissionNodes.WORLD_EDIT);
	}

	// ------------------------------------------------------------------ §63 killswitch

	/** `/squire admin killswitch on` denies writes and cancels tasks ON THE SAME TICK. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-m3-killswitch")
	public void killswitchTakesEffectOnTheSameTick(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m3-kill-owner");

		context.runAtTick(5, () -> {
			rt.setWorldEditEnabled(true);
			rt.permissions().grant(owner.getUuid(), PermissionNodes.WORLD_EDIT);
			world.spawnEntity(owner); // gateway resolves the requester through the world
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(abs(context, 2, 2, 2)));
			avatar.setIdleMode(); // 不用 STAY：待命现在是一个区域，会拒绝远处的移动任务；
			// 这里只是要他别跟着人走。
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			// one RUNNING walk + one PENDING copy — cancelAll must reach both
			dev.squire.server.task.Task walking = new dev.squire.server.task.Task(
				avatar.agentId(), owner.getUuid(), "navigation.move_to",
				dev.squire.server.task.TaskPriority.P3_USER_TASK, "walk far", null,
				null, 4000L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "m3",
				Map.of("x", abs(context, 20, 2, 20).getX(), "y", 2.0,
					"z", abs(context, 20, 2, 20).getZ()));
			dev.squire.server.task.Task queued = new dev.squire.server.task.Task(
				avatar.agentId(), owner.getUuid(), "navigation.move_to",
				dev.squire.server.task.TaskPriority.P3_USER_TASK, "queued walk", null,
				null, 4000L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "m3",
				Map.of("x", abs(context, 24, 2, 24).getX(), "y", 2.0,
					"z", abs(context, 24, 2, 24).getZ()));
			rt.scheduler().submit(walking, world.getTime());
			rt.scheduler().submit(queued, world.getTime());
		});

		context.runAtTick(25, () -> {
			var problems = new java.util.ArrayList<String>();
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();

			String reply = rt.setKillswitch(true);
			if (!reply.contains("ACTIVE")) {
				problems.add("activation reply: " + reply);
			}
			// SAME-TICK guarantees (spec §63): no async gap
			if (!rt.killswitch().isActive()) {
				problems.add("killswitch not active on the calling tick");
			}
			if (!rt.scheduler().isPaused()) {
				problems.add("scheduler not paused on the calling tick");
			}
			boolean allCancelled = rt.scheduler().finishedTasks().stream()
				.filter(t -> t.agentId().equals(avatar.agentId()))
				.allMatch(t -> t.state() == dev.squire.server.task.TaskState.CANCELLED);
			if (!allCancelled) {
				problems.add("not every task CANCELLED immediately");
			}
			ToolResult denied = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.fill", fillArgs(abs(context, 3, 2, 3),
					abs(context, 3, 2, 3), "minecraft:stone"));
			if (!(denied.errorOrNull().isPresent()
					&& denied.errorOrNull().get().code() == ErrorCode.POLICY_DENIED)) {
				problems.add("fill result: " + denied.status() + " "
					+ denied.errorOrNull());
			}
			if (!world.getBlockState(abs(context, 3, 2, 3)).isOf(Blocks.AIR)) {
				problems.add("blocks changed under killswitch");
			}

			// restore global state BEFORE any assertion can throw (a failed gametest
			// runs no further callbacks — leaking a killswitch would poison the suite)
			rt.setKillswitch(false);
			cleanup(rt, owner);

			context.assertTrue(problems.isEmpty(), String.join("; ", problems));
		});

		context.runAtTick(45, () -> {
			context.assertFalse(rt.killswitch().isActive(), "killswitch off");
			context.assertFalse(rt.scheduler().isPaused(), "scheduler resumed");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ §30 capability

	/** 越界必须拒绝: an edit outside capability bounds is refused with ZERO writes. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-m3-capability")
	public void capabilityScopeViolationRejectsWithZeroWrites(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m3-scope-owner");

		context.runAtTick(5, () -> {
			setBlock(context, 6, 2, 6, Blocks.STONE);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(abs(context, 2, 2, 2)));
			avatar.setStayMode();
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			BlockPos m = abs(context, 6, 2, 6);
			String toolName = dev.squire.server.world.WorldEditor
				.capabilityToolNameForKind(dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL);
			CapabilityStore caps = rt.capabilities();

			// capability covers ONLY cell A; plan targets cells B outside it
			BoundedRegion scopeA = BoundedRegion.ofCorners(
				abs(context, 3, 2, 3).getX(), abs(context, 3, 2, 3).getY(),
				abs(context, 3, 2, 3).getZ(), abs(context, 3, 2, 3).getX(),
				abs(context, 3, 2, 3).getY(), abs(context, 3, 2, 3).getZ());
			var cap = caps.issue(owner.getUuid(), avatar.agentId(), toolName,
				java.util.Set.of(toolName), world.getRegistryKey().getValue(), scopeA,
				1, world.getTime());

			BoundedRegion outside = BoundedRegion.ofCorners(m.getX(), m.getY(), m.getZ(),
				m.getX() + 1, m.getY(), m.getZ() + 1);
			var rejected = rt.worldEditor().begin(world,
				new dev.squire.server.world.WorldEditor.EditPlan(
					dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL,
					world.getRegistryKey().getValue(), outside, "minecraft:lava",
					avatar.agentId(), owner.getUuid(), UUID.randomUUID(),
					cap.capabilityId()),
				caps, world.getTime());
			context.assertTrue(!rejected.accepted()
				&& ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire().equals(rejected.errorCode()),
				"out-of-bounds edit must be CAPABILITY_SCOPE_VIOLATION, got "
					+ rejected.errorCode());
			context.assertTrue(world.getBlockState(m).isOf(Blocks.STONE),
				"越界必须拒绝: zero blocks changed");

			// single-use: after one legitimate use, replaying the id is refused too
			var accepted = rt.worldEditor().begin(world,
				new dev.squire.server.world.WorldEditor.EditPlan(
					dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL,
					world.getRegistryKey().getValue(), scopeA, "minecraft:stone",
					avatar.agentId(), owner.getUuid(), UUID.randomUUID(),
					cap.capabilityId()),
				caps, world.getTime());
			context.assertTrue(accepted.accepted(), "in-scope edit must be accepted");
			rt.worldEditor().tick(world.getTime()); // ≤256 cells → finishes this frame
			context.assertTrue(rt.worldEditor().isComplete(accepted.operationId()),
				"single-cell op completes in one frame");
			var replay = rt.worldEditor().begin(world,
				new dev.squire.server.world.WorldEditor.EditPlan(
					dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL,
					world.getRegistryKey().getValue(), scopeA, "minecraft:dirt",
					avatar.agentId(), owner.getUuid(), UUID.randomUUID(),
					cap.capabilityId()),
				caps, world.getTime());
			context.assertTrue(!replay.accepted(),
				"consumed capability must not be replayable");
			cleanup(rt, owner);
			context.complete();
		});
	}

	// ------------------------------------------------------------------ §52 undo

	/** Undo restores BlockState AND chest BlockEntity NBT (container contents return). */
	@GameTest(templateName = FLOOR, tickLimit = 900, batchId = "squire-m3-undo")
	public void undoRestoresBlockStateAndChestContents(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m3-undo-owner");
		BlockPos chestPos = abs(context, 4, 2, 4);

		context.runAtTick(5, () -> {
			world.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
			ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(chestPos);
			chest.setStack(0, new ItemStack(Items.DIAMOND, 5));
			chest.markDirty();
			setBlock(context, 3, 2, 3, Blocks.STONE_BRICKS);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(abs(context, 2, 2, 2)));
			avatar.setStayMode();
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			String toolName = dev.squire.server.world.WorldEditor
				.capabilityToolNameForKind(dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL);
			BoundedRegion region = BoundedRegion.ofCorners(
				abs(context, 3, 2, 3).getX(), abs(context, 3, 2, 3).getY(),
				abs(context, 3, 2, 3).getZ(), abs(context, 5, 2, 5).getX(),
				abs(context, 5, 2, 5).getY(), abs(context, 5, 2, 5).getZ());
			var cap = rt.capabilities().issue(owner.getUuid(), avatar.agentId(),
				toolName, java.util.Set.of(toolName), world.getRegistryKey().getValue(),
				region, 9, world.getTime());
			var result = rt.worldEditor().begin(world,
				new dev.squire.server.world.WorldEditor.EditPlan(
					dev.squire.server.world.WorldEditor.EditPlan.Kind.FILL,
					world.getRegistryKey().getValue(), region, "minecraft:stone",
					avatar.agentId(), owner.getUuid(), UUID.randomUUID(),
					cap.capabilityId()),
				rt.capabilities(), world.getTime());
			context.assertTrue(result.accepted(), result.message());
			UUID operationId = result.operationId();
			rt.worldEditor().tick(world.getTime());
			context.assertTrue(rt.worldEditor().isComplete(operationId), "op complete");

			// everything overwritten — including the chest
			context.assertTrue(world.getBlockState(chestPos).isOf(Blocks.STONE),
				"chest replaced by fill");
			context.assertTrue(rt.undoJournal().entryCount(operationId) > 0,
				"pre-states journaled");

			// exact restore, newest-first
			int restored = rt.undoJournal().undo(world, operationId);
			context.assertTrue(restored > 0, "undo restored cells");
			context.assertTrue(world.getBlockState(chestPos).isOf(Blocks.CHEST),
				"chest block state restored");
			ChestBlockEntity back = (ChestBlockEntity) world.getBlockEntity(chestPos);
			ItemStack diamonds = back.getStack(0);
			context.assertTrue(diamonds.isOf(Items.DIAMOND) && diamonds.getCount() == 5,
				"BlockEntity NBT restored: expected 5 diamonds, got " + diamonds);
			context.assertTrue(world.getBlockState(abs(context, 3, 2, 3))
					.isOf(Blocks.STONE_BRICKS),
				"plain BlockStates restored exactly");
			cleanup(rt, owner);
			context.complete();
		});
	}

	// ------------------------------------------------------------------ §82 non-owner

	/** Stage 5c: a MODEL call from someone who does not own the agent never runs. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-m3-owner")
	public void nonOwnerModelWriteIsRejected(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m3-own-owner");
		FakePlayer stranger = fakeOwner(world, "m3-own-stranger");
		AtomicBoolean handlerRan = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			world.spawnEntity(owner); // gateway resolves requesters through the world
			world.spawnEntity(stranger);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(abs(context, 2, 2, 2)));
			// ephemeral write tool owned by nobody — proves the GATEWAY layer stops it
			rt.toolRegistry().register(
				dev.squire.server.tool.ToolDefinition.builder("test.nonowner.write")
					.description("would place a block if ever executed")
					.arg(dev.squire.server.tool.ArgDefinition.required("blockId",
						dev.squire.server.tool.ArgDefinition.ArgType.BLOCK_ID, "block"))
					.permission(dev.squire.server.tool.AgentPermission.WORLD_PLACE)
					.node(PermissionNodes.WORLD_PLACE)
					.exposure(dev.squire.server.tool.ToolExposure.MODEL_PUBLIC)
					.build(),
				(call, c) -> {
					handlerRan.set(true);
					return ToolResult.success(call.callId(), Map.of());
				});
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			ToolResult strangerCall = dispatch(rt, world, avatar, stranger.getUuid(),
				"test.nonowner.write", Map.of("blockId", "minecraft:tnt"));
			context.assertTrue(strangerCall.errorOrNull().orElseThrow().code()
				== ErrorCode.PERMISSION_DENIED,
				"non-owner write must be PERMISSION_DENIED");
			context.assertFalse(handlerRan.get(),
				"handler must never run for a non-owner");
			// the real owner passes the same stage
			ToolResult ownerCall = dispatch(rt, world, avatar, owner.getUuid(),
				"test.nonowner.write", Map.of("blockId", "minecraft:stone"));
			context.assertTrue(ownerCall.isSuccess(), "owner's identical call goes through");
			context.assertTrue(handlerRan.get(), "owner path reaches the handler");
			context.complete();
		});
	}

	// ------------------------------------------------------------------ §94 defaults

	/** Defaults-off: world edits AND structured admin commands are denied until enabled. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-m3-defaults")
	public void worldEditsAndOptionalCommandsStayDisabledButGiveIsCore(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "m3-default-owner");

		context.runAtTick(5, () -> {
			rt.setWorldEditEnabled(false);
			rt.setAdminCommandsEnabled(false);
			world.spawnEntity(owner); // gateway resolves requesters through the world
			owner.getInventory().clear();
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(abs(context, 2, 2, 2)));
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			// node granted, flag OFF → still denied at the policy gate
			rt.permissions().grant(owner.getUuid(), PermissionNodes.WORLD_EDIT);
			ToolResult fill = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.fill", fillArgs(abs(context, 3, 2, 3),
					abs(context, 3, 2, 3), "minecraft:stone"));
			context.assertTrue(fill.errorOrNull().orElseThrow().code()
				== ErrorCode.POLICY_DENIED, "disabled worldedit must be POLICY_DENIED");
			context.assertTrue(world.getBlockState(abs(context, 3, 2, 3)).isOf(Blocks.AIR),
				"zero blocks changed while disabled");

			ToolResult give = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.give", Map.of("itemId", "minecraft:diamond", "count", 5));
			context.assertTrue(give.status() == ToolResult.Status.SUCCESS,
				"bounded /give is now a core fulfilment path: " + give.errorOrNull());
			context.assertTrue(owner.getInventory().count(Items.DIAMOND) == 5,
				"core /give targets the requesting player");

			// A self-targeted beneficial effect is a bounded core ability.
			rt.permissions().grant(owner.getUuid(), PermissionNodes.COMMAND_EFFECT);
			ToolResult effect = dispatch(rt, world, avatar, owner.getUuid(),
				"minecraft.command.effect", Map.of("effectId", "minecraft:speed",
					"durationTicks", 100, "amplifier", 0));
			context.assertTrue(effect.status() == ToolResult.Status.SUCCESS,
				"bounded beneficial effect remains a core ability: " + effect.errorOrNull());

			// Changing time affects every player and remains behind the admin flag.
			rt.permissions().grant(owner.getUuid(), PermissionNodes.COMMAND_WORLD);
			long timeBefore = world.getTimeOfDay();
			ToolResult setTime = dispatch(rt, world, avatar, owner.getUuid(),
				"world.set_time", Map.of("preset", "night"));
			context.assertTrue(setTime.errorOrNull().orElseThrow().code()
				== ErrorCode.POLICY_DENIED,
				"server-wide time changes stay disabled until explicitly enabled");
			context.assertTrue(world.getTimeOfDay() == timeBefore,
				"denied time change leaves the world clock untouched");

			// Enabling only world edit still does not unlock optional commands.
			rt.setWorldEditEnabled(true);
			ToolResult stillNoTime = dispatch(rt, world, avatar, owner.getUuid(),
				"world.set_time", Map.of("preset", "night"));
			context.assertTrue(stillNoTime.errorOrNull().orElseThrow().code()
				== ErrorCode.POLICY_DENIED, "flags are independent");
			cleanup(rt, owner);
			rt.setAdminCommandsEnabled(false);
			context.complete();
		});
	}
}
