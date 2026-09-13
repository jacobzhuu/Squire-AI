package dev.squire.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.gui.SquireActions;
import dev.squire.server.gui.SquireScreenHandler;
import dev.squire.server.gui.AvatarEquipmentInventory;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profession.TrainingMilestone;
import dev.squire.server.runtime.SquireRuntime;
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
 * 职业交互 UI：<b>一个不知道任何命令的玩家，只按按钮能不能走完整条成长线</b>。
 *
 * <h2>为什么按「按钮」而不是按「命令」测</h2>
 * <p>这一轮的问题从来不是机制不对，而是玩家够不着。所以每一条都从
 * {@link SquireActions} 里<b>按按钮 id 取出真正的 handler</b> 再执行——
 * 和玩家点下去走的是同一段代码。直接调 {@code runtime.setProfession(...)}
 * 只能证明机制还在，证明不了面板通不通。</p>
 *
 * <h2>客户端不许绕过</h2>
 * <p>面板把没解锁的按钮灰掉只是<b>显示</b>。这里刻意<b>不管灰不灰</b>，直接把按钮
 * 点下去（就像一个改过客户端的玩家会做的那样），断言服务端照样拒绝。</p>
 */
public final class M20ProfessionUiGameTests implements FabricGameTest {

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
		avatar.setIdleMode();
		return avatar;
	}

	/** 按下面板上的一个按钮，走的是玩家点它时的同一段代码。 */
	private static void press(int buttonId, FakePlayer owner, AvatarEntity avatar) {
		SquireActions.Action action = SquireActions.byId(buttonId);
		if (action == null) {
			throw new IllegalStateException("no panel button with id " + buttonId);
		}
		action.handler().run(owner, avatar);
	}

	/** 把五项新手训练全部做完（不经过 UI，这里测的是训练<b>之后</b>的事）。 */
	private static void finishTraining(SquireRuntime rt, AvatarEntity avatar) {
		for (TrainingMilestone milestone : TrainingMilestone.values()) {
			rt.noteTraining(avatar, milestone);
		}
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.blueprints().activeOf(owner.getUuid())
			.ifPresent(p -> rt.blueprints().remove(p.placementId));
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	// ================================================== Lv.0 训练闸

	/** 训练没做完，两个转职按钮点下去也必须被服务端挡住。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-untrained")
	public void anUntrainedSquireCannotTakeAProfession(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-untrained");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			context.assertFalse(data.trainingComplete(rt.professionConfig()),
				"刚召唤出来训练不该是满的");

			// 客户端会把这两个按钮灰掉；这里假装玩家改了客户端，直接点。
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.GUARD.ordinal(), owner, avatar);
			context.assertFalse(data.hasProfession(),
				"训练没做完却转职成功了——客户端绕过了服务端校验");

			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.ENGINEER.ordinal(), owner, avatar);
			context.assertFalse(data.hasProfession(), "工程师那条路一样要挡住");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 训练项目逐项累加，而且同一项做两次只算一次。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-training")
	public void trainingCountsEachMilestoneExactlyOnce(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-training");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			int before = data.trainingXp();

			rt.noteTraining(avatar, TrainingMilestone.KILL);
			int once = data.trainingXp();
			context.assertTrue(once == before + TrainingMilestone.KILL.xp(),
				"一项训练该加它自己的分，实际 " + before + " → " + once);

			rt.noteTraining(avatar, TrainingMilestone.KILL);
			context.assertTrue(data.trainingXp() == once,
				"同一项做两次不该加两次分");

			finishTraining(rt, avatar);
			context.assertTrue(data.trainingComplete(rt.professionConfig()),
				"五项做完就该够转职了，实际 " + data.trainingXp());

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 转职

	/** 训练做完之后，两个职业都能通过面板按钮选上；重复选被服务端拒绝。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-choose-guard")
	public void aTrainedSquireBecomesAGuardFromThePanel(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-guard");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);

			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.GUARD.ordinal(), owner, avatar);
			context.assertTrue(data.profession() == SquireProfession.GUARD,
				"点了转职按钮就该真的成为守卫");
			context.assertTrue(data.level == 1, "从 Lv1 起步");

			// 重复点：已经有职业了，服务端必须拒绝（否则等级会被清零）。
			data.xp = 50;
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.ENGINEER.ordinal(), owner, avatar);
			context.assertTrue(data.profession() == SquireProfession.GUARD,
				"已经有职业了还能被改掉——玩家几小时的养成会被一个按钮清零");
			context.assertTrue(data.xp == 50, "被拒绝的那次不该动经验");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-choose-engineer")
	public void aTrainedSquireBecomesAnEngineerFromThePanel(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-engineer");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);

			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.ENGINEER.ordinal(), owner, avatar);
			context.assertTrue(data.profession() == SquireProfession.ENGINEER,
				"工程师那条路也要走得通");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 晋升

	/** 晋升按钮：经验不够拒绝、材料不够拒绝、齐了才升，而且材料真的被扣。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-promote")
	public void thePromoteButtonChecksXpAndMaterialsAndSpendsThem(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-promote");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.GUARD.ordinal(), owner, avatar);

			// 经验不够：点了也不许升。
			owner.getInventory().clear();
			owner.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 64));
			press(SquireScreenHandler.BUTTON_PROFESSION_PROMOTE, owner, avatar);
			context.assertTrue(data.level == 1, "经验不够却升级了");
			context.assertTrue(owner.getInventory().count(Items.IRON_INGOT) == 64,
				"被拒绝的晋升不许扣材料");

			// 经验够了但材料没了：还是不许升。
			rt.professions().award(data, rt.professionConfig().xpToNext(1));
			owner.getInventory().clear();
			press(SquireScreenHandler.BUTTON_PROFESSION_PROMOTE, owner, avatar);
			context.assertTrue(data.level == 1, "没材料却升级了");

			// 两样都齐了：升级，而且铁锭真的少了 8 个。
			owner.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 10));
			press(SquireScreenHandler.BUTTON_PROFESSION_PROMOTE, owner, avatar);
			context.assertTrue(data.level == 2, "条件齐了却没升级");
			context.assertTrue(owner.getInventory().count(Items.IRON_INGOT) == 2,
				"8 个铁锭要真的扣掉，剩 "
					+ owner.getInventory().count(Items.IRON_INGOT));
			context.assertTrue(data.can(ProfessionAbility.GUARD_EQUIPMENT_AWARENESS),
				"Lv2 该解锁装备意识");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** 溢出经验在晋升时转进新的经验条，不多不少。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-overflow")
	public void overflowCarriesIntoTheNewBar(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-overflow");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.GUARD.ordinal(), owner, avatar);

			// Lv1 需要 100；给 130，多出来的 30 该存成溢出。
			rt.professions().award(data, rt.professionConfig().xpToNext(1) + 30);
			context.assertTrue(data.overflowXp == 30,
				"溢出该是 30，实际 " + data.overflowXp);

			owner.getInventory().clear();
			owner.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 8));
			press(SquireScreenHandler.BUTTON_PROFESSION_PROMOTE, owner, avatar);

			context.assertTrue(data.level == 2, "该升到 Lv2");
			context.assertTrue(data.xp == 30,
				"溢出该原样转进新条，实际 " + data.xp);
			context.assertTrue(data.overflowXp == 0, "转完之后溢出该清零");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 战斗姿态

	/** 姿态按钮：Lv.8 之前点了没用，之后才生效，而且落盘。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-stance")
	public void theStanceButtonsAreGatedAndPersisted(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-stance");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.GUARD.ordinal(), owner, avatar);

			int aggressive = SquireScreenHandler.BUTTON_STANCE_BASE
				+ dev.squire.server.profession.CombatStance.AGGRESSIVE.ordinal();
			press(aggressive, owner, avatar);
			context.assertTrue(data.combatStance()
					== dev.squire.server.profession.CombatStance.BALANCED,
				"Lv1 就能改姿态——那条 Lv.8 的闸没生效");

			data.level = 8;
			press(aggressive, owner, avatar);
			context.assertTrue(data.combatStance()
					== dev.squire.server.profession.CombatStance.AGGRESSIVE,
				"Lv8 之后该改得动");

			// 落盘：重新召唤一次，姿态必须还在。
			AvatarEntity again = rt.summonFor(owner);
			context.assertTrue(rt.professionOf(again).combatStance()
					== dev.squire.server.profession.CombatStance.AGGRESSIVE,
				"姿态没落盘，重进世界就丢了");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 蓝图权限

	/** 蓝图参数按钮：低等级点下去，服务端必须拒绝，规格一个字段都不许变。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-ui-design-gate")
	public void lowLevelEngineersCannotBypassBlueprintGates(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-design");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.ENGINEER.ordinal(), owner, avatar);

			// Retired player content stays retired even at master level; generic factories remain testable separately.
			var placed = rt.design(owner, "house");
			context.assertFalse(placed.success(), "retired parametric template cannot create a project");
			data.level = 10;
			context.assertFalse(rt.design(owner, "house").success(), "master does not resurrect retired player templates");
			context.assertTrue(rt.projectStart(owner, "keepitlevel_residence").success(), "catalog preview remains available");
			var before = rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
				.blueprintId;

			// 客户端会把这些灰掉；这里直接点，服务端必须挡住。
			for (int id : new int[] {SquireScreenHandler.BUTTON_DESIGN_FLOORS,
					SquireScreenHandler.BUTTON_DESIGN_ROOF,
					SquireScreenHandler.BUTTON_DESIGN_FOUNDATION,
					SquireScreenHandler.BUTTON_DESIGN_WINDOW,
					SquireScreenHandler.BUTTON_DESIGN_ENTRANCE,
					SquireScreenHandler.BUTTON_DESIGN_MIRROR,
					SquireScreenHandler.BUTTON_DESIGN_MODULE_BASE,
					SquireScreenHandler.BUTTON_DESIGN_PRESET_SAVE}) {
				press(id, owner, avatar);
			}
			String after = rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
				.blueprintId;
			context.assertTrue(before.equals(after),
				"Lv1 工程师改动了本该锁着的参数：" + before + " → " + after);

			// 练到 Lv6，多层那一条就该开了。
			data.level = 6;
			press(SquireScreenHandler.BUTTON_DESIGN_FLOORS, owner, avatar);
			String multi = rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
				.blueprintId;
			context.assertTrue(before.equals(multi), "legacy designer buttons cannot reshape fixed catalog blueprints");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Resource catalog buttons and the server-side level gate share the same metadata. */
	@GameTest(templateName = FLOOR, tickLimit = 250, batchId = "squire-ui-blueprint-library")
	public void blueprintLibraryButtonsRespectEngineerLevels(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-blueprint-library");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			finishTraining(rt, avatar);
			rt.setProfession(owner, SquireProfession.ENGINEER.id());
			ProfessionData data = rt.professionOf(avatar);
			var definition = rt.blueprints().registry().catalog().variant("keepitlevel_warehouse").orElseThrow();
			SquireScreenHandler panel = new SquireScreenHandler(77, owner.getInventory(),
				avatar.items().mainInventory(), new AvatarEquipmentInventory(avatar,
					SquireScreenHandler.EQUIPMENT_ORDER), avatar.backpackSlotInventory(), avatar);
			panel.syncState(owner);
			context.assertTrue(panel.state().profession().catalog().stream().allMatch(e -> e.kind().equals("family")), "root groups families, not 750 variants");
			panel.selectBlueprint(owner, definition.family(), panel.state().profession().catalogVersion());
			context.assertTrue(panel.state().profession().catalog().stream().anyMatch(e -> e.id().equals(definition.id())), "family drilldown includes warehouse");
			panel.selectBlueprint(owner, definition.id(), panel.state().profession().catalogVersion());
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isEmpty(),
				"Lv1 bypassed the metadata warehouse lock");

			data.level = definition.requiredEngineerLevel();
			panel.selectBlueprint(owner, "keepitlevel_warehouse", panel.state().profession().catalogVersion());
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isEmpty(), "stale catalog revision must be refused even after promotion");
			panel.syncState(owner);
			panel.selectBlueprint(owner, definition.id(), panel.state().profession().catalogVersion());
			context.assertTrue("keepitlevel_warehouse".equals(rt.blueprints()
				.activeOf(owner.getUuid()).orElseThrow().blueprintId),
				"metadata level did not unlock the warehouse through the UI path");
			var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
			data.level = 4;
			context.assertTrue(rt.blueprintCycleMaterial(owner, 0, 1).success(), "basic theme starts at configured level");
			var basicPalette = placement.materials();
			context.assertFalse(rt.blueprintCycleMaterial(owner, 1, 1).success(), "full palette is a master ability");
			context.assertTrue(placement.materials().equals(basicPalette), "locked slot must not mutate the preview");
			data.level = 10;
			context.assertTrue(rt.blueprintCycleMaterial(owner, 1, 1).success(), "master edits the second region");
			context.assertFalse(rt.design(owner, "house").success(), "old designer cannot overwrite a catalog ghost");
			context.assertTrue(placement.blueprintId.equals("keepitlevel_warehouse"), "retired reshape preserves the active catalog ID");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 面板一致性

	/** 面板推给客户端的那份快照，必须和服务端档案一致（重载之后也一样）。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-ui-snapshot")
	public void thePanelSnapshotMatchesTheServer(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "ui-snapshot");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			finishTraining(rt, avatar);
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.GUARD.ordinal(), owner, avatar);
			rt.professions().award(data, 40);
			owner.getInventory().clear();
			owner.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3));

			var view = dev.squire.server.gui.ProfessionView.of(data,
				rt.professionConfig(),
				itemId -> dev.squire.server.runtime.SquireProfessionService
					.countMaterial(owner, avatar, itemId));

			context.assertTrue(view.profession() == SquireProfession.GUARD,
				"面板要显示他真的是守卫");
			context.assertTrue(view.level() == data.level, "面板等级要和档案一致");
			context.assertTrue(view.xp() == data.xp,
				"面板经验和档案对不上：" + view.xp() + " vs " + data.xp);
			context.assertTrue(view.trainingComplete(), "训练做完了该显示成做完");
			context.assertTrue(view.materials().size() == 1, "Lv1→2 是一种材料");
			context.assertTrue(view.materials().get(0).have() == 3,
				"面板要显示玩家真的有几个，实际 " + view.materials().get(0).have());
			context.assertFalse(view.canPromote(),
				"经验和材料都不够，晋升按钮不该亮");

			// 材料交给侍从保管之后，面板必须还认得出来。
			owner.getInventory().clear();
			avatar.items().insert(new ItemStack(Items.IRON_INGOT, 8));
			var afterHandover = dev.squire.server.gui.ProfessionView.of(data,
				rt.professionConfig(),
				itemId -> dev.squire.server.runtime.SquireProfessionService
					.countMaterial(owner, avatar, itemId));
			context.assertTrue(afterHandover.materials().get(0).have() == 8,
				"把材料交给他之后面板说没有了——玩家会以为东西丢了");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 管理员调试入口

	/** OP can create exact profession snapshots; an ordinary player cannot see the branch. */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-admin-profession")
	public void adminCanSetExactProfessionLevelsWithoutProgressionCosts(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "admin-profession-level");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			var commands = context.getWorld().getServer().getCommandManager();
			String setGuard = "squire admin profession set guard 8";

			int denied = commands.executeWithPrefix(
				owner.getCommandSource().withLevel(0), setGuard);
			context.assertTrue(denied == 0,
				"a non-operator must not reach the debug profession command");
			context.assertFalse(rt.professionOf(avatar).hasProfession(),
				"the denied command leaves the profile untouched");

			int guardResult = commands.executeWithPrefix(
				owner.getCommandSource().withLevel(2), setGuard);
			ProfessionData data = rt.professionOf(avatar);
			context.assertTrue(guardResult == 1, "the OP debug command succeeds");
			context.assertTrue(data.profession() == SquireProfession.GUARD
				&& data.level == 8, "the command creates the exact Guard level");
			context.assertTrue(data.xp == 0 && data.overflowXp == 0,
				"debug snapshots start with deterministic empty XP bars");
			var maxHealth = avatar.getAttributeInstance(
				net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
			context.assertTrue(maxHealth != null && Math.abs(maxHealth.getBaseValue()
				- rt.professionConfig().guardMaxHealth(8)) < 0.01,
				"level-derived Guard attributes refresh immediately");

			int engineerResult = commands.executeWithPrefix(
				owner.getCommandSource().withLevel(2),
				"squire admin profession set engineer 6");
			context.assertTrue(engineerResult == 1
				&& data.profession() == SquireProfession.ENGINEER && data.level == 6,
				"the same command can switch to an exact Engineer level");
			context.assertTrue(Math.abs(maxHealth.getBaseValue() - 20.0) < 0.01,
				"switching away from Guard removes its derived health");
			context.assertTrue(rt.agentStore().recordOfAgent(avatar.agentId())
				.orElseThrow().profile.profession.level == 6,
				"the debug level is persisted in the authoritative profile");

			cleanUp(rt, owner);
			context.complete();
		});
	}
}
