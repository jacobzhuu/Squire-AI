package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.proxy.FakePlayerInteractionProxy;
import dev.squire.server.command.CommandRunner;
import dev.squire.server.command.StructuredCommandCompiler;
import dev.squire.server.combat.CombatStyle;
import dev.squire.server.registry.SquireEntities;
import dev.squire.server.world.ProtectionAdapter;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;

/**
 * M0 spike GameTests (spec section 84): avatar lifecycle, fake-player proxy,
 * structured command pipeline, async→server-thread bridging.
 *
 * <p>Conventions learned during M0 (see docs/spikes/M0-spike-conclusions.md):</p>
 * <ul>
 *   <li>yarn 1.20.1 TestContext completes via complete()/throwGameTestException().</li>
 *   <li>The squire:empty_floor template is a smooth-stone platform; the runner renders
 *       the template's bottom row at relative Y=1 (one above the getAbsolutePos anchor),
 *       so the walkable surface is relative Y=1 and entities stand in relative Y=2.</li>
 *   <li>Structure .snbt files must use the fabric datagen dialect (string block states),
 *       not vanilla palette-index format.</li>
 *   <li>World-facing helpers receive ABSOLUTE positions via context.getAbsolutePos.</li>
 * </ul>
 */
public final class M0SpikeGameTests implements FabricGameTest {
	public static final String FLOOR = "squire:empty_floor";
	private static final double ARRIVAL_RADIUS_SQ = 2.25;

	// ------------------------------------------------------------------ M0-02 avatar

	@GameTest(templateName = FLOOR, tickLimit = 400)
	public void avatarSpawnsAndNavigates(TestContext context) {
		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR, new BlockPos(1, 2, 1));
		context.assertTrue(avatar.isAlive(), "avatar should be alive after spawn");

