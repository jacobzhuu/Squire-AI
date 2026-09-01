package dev.squire.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.perception.PatrolInspector;
import dev.squire.server.profile.Ability;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.HomeRoutineExecutor;
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
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/**
 * 第 2 期后半验收：行为模式真的不一样了。
 *
 * <p>待命是<b>一个区域</b>而不是一个钉子，巡逻是<b>一条线</b>而不是原地转圈，
 * 回到家会自己做点事。三者的共同点是：拒绝和触发都要有<b>说得出来的理由</b>——
 * 一个默默不动的伙伴和一个坏掉的伙伴，玩家分不出来。</p>
 */
public final class M15BehaviourGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static AvatarEntity summon(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos relative) {
		context.getWorld().spawnEntity(owner);
		AvatarEntity avatar = rt.summonFor(owner);
		Vec3d feet = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
		avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
		return avatar;
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	private static TargetPosition at(TestContext context, BlockPos relative) {
		BlockPos abs = context.getAbsolutePos(relative);
		return new TargetPosition(
			context.getWorld().getRegistryKey().getValue().toString(),
			abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
	}

	// ================================================== 待命是一个区域

	/**
	 * 待命时他可以继续干活，但不能离开区域——而且被拦住时给的是
	 * {@code OUT_OF_STAY_AREA}，不是笼统的「走不过去」。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-stay-area")
	public void stayAreaRefusesAMoveOutsideItWithItsOwnReason(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "stay-area-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(4, 2, 4));
			SquireProfile profile = rt.profileOf(avatar);
			profile.stayRadius = 3;
			avatar.setStayMode();
			context.assertTrue(avatar.stayArea().isPresent(),
				"待命时区域锚点必须存在");

			// 区域内：照常接受，待命不该让他连活都干不了。
			MoveHandle inside = avatar.moveTo(at(context, new BlockPos(5, 2, 5)),
				MoveOptions.WALK);
			context.assertFalse(inside.state() == MoveHandle.State.FAILED
					&& AvatarEntity.OUT_OF_STAY_AREA.equals(inside.errorCode()),
				"a target inside the circle must not be refused by the stay gate");

			// 区域外：拒绝，并说清楚是哪一条规则拦的。
			avatar.setStayMode();
			MoveHandle outside = avatar.moveTo(at(context, new BlockPos(8, 2, 8)),
				MoveOptions.WALK);
			context.assertTrue(outside.state() == MoveHandle.State.FAILED,
				"a target outside the stay area must be refused");
			context.assertTrue(AvatarEntity.OUT_OF_STAY_AREA.equals(outside.errorCode()),
				"the reason must be the stay area, not a generic path failure - got "
					+ outside.errorCode());
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 闸门在<b>多段</b>移动上仍然有效。
	 *
	 * <p>这是刻意分开存 {@code stayArea} 的理由：{@code moveTo} 会把 mode 改成 IDLE，
	 * 只看 mode 的话，一个多段移动的任务只有第一段会被拦住，后面几段照样能把伙伴
	 * 带出待命区。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-stay-persist")
	public void theStayGateSurvivesATaskTakingOverMovement(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "stay-persist-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(4, 2, 4));
			rt.profileOf(avatar).stayRadius = 3;
			avatar.setStayMode();
			avatar.moveTo(at(context, new BlockPos(5, 2, 5)), MoveOptions.WALK);
			// 第一段之后 mode 已经是 IDLE 了；闸门必须还在。
			MoveHandle second = avatar.moveTo(at(context, new BlockPos(8, 2, 8)),
				MoveOptions.WALK);
			context.assertTrue(AvatarEntity.OUT_OF_STAY_AREA.equals(second.errorCode()),
				"the second leg of a multi-step move must still be gated");

			// 换一个模式才真正解除区域。
			avatar.setFollowMode(owner.getUuid());
			context.assertTrue(avatar.stayArea().isEmpty(), "leaving STAY clears the area");
			MoveHandle free = avatar.moveTo(at(context, new BlockPos(8, 2, 8)),
				MoveOptions.WALK);
			context.assertFalse(AvatarEntity.OUT_OF_STAY_AREA.equals(free.errorCode()),
				"once he is not on stay, nothing should refuse the move for that reason");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 巡逻是一条线

	/** 配了巡逻点就按点走，走到一个点会推进游标。 */
	@GameTest(templateName = FLOOR, tickLimit = 700, batchId = "squire-patrol-route")
	public void patrolWalksTheConfiguredPointsInOrder(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "patrol-route-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			SquireProfile profile = rt.profileOf(avatar);
			profile.patrolPoints.clear();
			profile.patrolCursor = 0;
			for (BlockPos relative : new BlockPos[] {new BlockPos(6, 2, 1),
					new BlockPos(6, 2, 6)}) {
				profile.patrolPoints.add(GlobalPos.create(world.getRegistryKey(),
					context.getAbsolutePos(relative)));
			}
			avatar.setPatrolMode();
		});

