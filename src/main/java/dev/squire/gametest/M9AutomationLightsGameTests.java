package dev.squire.gametest;

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
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * B09 / 工作包 G 黑盒验收：“每天晚上在基地开灯”从聊天入口创建真实 AutomationGraph，
 * 夜里真的翻动拉杆点亮红石灯，白天关掉，全程世界里不新增任何命令方块。
 *
 * <p>灯光锚点故意用拉杆而不是直接改红石灯的 LIT——香草会在下一 tick 把没有信号的
 * 灯关掉，所以只有真实的开关状态才算数。</p>
 */
public final class M9AutomationLightsGameTests implements FabricGameTest {
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

	/** A floor-mounted lever sitting next to a redstone lamp: a real light switch. */
	private static void placeLightSwitch(TestContext context, BlockPos leverRel,
			BlockPos lampRel) {
		context.setBlockState(lampRel, Blocks.REDSTONE_LAMP);
		context.setBlockState(leverRel, Blocks.LEVER.getDefaultState()
			.with(Properties.WALL_MOUNT_LOCATION, WallMountLocation.FLOOR)
			.with(Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.NORTH)
			.with(LeverBlock.POWERED, false));
	}

	private static boolean leverOn(TestContext context, BlockPos leverRel) {
		return context.getWorld().getBlockState(context.getAbsolutePos(leverRel))
			.get(LeverBlock.POWERED);
	}

	/**
	 * Command blocks inside THIS test's own structure. Scanning by absolute radius
	 * spills into neighbouring structures — the CBP suite legitimately places command
	 * blocks in the shared world, and counting those says nothing about automation.
	 */
	private static int commandBlocksInStructure(TestContext context) {
		int found = 0;
		for (int x = 0; x < 9; x++) {
			for (int y = 0; y < 4; y++) {
				for (int z = 0; z < 9; z++) {
					var block = context.getWorld()
						.getBlockState(context.getAbsolutePos(new BlockPos(x, y, z)))
						.getBlock();
					if (block == Blocks.COMMAND_BLOCK || block == Blocks.CHAIN_COMMAND_BLOCK
							|| block == Blocks.REPEATING_COMMAND_BLOCK) {
						found++;
					}
				}
			}
		}
		return found;
	}

