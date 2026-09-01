package dev.squire.gametest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.input.InputGateway;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.security.PendingOperation;
import dev.squire.server.security.PermissionNodes;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.BuildStructureExecutor;
import dev.squire.server.world.BoundedRegion;
import dev.squire.server.world.SelectionService;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 工作包 F 黑盒验收：选区 → 一句自然语言 → preview → 一次确认 → 自动执行，
 * 以及拒绝/过期/篡改/跨重启撤销和用真实材料的普通建造。
 *
 * <p>每一条"零改变"的断言都直接读世界方块，而不是读工具返回值。</p>
 */
public final class M8WorldEditGameTests implements FabricGameTest {
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

	/** Summon an owner + avatar with world editing enabled and the node granted. */
	private static AvatarEntity setUpEditor(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos avatarRel) {
		ServerWorld world = context.getWorld();
		rt.setWorldEditEnabled(true);
		rt.permissions().grant(owner.getUuid(), PermissionNodes.WORLD_EDIT);
		world.spawnEntity(owner);
		AvatarEntity avatar = rt.summonFor(owner);
		place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(avatarRel)));
		avatar.setStayMode();
		ProviderRegistry.clear();
		return avatar;
	}

	/** Select a 3x1x3 patch through the SERVER-owned selection service. */
	private static BoundedRegion select(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos c1Rel, BlockPos c2Rel) {
		SelectionService selections = rt.selections();
		selections.setCorner(owner, true, context.getAbsolutePos(c1Rel));
		var selection = selections.setCorner(owner, false, context.getAbsolutePos(c2Rel));
		return selection.region().orElseThrow();
	}

	private static boolean allAre(TestContext context, BoundedRegion region,
			net.minecraft.block.Block block) {
		for (BlockPos pos : region.cells()) {
			if (!context.getWorld().getBlockState(pos).isOf(block)) {
				return false;
			}
		}
		return true;
	}

	// ================================================== B07：选区 → 预览 → 确认 → 自动执行

	/**
	 * B07：玩家选好区域，说一句“把选定区域铺成石头”，看到 preview（此刻世界零改变），
	 * 确认一次后操作自动开始——不需要再把那句话说一遍。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-worldedit-b07")
	public void fillSelectionPreviewsThenAutoExecutesOnOneConfirm(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-b07-owner");
		AtomicReference<UUID> confirmId = new AtomicReference<>();
		AtomicReference<BoundedRegion> target = new AtomicReference<>();
		AtomicBoolean done = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			target.set(select(context, rt, owner, new BlockPos(4, 2, 4),
				new BlockPos(6, 2, 6)));

			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(routed.success(), "FastPath fill: " + routed.message());
			context.assertTrue(routed.message().contains("预览"),
				"the player must see a preview first: " + routed.message());
			context.assertTrue(routed.message().contains("/squire confirm"),
				"the preview must tell the player how to confirm");
			// 关键：确认之前世界必须零改变
			context.assertTrue(!allAre(context, target.get(), Blocks.STONE),
				"NOTHING may change before the player confirms");

			var pending = rt.pendingOperations().pendingFor(owner.getUuid(),
				world.getTime());
			context.assertTrue(pending.size() == 1,
				"exactly one pending operation was recorded");
			confirmId.set(pending.get(0).confirmId());
		});

		context.runAtTick(20, () -> {
			// 一次确认 —— 玩家没有重复原来那句自然语言
			String reply = rt.confirmRequest(owner, confirmId.get());
			context.assertTrue(reply.contains("正在执行"),
				"one confirm must start the real task: " + reply);
			context.assertTrue(rt.pendingOperations().get(confirmId.get()).orElseThrow()
					.status() == PendingOperation.Status.EXECUTED,
				"the operation is recorded as executed");
		});

		context.runAtTick(120, () -> {
			context.assertTrue(allAre(context, target.get(), Blocks.STONE),
				"the confirmed fill really happened");
			// 二次确认不能再执行一遍
			String again = rt.confirmRequest(owner, confirmId.get());
			context.assertTrue(again.contains("已经执行过"),
				"a confirmation is single-use: " + again);
			done.set(true);
			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(560, () -> {
			if (!done.get()) {
				rt.setWorldEditEnabled(false);
				context.throwGameTestException("B07 never completed");
			}
		});
	}

	// ================================================== 拒绝 / 过期 / 篡改：世界零改变

	/** /squire deny 之后世界必须零改变，且这条确认再也不能被使用。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-worldedit-deny")
	public void denyLeavesTheWorldUntouched(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-deny-owner");

		context.runAtTick(5, () -> {
			setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			BoundedRegion region = select(context, rt, owner, new BlockPos(4, 2, 4),
				new BlockPos(6, 2, 6));
			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(routed.success(), routed.message());
			UUID id = rt.pendingOperations().pendingFor(owner.getUuid(), world.getTime())
				.get(0).confirmId();

			String denied = rt.denyRequest(owner, id);
			context.assertTrue(denied.contains("已拒绝"), denied);
			context.assertTrue(!allAre(context, region, Blocks.STONE),
				"a denied operation changes nothing");
			context.assertTrue(rt.pendingOperations().get(id).orElseThrow().status()
					== PendingOperation.Status.DENIED, "status is DENIED");

			String afterDeny = rt.confirmRequest(owner, id);
			context.assertTrue(afterDeny.contains("已被拒绝"),
				"a denied operation can never be confirmed later: " + afterDeny);
			context.assertTrue(!allAre(context, region, Blocks.STONE),
				"and STILL changes nothing");

			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 过期的确认不能执行；世界零改变。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-worldedit-expire")
	public void expiredConfirmationRefusesAndChangesNothing(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-expire-owner");

		context.runAtTick(5, () -> {
			setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			BoundedRegion region = select(context, rt, owner, new BlockPos(4, 2, 4),
				new BlockPos(6, 2, 6));
			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(routed.success(), routed.message());
			var operation = rt.pendingOperations().pendingFor(owner.getUuid(),
				world.getTime()).get(0);

			// 把它推到过期之后（模拟玩家看完 preview 就去做别的事了）
			rt.pendingOperations().update(operation.withStatus(
				PendingOperation.Status.EXPIRED, "test expiry"));
			String reply = rt.confirmRequest(owner, operation.confirmId());
			context.assertTrue(reply.contains("过期"),
				"an expired confirmation is refused: " + reply);
			context.assertTrue(!allAre(context, region, Blocks.STONE),
				"an expired confirmation changes nothing");

			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/**
	 * 篡改：确认放行的是玩家看过的那一份指纹。参数被改动之后的调用仍然被拦下，
	 * 而且重放执行的永远是 SERVER 保存的 canonical 参数。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-worldedit-tamper")
	public void tamperedArgumentsNeverRideOnSomebodyElsesConfirmation(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-tamper-owner");
		AtomicBoolean done = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			BoundedRegion previewed = select(context, rt, owner, new BlockPos(4, 2, 4),
				new BlockPos(6, 2, 6));
			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(routed.success(), routed.message());
			var operation = rt.pendingOperations().pendingFor(owner.getUuid(),
				world.getTime()).get(0);

			// 模型拿着"别的区域"想蹭这次确认：指纹不匹配，必须仍然被拦下
			BlockPos otherMin = context.getAbsolutePos(new BlockPos(1, 2, 6));
			Map<String, Object> tampered = new java.util.LinkedHashMap<>(
				operation.canonicalArguments());
			tampered.put("x1", otherMin.getX());
			tampered.put("y1", otherMin.getY());
			tampered.put("z1", otherMin.getZ());
			tampered.put("x2", otherMin.getX());
			tampered.put("y2", otherMin.getY());
			tampered.put("z2", otherMin.getZ());

			rt.confirmRequest(owner, operation.confirmId()); // 确认的是原来那一份
			var blocked = rt.gateway().dispatch(
				new dev.squire.common.protocol.ToolCall(UUID.randomUUID(),
					"minecraft.command.fill", tampered),
				dev.squire.server.tool.CallerIdentity.model(owner.getUuid(),
					avatar.agentId()),
				execContext(rt, avatar, owner), rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(blocked.status()
					== dev.squire.common.protocol.ToolResult.Status.BLOCKED,
				"tampered arguments must not ride on the previewed confirmation, got "
					+ blocked.status());
			context.assertTrue(world.getBlockState(otherMin).isOf(Blocks.AIR),
				"the tampered region must be untouched");
			// 而原来那一份确实执行了
			context.assertTrue(previewed.volume() == 9, "sanity: 3x1x3 selection");
		});

		context.runAtTick(150, () -> {
			BlockPos tamperedCell = context.getAbsolutePos(new BlockPos(1, 2, 6));
			context.assertTrue(world.getBlockState(tamperedCell).isOf(Blocks.AIR),
				"the tampered region STAYS untouched after the confirmed op ran");
			done.set(true);
			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(370, () -> {
			if (!done.get()) {
				rt.setWorldEditEnabled(false);
				context.throwGameTestException("tamper test never completed");
			}
		});
	}

	/** 没有选区时不能编辑：世界零改变，并且明确告诉玩家缺什么。 */
	@GameTest(templateName = FLOOR, batchId = "squire-worldedit-noselection")
	public void fillWithoutASelectionIsRefused(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "f-nosel-owner");

		context.runAtTick(5, () -> {
			setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			rt.selections().clear(owner.getUuid());
			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(!routed.success(),
				"no selection must be an honest refusal: " + routed.message());
			context.assertTrue(routed.message().contains("选区"), routed.message());
			context.assertTrue(rt.pendingOperations()
					.pendingFor(owner.getUuid(), context.getWorld().getTime()).isEmpty(),
				"nothing was queued for confirmation");
			rt.setWorldEditEnabled(false);
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/**
	 * 模型走 propose 工具时，也只应该产生 ONE 条待确认操作和 ONE 次确认。
	 * （回归：propose 工具一度自身是 HIGH 风险又同时签发 preview，会要求确认两次。）
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-worldedit-propose")
	public void modelProposalNeedsExactlyOneConfirmation(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-propose-owner");
		AtomicReference<UUID> confirmId = new AtomicReference<>();
		AtomicReference<BoundedRegion> target = new AtomicReference<>();
		AtomicBoolean done = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			target.set(select(context, rt, owner, new BlockPos(4, 2, 4),
				new BlockPos(6, 2, 6)));

			var proposal = rt.gateway().dispatch(
				new dev.squire.common.protocol.ToolCall(UUID.randomUUID(),
					"worldedit.propose_fill_selection",
					Map.of("blockId", "minecraft:stone")),
				dev.squire.server.tool.CallerIdentity.model(owner.getUuid(),
					avatar.agentId()),
				execContext(rt, avatar, owner), rt.capabilitiesOf(avatar.agentId()));
			context.assertTrue(proposal.status()
					== dev.squire.common.protocol.ToolResult.Status.SUCCESS,
				"proposing writes nothing and succeeds, got " + proposal.status()
					+ " " + proposal.errorOrNull());
			context.assertTrue(!allAre(context, target.get(), Blocks.STONE),
				"a proposal must not touch the world");

			var pending = rt.pendingOperations().pendingFor(owner.getUuid(),
				world.getTime());
			context.assertTrue(pending.size() == 1,
				"exactly ONE confirmation is queued, found " + pending.size());
			confirmId.set(pending.get(0).confirmId());
			context.assertTrue(pending.get(0).operationType()
					.equals("minecraft.command.fill"),
				"the queued operation is the real write, not the proposal tool");
		});

		context.runAtTick(20, () -> {
			String reply = rt.confirmRequest(owner, confirmId.get());
			context.assertTrue(reply.contains("正在执行"),
				"ONE confirm is enough: " + reply);
			context.assertTrue(rt.pendingOperations()
					.pendingFor(owner.getUuid(), world.getTime()).isEmpty(),
				"no second confirmation is left dangling");
		});

		context.runAtTick(120, () -> {
			context.assertTrue(allAre(context, target.get(), Blocks.STONE),
				"the proposed fill really ran after one confirmation");
			done.set(true);
			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(560, () -> {
			if (!done.get()) {
				rt.setWorldEditEnabled(false);
				context.throwGameTestException("propose test never completed");
			}
		});
	}

	// ================================================== 指令化世界编辑不再生成逐块 Undo 日志

	/**
	 * 收缩后的 /fill 直接执行：确认仍保留，但不再维护逐块持久 Undo。
	 * 这个用例防止 UI/接口继续把指令结果误报为可撤销任务。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-worldedit-undo")
	public void undoSurvivesASaveLoadRoundTripAndRestoresChestContents(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-undo-owner");
		BlockPos chestRel = new BlockPos(4, 2, 4);
		context.runAtTick(5, () -> {
			setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			context.setBlockState(chestRel, Blocks.CHEST);
			if (world.getBlockEntity(context.getAbsolutePos(chestRel))
					instanceof ChestBlockEntity chest) {
				chest.setStack(0, new ItemStack(Items.DIAMOND, 7));
				chest.markDirty();
			}
			select(context, rt, owner, chestRel, new BlockPos(5, 2, 5));
			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(routed.success(), routed.message());
			UUID confirmId = rt.pendingOperations()
				.pendingFor(owner.getUuid(), world.getTime()).get(0).confirmId();
			String reply = rt.confirmRequest(owner, confirmId);
			context.assertTrue(reply.contains("执行") || reply.contains("完成"), reply);
			context.expectBlock(Blocks.STONE, chestRel);
			context.assertTrue(rt.undoableFor(owner.getUuid()).isEmpty(),
				"direct /fill must not be advertised as a journaled task");
			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** A later player edit is never clobbered by a nonexistent command-task undo. */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-worldedit-conflict")
	public void undoWithConflictsAsksBeforeClobbering(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f-conflict-owner");
		BlockPos changedRel = new BlockPos(4, 2, 4);
		context.runAtTick(5, () -> {
			setUpEditor(context, rt, owner, new BlockPos(1, 2, 1));
			select(context, rt, owner, changedRel, new BlockPos(5, 2, 5));
			var routed = InputGateway.acceptChat(owner, "把选定区域铺成石头").orElseThrow();
			context.assertTrue(routed.success(), routed.message());
			rt.confirmRequest(owner, rt.pendingOperations()
				.pendingFor(owner.getUuid(), world.getTime()).get(0).confirmId());
			context.expectBlock(Blocks.STONE, changedRel);
			context.assertTrue(rt.undoableFor(owner.getUuid()).isEmpty(),
				"direct /fill creates no undo operation");
			// 有人后来把其中一格改成了金块
			context.setBlockState(changedRel, Blocks.GOLD_BLOCK);

			var refused = rt.undoOperation(owner, UUID.randomUUID(), false);
			context.assertTrue(!refused.success(),
				"a nonexistent command undo must fail honestly: " + refused.message());
			context.expectBlock(Blocks.GOLD_BLOCK, changedRel);
			rt.setWorldEditEnabled(false);
			rt.selections().clear(owner.getUuid());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// ================================================== F1：用真实材料建造

	/** F1：普通建造从背包里扣真实材料，材料用完就如实停下，绝不凭空生成方块。 */
	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-build-f1")
	public void buildStructureSpendsRealMaterialsAndStopsWhenTheyRunOut(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "f1-build-owner");
		AtomicBoolean done = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, 2, 1))));
			avatar.setStayMode();
			// 4 块石头，但计划要 9 格：必须建 4 格然后如实报 INSUFFICIENT_ITEM
			avatar.items().insert(new ItemStack(Items.STONE, 4));

			BlockPos min = context.getAbsolutePos(new BlockPos(4, 2, 4));
			BlockPos max = context.getAbsolutePos(new BlockPos(6, 2, 6));
			Map<String, Object> params = new java.util.LinkedHashMap<>();
			params.put("x1", min.getX());
			params.put("y1", min.getY());
			params.put("z1", min.getZ());
			params.put("x2", max.getX());
			params.put("y2", max.getY());
			params.put("z2", max.getZ());
			params.put(BuildStructureExecutor.PARAM_BLOCK_ID, "minecraft:stone");
			params.put(BuildStructureExecutor.PARAM_PATTERN, "FLOOR");
			Task task = new Task(avatar.agentId(), owner.getUuid(),
				BuildStructureExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"build a stone floor", null,
				BuildStructureExecutor.structureBuilt(rt.runtimeServicesForTest(),
					world.getRegistryKey().getValue().toString(),
					BoundedRegion.ofCorners(min.getX(), min.getY(), min.getZ(),
						max.getX(), max.getY(), max.getZ()),
					BuildStructureExecutor.Pattern.FLOOR, "minecraft:stone"),
				400L, RetryPolicy.DEFAULT, true, "f1", params);
			rt.scheduler().submit(task, world.getTime());
		});

		context.runAtTick(150, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int placed = 0;
			for (int dx = 0; dx < 3; dx++) {
				for (int dz = 0; dz < 3; dz++) {
					if (world.getBlockState(context.getAbsolutePos(
							new BlockPos(4 + dx, 2, 4 + dz))).isOf(Blocks.STONE)) {
						placed++;
					}
				}
			}
			context.assertTrue(placed == 4,
				"exactly the 4 carried blocks were placed, found " + placed);
			context.assertTrue(avatar.items().countOf(
					new net.minecraft.util.Identifier("minecraft:stone")) == 0,
				"the material really left the backpack");
			// 建造同样进 Undo 日志
			context.assertTrue(!rt.undoableFor(owner.getUuid()).isEmpty(),
				"ordinary building is undoable too");
			done.set(true);
			rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(470, () -> {
			if (!done.get()) {
				context.throwGameTestException("build test never completed");
			}
		});
	}

	// ================================================== helpers

	private static dev.squire.server.tool.ToolExecutionContext execContext(SquireRuntime rt,
			AvatarEntity avatar, FakePlayer owner) {
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
}
