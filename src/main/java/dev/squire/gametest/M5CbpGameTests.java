package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;

import dev.squire.server.cbp.CbpMaterializer;
import dev.squire.server.cbp.CbpSpec;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * M5b CBP GameTests (spec §50/§89 DoD): 玩家明确要求时可物化；未确认不执行；
 * 只在 Workspace 内物化；可完整删除与 Undo。Real blocks, real undo journal,
 * real confirmation service — the full pipeline against a live world.
 */
public final class M5CbpGameTests implements FabricGameTest {
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
		rt.automation().setEnabled(false);
		rt.setCbpEnabled(true); // tests opt in explicitly (§94 ships OFF)
		return rt;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(
				message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}

	private static void assertFalse(boolean value, String message) {
		if (value) {
			throw new AssertionError(message);
		}
	}

	private interface Body {
		void run(SquireRuntime rt, ServerWorld world, FakePlayer owner);
	}

	/**
	 * 测试的主人必须落在<b>本次测试自己的场地里</b>。
	 *
	 * <p>{@code FakePlayer.get} 按 profile 缓存，同一个实例会被这个文件里的每一条
	 * 用例复用；以前这里只 {@code spawnEntity} 而不设坐标，于是主人停在上一条
	 * 用例把他丢下的位置。而下面的断言全都是
	 * {@code owner.getBlockPos().add(3, 0, 3)}——主人站在哪儿，“那一格应该是空气”
	 * 就在验哪儿。只要批次顺序一变（比如新增了其它 GameTest），它就可能落在地板上
	 * 而无缘无故地红。固定落点之后，这些断言才真的在验 CBP。</p>
	 */
	private static void at(TestContext context, int tick, Body body) {
		context.runAtTick(tick, () -> {
			ServerWorld world = context.getWorld();
			FakePlayer owner = fakeOwner(world, "m5-cbp-owner");
			world.spawnEntity(owner);
			var feet = net.minecraft.util.math.Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(1, 2, 1)));
			owner.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
			body.run(runtime(context), world, owner);
		});
	}

	private static void pollUntil(TestContext context, BooleanSupplier condition,
			int startTick, int intervalTicks, Runnable onSuccess,
			AtomicBoolean done) {
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

	private static CbpSpec spec(FakePlayer owner, BlockPos pos) {
		return CbpSpec.builder(owner.getUuid(), owner.getUuid(), "welcome-gate")
			.entry(pos, "minecraft:command_block", "give @p bread 1", false)
			.build();
	}

	private static void assertAir(ServerWorld world, BlockPos pos, String message) {
		assertTrue(world.getBlockState(pos).isAir(), message
			+ " (found " + world.getBlockState(pos).getBlock() + ")");
	}

	// ------------------------------------------------- §89: 未确认不执行

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m5-cbp-noconfirm")
	public void unconfirmedCbpNeverPlacesBlocks(TestContext context) {
		at(context, 5, (rt, world, owner) -> {
			BlockPos target = owner.getBlockPos().add(3, 0, 3);
			var area = dev.squire.server.world.BoundedRegion.ofCorners(
				target.getX() - 6, target.getY() - 2, target.getZ() - 6,
				target.getX() + 6, target.getY() + 4, target.getZ() + 6);
			assertTrue(rt.cbpWorkspace()
					.set(owner.getUuid(),
						world.getRegistryKey().getValue().toString(), area)
					.isEmpty(),
				"workspace accepted");

			var plan = rt.cbp().plan(spec(owner, target), world.getTime());
			assertTrue(plan.ok(), "plan refused: " + plan.message());
			assertAir(world, target, "planning must not touch the world");

			context.runAtTick(120, () -> {
				assertAir(world, target,
					"unconfirmed CBP must never place anything");
				assertEquals(1, rt.cbp().pendingCount(),
					"request stays pending until confirmed or expired");
				rt.cbpWorkspace().clear(owner.getUuid());
				rt.setCbpEnabled(false);
				context.complete();
			});
		});
	}

	// ------------------------------- §50 full pipeline + 可完整删除与 Undo

	@GameTest(templateName = FLOOR, tickLimit = 700, batchId = "squire-m5-cbp-full")
	public void confirmedCbpPlacesVerifiesAndUndoRemovesFully(
			TestContext context) {
		at(context, 5, (rt, world, owner) -> {
			BlockPos target = owner.getBlockPos().add(3, 0, -3);
			var area = dev.squire.server.world.BoundedRegion.ofCorners(
				target.getX() - 6, target.getY() - 2, target.getZ() - 6,
				target.getX() + 6, target.getY() + 4, target.getZ() + 6);
			assertTrue(rt.cbpWorkspace()
					.set(owner.getUuid(),
						world.getRegistryKey().getValue().toString(), area)
					.isEmpty(),
				"workspace accepted");
			assertAir(world, target, "target must start as air");

			var plan = rt.cbp().plan(spec(owner, target), world.getTime());
			assertTrue(plan.ok(), "plan refused: " + plan.message());

			// the OWNER confirms by id — exactly what /squire confirm does
			String verdict = rt.confirmations()
				.confirm(owner.getUuid(), plan.confirmId(), world.getTime());
			assertTrue(verdict.startsWith("[Squire] Confirmed"),
				"owner confirmation failed: " + verdict);

			AtomicBoolean registered = new AtomicBoolean(false);
			pollUntil(context, () -> rt.cbpRegistry().ownedBy(owner.getUuid()).size() > 0,
				40, 10, () -> registered.set(true), registered);

			context.runAtTick(200, () -> {
				assertTrue(registered.get(), "project never appeared in the registry");
				var project = rt.cbpRegistry().ownedBy(owner.getUuid()).get(0);

				// placed + verified: right block, right command baked in
				assertEquals(net.minecraft.block.Blocks.COMMAND_BLOCK,
					world.getBlockState(target).getBlock(), "block type");
				BlockEntity be = world.getBlockEntity(target);
				assertTrue(be instanceof CommandBlockBlockEntity,
					"command block entity present");
				assertEquals("give @p bread 1",
					((CommandBlockBlockEntity) be).getCommandExecutor().getCommand(),
					"structured command baked into the block");
				assertFalse(project.disabled(), "registered project starts armed");

				// disable → inert; then remove → FULL undo back to air
				assertTrue(rt.cbp().disable(project.id(), owner.getUuid(), false).ok(),
					"owner disables");
				assertFalse(((CommandBlockBlockEntity) world.getBlockEntity(target))
					.isAuto(), "disabled block must be auto=off");
				assertTrue(rt.cbp().remove(project.id(), owner.getUuid(), false).ok(),
					"owner removes with undo");
				assertAir(world, target, "undo must restore air exactly");
				assertEquals(0, rt.cbpRegistry().ownedBy(owner.getUuid()).size(),
					"registry entry gone after removal");
				rt.cbpWorkspace().clear(owner.getUuid());
				rt.setCbpEnabled(false);
				context.complete();
			});
		});
	}

	// ------------------------------------------- §89/§50: 只在 Workspace

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-m5-cbp-outside")
	public void cbpOutsideTheWorkspaceIsRefused(TestContext context) {
		at(context, 5, (rt, world, owner) -> {
			BlockPos inside = owner.getBlockPos().add(2, 0, 2);
			var tiny = dev.squire.server.world.BoundedRegion.ofCorners(
				inside.getX() - 1, inside.getY() - 1, inside.getZ() - 1,
				inside.getX() + 1, inside.getY() + 1, inside.getZ() + 1);
			assertTrue(rt.cbpWorkspace()
					.set(owner.getUuid(),
						world.getRegistryKey().getValue().toString(), tiny)
					.isEmpty(),
				"workspace accepted");
			BlockPos outside = inside.add(64, 0, 64); // well beyond the workspace

			CbpSpec sneaky = CbpSpec.builder(owner.getUuid(), owner.getUuid(),
					"sneaky-build")
				.entry(outside, "minecraft:repeating_command_block",
					"effect give @p speed 30 1", true)
				.build();
			var plan = rt.cbp().plan(sneaky, world.getTime());
			assertFalse(plan.ok(), "outside-workspace spec must be refused");
			assertTrue(plan.message().contains("workspace"),
				"refusal must name the workspace rule");
			assertEquals(0, rt.cbp().pendingCount(), "nothing pending");
			assertAir(world, outside, "nothing may appear far away either");

			context.runAtTick(80, () -> {
				assertAir(world, outside, "still nothing placed later");
				rt.cbpWorkspace().clear(owner.getUuid());
				rt.setCbpEnabled(false);
				context.complete();
			});
		});
	}
}