	private static AvatarEntity setUp(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos avatarRel) {
		ServerWorld world = context.getWorld();
		rt.automation().setEnabled(true);
		rt.permissions().grant(owner.getUuid(), PermissionNodes.AUTOMATION);
		world.spawnEntity(owner);
		AvatarEntity avatar = rt.summonFor(owner);
		place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(avatarRel)));
		avatar.setStayMode();
		ProviderRegistry.clear();
		return avatar;
	}

	private static void cleanUp(TestContext context, SquireRuntime rt, FakePlayer owner) {
		for (var graph : rt.automation().ownedBy(owner.getUuid())) {
			rt.automation().remove(graph.id(), owner.getUuid(), true);
		}
		rt.automation().setEnabled(false);
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	// ================================================== B09

	/**
	 * 收缩后，长期灯光自动化不再是伙伴聊天能力；旧实现仅保留给内部/管理入口。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-lights-b09")
	public void nightLightsAutomationIsCreatedFromOneSentence(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "g-b09-owner");

		context.runAtTick(5, () -> {
			place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(5, 2, 5))));
			setUp(context, rt, owner, new BlockPos(5, 2, 5));
			var routed = InputGateway.acceptChat(owner, "每天晚上在基地开灯");
			context.assertTrue(routed.isEmpty(),
				"physical automation must not match the companion FastPath");
			context.assertTrue(rt.automation().ownedBy(owner.getUuid()).isEmpty(),
				"no automation graph is created");
			context.assertTrue(rt.pendingOperations()
				.pendingFor(owner.getUuid(), world.getTime()).isEmpty(),
				"no confirmation is issued for a removed chat capability");
			context.assertTrue(commandBlocksInStructure(context) == 0,
				"the removed route leaves the world untouched");
			cleanUp(context, rt, owner);
			context.complete();
		});
	}

	/**
	 * G2：开灯真的改变红石状态——拉杆被翻开、旁边的红石灯真的亮了；关灯把它复原。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-lights-real")
	public void lightsTaskFlipsRealLeversAndPowersTheLamp(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "g-lights-owner");
		BlockPos leverRel = new BlockPos(4, 2, 4);
		BlockPos lampRel = new BlockPos(4, 2, 5);
		AtomicBoolean done = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = setUp(context, rt, owner, new BlockPos(5, 2, 5));
			placeLightSwitch(context, leverRel, lampRel);
			context.assertFalse(leverOn(context, leverRel), "the switch starts off");

			BlockPos base = context.getAbsolutePos(leverRel);
			submitLights(rt, avatar, owner, base, true);
		});

		context.runAtTick(60, () -> {
			context.assertTrue(leverOn(context, leverRel),
				"the REAL lever was flipped on");
			context.assertTrue(world.getBlockState(context.getAbsolutePos(lampRel))
					.get(net.minecraft.block.RedstoneLampBlock.LIT),
				"and the lamp is really lit by that redstone signal");
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			submitLights(rt, avatar, owner, context.getAbsolutePos(leverRel), false);
		});

		context.runAtTick(140, () -> {
			context.assertFalse(leverOn(context, leverRel),
				"morning switches the lever back off");
			context.assertFalse(world.getBlockState(context.getAbsolutePos(lampRel))
					.get(net.minecraft.block.RedstoneLampBlock.LIT),
				"and the lamp really goes dark again");
			context.assertTrue(commandBlocksInStructure(context) == 0,
				"no command block was ever placed");
			done.set(true);
			cleanUp(context, rt, owner);
			context.complete();
		});
		context.runAtTick(470, () -> {
			if (!done.get()) {
				rt.automation().setEnabled(false);
				context.throwGameTestException("lights task never completed");
			}
		});
	}

	/** The model catalog no longer advertises the legacy physical automation tools. */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-lights-noanchor")
	public void withoutARealSwitchTheAutomationIsRefused(TestContext context) {
		SquireRuntime rt = runtime(context);
		context.runAtTick(5, () -> {
			var names = rt.toolRegistry().modelVisible().stream()
				.map(t -> t.descriptor().name()).toList();
			context.assertFalse(names.contains("automation.create"),
				"automation.create must not be model-visible");
			context.assertFalse(names.contains("base.lights.set"),
				"base.lights.set must not be model-visible");
			context.complete();
		});
	}

	/** G3：pause 之后不再触发，resume 后继续，remove 后永久停止。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-lights-lifecycle")
	public void pauseResumeRemoveControlTheLongTermAutomation(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "g-lifecycle-owner");
		BlockPos leverRel = new BlockPos(4, 2, 4);

		context.runAtTick(5, () -> {
			setUp(context, rt, owner, new BlockPos(5, 2, 5));
			placeLightSwitch(context, leverRel, new BlockPos(4, 2, 5));
			var created = rt.registerNightLights(owner, context.getAbsolutePos(leverRel), 6);
			context.assertTrue(created.success(), created.message());

			var graphs = rt.automation().ownedBy(owner.getUuid());
			context.assertTrue(graphs.size() == 2, "two graphs registered");
			UUID first = graphs.get(0).id();

			context.assertTrue(rt.automation().pause(first, owner.getUuid(), false).ok(),
				"owner can pause");
			context.assertTrue(rt.automation().get(first).orElseThrow().state()
					== dev.squire.server.automation.AutomationGraph.State.PAUSED,
				"paused graphs do not fire");
			context.assertTrue(rt.automation().resume(first, owner.getUuid(), false).ok(),
				"owner can resume");
			context.assertTrue(rt.automation().get(first).orElseThrow().state()
					== dev.squire.server.automation.AutomationGraph.State.ACTIVE,
				"resumed graphs are active again");
			context.assertTrue(rt.automation().remove(first, owner.getUuid(), false).ok(),
				"owner can remove");
			context.assertTrue(rt.automation().get(first).isEmpty(),
				"removed means gone for good");

			cleanUp(context, rt, owner);
			context.complete();
		});
	}

	private static void submitLights(SquireRuntime rt, AvatarEntity avatar,
			FakePlayer owner, BlockPos base, boolean on) {
		java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
		params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_ON, on);
		params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_X, base.getX());
		params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_Y, base.getY());
		params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_Z, base.getZ());
		params.put(dev.squire.server.task.executors.BaseLightsExecutor.PARAM_RADIUS, 6);
		var task = new dev.squire.server.task.Task(avatar.agentId(), owner.getUuid(),
			dev.squire.server.task.executors.BaseLightsExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P3_USER_TASK,
			on ? "lights on" : "lights off", null,
			dev.squire.server.task.executors.BaseLightsExecutor.lightsAre(
				rt.runtimeServicesForTest(), on,
				avatar.getWorld().getRegistryKey().getValue().toString(), base, 6),
			200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "g2", params);
		rt.scheduler().submit(task, rt.tickNow());
	}
}
