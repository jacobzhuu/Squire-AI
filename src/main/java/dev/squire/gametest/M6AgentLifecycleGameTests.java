package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;

import dev.squire.server.agent.SquireAgentStateStore;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.input.InputGateway;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.TaskState;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 方案 A1–A4 黑盒验收：长期身份复用、dismiss/summon 家当恢复、stop 取消任务、
 * STAY 锚点回归、look/emote 聊天入口（无 LLM）。
 *
 * <p>每个测试使用独立 fake-owner，避免共享 runtime 的档案互相污染。</p>
 */
public final class M6AgentLifecycleGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static void place(net.minecraft.entity.Entity entity, Vec3d feetCenter) {
		entity.refreshPositionAndAngles(feetCenter.x, feetCenter.y, feetCenter.z, 0.0f,
			0.0f);
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	// ------------------------------------------------------- A1：身份永久复用

	@GameTest(templateName = FLOOR)
	public void repeatSummonKeepsIdentity(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a1-repeat-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity first = rt.summonFor(owner);
			UUID firstAgentId = first.agentId();
			String firstName = first.getCustomName() == null
				? null : first.getCustomName().getString();

			AvatarEntity second = rt.summonFor(owner); // re-summon replaces the body
			context.assertTrue(first.isRemoved(),
				"old body must be discarded on re-summon");
			context.assertTrue(second.isAlive(), "new body alive");
			context.assertTrue(firstAgentId.equals(second.agentId()),
				"agentId must survive re-summon (方案 A1)");
			context.assertTrue(firstName != null && firstName.equals(
					second.getCustomName() == null ? null
						: second.getCustomName().getString()),
				"display name must be stable across summons (无 summonCounter)");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// ------------------------------------------- A2：dismiss→summon 恢复身份家当

	@GameTest(templateName = FLOOR)
	public void dismissResummonRestoresBelongingsAndMode(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a2-belongings-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity first = rt.summonFor(owner);
			first.insertStack(new ItemStack(Items.OAK_LOG, 7));
			first.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND,
				new ItemStack(Items.IRON_SWORD));
			rt.executeControl(owner, SquireRuntime.ControlIntent.STAY); // mode → STAY

			var dismissed = rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.assertTrue(dismissed.success(), "dismiss succeeds");

			var record = rt.agentStore().recordOfOwner(owner.getUuid());
			context.assertTrue(record.isPresent(),
				"A1: AgentRecord survives dismiss");
			context.assertTrue(!record.get().activeBody,
				"record marks the body inactive after dismiss");

			AvatarEntity second = rt.summonFor(owner);
			context.assertTrue(first.agentId().equals(second.agentId()),
				"same identity across dismiss/resummon");
			context.assertTrue(second.countItem(net.minecraft.registry.Registries.ITEM
					.getId(Items.OAK_LOG)) == 7,
				"inventory restored after resummon");
			ItemStack mainHand = second.getEquippedStack(
				net.minecraft.entity.EquipmentSlot.MAINHAND);
			context.assertTrue(mainHand.getItem() == Items.IRON_SWORD,
				"equipment restored after resummon");
			context.assertTrue(second.mode() == AvatarEntity.MovementMode.STAY,
				"movement mode restored from the record (方案 A2)");
			context.complete();
		});
	}

	// --------------------------------- 方案 0.6：PATROL 同样必须跨 dismiss 恢复

	/**
	 * 巡逻是恢复链路上唯一曾被漏掉的模式：档案里存了 PATROL，三处恢复 switch 都没写
	 * 这个分支，静默退化成 IDLE——玩家点了巡逻、退出再进来，他就站着不动了。
	 * 收敛到 {@code applyMovementState} 之后，这条测试守住它不再烂回去。
	 */
	@GameTest(templateName = FLOOR)
	public void patrolModeSurvivesDismissAndResummon(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "p0-patrol-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity first = rt.summonFor(owner);
			first.setPatrolMode(); // 面板「巡逻」按钮走的同一条路径

			var dismissed = rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.assertTrue(dismissed.success(), "dismiss succeeds");

			var record = rt.agentStore().recordOfOwner(owner.getUuid());
			context.assertTrue(record.isPresent()
					&& "PATROL".equals(record.get().movementMode),
				"the record stores PATROL while dismissed");

			AvatarEntity second = rt.summonFor(owner);
			context.assertTrue(second.mode() == AvatarEntity.MovementMode.PATROL,
				"patrol must survive dismiss/resummon (was silently downgraded)");
			context.complete();
		});
	}

	// ------------------------------------------------ A3：stop 取消 RUNNING 任务

	@GameTest(templateName = FLOOR)
	public void stopCancelsRunningTask(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a3-stop-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AtomicReference<AvatarEntity> avatarRef = new AtomicReference<>();
		AtomicReference<Task> taskRef = new AtomicReference<>();
		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			avatarRef.set(avatar);
			rt.scheduler().register(new dev.squire.server.task.TaskExecutor() {
				@Override public String type() { return "gametest.long_running"; }
				@Override public void start(Task task) { }
				@Override public StepOutcome tick(Task task, long tick) {
					return StepOutcome.CONTINUE;
				}
				@Override public void cancel(Task task) { avatar.stopMoving(); }
			});

			Task walk = new Task(avatar.agentId(), owner.getUuid(),
				"gametest.long_running",
				TaskPriority.P3_USER_TASK, "walk far away", null, null, 600L,
				RetryPolicy.DEFAULT, true, "gametest", java.util.Map.of());
			rt.scheduler().submit(walk, rt.tickNow());
			taskRef.set(walk);
		});

		context.runAtTick(10, () -> {
			AvatarEntity avatar = avatarRef.get();
			Task walk = taskRef.get();
			context.assertTrue(walk.state() == TaskState.RUNNING,
				"test precondition: walk really reached RUNNING before stop");
			var stopped = rt.executeControl(owner, SquireRuntime.ControlIntent.STOP);
			context.assertTrue(stopped.success(), "owner STOP succeeds");
			context.assertTrue(rt.scheduler().current(avatar.agentId()).isEmpty()
					|| !rt.scheduler().current(avatar.agentId()).get().taskId()
						.equals(walk.taskId()),
				"stop must cancel the agent's running task (方案 A3)");
			boolean cancelled = rt.scheduler().finishedTasks().stream()
				.anyMatch(t -> t.taskId().equals(walk.taskId())
					&& t.state() == TaskState.CANCELLED);
			context.assertTrue(cancelled, "task state must be CANCELLED, got "
				+ rt.scheduler().finishedTasks().stream()
					.filter(t -> t.taskId().equals(walk.taskId()))
					.map(t -> t.state().name()).findFirst().orElse("<missing>"));
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.IDLE,
				"stop parks the body in IDLE");
			context.complete();
		});
	}

	// --------------------------------------------- A3：STAY 锚点被推走后自动回归

	@GameTest(templateName = FLOOR, tickLimit = 300)
	public void stayReturnsToAnchorAfterPush(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a3-stay-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
		BlockPos anchorRel = new BlockPos(5, 2, 5);
		Vec3d anchorAbs = Vec3d.ofBottomCenter(context.getAbsolutePos(anchorRel));
		AtomicReference<AvatarEntity> avatarRef = new AtomicReference<>();

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			avatarRef.set(avatar);
			place(avatar, anchorAbs);
			InputGateway.acceptChat(owner, "待在这里"); // FastPath STAY at anchor
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"'待在这里' anchors STAY here");
			// 推开约 5.7 格，但<b>仍然踩在测试场地上</b>。原来这里推到 (13,2,13)，
			// 而 empty_floor 只有 9×4×9（相对坐标 0..8）——那是把他推进了虚空，
			// 他既走不回来也不会失败，测试只会静静地耗到 tickLimit。
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(
				new BlockPos(1, 2, 1))));
		});

		// 待命的语义是「就站这一格」：DEFAULT_STAY_RADIUS 是 0，StayAreaGoal 会把他
		// 一路导航回锚点方块。所以判据是<b>他回到了锚点那一格</b>——用
		// `dist² <= radius²` 是不可能满足的，一个走回来的实体落在方块内的某个
		// 浮点位置上，永远不会和锚点分毫不差。
		BlockPos anchorAbsBlock = context.getAbsolutePos(anchorRel);
		context.forEachRemainingTick(() -> {
			AvatarEntity avatar = avatarRef.get();
			if (avatar != null && avatar.getBlockPos().equals(anchorAbsBlock)
					&& avatar.squaredDistanceTo(anchorAbs) <= 1.0) {
				context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
					"still STAY after walking back");
				context.complete();
			}
		});
	}

	// ------------------------------------- A3：look/emote 聊天入口（无 provider）

	@GameTest(templateName = FLOOR)
	public void lookAndEmoteViaChatWithoutProvider(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a3-look-owner");
		AtomicReference<AvatarEntity> ref = new AtomicReference<>();

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			ref.set(avatar);
			float pitchBefore = avatar.getPitch();

			dev.squire.server.provider.ProviderRegistry.clear(); // NO LLM available
			InputGateway.acceptChat(owner, "看着我"); // must NOT touch any provider
			context.assertTrue(avatar.isAlive(), "'看着我' never harms the agent");

			InputGateway.acceptChat(owner, "挥手");
			InputGateway.acceptChat(owner, "跳一下");
			InputGateway.acceptChat(owner, "点头");
			context.assertTrue(Math.abs(avatar.getPitch() - pitchBefore) > 0.0f,
				"emotes produce a visible head/body change");
			context.complete();
		});
	}

	// ------------------------------- A4：任务快照声明式导出与幂等恢复（单机重启语义）

	@GameTest(templateName = FLOOR)
	public void taskSnapshotsRoundTripThroughSchedulerRestore(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a4-task-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);

			java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
			params.put("x", 10.0);
			params.put("y", 2.0);
			params.put("z", 10.0);
			params.put("arriveWithinSq", 2.25);
			Task walk = new Task(avatar.agentId(), owner.getUuid(),
				dev.squire.server.task.executors.MoveToExecutor.TYPE,
				TaskPriority.P3_USER_TASK, "exported walk", null, null, 1200L,
				RetryPolicy.DEFAULT, true, "gametest", params);
			rt.scheduler().submit(walk, rt.tickNow());

			var snapshots = rt.scheduler().exportActive(rt.tickNow());
			context.assertTrue(snapshots.size() >= 1, "live task exported");
			var snap = snapshots.stream()
				.filter(s -> s.type()
					.equals(dev.squire.server.task.executors.MoveToExecutor.TYPE))
				.findFirst().orElseThrow();

			// simulate restart: fresh scheduler restores from declarative snapshots
			dev.squire.server.task.TaskScheduler fresh =
				new dev.squire.server.task.TaskScheduler();
			fresh.register(new dev.squire.server.task.executors.MoveToExecutor(
				rt.runtimeServicesForTest()));
			java.util.concurrent.atomic.AtomicBoolean markedUnrecoverable =
				new java.util.concurrent.atomic.AtomicBoolean(false);
			int rearmed = fresh.restoreFromSnapshots(java.util.List.of(snap),
				rt.tickNow(), s -> markedUnrecoverable.set(true));
			context.assertTrue(!markedUnrecoverable.get(),
				"known executor must not be unrecoverable");
			context.assertTrue(rearmed == 1, "one task re-armed, got " + rearmed);
			context.assertTrue(fresh.liveCount() == 1, "restored task is pending/running");
			Task restored = fresh.findTask(snap.taskId()).orElseThrow();
			context.assertTrue(restored.taskId().equals(snap.taskId()),
				"restart preserves taskId");
			context.assertTrue(restored.successCondition() != null,
				"restored task keeps a machine-checkable success condition");
			fresh.cancelAll();
			context.complete();
		});
	}

	// ------------------------------------------------- A1：Store NBT 往返 + 去重

	@GameTest(templateName = FLOOR)
	public void agentStoreNbtRoundTripKeepsRecords(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "a1-nbt-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			avatar.insertStack(new ItemStack(Items.COBBLESTONE, 12));
			rt.persistSnapshot(avatar);

			SquireAgentStateStore store = rt.agentStore();
			store.recordOfAgent(avatar.agentId()).orElseThrow().bellTier =
				dev.squire.server.item.BellTier.RESONANT.id();
			store.markDeath(avatar.agentId(), 100L, 14_500L);
			net.minecraft.nbt.NbtCompound nbt = store.writeNbt(new net.minecraft.nbt.NbtCompound());
			SquireAgentStateStore revived =
				SquireAgentStateStore.createFromNbtPublic(nbt);
			var record = revived.recordOfAgent(avatar.agentId());
			context.assertTrue(record.isPresent(), "agentId indexed after NBT round trip");
			context.assertTrue(record.get().displayName.equals(
					store.recordOfAgent(avatar.agentId()).orElseThrow().displayName),
				"display name survives round trip");
			context.assertTrue(record.get().inventory.stream()
					.filter(s -> !s.isEmpty())
					.mapToInt(ItemStack::getCount).sum() == 12,
				"inventory count survives round trip");
			context.assertTrue(record.get().deathPending
					&& record.get().deathTick == 100L
					&& record.get().reviveAvailableTick == 14_500L,
				"death recall cooldown survives world NBT round trip");
			context.assertTrue(record.get().bellTier.equals(
				dev.squire.server.item.BellTier.RESONANT.id()),
				"bell quality survives world NBT round trip");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// -------------------------------- 收缩后：资源措辞统一编译为 /give

	@GameTest(templateName = FLOOR, tickLimit = 700, batchId = "squire-fastpath-b01")
	public void fastPathGivesThirtyTwoTorchesWithoutProvider(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "b01-fastpath-torch-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			owner.getInventory().clear();
			// Existing avatar stock must remain untouched: no gather/craft/delivery graph.
			avatar.insertStack(new ItemStack(Items.TORCH, 5));
			avatar.insertStack(new ItemStack(Items.COAL, 7));
			avatar.insertStack(new ItemStack(Items.OAK_LOG, 1));
			ProviderRegistry.clear();
			var routed = InputGateway.acceptChat(owner, "给我 32 个火把").orElseThrow();
			context.assertTrue(routed.success(), "FastPath /give executes: " + routed.message());
			// 取物不再是"瞬间出现在玩家背包"：伙伴先用指令给自己，再走过来扔给你。
			// 但它仍然不允许退化成一整套物理采集目标图。
			context.assertTrue(rt.goals().all().stream().noneMatch(g ->
				g.ownerId().equals(owner.getUuid())
					&& (g.state() == dev.squire.server.goal.GoalRecord.State.RUNNING
						|| g.state() == dev.squire.server.goal.GoalRecord.State.REPLANNING)),
				"resource request must not create a physical goal graph");
		});
		context.runAtTick(400, () -> {
			AvatarEntity avatar = rt.agents().resolveForOwner(owner.getUuid()).orElseThrow();
			// FakePlayer 不会像真玩家那样自动拾取地上的物品，所以这里数"到手 + 脚边"
			// 的总量——这正是交付任务的完成判据。
			context.assertTrue(handedOver(owner, Items.TORCH) == 32,
				"the 32 torches are handed over to the player, not teleported into his bag");
			// 伙伴原有的 5 个火把只是路过它的背包，最后必须原封不动地留下。
			context.assertTrue(avatar.inventory().countOf("minecraft:torch") == 5,
				"the avatar's own stock is neither consumed nor kept from the delivery");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 玩家背包里的数量 + 已经放在他脚边的数量。 */
	private static int handedOver(FakePlayer player, net.minecraft.item.Item item) {
		int total = player.getInventory().count(item);
		for (var entity : player.getWorld().getEntitiesByClass(
				net.minecraft.entity.ItemEntity.class,
				player.getBoundingBox().expand(4.0),
				e -> e.isAlive() && e.getStack().getItem() == item)) {
			total += entity.getStack().getCount();
		}
		return total;
	}

	// -------------------------------- 制作措辞同样只走 /give

	@GameTest(templateName = FLOOR, tickLimit = 1800, batchId = "squire-fastpath-b02")
	public void fastPathCraftsIronPickaxeWithoutProvider(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "b02-fastpath-pick-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			owner.getInventory().clear();
			avatar.insertStack(new ItemStack(Items.RAW_IRON, 3));
			avatar.insertStack(new ItemStack(Items.COAL, 1));
			avatar.insertStack(new ItemStack(Items.OAK_LOG, 1));
			ProviderRegistry.clear();
			var routed = InputGateway.acceptChat(owner, "帮我做一把铁镐").orElseThrow();
			context.assertTrue(routed.success(), "FastPath /give executes: " + routed.message());
		});
		context.runAtTick(400, () -> {
			AvatarEntity avatar = rt.agents().resolveForOwner(owner.getUuid()).orElseThrow();
			context.assertTrue(handedOver(owner, Items.IRON_PICKAXE) == 1,
				"craft wording is fulfilled by command + handover, no furnace state machine");
			context.assertTrue(avatar.inventory().countOf("minecraft:raw_iron") == 3,
				"avatar materials stay untouched");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}
}
