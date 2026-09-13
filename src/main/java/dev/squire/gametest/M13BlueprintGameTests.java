package dev.squire.gametest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfile;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintLoader;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.BlueprintStep;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.project.Project;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.BlueprintBuildExecutor;
import dev.squire.server.task.executors.ExcavateExecutor;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 第 1 期黑盒验收：蓝图摆放世界零改变，施工从<b>真实背包</b>扣料，缺料如实停下，
 * 掘进只动蓝图自己的负空间。
 *
 * <p>每一条断言都直接读世界方块和真实背包数量，绝不读执行器自己的计数——这一期
 * 存在的全部理由就是「材料真的少了」，用计数验它等于什么都没验。</p>
 */
public final class M13BlueprintGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void pairedBlocksAndSlabsUseTheSameWorldStateAndItemBill(TestContext context) {
		var world = context.getWorld();
		BlockPos p = context.getAbsolutePos(new BlockPos(2, 2, 2));
		world.setBlockState(p.down(), Blocks.STONE.getDefaultState(), 2);
		var lower = new Blueprint.Cell(p, "minecraft:oak_door", Map.of("half", "lower", "facing", "north"), 0, "door", false);
		var upper = new Blueprint.Cell(p.up(), "minecraft:oak_door", Map.of("half", "upper", "facing", "north"), 1, "door", false);
		var resolved = new Blueprint.Resolved(new dev.squire.server.world.BoundedRegion(p, p.up()), List.of(lower, upper), List.of());
		var assembly = dev.squire.server.blueprint.BlueprintAssembly.pending(world, lower, resolved);
		context.assertTrue(dev.squire.server.blueprint.BlueprintAssembly.cost(assembly) == 1, "one door item pays for both halves");
		context.assertTrue(dev.squire.server.blueprint.BlueprintAssembly.place(world, assembly), "door halves placed together");
		context.assertTrue(BlueprintManager.matches(world.getBlockState(p), lower)
			&& BlueprintManager.matches(world.getBlockState(p.up()), upper), "both authored halves survive neighbour updates");
		context.assertTrue(dev.squire.server.blueprint.BlueprintAssembly.pending(world, upper, resolved).isEmpty(), "already installed pair never charged twice");
		var slab = new Blueprint.Cell(p.east(2), "minecraft:stone_slab", Map.of("type", "double"), 2, "slab", false);
		context.assertTrue(BlueprintManager.itemCost(slab) == 2, "double slab requires two items");
		context.assertTrue(BlueprintManager.itemId("minecraft:wall_torch").equals(new Identifier("minecraft:torch")), "wall torch bills the inventory item");
		var malformed = new Blueprint.Resolved(resolved.bounds(), List.of(lower,
			new Blueprint.Cell(p.up(), lower.blockId(), lower.properties(), 1, "bad half", false)), List.of());
		boolean rejected = false;
		try { dev.squire.server.blueprint.BlueprintAssembly.cells(lower, malformed); }
		catch (IllegalArgumentException expected) { rejected = true; }
		context.assertTrue(rejected, "mismatched pair rejected before world edits");
		context.complete();
	}

	/** 测试专用的小蓝图：3×3×3 石壳，正中掏空一格。放得进 9×4×9 的测试场地。 */
	private static final String SHELL = "gametest_shell";
	private static final String WINDOW_SHELL = "gametest_shell_window";
	private static final int MAX_LEVEL_STONE_COST = 13;
	/** 26 = 27 格实心减掉掏空的那一格。展平之后要砌的就是这个数。 */
	private static final int SHELL_CELLS = 26;
	private static final List<String> KEEPITLEVEL = List.of(
		"keepitlevel_residence", "keepitlevel_fountain", "keepitlevel_builder_lodge",
		"keepitlevel_guardtower", "keepitlevel_library", "keepitlevel_warehouse");

	private static void registerShell(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(SHELL, "测试壳", 1,
			Blueprint.Category.SHELTER, 3, 3, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 2, 2, "minecraft:stone",
					"壳", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空")),
			Set.of()));
	}

	/** 带一格可选窗的变体：缺料时那一格要被跳过，而不是让整栋停在半截。 */
	private static void registerShellWithWindow(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(WINDOW_SHELL, "测试壳", 1,
			Blueprint.Category.SHELTER, 3, 3, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 2, 2, "minecraft:stone",
					"壳", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空"),
				BlueprintStep.place(2, 0, 1, 1, 0, 1, 1, "minecraft:glass_pane",
					"窗", true)),
			Set.of()));
	}

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
		// 主人也要钉在本次测试自己的场地里：FakePlayer 按 profile 缓存，
		// 而蓝图是摆在「主人面前」的——他站在哪儿，工地就在哪儿。
		net.minecraft.util.math.Vec3d ownerFeet = Vec3d.ofBottomCenter(
			context.getAbsolutePos(new BlockPos(1, 2, 1)));
		owner.refreshPositionAndAngles(ownerFeet.x, ownerFeet.y, ownerFeet.z, 0.0f, 0.0f);
		AvatarEntity avatar = rt.summonFor(owner);
		rt.profileOf(avatar).profession.setProfession(SquireProfession.ENGINEER);
		rt.profileOf(avatar).profession.level = SquireProfession.MAX_LEVEL;
		avatar.refreshPositionAndAngles(
			Vec3d.ofBottomCenter(context.getAbsolutePos(relative)).x,
			Vec3d.ofBottomCenter(context.getAbsolutePos(relative)).y,
			Vec3d.ofBottomCenter(context.getAbsolutePos(relative)).z, 0.0f, 0.0f);
		// 用 IDLE 而不是 STAY：这里只是不想让他跟着人走。
		// STAY 现在带着一个区域闸门，会让施工走到圈边时被拒。
		avatar.setIdleMode();
		return avatar;
	}

	/** 直接摆一个落点写死的摆放：位置可控，断言才能逐格对。 */
	private static BlueprintPlacement placeAt(SquireRuntime rt, TestContext context,
			FakePlayer owner, AvatarEntity avatar, BlockPos relativeOrigin) {
		return placeAt(rt, context, owner, avatar, relativeOrigin, SHELL);
	}

	private static BlueprintPlacement placeAt(SquireRuntime rt, TestContext context,
			FakePlayer owner, AvatarEntity avatar, BlockPos relativeOrigin, String blueprintId) {
		BlueprintPlacement placement = new BlueprintPlacement(UUID.randomUUID(),
			owner.getUuid(), avatar.agentId(), blueprintId,
			context.getWorld().getRegistryKey().getValue().toString(),
			context.getAbsolutePos(relativeOrigin), Direction.NORTH, rt.tickNow());
		rt.blueprints().put(placement);
		return placement;
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.blueprints().activeOf(owner.getUuid())
			.ifPresent(p -> rt.blueprints().remove(p.placementId));
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	// ================================================== 摆放：世界零改变

	/** 摆一份蓝图只该产生粒子和一份账，世界一格都不能变。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-blueprint-ghost")
	public void placingABlueprintChangesNothingInTheWorld(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "bp-ghost-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			summon(context, rt, owner, new BlockPos(1, 2, 1));
			var result = rt.blueprintPlace(owner, SHELL);
			context.assertTrue(result.success(), "placing must succeed: "
				+ result.message());

			BlueprintPlacement placement = rt.blueprints().activeOf(owner.getUuid())
				.orElseThrow();
			context.assertTrue(placement.state() == BlueprintPlacement.State.GHOST,
				"a fresh placement is a ghost, not a build order");
			Blueprint.Resolved resolved = rt.blueprints().resolve(placement)
				.orElseThrow();
			for (Blueprint.Cell cell : resolved.toPlace()) {
				context.assertTrue(
					context.getWorld().getBlockState(cell.pos()).isAir(),
					"the world must be untouched at " + cell.pos());
			}
			context.assertTrue(result.message().contains("石头")
					&& !result.message().contains("minecraft:stone"),
				"the reply must name the material the player has to find");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 施工：材料真的少了

	/** 施工逐格从真实背包扣料；盖完之后背包里正好少了那么多。 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-blueprint-build")
	public void buildingSpendsRealMaterialsOutOfTheBackpack(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-build-owner");
		AtomicBoolean done = new AtomicBoolean(false);
		registerShell(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.STONE, 64));
			avatar.items().insert(new ItemStack(Items.TORCH, 8));
			placeAt(rt, context, owner, avatar, new BlockPos(4, 2, 4));
			var result = rt.blueprintBuild(owner);
			context.assertTrue(result.success(), "build must start: " + result.message());
		});

		context.runAtTick(300, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int placed = 0;
			for (BlockPos pos : BlockPos.iterate(context.getAbsolutePos(
					new BlockPos(4, 2, 4)), context.getAbsolutePos(
					new BlockPos(6, 4, 6)))) {
				if (world.getBlockState(pos).isOf(Blocks.STONE)) {
					placed++;
				}
			}
			context.assertTrue(placed == SHELL_CELLS,
				"expected the " + SHELL_CELLS + "-cell shell, found " + placed);
			context.assertTrue(!world.getBlockState(context.getAbsolutePos(
					new BlockPos(5, 3, 5))).isOf(Blocks.STONE),
				"the hollowed centre is not filled by the shell; it may hold a torch");
			int left = avatar.items().countOf(new Identifier("minecraft:stone"));
			context.assertTrue(left == 64 - MAX_LEVEL_STONE_COST,
				"the backpack must really be " + MAX_LEVEL_STONE_COST + " lighter, has " + left);
			context.assertTrue(!rt.undoableFor(owner.getUuid()).isEmpty(),
				"blueprint building is undoable like every other world write");
			done.set(true);
			cleanUp(rt, owner);
			context.complete();
		});

		context.runAtTick(390, () -> {
			if (!done.get()) {
				context.throwGameTestException("blueprint build never finished");
			}
		});
	}

	/** 材料不够时施工如实停下：盖了几格就是几格，绝不凭空补齐。 */
	@GameTest(templateName = FLOOR, tickLimit = 400,
		batchId = "squire-blueprint-short")
	public void runningOutOfMaterialStopsHonestly(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-short-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.STONE, 4));
			BlueprintPlacement placement = placeAt(rt, context, owner, avatar,
				new BlockPos(4, 2, 4));
			// 走一次玩家入口：4 块石头盖不起 22 格的壳，所以确认会建立工程、
			// 锁住这一批真实材料并停在备料阶段，而不是要求一次装齐或凭空补齐。
			var confirmed = rt.blueprintBuild(owner);
			context.assertTrue(confirmed.success(), confirmed.message());
			context.assertTrue(confirmed.message().contains("石头"),
				"the material blocker must say what is short, got: "
					+ confirmed.message());
			Project waiting = rt.projects().activeOf(owner.getUuid()).orElseThrow();
			context.assertTrue(waiting.state() == Project.State.PAUSED,
				"an incomplete real batch must leave a durable waiting project");
			context.assertTrue(waiting.reservedCount(
				new Identifier("minecraft:stone")) == 4,
				"all four real blocks must be held by the project pool");
			// 下面单独验底层执行器的缺料行为；先取消工程把同一批四块石头
			// 原样返还给侍从。Coordinator cancel 不会删除本测试仍要直接使用的 placement。
			rt.projects().cancel(waiting);
			context.assertTrue(avatar.items().countOf(
				new Identifier("minecraft:stone")) == 4,
				"cancelling must return the staged batch before the direct executor test");
			// 再直接提交任务，验执行器本身也会诚实停下，而不是凭空补料。
			Map<String, Object> params = Map.of(
				BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
				placement.placementId.toString());
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"short build", null,
				BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
					placement.placementId),
				300L, RetryPolicy.DEFAULT, true, "c1", params), world.getTime());
		});

		context.runAtTick(250, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int placed = 0;
			for (BlockPos pos : BlockPos.iterate(context.getAbsolutePos(
					new BlockPos(4, 2, 4)), context.getAbsolutePos(
					new BlockPos(6, 4, 6)))) {
				if (world.getBlockState(pos).isOf(Blocks.STONE)) {
					placed++;
				}
			}
			context.assertTrue(placed == 4,
				"exactly the 4 carried blocks went into the wall, found " + placed);
			context.assertTrue(avatar.items().countOf(
					new Identifier("minecraft:stone")) == 0,
				"the material really left the backpack");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 可选步骤缺料时被跳过，整栋不会因为差两块玻璃而卡住。 */
	@GameTest(templateName = FLOOR, tickLimit = 400,
		batchId = "squire-blueprint-optional")
	public void anOptionalCellIsSkippedWhenItsMaterialIsMissing(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-optional-owner");
		registerShellWithWindow(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.STONE, 64)); // 石头够，玻璃一块没有
			BlueprintPlacement placement = placeAt(rt, context, owner, avatar,
				new BlockPos(4, 2, 4), WINDOW_SHELL);
			Map<String, Object> params = Map.of(
				BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
				placement.placementId.toString());
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"build with a window we cannot afford", null,
				BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
					placement.placementId),
				300L, RetryPolicy.DEFAULT, true, "c1", params), world.getTime());
		});

		context.runAtTick(300, () -> {
			BlueprintPlacement placement = rt.blueprints().activeOf(owner.getUuid())
				.orElseThrow();
			// 窗那一格没装上，但成功条件仍然成立：可选就是真的可选。
			context.assertTrue(world.getBlockState(context.getAbsolutePos(
					new BlockPos(4, 3, 5))).isAir(),
				"the window we could not afford stays empty");
			var condition = BlueprintBuildExecutor.blueprintBuilt(
				rt.runtimeServicesForTest(), placement.placementId);
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(condition.evaluate(
					new dev.squire.server.task.TaskEvaluationContext() {
						@Override
						public long tick() {
							return world.getTime();
						}

						@Override
						public UUID agentId() {
							return avatar.agentId();
						}

						@Override
						public dev.squire.api.body.AgentBody body() {
							return avatar;
						}
					}),
				"a missing OPTIONAL cell must not keep the task stuck in VERIFYING");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 掘进：只动自己的足印

	/** 掘进只挖蓝图自己的负空间，足印外一格都不动——这条硬拦在服务端，不靠提示词。 */
	@GameTest(templateName = FLOOR, tickLimit = 400,
		batchId = "squire-blueprint-excavate")
	public void excavationNeverTouchesAnythingOutsideTheFootprint(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-dig-owner");
		registerShell(rt);

		BlockPos inside = context.getAbsolutePos(new BlockPos(5, 3, 5));  // 掏空的正中
		BlockPos outside = context.getAbsolutePos(new BlockPos(7, 3, 5)); // 足印之外

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.IRON_PICKAXE, 1));
			world.setBlockState(inside, Blocks.DIRT.getDefaultState());
			world.setBlockState(outside, Blocks.DIRT.getDefaultState());
			BlueprintPlacement placement = placeAt(rt, context, owner, avatar,
				new BlockPos(4, 2, 4));
			Map<String, Object> params = Map.of(
				ExcavateExecutor.PARAM_PLACEMENT_ID, placement.placementId.toString());
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				ExcavateExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"clear the negative space", null,
				ExcavateExecutor.blueprintCleared(rt.runtimeServicesForTest(),
					placement.placementId),
				300L, RetryPolicy.DEFAULT, true, "c1", params), world.getTime());
		});

		context.runAtTick(250, () -> {
			context.assertTrue(world.getBlockState(inside).isAir(),
				"the blueprint's own negative space must be dug out");
			context.assertTrue(world.getBlockState(outside).isOf(Blocks.DIRT),
				"a block one step outside the footprint must be untouched");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 足印硬拦是一个纯判定，单独钉住：摆放没了就一格都不许挖，绝不退化成随便挖。 */
	@GameTest(templateName = FLOOR, tickLimit = 100,
		batchId = "squire-blueprint-footprint")
	public void anEmptyFootprintForbidsEverything(TestContext context) {
		context.assertFalse(ExcavateExecutor.allowed(Set.of(), BlockPos.ORIGIN),
			"no footprint means no digging at all");
		context.assertFalse(ExcavateExecutor.allowed(null, BlockPos.ORIGIN),
			"a missing footprint is not a licence to dig");
		context.assertTrue(
			ExcavateExecutor.allowed(Set.of(BlockPos.ORIGIN), BlockPos.ORIGIN),
			"cells inside the footprint are allowed");
		context.complete();
	}

	// ================================================== 账目读的是世界

	/** 已经就位的方块从需求里扣掉：在半成品上再看一次账，看到的是剩余量。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-blueprint-bill")
	public void theBillDiscountsBlocksAlreadyStanding(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-bill-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			BlueprintPlacement placement = placeAt(rt, context, owner, avatar,
				new BlockPos(4, 2, 4));
			Blueprint.Resolved resolved = rt.blueprints().resolve(placement)
				.orElseThrow();
			var before = BlueprintManager.bill(world, resolved, avatar.items());
			context.assertTrue(before.totalRequired() == MAX_LEVEL_STONE_COST,
				"a bare site needs the whole shell, got " + before.totalRequired());

			// 玩家自己先砌了一角。
			var paidCell = resolved.costPlan().operations().stream()
				.filter(op -> op.total() == 1).findFirst().orElseThrow().cells().get(0);
			world.setBlockState(paidCell.pos(), Blocks.STONE.getDefaultState());
			var after = BlueprintManager.bill(world, resolved, avatar.items());
			context.assertTrue(after.totalRequired() == MAX_LEVEL_STONE_COST - 1,
				"an already-standing block must leave the requirement");
			context.assertTrue(after.totalPlaced() == 1,
				"and be reported as progress instead");
			context.assertFalse(after.satisfied(), "an empty backpack is not enough");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 地不平也要能盖完：挡在施工位上的方块先被清掉，而不是被跳过。
	 *
	 * <p>这是一个安静的死锁：施工执行器把埋在土里的墙体格当「已被占」跳过、报
	 * WORK_DONE，而成功条件读到那几格不是目标方块，于是永远不满足——任务卡在
	 * VERIFYING 直到超时。玩家看到的是「盖了几块就停在那儿了」。平整场地本来就是
	 * 施工的一部分，而且全部发生在蓝图自己的足印内。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-blueprint-level")
	public void anObstructedSiteIsLevelledInsteadOfSkipped(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-level-owner");
		registerShell(rt);
		BlockPos buried = context.getAbsolutePos(new BlockPos(5, 2, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.STONE, 64));
			avatar.items().insert(new ItemStack(Items.TORCH, 8));
			avatar.items().insert(new ItemStack(Items.IRON_PICKAXE, 1));
			// 一格泥土正好压在墙体位上——地不平时到处都是这种格子。
			world.setBlockState(buried, Blocks.DIRT.getDefaultState());
			placeAt(rt, context, owner, avatar, new BlockPos(4, 2, 4));
			var started = rt.blueprintBuild(owner);
			context.assertTrue(started.success(), started.message());
		});

		context.runAtTick(400, () -> {
			context.assertTrue(world.getBlockState(buried).isOf(Blocks.STONE),
				"the obstruction was cleared and the wall went up in its place, found "
					+ world.getBlockState(buried).getBlock());
			int placed = 0;
			for (BlockPos pos : BlockPos.iterate(context.getAbsolutePos(
					new BlockPos(4, 2, 4)), context.getAbsolutePos(
					new BlockPos(6, 4, 6)))) {
				if (world.getBlockState(pos).isOf(Blocks.STONE)) {
					placed++;
				}
			}
			context.assertTrue(placed == SHELL_CELLS,
				"and the shell is complete, found " + placed);
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 沙地上的真实回归：掘进必须从上往下排，且阶段交界处迟到的落沙由施工安全补清。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 500,
		batchId = "squire-blueprint-falling-refill")
	public void fallingTerrainCannotWedgeConstruction(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-falling-owner");
		registerShell(rt);
		BlockPos bottom = context.getAbsolutePos(new BlockPos(4, 2, 4));
		BlockPos top = context.getAbsolutePos(new BlockPos(4, 4, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.STONE, 64));
			avatar.items().insert(new ItemStack(Items.IRON_SHOVEL, 1));
			BlueprintPlacement placement = placeAt(rt, context, owner, avatar,
				new BlockPos(4, 2, 4));
			Blueprint.Resolved resolved = rt.blueprints().resolve(placement)
				.orElseThrow();

			world.setBlockState(bottom, Blocks.SAND.getDefaultState());
			world.setBlockState(top, Blocks.SAND.getDefaultState());
			List<BlockPos> clearing = BlueprintManager.pendingClear(world, resolved);
			context.assertTrue(clearing.indexOf(top) >= 0
					&& clearing.indexOf(bottom) >= 0
					&& clearing.indexOf(top) < clearing.indexOf(bottom),
				"gravity-affected site cells must be excavated top-down: " + clearing);

			// 模拟掘进验收后、施工启动前才落回地基格的沙：此时项目已经进入
			// BUILD，不能要求玩家取消重来，也不能开放对普通玩家方块的拆改。
			world.setBlockState(top, Blocks.AIR.getDefaultState());
			Map<String, Object> params = Map.of(
				BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
				placement.placementId.toString());
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"build after a falling terrain refill", null,
				BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
					placement.placementId),
				300L, RetryPolicy.DEFAULT, true, "c1", params), world.getTime());
		});

		context.runAtTick(350, () -> {
			context.assertTrue(world.getBlockState(bottom).isOf(Blocks.STONE),
				"late sand refill must be safely cleared and replaced by the blueprint");
			int placed = 0;
			for (BlockPos pos : BlockPos.iterate(context.getAbsolutePos(
					new BlockPos(4, 2, 4)), context.getAbsolutePos(
					new BlockPos(6, 4, 6)))) {
				if (world.getBlockState(pos).isOf(Blocks.STONE)) placed++;
			}
			context.assertTrue(placed == SHELL_CELLS,
				"the refill must not leave the task retrying; found " + placed
					+ " of " + SHELL_CELLS + " cells");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** A failed route may pause construction, but it must never degrade to remote writes. */
	@GameTest(templateName = FLOOR, tickLimit = 220,
		batchId = "squire-blueprint-no-remote")
	public void anEnclosedWorkerCannotBuildFromAcrossTheSite(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-no-remote-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			BlockPos feet = context.getAbsolutePos(new BlockPos(1, 2, 1));
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			avatar.items().insert(new ItemStack(Items.STONE, 64));
			for (Direction direction : Direction.Type.HORIZONTAL) {
				world.setBlockState(feet.offset(direction), Blocks.BEDROCK.getDefaultState());
				world.setBlockState(feet.offset(direction).up(),
					Blocks.BEDROCK.getDefaultState());
			}
			world.setBlockState(feet.up(2), Blocks.BEDROCK.getDefaultState());
			BlueprintPlacement placement = placeAt(rt, context, owner, avatar,
				new BlockPos(5, 2, 5));
			Map<String, Object> params = Map.of(
				BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
				placement.placementId.toString());
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"physically blocked build", null,
				BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
					placement.placementId),
				160L, RetryPolicy.DEFAULT, true, "no-remote", params), world.getTime());
		});

		context.runAtTick(180, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int placed = 0;
			for (BlockPos pos : BlockPos.iterate(context.getAbsolutePos(
					new BlockPos(5, 2, 5)), context.getAbsolutePos(
					new BlockPos(7, 4, 7)))) {
				if (world.getBlockState(pos).isOf(Blocks.STONE)) placed++;
			}
			context.assertTrue(placed == 0,
				"an unreachable worker must place zero remote blocks, found " + placed);
			context.assertTrue(avatar.items().countOf(
					new Identifier("minecraft:stone")) == 64,
				"failed pathfinding must not consume construction material");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Vanilla StructureTemplate decoding feeds the exact same resolved-cell pipeline. */
	@GameTest(templateName = FLOOR, tickLimit = 40,
		batchId = "squire-blueprint-native-nbt")
	public void nativeStructureNbtImportsBlockStatesAirAndPaletteSlots(TestContext context) {
		try {
			NbtCompound root = new NbtCompound();
			root.put("size", ints(3, 1, 1));
			NbtList palette = new NbtList();
			NbtCompound stairs = new NbtCompound();
			stairs.putString("Name", "minecraft:oak_stairs");
			NbtCompound properties = new NbtCompound();
			properties.putString("facing", "north");
			properties.putString("half", "bottom");
			properties.putString("shape", "straight");
			properties.putString("waterlogged", "false");
			stairs.put("Properties", properties);
			palette.add(stairs);
			NbtCompound air = new NbtCompound();
			air.putString("Name", "minecraft:air");
			palette.add(air);
			NbtCompound wall = new NbtCompound();
			wall.putString("Name", "minecraft:cobblestone_wall");
			NbtCompound wallProperties = new NbtCompound();
			wallProperties.putString("north", "low");
			wall.put("Properties", wallProperties);
			palette.add(wall);
			root.put("palette", palette);
			NbtList blocks = new NbtList();
			blocks.add(structureBlock(0, 0, 0, 0));
			blocks.add(structureBlock(1, 0, 0, 2));
			blocks.add(structureBlock(2, 0, 0, 1));
			root.put("blocks", blocks);
			root.put("entities", new NbtList());
			root.putInt("DataVersion", 3465);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			NbtIo.writeCompressed(root, output);

			Blueprint blueprint = new BlueprintLoader().load("native_test", """
				{"schemaVersion":1,"id":"native_test","format":"minecraft:structure_nbt",
				 "structure":"squire:test/native","airMode":"clear",
				 "materialSlots":[{"id":"roof","type":"ROOF",
				   "defaultFamily":"squire:spruce","requiredVariants":["stairs"]}],
				 "palette":{"minecraft:oak_stairs":{"slot":"roof","variant":"stairs"}}}
				""", id -> new ByteArrayInputStream(output.toByteArray()));
			Blueprint.Resolved resolved = blueprint.resolve(BlockPos.ORIGIN, Direction.EAST);
			context.assertTrue(resolved.toPlace().size() == 2,
				"native structure must import both solid blocks");
			context.assertTrue(resolved.toClear().size() == 1,
				"airMode=clear must import one negative-space cell");
			Blueprint.Cell stairCell = resolved.toPlace().stream()
				.filter(cell -> "minecraft:spruce_stairs".equals(cell.blockId()))
				.findFirst().orElseThrow();
			context.assertTrue("east".equals(stairCell.properties()
				.get("facing")), "NBT block state must rotate with the blueprint");
			Blueprint.Cell wallCell = resolved.toPlace().stream()
				.filter(cell -> "minecraft:cobblestone_wall".equals(cell.blockId()))
				.findFirst().orElseThrow();
			context.assertTrue("low".equals(wallCell.properties().get("east"))
					&& "none".equals(wallCell.properties().get("north")),
				"native rotation must move directional wall connections, not only facing");
			context.complete();
		} catch (Exception bad) {
			throw new AssertionError("native NBT import failed", bad);
		}
	}

	private static NbtCompound structureBlock(int x, int y, int z, int state) {
		NbtCompound block = new NbtCompound();
		block.put("pos", ints(x, y, z));
		block.putInt("state", state);
		return block;
	}

	private static NbtList ints(int... values) {
		NbtList list = new NbtList();
		for (int value : values) list.add(NbtInt.of(value));
		return list;
	}

	/** Imported structures must resolve into valid 1.20.1 block items in every rotation. */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-blueprint-import")
	public void keepItLevelImportsResolveToVanillaStatesInEveryRotation(TestContext context) {
		SquireRuntime rt = runtime(context);
		for (String id : KEEPITLEVEL) {
			Blueprint blueprint = rt.blueprints().registry().byId(id).orElseThrow();
			context.assertTrue("MIT".equals(blueprint.metadata().license()),
				id + " lost its redistribution license metadata");
			int cells = -1;
			for (Direction direction : Direction.Type.HORIZONTAL) {
				Blueprint.Resolved resolved = blueprint.resolve(BlockPos.ORIGIN, direction);
				if (cells < 0) cells = resolved.cellCount();
				context.assertTrue(cells == resolved.cellCount(),
					id + " changed cell count when rotated");
				for (Blueprint.Cell cell : resolved.toPlace()) {
					context.assertTrue(cell.blockId().startsWith("minecraft:"),
						id + " still requires " + cell.blockId());
					BlueprintManager.targetState(cell);
				}
			}
			context.assertTrue(BlueprintManager.unknownBlocks(blueprint,
				rt.blueprints().registry().materials()).isEmpty(),
				id + " contains an invalid or unplaceable state");
		}
		context.complete();
	}

	/** A redistributed asset goes through the real item-consuming construction executor. */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-blueprint-import-build")
	public void importedFountainIsActuallyBuiltFromItsResolvedBlueprint(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "bp-import-build-owner");
		BlockPos origin = context.getAbsolutePos(new BlockPos(3, 2, 3));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			Blueprint blueprint = rt.blueprints().registry().byId("keepitlevel_fountain")
				.orElseThrow();
			BlueprintPlacement placement = new BlueprintPlacement(UUID.randomUUID(),
				owner.getUuid(), avatar.agentId(), blueprint.id(),
				world.getRegistryKey().getValue().toString(), origin, Direction.NORTH,
				rt.tickNow());
			rt.blueprints().put(placement);
			Map<Identifier, Integer> required = new java.util.LinkedHashMap<>();
			for (Blueprint.Cell cell : blueprint.resolve(origin, Direction.NORTH).toPlace()) {
				required.merge(BlueprintManager.itemId(cell.blockId()), 1, Integer::sum);
			}
			for (var entry : required.entrySet()) {
				avatar.items().insert(new ItemStack(Registries.ITEM.get(entry.getKey()),
					entry.getValue()));
			}
			avatar.items().insert(new ItemStack(Items.IRON_PICKAXE));
			Map<String, Object> params = Map.of(BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
				placement.placementId.toString());
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"build imported fountain", null,
				BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
					placement.placementId), 500L, RetryPolicy.DEFAULT, true, "import", params),
				world.getTime());
		});

		context.runAtTick(480, () -> {
			BlueprintPlacement placement = rt.blueprints().activeOf(owner.getUuid())
				.orElseThrow();
			Blueprint.Resolved resolved = rt.blueprints().resolve(placement).orElseThrow();
			for (Blueprint.Cell cell : resolved.toPlace()) {
				context.assertTrue(BlueprintManager.matches(world.getBlockState(cell.pos()), cell),
					"imported build diverged from preview at " + cell.pos());
			}
			cleanUp(rt, owner);
			context.complete();
		});
	}

}
