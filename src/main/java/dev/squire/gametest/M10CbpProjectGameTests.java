package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.cbp.CbpPlanner;
import dev.squire.server.input.InputGateway;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.world.BoundedRegion;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * B10 / 工作包 H 黑盒验收：只有玩家明确要求"真实命令方块装置"时才进入物化流程，
 * 预览之前世界零改变，确认一次后生成真实、可见、可编辑、可接红石的多方块装置，
 * remove 精确恢复原区域。
 */
public final class M10CbpProjectGameTests implements FabricGameTest {
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

	/** Enable CBP, summon, and give the owner a workspace covering this structure. */
	private static AvatarEntity setUp(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos anchorRel) {
		ServerWorld world = context.getWorld();
		rt.setCbpEnabled(true);
		rt.permissions().grant(owner.getUuid(),
			dev.squire.server.security.PermissionNodes.WORLD_EDIT);
		world.spawnEntity(owner);
		AvatarEntity avatar = rt.summonFor(owner);
		place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, 2, 1))));
		avatar.setStayMode();
		// 工作区的最小角就是装置的锚点：requestRealCbp 把装置放在工作区角上，
		// 测试必须按同一个规则去看，而不是自己另猜一个位置。
		BlockPos anchor = context.getAbsolutePos(anchorRel);
		rt.cbpWorkspace().set(owner.getUuid(),
			world.getRegistryKey().getValue().toString(),
			BoundedRegion.ofCorners(anchor.getX(), anchor.getY(), anchor.getZ(),
				anchor.getX() + 6, anchor.getY() + 3, anchor.getZ() + 3));
		ProviderRegistry.clear();
		return avatar;
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.setCbpEnabled(false);
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

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

	// ================================================== B10

	/**
	 * Materialized CBP projects are no longer a companion-chat capability. Long
	 * compiled commands use an ephemeral carrier instead and leave no blocks behind.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-cbp-b10")
	public void realDayNightControllerIsPreviewedThenMaterialized(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "h-b10-owner");
		BlockPos anchorRel = new BlockPos(2, 2, 4);

		context.runAtTick(5, () -> {
			setUp(context, rt, owner, anchorRel);
			context.assertTrue(commandBlocksInStructure(context) == 0,
				"the area starts clean");

			var routed = InputGateway.acceptChat(owner, "做一个真实命令方块昼夜控制器");
			context.assertTrue(routed.isEmpty(),
				"materialized CBP must not match the companion FastPath");
			context.assertTrue(commandBlocksInStructure(context) == 0,
				"the removed route leaves no command blocks behind");
			context.assertTrue(rt.confirmations().pendingFor(owner.getUuid()).isEmpty(),
				"no materialization confirmation is issued");
			var visible = rt.toolRegistry().modelVisible().stream()
				.map(t -> t.descriptor().name()).toList();
			context.assertFalse(visible.stream().anyMatch(n -> n.startsWith("cbp.")),
				"CBP project tools must not be model-visible");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Poll until the condition holds; GameTest has no built-in wait-for. */
	private static void pollUntil(TestContext context,
			java.util.function.BooleanSupplier condition, int startTick,
			int intervalTicks, Runnable onSuccess, AtomicBoolean done) {
		if (done.get()) {
			return;
		}
		context.runAtTick(startTick, () -> {
			if (done.get()) {
				return;
			}
			if (condition.getAsBoolean()) {
				onSuccess.run();
			} else if (startTick + intervalTicks < 540) {
				pollUntil(context, condition, startTick + intervalTicks, intervalTicks,
					onSuccess, done);
			}
		});
	}

	/**
	 * H1：普通自动化请求绝不能悄悄物化成命令方块——这是整条意图门槛存在的理由。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-cbp-intent")
	public void ordinaryAutomationRequestNeverMaterializesCommandBlocks(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "h-intent-owner");

		context.runAtTick(5, () -> {
			setUp(context, rt, owner, new BlockPos(2, 2, 4));
			// 直接把一句自动化需求喂进 CBP 入口：必须被劝回 AutomationGraph
			var refused = rt.requestRealCbp(owner, "每天晚上在基地开灯");
			context.assertTrue(!refused.success(),
				"an automation request must not enter the CBP flow: "
					+ refused.message());
			context.assertTrue(refused.message().contains("AutomationGraph"),
				"and the player is told where it DOES belong: " + refused.message());

			var vague = rt.requestRealCbp(owner, "弄个真实一点的东西");
			context.assertTrue(!vague.success(), vague.message());
			context.assertTrue(commandBlocksInStructure(context) == 0,
				"neither request placed a single command block");
			context.assertTrue(rt.confirmations().pendingFor(owner.getUuid()).isEmpty(),
				"and neither queued a confirmation");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** H3：inspect / disable / remove 可用；remove 精确恢复原区域。 */
	@GameTest(templateName = FLOOR, tickLimit = 700, batchId = "squire-cbp-remove")
	public void removeRestoresTheOriginalBlocksExactly(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "h-remove-owner");
		BlockPos anchorRel = new BlockPos(2, 2, 4);
		AtomicReference<UUID> confirmId = new AtomicReference<>();
		AtomicBoolean done = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = setUp(context, rt, owner, anchorRel);
			// 装置底下先放一块可辨认的原方块，remove 之后必须一模一样地回来
			context.setBlockState(anchorRel.east(), Blocks.GOLD_BLOCK);

			var planned = rt.planCbp(owner, avatar,
				CbpPlanner.Template.PULSE_TEACHING_DEVICE,
				context.getAbsolutePos(anchorRel));
			context.assertTrue(planned.success(), planned.message());
			confirmId.set(rt.confirmations().pendingFor(owner.getUuid())
				.get(rt.confirmations().pendingFor(owner.getUuid()).size() - 1)
				.confirmId());
			rt.confirmRequest(owner, confirmId.get());
		});

		context.runAtTick(120, () -> {
			BlockPos anchor = context.getAbsolutePos(anchorRel);
			context.assertTrue(world.getBlockState(anchor.east())
					.isOf(Blocks.REPEATING_COMMAND_BLOCK),
				"the repeating block replaced the gold block");
			var projects = rt.cbpRegistry().ownedBy(owner.getUuid());
			context.assertTrue(!projects.isEmpty(), "the project is registered");

			UUID projectId = projects.get(0).id();
			var disabled = rt.cbp().disable(projectId, owner.getUuid(), false);
			context.assertTrue(disabled.ok(), disabled.message());

			var removed = rt.cbp().remove(projectId, owner.getUuid(), false);
			context.assertTrue(removed.ok(), "remove: " + removed.message());
			context.assertTrue(world.getBlockState(anchor.east()).isOf(Blocks.GOLD_BLOCK),
				"the ORIGINAL block came back exactly, found "
					+ world.getBlockState(anchor.east()).getBlock());
			context.assertTrue(commandBlocksInStructure(context) == 0,
				"every command block is gone");
			context.assertTrue(rt.cbpRegistry().get(projectId).isEmpty(),
				"and the project left the registry");
			done.set(true);
			cleanUp(rt, owner);
			context.complete();
		});
		context.runAtTick(660, () -> {
			if (!done.get()) {
				rt.setCbpEnabled(false);
				context.throwGameTestException("remove test never completed");
			}
		});
	}
}
