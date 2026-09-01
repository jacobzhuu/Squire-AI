package dev.squire.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.BlueprintStep;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profile.Ability;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Track;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.BlueprintBuildExecutor;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/**
 * 第 2 期验收：职业 / 能力槽 / 成长 / 撤销回扣 / 进阶能力真的做事。
 *
 * <p>成长的断言全部读<b>真实档案</b>（跨过任务终态那一个记账入口），能力的断言全部读
 * <b>真实世界方块</b>——「装上了但什么都没发生」正是这一期最该防住的失败。</p>
 */
public final class M14ProfileGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	/** 3×3×3 橡木板小壳，正中掏空一格：26 格要放。 */
	private static final String SHELL = "gametest_profile_shell";
	private static final int SHELL_CELLS = 26;

	private static void registerShell(SquireRuntime rt) {
		rt.blueprints().registry().register(new Blueprint(SHELL, "测试壳", 1,
			Blueprint.Category.SHELTER, 3, 3, 3,
			List.of(BlueprintStep.place(0, 0, 0, 0, 2, 2, 2, "minecraft:oak_planks",
					"壳", false),
				BlueprintStep.dig(1, 1, 1, 1, 1, 1, 1, "掏空")),
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
		AvatarEntity avatar = rt.summonFor(owner);
		Vec3d feet = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
		avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
		// 用 IDLE 而不是 STAY：这里只是不想让他跟着人走。
		// STAY 现在带着一个区域闸门，会让施工走到圈边时被拒。
		avatar.setIdleMode();
		return avatar;
	}

	/** 给这只随从一个职业和一项已装备的进阶能力，跳过练级。 */
	private static SquireProfile specialise(SquireRuntime rt, AvatarEntity avatar,
			Role role, Ability... abilities) {
		SquireProfile profile = rt.profileOf(avatar);
		profile.roleId = role.id();
		for (Ability ability : abilities) {
			profile.unlockedAbilities.add(ability.id());
			profile.equippedAbilities.add(ability.id());
		}
		return profile;
	}

	private static BlueprintPlacement placeAt(SquireRuntime rt, TestContext context,
			FakePlayer owner, AvatarEntity avatar, BlockPos relativeOrigin) {
		BlueprintPlacement placement = new BlueprintPlacement(UUID.randomUUID(),
			owner.getUuid(), avatar.agentId(), SHELL,
			context.getWorld().getRegistryKey().getValue().toString(),
			context.getAbsolutePos(relativeOrigin), Direction.NORTH, rt.tickNow());
		rt.blueprints().put(placement);
		return placement;
	}

	private static void submitBuild(SquireRuntime rt, ServerWorld world,
			FakePlayer owner, AvatarEntity avatar, BlueprintPlacement placement) {
		Map<String, Object> params = Map.of(
			BlueprintBuildExecutor.PARAM_PLACEMENT_ID, placement.placementId.toString());
		rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
			BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK, "profile build",
			null, BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
				placement.placementId),
			600L, RetryPolicy.DEFAULT, true, "c2", params), world.getTime());
	}

	private static int countIn(TestContext context, BlockPos min, BlockPos max,
			net.minecraft.block.Block block) {
		int found = 0;
		for (BlockPos pos : BlockPos.iterate(context.getAbsolutePos(min),
				context.getAbsolutePos(max))) {
			if (context.getWorld().getBlockState(pos).isOf(block)) {
				found++;
			}
		}
		return found;
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.blueprints().activeOf(owner.getUuid())
			.ifPresent(p -> rt.blueprints().remove(p.placementId));
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	// ================================================== 档案与成长

	/** 首次召唤就有性格；选职业不会让他忘掉基础能力。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-profile-basics")
	public void aFreshCompanionHasTraitsAndKeepsItsBasicAbilities(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "profile-basics-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			SquireProfile profile = rt.profileOf(avatar);
			context.assertTrue(profile != null, "a summoned companion has a profile");
			context.assertFalse(profile.traits.isEmpty(),
				"traits are rolled once, on the first summon");
			context.assertTrue(profile.traits.size() <= 2, "one or two, never more");
			context.assertTrue(rt.can(avatar, Ability.BUILD_BLUEPRINT),
				"a roleless companion still does everything it did yesterday");
			context.assertFalse(rt.can(avatar, Ability.BUILD_FAST),
				"advanced abilities have to be earned and equipped");

			var result = rt.setRole(owner, Role.BUILDER.id());
			context.assertTrue(result.success(), result.message());
			context.assertTrue(rt.can(avatar, Ability.BUILD_BLUEPRINT),
				"picking a role must never take away a basic ability");
			context.assertFalse(rt.setRole(owner, Role.FARMER.id()).success(),
				"the farmer role is advertised but honestly refused");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 熟练度只在任务终态成功时记一次；撤销那次操作会把它扣回来。 */
	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-profile-growth")
	public void proficiencyIsAwardedOnceAndTakenBackByUndo(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "profile-growth-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			specialise(rt, avatar, Role.BUILDER);
			avatar.items().insert(new ItemStack(Items.OAK_PLANKS, 64));
			submitBuild(rt, world, owner, avatar,
				placeAt(rt, context, owner, avatar, new BlockPos(4, 2, 4)));
		});

		context.runAtTick(300, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			SquireProfile profile = rt.profileOf(avatar);
			context.assertTrue(profile.proficiencyOf(Track.BUILD) == SHELL_CELLS,
				"proficiency must equal the blocks that really went up, got "
					+ profile.proficiencyOf(Track.BUILD));
			context.assertTrue(profile.level() == 1,
				"26 格还没到第一档里程碑（40）——里程碑不该随手就过");

			// 撤销回扣：没有它，「建 → undo → 建」就是无限刷。
			var undone = rt.undoOperation(owner, null, true);
			context.assertTrue(undone.success(), undone.message());
			context.assertTrue(profile.proficiencyOf(Track.BUILD) == 0,
				"undoing the build must take the proficiency back, left "
					+ profile.proficiencyOf(Track.BUILD));
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 进阶能力真的做事

	/** 「通用建材」：缺橡木板时云杉木板顶得上，而且成功条件认这堵墙。 */
	@GameTest(templateName = FLOOR, tickLimit = 500,
		batchId = "squire-profile-substitute")
	public void substituteMaterialsFinishTheWallAndStillVerify(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "profile-subst-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			specialise(rt, avatar, Role.BUILDER, Ability.BUILD_SUBSTITUTE);
			// 一块橡木板都没有，只有云杉木板。
			avatar.items().insert(new ItemStack(Items.SPRUCE_PLANKS, 64));
			submitBuild(rt, world, owner, avatar,
				placeAt(rt, context, owner, avatar, new BlockPos(4, 2, 4)));
		});

		context.runAtTick(300, () -> {
			int spruce = countIn(context, new BlockPos(4, 2, 4), new BlockPos(6, 4, 6),
				Blocks.SPRUCE_PLANKS);
			context.assertTrue(spruce == SHELL_CELLS,
				"the substitute really went up, found " + spruce);
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(rt.profileOf(avatar).proficiencyOf(Track.BUILD) > 0,
				"a wall built out of substitutes still counts as work done - "
					+ "if the success condition refused it, the task never completed");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 「拆改」：挡路的错方块被拆掉再盖；没有这项能力时原样跳过。 */
	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-profile-demolish")
	public void demolishClearsAWrongBlockThatWouldOtherwiseBeSkipped(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "profile-demo-owner");
		registerShell(rt);
		BlockPos blocker = context.getAbsolutePos(new BlockPos(4, 2, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			specialise(rt, avatar, Role.BUILDER, Ability.BUILD_DEMOLISH);
			avatar.items().insert(new ItemStack(Items.OAK_PLANKS, 64));
			world.setBlockState(blocker, Blocks.OBSIDIAN.getDefaultState());
			submitBuild(rt, world, owner, avatar,
				placeAt(rt, context, owner, avatar, new BlockPos(4, 2, 4)));
		});

		context.runAtTick(320, () -> {
			context.assertTrue(world.getBlockState(blocker).isOf(Blocks.OAK_PLANKS),
				"the wrong block was cleared and the wall went up in its place");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 没有「拆改」时，挡路的方块原样留着——默认绝不动玩家已经放好的东西。 */
	@GameTest(templateName = FLOOR, tickLimit = 500, batchId = "squire-profile-respect")
	public void withoutDemolishAWrongBlockIsLeftAlone(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "profile-respect-owner");
		registerShell(rt);
		BlockPos blocker = context.getAbsolutePos(new BlockPos(4, 2, 4));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			specialise(rt, avatar, Role.BUILDER);
			avatar.items().insert(new ItemStack(Items.OAK_PLANKS, 64));
			world.setBlockState(blocker, Blocks.OBSIDIAN.getDefaultState());
			submitBuild(rt, world, owner, avatar,
				placeAt(rt, context, owner, avatar, new BlockPos(4, 2, 4)));
		});

		context.runAtTick(320, () -> {
			context.assertTrue(world.getBlockState(blocker).isOf(Blocks.OBSIDIAN),
				"a companion without 拆改 must not touch what the player put there");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 档案里的巡逻点

	/**
	 * 巡逻点位跨重启存活，坏点位只跳过自己。
	 *
	 * <p>本质是纯 NBT 往返，但 {@code GlobalPos} 要注册表引导，headless 单测的类加载器
	 * 起不来 Bootstrap（和 M12 同一个原因），所以放在这里跑。不碰世界，只借环境。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-profile-patrol")
	public void patrolPointsRoundTripAndSurviveACorruptRow(TestContext context) {
		SquireProfile original = new SquireProfile();
		original.roleId = Role.SCOUT.id();
		original.patrolPoints.add(GlobalPos.create(
			RegistryKey.of(RegistryKeys.WORLD, new Identifier("minecraft:the_nether")),
			new BlockPos(10, 64, -30)));
		original.patrolCursor = 1;

		SquireProfile back = new SquireProfile();
		back.readNbt(original.writeNbt(new NbtCompound()));
		context.assertTrue(back.patrolPoints.size() == 1, "the point round-trips");
		context.assertTrue(new BlockPos(10, 64, -30).equals(
			back.patrolPoints.get(0).getPos()), "position preserved");
		context.assertTrue("minecraft:the_nether".equals(
				back.patrolPoints.get(0).getDimension().getValue().toString()),
			"a patrol point has to remember which world it is in");
		context.assertTrue(back.patrolCursor == 1, "the cursor round-trips too");

		NbtCompound broken = original.writeNbt(new NbtCompound());
		broken.getList("patrolPoints", net.minecraft.nbt.NbtElement.COMPOUND_TYPE)
			.getCompound(0).putString("dim", "NOT A VALID ID");
		SquireProfile survivor = new SquireProfile();
		survivor.readNbt(broken);
		context.assertTrue(survivor.patrolPoints.isEmpty(), "the bad row is skipped");
		context.assertTrue(Role.SCOUT == survivor.role(),
			"one bad point must not sink the rest of the profile");
		context.complete();
	}
}