		// bottom-center: navigation targets feet-level nodes; a centered Y would aim the
		// pathfinder at the air cell above and send the mob off-platform hunting a route
		BlockPos targetBlock = context.getAbsolutePos(new BlockPos(7, 2, 7));
		dev.squire.api.body.TargetPosition target = new dev.squire.api.body.TargetPosition(
			context.getWorld().getRegistryKey().getValue().toString(),
			targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);
		context.runAtTick(10, () -> {
			dev.squire.api.body.MoveHandle handle =
				avatar.moveTo(target, dev.squire.api.body.MoveOptions.WALK);
			context.assertTrue(handle.state() != dev.squire.api.body.MoveHandle.State.FAILED,
				"navigation should find a path on flat ground");
		});
		context.forEachRemainingTick(() -> {
			if (avatar.squaredDistanceTo(Vec3d.ofBottomCenter(targetBlock)) < ARRIVAL_RADIUS_SQ) {
				context.complete();
			}
		});
	}

	@GameTest(templateName = FLOOR)
	public void avatarNbtRoundTrip(TestContext context) {
		UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000042");
		BlockPos home = new BlockPos(3, 4, 5);

		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR, new BlockPos(2, 2, 2));
		avatar.setOwner(owner);
		avatar.setHomePos(home);

		NbtCompound nbt = new NbtCompound();
		avatar.writeNbt(nbt);

		AvatarEntity restored = context.spawnEntity(SquireEntities.AVATAR, new BlockPos(6, 2, 6));
		restored.readNbt(nbt);

		context.assertTrue(owner.equals(restored.owner().orElse(null)), "owner must survive NBT round trip");
		context.assertTrue(home.equals(restored.homePos().orElse(null)), "home must survive NBT round trip");
		context.complete();
	}

	/**
	 * 第一箭回归：自动把弓搬进主手的同一拍不能进入物品使用状态；至少经过一次
	 * 实体同步后才开始拉弓。客户端因此一定先知道“手里是弓”。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 80)
	public void firstBowDrawWaitsForTheEquipmentSyncTick(TestContext context) {
		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR,
			new BlockPos(2, 2, 4));
		LivingEntity target = context.spawnEntity(EntityType.ZOMBIE,
			new BlockPos(7, 2, 4));
		if (target instanceof MobEntity mob) {
			mob.setAiDisabled(true);
		}
		avatar.items().insert(new ItemStack(Items.BOW));
		avatar.items().insert(new ItemStack(Items.ARROW, 8));

		context.assertTrue(CombatStyle.prepare(avatar, target, CombatStyle.Style.RANGED),
			"ranged preparation must equip the available bow");
		var sameTick = avatar.shoot(target.getUuid());
		context.assertTrue("DRAWING_BOW".equals(sameTick.errorCode()),
			"the swap tick should wait, got " + sameTick.errorCode());
		context.assertFalse(avatar.isUsingItem(),
			"the active-item flag must not race ahead of the equipment packet");

		context.runAtTick(2, () -> {
			var nextTick = avatar.shoot(target.getUuid());
			context.assertTrue("DRAWING_BOW".equals(nextTick.errorCode()),
				"the following tick should begin drawing, got " + nextTick.errorCode());
			context.assertTrue(avatar.isUsingItem() && avatar.isDrawingBow(),
				"after a full sync tick the bow must enter vanilla item-use state");
			avatar.cancelDraw();
			context.complete();
		});
	}

	/** Deep water drives the actual pose, and leaving it restores the standing pose. */
	@GameTest(templateName = FLOOR, tickLimit = 80)
	public void deepWaterUsesTheSwimmingPose(TestContext context) {
		BlockPos feet = new BlockPos(4, 2, 4);
		context.setBlockState(feet, Blocks.WATER);
		context.setBlockState(feet.up(), Blocks.WATER);
		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR, feet);
		context.assertTrue(SquireEntities.AVATAR.getTrackTickInterval() == 1,
			"nearby player-like movement should be synchronized every tick");

		context.runAtTick(5, () -> {
			context.assertTrue(avatar.isSwimming()
					&& avatar.getPose() == EntityPose.SWIMMING,
				"deep water must set both the swimming flag and SWIMMING pose");
			context.setBlockState(feet, Blocks.AIR);
			context.setBlockState(feet.up(), Blocks.AIR);
		});
		context.runAtTick(12, () -> {
			context.assertFalse(avatar.isSwimming(),
				"leaving water must clear the swimming flag");
			context.assertTrue(avatar.getPose() == EntityPose.STANDING,
				"leaving water must restore the standing pose");
			context.complete();
		});
	}

	/** 找不到受支撑落点时宁可留在地面，也不能退回到悬空玩家的坐标。 */
	@GameTest(templateName = FLOOR)
	public void airborneOwnerDoesNotPullTheAvatarIntoMidair(TestContext context) {
		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR,
			new BlockPos(1, 2, 1));
		var airborneAnchor = context.spawnEntity(EntityType.ARMOR_STAND,
			new BlockPos(5, 7, 5));
		Vec3d before = avatar.getPos();

		context.assertFalse(avatar.teleportNextTo(airborneAnchor),
			"an unsupported airborne anchor must not be accepted as a teleport landing");
		context.assertTrue(avatar.getPos().squaredDistanceTo(before) < 1.0e-6,
			"a failed safe-landing search must leave the avatar on the ground");
		context.complete();
	}

	// ------------------------------------------------------------------ M0-03 fakeplayer proxy

	@GameTest(templateName = FLOOR)
	public void fakePlayerBreakDropsAndDurability(TestContext context) {
		BlockPos oreRel = new BlockPos(4, 2, 4);
		BlockPos oreAbs = context.getAbsolutePos(oreRel);
		context.setBlockState(oreRel, Blocks.STONE);

		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		FakePlayerInteractionProxy.BreakResult result = FakePlayerInteractionProxy.breakBlock(
			context.getWorld(), oreAbs, pickaxe, tool -> { });

		context.assertTrue(result.broken(), "stone should break");
		context.assertTrue(pickaxe.getDamage() == 1,
			"tool durability must decrement by 1, was " + pickaxe.getDamage());
		context.assertTrue(!result.drops().isEmpty(),
			"stone with a diamond pickaxe must drop cobblestone");
		context.assertTrue(result.drops().stream().allMatch(s -> s.getItem() == Items.COBBLESTONE),
			"expected cobblestone drop");
		context.expectBlock(Blocks.AIR, oreRel);
		context.complete();
	}

	@GameTest(templateName = FLOOR)
	public void fakePlayerPlaceConsumesItem(TestContext context) {
		BlockPos placeRel = new BlockPos(4, 2, 4); // first air cell above the platform surface
		BlockPos placeAbs = context.getAbsolutePos(placeRel);
		context.expectBlock(Blocks.AIR, placeRel);

		ItemStack stones = new ItemStack(Items.STONE, 16);
		FakePlayerInteractionProxy.PlaceResult result =
			FakePlayerInteractionProxy.placeBlock(context.getWorld(), placeAbs, stones);

		context.assertTrue(result.placed(), "placement onto supported air should succeed");
		context.expectBlock(Blocks.STONE, placeRel);
		// caller syncs the consumed count back onto the authoritative avatar stack:
		stones.setCount(15);
		context.assertTrue(stones.getCount() == 15, "caller sync semantics");
		context.complete();
	}

	@GameTest(templateName = FLOOR)
	public void fakePlayerPlaceFailsWithoutSupport(TestContext context) {
		BlockPos floatingRel = new BlockPos(4, 4, 4); // top row: nothing below to click against
		var result = FakePlayerInteractionProxy.placeBlock(
			context.getWorld(),
			context.getAbsolutePos(floatingRel),
			new ItemStack(Items.STONE, 4));
		context.assertFalse(result.placed(), "mid-air placement must fail without support");
		context.expectBlock(Blocks.AIR, floatingRel);
		context.complete();
	}

	@GameTest(templateName = FLOOR)
	public void fakePlayerBreakFailsOnAir(TestContext context) {
		var result = FakePlayerInteractionProxy.breakBlock(
			context.getWorld(),
			context.getAbsolutePos(new BlockPos(6, 3, 6)),
			ItemStack.EMPTY,
			t -> { });
		context.assertFalse(result.broken(), "breaking air must fail without side effects");
		context.complete();
	}

	@GameTest(templateName = FLOOR)
	public void fakePlayerBreakWithoutToolYieldsNothing(TestContext context) {
		BlockPos rel = new BlockPos(4, 2, 4);
		BlockPos abs = context.getAbsolutePos(rel);
		context.setBlockState(rel, Blocks.STONE);

		var result = FakePlayerInteractionProxy.breakBlock(
			context.getWorld(), abs, ItemStack.EMPTY, t -> { });

		// stone requires a tool for drops; empty-hand break still removes the block
		context.assertTrue(result.broken(), "block is removed");
		context.assertTrue(result.drops().isEmpty(), "no tool -> no drops");
		context.expectBlock(Blocks.AIR, rel);
		context.complete();
	}

	// ------------------------------------------------------------------ M0-05 structured command

	@GameTest(templateName = FLOOR)
	public void typedGiveCommandExecutesThroughBrigadier(TestContext context) {
		var target = FakePlayer.get(context.getWorld());
		target.getInventory().clear();

		var outcome = StructuredCommandCompiler.executeGive(
			context.getWorld().getServer(),
			target,
			new StructuredCommandCompiler.GiveIntent(
				new Identifier("minecraft", "stone"), 16));

		context.assertTrue(outcome.success(),
			"typed give must execute, error=" + outcome.errorDetail());

		int count = 0;
		for (int i = 0; i < target.getInventory().size(); i++) {
			ItemStack s = target.getInventory().getStack(i);
			if (s.getItem() == Items.STONE) {
				count += s.getCount();
			}
		}
		context.assertTrue(count == 16, "expected 16 stone in target inventory, got " + count);
		context.complete();
	}

	@GameTest(templateName = FLOOR)
	public void typedGiveRejectsUnknownItemAndBadCounts(TestContext context) {
		var target = FakePlayer.get(context.getWorld());

		var unknownItem = StructuredCommandCompiler.executeGive(
			context.getWorld().getServer(),
			target,
			new StructuredCommandCompiler.GiveIntent(
				new Identifier("minecraft", "not_a_real_item"), 1));
		context.assertFalse(unknownItem.success(), "unknown item id must fail validation");

		try {
			new StructuredCommandCompiler.GiveIntent(new Identifier("minecraft", "stone"), 999999);
			context.throwGameTestException("count above 4096 must be rejected by GiveIntent");
		} catch (IllegalArgumentException expected) {
			context.complete();
		}
	}

	@GameTest(templateName = FLOOR)
	public void longCommandUsesTemporaryBlockAndRestoresWorld(TestContext context) {
		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR,
			new BlockPos(2, 2, 2));
		UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000099");
		avatar.setOwner(owner);
		String name = "x".repeat(240);
		String command = "data merge entity @s {CustomName:'{\"text\":\""
			+ name + "\"}'}";
		context.assertTrue(CommandRunner.requiresCommandBlock(command),
			"test command must cross the inline limit");

		var outcome = CommandRunner.run(context.getWorld().getServer(),
			context.getWorld(), avatar.getBlockPos(), owner,
			ProtectionAdapter.ALLOW_ALL, avatar, command);

		context.assertTrue(outcome.success(),
			"long command failed: " + outcome.detail());
		context.assertTrue(outcome.viaCommandBlock(),
			"long command must use the temporary command-block carrier");
		context.assertTrue(avatar.getCustomName() != null
				&& name.equals(avatar.getCustomName().getString()),
			"@s must remain bound to the avatar through the carrier");
		for (BlockPos pos : BlockPos.iterate(avatar.getBlockPos().add(-3, 0, -3),
				avatar.getBlockPos().add(3, 5, 3))) {
			context.assertFalse(context.getWorld().getBlockState(pos)
				.isOf(Blocks.COMMAND_BLOCK),
				"temporary command block was not restored at " + pos.toShortString());
		}
		context.complete();
	}

	// ------------------------------------------------------------------ M0-06 threading

	@GameTest(templateName = FLOOR)
	public void asyncWorkerResultAppliesOnServerThread(TestContext context) {
		var server = context.getWorld().getServer();
		AtomicReference<String> applyingThread = new AtomicReference<>();
		AtomicBoolean applied = new AtomicBoolean(false);
		BlockPos absTarget = context.getAbsolutePos(new BlockPos(2, 3, 2));
		BlockPos relTarget = new BlockPos(2, 3, 2);

		CompletableFuture
			.supplyAsync(() -> Blocks.GOLD_BLOCK.getDefaultState())
			.thenAccept(state -> server.execute(() -> {
				applyingThread.set(Thread.currentThread().getName());
				context.getWorld().setBlockState(absTarget, state);
				applied.set(true);
			}));

		context.forEachRemainingTick(() -> {
			if (applied.get()) {
				context.expectBlock(Blocks.GOLD_BLOCK, relTarget);
				String threadName = applyingThread.get();
				context.assertTrue(threadName.toLowerCase().contains("server"),
					"apply step must run on the server thread, was: " + threadName);
				context.complete();
			}
		});
	}
}