		context.runAtTick(600, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			SquireProfile profile = rt.profileOf(avatar);
			context.assertTrue(profile.patrolCursor != 0,
				"he must have reached the first waypoint and moved on, cursor is still "
					+ profile.patrolCursor);
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 没有巡逻点时退回老行为：绕着锚点转圈，而不是站着不动。
	 *
	 * <p>断言的是<b>他真的开始走</b>，不是他最后停在哪儿：转圈的落点本来就是随机的，
	 * 而测试场地只有 9×9，随机点经常落在平台外面（那时寻路失败，他原地等下一次）。
	 * 拿最终位移当判据会得到一条时灵时不灵的测试。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-patrol-legacy")
	public void patrolWithoutWaypointsStillCirclesTheAnchor(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "patrol-legacy-owner");
		BlockPos start = context.getAbsolutePos(new BlockPos(4, 2, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(4, 2, 4));
			rt.profileOf(avatar).patrolPoints.clear();
			avatar.setPatrolMode();
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.PATROL,
				"patrol is the standing order");
		});

		context.forEachRemainingTick(() -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElse(null);
			if (avatar == null) {
				return;
			}
			boolean walking = !avatar.getNavigation().isIdle();
			boolean moved = avatar.getBlockPos().getSquaredDistance(start) > 1.0;
			if (walking || moved) {
				context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.PATROL,
					"an upgrade must not leave existing patrols standing still");
				cleanUp(rt, owner);
				context.complete();
			}
		});
	}

	/**
	 * 巡检读的是<b>真实世界</b>：附近有敌对生物就报，什么都没有就闭嘴。
	 *
	 * <p>「没事就不说话」和「有事要说」一样重要：一个每到一个点都汇报一次的伙伴，
	 * 第三句起就没人看了，真出事那句也跟着被忽略。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-patrol-inspect")
	public void inspectionReportsHostilesAndStaysQuietOtherwise(TestContext context) {
		ServerWorld world = context.getWorld();
		BlockPos centre = context.getAbsolutePos(new BlockPos(4, 2, 4));

		context.runAtTick(5, () -> {
			var zombie = net.minecraft.entity.EntityType.ZOMBIE.create(world);
			context.assertTrue(zombie != null, "zombie entity type available");
			zombie.refreshPositionAndAngles(centre.getX() + 1.5, centre.getY(),
				centre.getZ() + 1.5, 0.0f, 0.0f);
			world.spawnEntity(zombie);

			var report = PatrolInspector.inspect(world, centre, 6);
			context.assertTrue(report.hostiles() >= 1,
				"a zombie two steps away is exactly what a patrol should report");
			context.assertTrue(report.anythingToSay(), "a hostile is worth saying");
			context.assertFalse(report.describe().isEmpty(),
				"a report with findings must have words");
			zombie.discard();

			// 一片空旷、明亮、没有门的地方：没什么可说的。
			var quiet = new PatrolInspector.Report(0, java.util.List.of(),
				java.util.List.of());
			context.assertFalse(quiet.anythingToSay(), "an empty report says nothing");
			context.assertTrue(quiet.describe().isEmpty(),
				"nothing found means silence, not '一切正常'");
			context.complete();
		});
	}

	// ================================================== 回家会自己做点事

	/** 「装备维护」换上更好的护甲；「囤积」把余料存进最近的箱子。 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-home-routine")
	public void theHomeRoutineUpgradesArmourAndStowsLeftovers(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "home-routine-owner");
		BlockPos chest = context.getAbsolutePos(new BlockPos(5, 2, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(4, 2, 4));
			SquireProfile profile = rt.profileOf(avatar);
			profile.roleId = Role.STEWARD.id();
			profile.unlockedAbilities.add(Ability.LOGISTICS_UPKEEP.id());
			profile.equippedAbilities.add(Ability.LOGISTICS_UPKEEP.id());
			profile.traits.clear();
			profile.traits.add(Trait.HOARDER.id());
			profile.autonomy = AutonomyLevel.STANDARD.id();

			world.setBlockState(chest, Blocks.CHEST.getDefaultState());
			avatar.items().insert(new ItemStack(Items.DIAMOND_CHESTPLATE, 1));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 32));

			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				HomeRoutineExecutor.TYPE, TaskPriority.P7_IDLE, "home routine", null,
				HomeRoutineExecutor.routineDone(), 200L, RetryPolicy.DEFAULT, true, "c2",
				java.util.Map.of()), world.getTime());
		});

		context.runAtTick(200, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(avatar.items()
					.equipped(net.minecraft.entity.EquipmentSlot.CHEST)
					.isOf(Items.DIAMOND_CHESTPLATE),
				"the better chestplate must be worn, not carried");
			context.assertTrue(
				world.getBlockEntity(chest) instanceof ChestBlockEntity box
					&& !box.isEmpty(),
				"a hoarder stows the leftovers instead of hauling them around");
			context.assertTrue(avatar.items().countOf(
					new net.minecraft.util.Identifier("minecraft:cobblestone")) == 0,
				"the cobblestone really left his backpack");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 默认档位、没有囤积性格的伙伴不会动你背包里的任何东西。 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-home-quiet")
	public void aPlainCompanionDoesNotTouchYourThingsAtHome(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "home-quiet-owner");
		BlockPos chest = context.getAbsolutePos(new BlockPos(5, 2, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(4, 2, 4));
			SquireProfile profile = rt.profileOf(avatar);
			profile.traits.clear();
			profile.autonomy = AutonomyLevel.STANDARD.id();
			world.setBlockState(chest, Blocks.CHEST.getDefaultState());
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 32));

			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				HomeRoutineExecutor.TYPE, TaskPriority.P7_IDLE, "home routine", null,
				HomeRoutineExecutor.routineDone(), 200L, RetryPolicy.DEFAULT, true, "c2",
				java.util.Map.of()), world.getTime());
		});

		context.runAtTick(200, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(avatar.items().countOf(
					new net.minecraft.util.Identifier("minecraft:cobblestone")) == 32,
				"the default autonomy level must not move anything on its own");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 指挥权：互斥与可中断

	/**
	 * 任务接管身体时，玩家的<b>长期命令不丢</b>；任务一结束他自己就接着跟随。
	 *
	 * <p>以前 {@code moveTo} 会顺手把模式改成 IDLE，于是一只随从做完第一件活之后就
	 * 永远既不跟随也不待命也不巡逻，看起来就是「他不干活了」。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-control-standing")
	public void aTaskNeverErasesTheStandingOrder(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "control-standing-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.setFollowMode(owner.getUuid());
		});

		// 另起一拍再发移动：刚被 refreshPositionAndAngles 挪过的实体，
		// 同一 tick 里寻路还没重建，startMovingTo 会直接返回 false。
		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			MoveHandle handle = avatar.moveTo(at(context, new BlockPos(7, 2, 7)),
				MoveOptions.WALK);
			context.assertTrue(handle.state() == MoveHandle.State.MOVING,
				"the runtime move started");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"the standing order survives a task taking the body");
			context.assertTrue(avatar.taskDriven(),
				"but the follow goal stands down while it runs");
		});

		context.runAtTick(200, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"and it is still there afterwards, so he goes back to following");
			context.assertFalse(avatar.taskDriven(),
				"once the move is over the body is his own again");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 玩家最近一次站位命令当场打断手上的活。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-control-preempt")
	public void theNewestPlayerCommandCancelsRunningWork(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "control-preempt-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			BlockPos far = context.getAbsolutePos(new BlockPos(8, 2, 8));
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				"navigation.move_to", TaskPriority.P3_USER_TASK, "walk far", null,
				null, 2000L, RetryPolicy.DEFAULT, true, "control",
				java.util.Map.of("x", far.getX(), "y", 2.0, "z", far.getZ())),
				world.getTime());
		});

		context.runAtTick(20, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(rt.scheduler().current(avatar.agentId()).isPresent(),
				"the walk is running");

			var reply = rt.executeControl(owner, SquireRuntime.ControlIntent.STAY);
			context.assertTrue(reply.success(), reply.message());
			context.assertTrue(rt.scheduler().current(avatar.agentId()).isEmpty(),
				"the newest command wins - the running work is dropped at once");
			context.assertTrue(reply.message().contains("1"),
				"and he says what he put down: " + reply.message());
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"the new standing order is in force");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 待命中接到一个圈外的活：<b>放弃待命去干</b>，而不是把活拒掉。
	 *
	 * <p>圈内的活不受影响。待命仍然拦得住他<b>自己</b>跑出去（见上面那两条）。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-control-release")
	public void anOrderedJobOutsideTheStayAreaReleasesStayInsteadOfRefusing(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "control-release-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			rt.profileOf(avatar).stayRadius = 3;
			avatar.setStayMode();

			// 圈内的活：待命照旧。
			BlockPos near = context.getAbsolutePos(new BlockPos(2, 2, 2));
			context.assertTrue(rt.beginOrderedWork(avatar, near).isEmpty(),
				"a job inside the circle does not disturb the stay order");
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"still on stay");

			// 圈外的活：最近一次命令优先，待命让位，并且如实说一声。
			BlockPos far = context.getAbsolutePos(new BlockPos(8, 2, 8));
			String note = rt.beginOrderedWork(avatar, far);
			context.assertFalse(note.isEmpty(),
				"giving up the stay order has to be said out loud");
			context.assertFalse(avatar.mode() == AvatarEntity.MovementMode.STAY,
				"the newest order wins over an older 待命");
			MoveHandle handle = avatar.moveTo(at(context, new BlockPos(8, 2, 8)),
				MoveOptions.WALK);
			context.assertFalse(AvatarEntity.OUT_OF_STAY_AREA.equals(handle.errorCode()),
				"and the job is no longer refused for being outside the circle");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ---------------------------------------------------------------- 按 K 叫他过来

	/**
	 * 按 K 开面板：人在远处就<b>把他叫到面前</b>，而且不改他的模式。
	 *
	 * <p>玩家的原话是「按 k 侍从不会传送到我面前」。之前这里同维度一律原地不动——
	 * 面板开了、人还在几十格外，那个键看起来就是坏的。现在超过
	 * {@link SquireRuntime#PANEL_SUMMON_DISTANCE} 格才搬，而且只搬身体：
	 * 「待命」还是「待命」，手上的活也不取消。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 120, batchId = "squire-panel-key")
	public void pressingThePanelKeyBringsADistantCompanionToYou(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "panel-key-owner");

		context.runAtTick(5, () -> {
			Vec3d ownerFeet = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(1, 2, 1)));
			owner.refreshPositionAndAngles(ownerFeet.x, ownerFeet.y, ownerFeet.z, 0f, 0f);
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(8, 2, 8));
			avatar.setStayMode();
			double before = avatar.distanceTo(owner);
			context.assertTrue(before > SquireRuntime.PANEL_SUMMON_DISTANCE,
				"这条测试要求他一开始就在触发距离之外，实际=" + before);

			AvatarEntity opened = rt.bringWithinPanelRange(owner, avatar);

			context.assertTrue(opened == avatar,
				"同维度不该换出另一具身体");
			double after = opened.distanceTo(owner);
			context.assertTrue(after <= SquireRuntime.PANEL_SUMMON_DISTANCE,
				"按 K 之后他必须站在你面前，距离=" + after);
			context.assertTrue(opened.mode() == AvatarEntity.MovementMode.STAY,
				"叫他过来只搬身体，不许顺手改成跟随");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 已经在跟前的人不许再传一次——那会在玩家眼前原地闪一下。 */
	@GameTest(templateName = FLOOR, tickLimit = 120, batchId = "squire-panel-key")
	public void pressingThePanelKeyLeavesACompanionAlreadyNextToYouAlone(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "panel-key-near-owner");

		context.runAtTick(5, () -> {
			Vec3d ownerFeet = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(4, 2, 4)));
			owner.refreshPositionAndAngles(ownerFeet.x, ownerFeet.y, ownerFeet.z, 0f, 0f);
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(5, 2, 4));
			Vec3d before = avatar.getPos();

			rt.bringWithinPanelRange(owner, avatar);

			context.assertTrue(avatar.getPos().squaredDistanceTo(before) < 0.001,
				"跟前的人不该被再挪一次：" + before + " -> " + avatar.getPos());
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ---------------------------------------------------------------- 名牌上的状态

	/**
	 * 名牌上永远只有<b>一个</b>状态标签。
	 *
	 * <p>玩家看到的是「他有时候同时挂着两个状态」。病根是名牌
	 * {@code 本名 + [模式] + [进度] + 活动后缀} 拼好之后会被原版当成 CustomName 存进
	 * NBT，重新加载出来的实例本名是空的，于是把<em>已经带着 [跟随] 的那一串</em>
	 * 当成本名——下一次切模式就叠成「[跟随] [巡逻]」，再重启还能叠第三个。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 120, batchId = "squire-nameplate")
	public void theNameplateNeverStacksTwoStateTags(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "nameplate-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(2, 2, 2));
			avatar.setBaseName(net.minecraft.text.Text.literal("[Squire] 阿福"));
			avatar.setFollowMode(owner.getUuid());
			String decorated = avatar.getCustomName().getString();
			context.assertTrue(decorated.contains("[跟随]"),
				"跟随状态本来就该写在名牌上：" + decorated);

			// 模拟重新加载：一具新身体，只有那串装饰过的名牌，没有本名。
			AvatarEntity reloaded = dev.squire.server.registry.SquireEntities.AVATAR
				.create(context.getWorld());
			context.assertTrue(reloaded != null, "实体类型必须造得出来");
			reloaded.setCustomName(net.minecraft.text.Text.literal(decorated));
			reloaded.setPatrolMode();

			String shown = reloaded.getCustomName().getString();
			context.assertTrue(shown.contains("[巡逻]"), "新状态要显示出来：" + shown);
			context.assertFalse(shown.contains("[跟随]"),
				"旧状态必须被剥掉，不能和新状态叠在一起：" + shown);
			context.assertTrue(shown.startsWith("[Squire] 阿福"),
				"本名不许被一起剥掉：" + shown);
			reloaded.discard();

			// 存档往返之后本名单独存在 NBT 里，连兜底剥离都不需要走。
			net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
			avatar.writeCustomDataToNbt(nbt);
			AvatarEntity restored = dev.squire.server.registry.SquireEntities.AVATAR
				.create(context.getWorld());
			restored.readCustomDataFromNbt(nbt);
			restored.setStayMode();
			String after = restored.getCustomName().getString();
			context.assertTrue(after.equals("[Squire] 阿福 [待命]"),
				"存档往返之后名牌应该是「本名 + 一个状态」，实际=" + after);
			restored.discard();

			cleanUp(rt, owner);
			context.complete();
		});
	}

}
