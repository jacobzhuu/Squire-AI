package dev.squire.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.BlueprintStep;
import dev.squire.server.blueprint.ProjectSpec;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.BlueprintBuildExecutor;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 职业与成长系统的现场验收（设计文档 v1）。
 *
 * <p>纯规则已经被 {@code ProfessionProgressionTest} 逐条钉住了；这里要回答的是另一个
 * 问题：<b>那些规则真的接到世界上了吗</b>。所以每一条断言读的都是真实档案、真实背包、
 * 真实方块，而不是一个内存里的计算结果。</p>
 *
 * <p>三条最容易悄悄坏掉、也最伤玩家的事，各有一条测试守着：</p>
 * <ul>
 *   <li>职业系统上线<b>不该</b>让没有职业的随从少会一件事；</li>
 *   <li>经验满了<b>不会</b>自动升级，材料不够时说得出缺什么；</li>
 *   <li>晋升材料是<b>真的</b>从玩家背包里扣掉的。</li>
 * </ul>
 */
public final class M18ProfessionGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	/** 3×3×3 橡木板小壳，正中掏空一格：26 格要放。 */
	private static final String SHELL = "gametest_profession_shell";

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
		avatar.setIdleMode();
		return avatar;
	}

	/**
	 * 把五项新手训练做完。
	 *
	 * <p>转职现在有 Lv.0 训练门槛（玩家要求的那条），而这一组测试关心的是
	 * <b>转职之后</b>的事。训练本身由 {@code M20ProfessionUiGameTests} 专门守着。</p>
	 */
	private static void finishTraining(SquireRuntime rt, AvatarEntity avatar) {
		for (var milestone
				: dev.squire.server.profession.TrainingMilestone.values()) {
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

	// ================================================== 选职业

	/**
	 * 选职业不会让他忘掉任何已经会的事，而且改行必须先显式卸任。
	 *
	 * <p>后半条是「不让玩家手滑清零几小时养成」——设计文档没写，但
	 * 「任何输入都要有出路」要求这条拒绝里必须带着那条出路。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-profession-pick")
	public void pickingAProfessionAddsWithoutTakingAnythingAway(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "prof-pick-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			ProfessionData data = rt.professionOf(avatar);
			context.assertTrue(data != null, "召唤出来的随从有一份职业进度");
			context.assertFalse(data.hasProfession(), "默认没有职业");
			context.assertTrue(rt.can(avatar,
					dev.squire.server.profile.Ability.BUILD_BLUEPRINT),
				"没有职业的随从照样会做他昨天会做的事");

			// 转职前必须先把新手训练做完（Lv.0 那道门槛）。
			context.assertFalse(rt.setProfession(owner,
					SquireProfession.GUARD.id()).success(),
				"训练没做完就不该转得了职");
			finishTraining(rt, avatar);

			var chosen = rt.setProfession(owner, SquireProfession.GUARD.id());
			context.assertTrue(chosen.success(), chosen.message());
			context.assertTrue(data.profession() == SquireProfession.GUARD, "职业真的定下来了");
			context.assertTrue(data.level == 1, "从 Lv1 起步");
			context.assertTrue(rt.can(avatar,
					dev.squire.server.profile.Ability.BUILD_BLUEPRINT),
				"选了守卫也不该让他忘记怎么按蓝图施工");
			context.assertTrue(data.can(ProfessionAbility.GUARD_BASIC_MELEE), "Lv1 就会基础近战");
			context.assertFalse(data.can(ProfessionAbility.GUARD_BOW_PROFICIENCY),
				"弓是 Lv4，现在不该会");

			// 改行必须先卸任，而且拒绝里要写清楚怎么办。
			var switched = rt.setProfession(owner, SquireProfession.ENGINEER.id());
			context.assertFalse(switched.success(), "不许一句话就把养成清零");
			context.assertTrue(switched.message().contains("职业页"),
				"拒绝必须带着出路，实际是：" + switched.message());
			context.assertTrue(rt.forgetProfession(owner).success(), "卸任这条路要走得通");
			context.assertTrue(rt.setProfession(owner,
				SquireProfession.ENGINEER.id()).success(), "卸任之后就能改行了");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 晋升

	/** 经验满了不会自动升级；材料不够说得出缺什么；材料够了真的从背包里扣。 */
	@GameTest(templateName = FLOOR, tickLimit = 200,
		batchId = "squire-profession-promote")
	public void promotionNeedsXpMaterialsAndAnExplicitClick(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "prof-promote-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			finishTraining(rt, avatar);
			rt.setProfession(owner, SquireProfession.GUARD.id());
			ProfessionData data = rt.professionOf(avatar);
			ProfessionConfig config = rt.professionConfig();

			// 经验不够时的拒绝要说得出还差多少。
			var early = rt.promoteProfession(owner);
			context.assertFalse(early.success(), "经验不够不许晋升");
			context.assertTrue(early.message().contains("还差"),
				"拒绝要说清还差多少，实际是：" + early.message());

			// 把这一级的经验条打满——它<b>不会</b>顺手升级。
			rt.professions().award(data, config.xpToNext(1));
			context.assertTrue(data.level == 1, "禁止自动晋升");
			context.assertTrue(data.xpFull(config), "经验条确实满了");

			// 材料不够：拒绝里要写清楚缺哪几件。
			owner.getInventory().clear();
			var noMaterials = rt.promoteProfession(owner);
			context.assertFalse(noMaterials.success(), "没材料不许晋升");
			context.assertTrue(noMaterials.message().contains("材料还差"),
				"缺料要点名，实际是：" + noMaterials.message());
			context.assertTrue(data.level == 1, "被拒绝时一级都不许涨");

			// 材料齐了：晋升成功，而且铁锭是<b>真的</b>被扣掉的。
			owner.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 10));
			var promoted = rt.promoteProfession(owner);
			context.assertTrue(promoted.success(), promoted.message());
			context.assertTrue(data.level == 2, "现在才是 Lv2");
			context.assertTrue(data.can(ProfessionAbility.GUARD_EQUIPMENT_AWARENESS),
				"Lv2 解锁装备意识");
			context.assertTrue(owner.getInventory().count(Items.IRON_INGOT) == 2,
				"8 个铁锭要真的扣掉，剩下 "
					+ owner.getInventory().count(Items.IRON_INGOT));

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/** Lv.10 守卫的职业基础生命上限是 30，皮实的 +2 也必须跨召唤保持。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-profession-health")
	public void aMaxLevelGuardKeepsItsHealthAcrossResummons(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "prof-health-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			finishTraining(rt, avatar);
			rt.setProfession(owner, SquireProfession.GUARD.id());
			ProfessionData data = rt.professionOf(avatar);
			data.level = 10;
			rt.professions().award(data, 1); // 落一次盘

			// 再召唤一次：等级效果必须在物化时重新写上去，否则 Lv10 会变回 20 点。
			AvatarEntity again = rt.summonFor(owner);
			float expectedMaxHealth = 30.0f
				+ (rt.profileOf(again).hasTrait(Trait.STURDY)
					? (float) Trait.STURDY_MAX_HEALTH_BONUS : 0.0f);
			context.assertTrue(again.getMaxHealth() == expectedMaxHealth,
				"Lv10 守卫的职业生命与性格修正都应保持，预期 "
					+ expectedMaxHealth + "，实际 " + again.getMaxHealth());
			context.assertTrue(again.getHealth() <= again.getMaxHealth(),
				"血量不该超过上限");

			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 名牌

	/**
	 * 头顶要看得见职业和等级，而且<b>不会越叠越长</b>。
	 *
	 * <p>名牌是 {@code 本名 + [职业 Lv] + [模式] + [进度] + 活动} 拼出来的，而香草会把
	 * 拼好的那一串当成 CustomName 存进 NBT。这里连着改几次状态，就是在验
	 * 「重新拼」真的是重新拼，而不是在上一次的成品后面接着加。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200,
		batchId = "squire-profession-nameplate")
	public void theNameplateShowsTheProfessionAndNeverStacks(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "prof-name-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			String plain = nameplate(avatar);
			context.assertFalse(plain.contains("守卫"),
				"还没选职业就不该有职业标签，实际是：" + plain);

			finishTraining(rt, avatar);
			rt.setProfession(owner, SquireProfession.GUARD.id());
			ProfessionData data = rt.professionOf(avatar);
			context.assertTrue(nameplate(avatar).contains("守卫 Lv1"),
				"选完职业头顶就该看得见，实际是：" + nameplate(avatar));

			// 升到 Lv6 并把经验条打满：名牌要跟着变，而且挂上「可以晋升」的星。
			data.level = 6;
			rt.professions().award(data, rt.professionConfig().xpToNext(6));
			avatar.refreshNameplate();
			String ready = nameplate(avatar);
			context.assertTrue(ready.contains("守卫 Lv6"),
				"等级要跟着变，实际是：" + ready);
			context.assertTrue(ready.contains("★"),
				"经验满了要在头顶提示一下，实际是：" + ready);

			// 连着换几次模式：标签只能有一份，绝不叠。
			avatar.setFollowMode(owner.getUuid());
			avatar.setIdleMode();
			avatar.setFollowMode(owner.getUuid());
			String after = nameplate(avatar);
			context.assertTrue(countOf(after, "守卫 Lv6") == 1,
				"职业标签叠了：" + after);
			context.assertTrue(countOf(after, "[跟随]") == 1,
				"模式标签叠了：" + after);

			// 卸任之后标签要消失，本名一个字都不能少。
			rt.forgetProfession(owner);
			String bare = nameplate(avatar);
			context.assertFalse(bare.contains("守卫"),
				"卸任之后不该还挂着职业，实际是：" + bare);
			context.assertTrue(bare.contains(rt.displayNameOf(avatar)),
				"本名不该被剥掉，实际是：" + bare);

			cleanUp(rt, owner);
			context.complete();
		});
	}

	private static String nameplate(AvatarEntity avatar) {
		return avatar.getCustomName() == null ? "" : avatar.getCustomName().getString();
	}

	private static int countOf(String text, String needle) {
		int found = 0;
		int at = text.indexOf(needle);
		while (at >= 0) {
			found++;
			at = text.indexOf(needle, at + needle.length());
		}
		return found;
	}

	// ================================================== 守卫经验

	/** 击杀真的进账，而且同一种怪刷多了会被衰减压下去。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-profession-kills")
	public void killsPayOutAndRepeatedOnesStopPaying(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "prof-kill-owner");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			finishTraining(rt, avatar);
			rt.setProfession(owner, SquireProfession.GUARD.id());
			ProfessionData data = rt.professionOf(avatar);

			ZombieEntity victim = EntityType.ZOMBIE.create(world);
			context.assertTrue(victim != null, "test zombie");
			Vec3d at = Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2)));
			victim.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
			world.spawnEntity(victim);

			int before = data.xp;
			SquireRuntime.onAvatarKill(avatar, victim);
			context.assertTrue(data.xp == before + 2,
				"一只僵尸是第 1 档 2 点，实际涨了 " + (data.xp - before));

			// 同一种怪连杀：窗口内到第 11 只开始打折，第 51 只之后基本白干。
			for (int i = 0; i < 60; i++) {
				SquireRuntime.onAvatarKill(avatar, victim);
			}
			int after = data.xp;
			SquireRuntime.onAvatarKill(avatar, victim);
			context.assertTrue(data.xp == after,
				"刷到第 60 只之后一只僵尸已经一分不值，却涨了 " + (data.xp - after));

			victim.discard();
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 工程师经验

	/** 施工验收通过之后工程经验一次性到账；参数化蓝图的规格跨重启还原得回来。 */
	@GameTest(templateName = FLOOR, tickLimit = 1200, batchId = "squire-profession-build")
	public void aFinishedBuildPaysTheEngineerOnce(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "prof-build-owner");
		registerShell(rt);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(1, 2, 1));
			finishTraining(rt, avatar);
			rt.setProfession(owner, SquireProfession.ENGINEER.id());
			avatar.items().insert(new ItemStack(Items.OAK_PLANKS, 64));

			BlueprintPlacement placement = new BlueprintPlacement(UUID.randomUUID(),
				owner.getUuid(), avatar.agentId(), SHELL,
				world.getRegistryKey().getValue().toString(),
				context.getAbsolutePos(new BlockPos(4, 2, 4)), Direction.NORTH,
				rt.tickNow());
			rt.blueprints().put(placement);
			rt.scheduler().submit(new Task(avatar.agentId(), owner.getUuid(),
				BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
				"profession build", null,
				BlueprintBuildExecutor.blueprintBuilt(rt.runtimeServicesForTest(),
					placement.placementId),
				1000L, RetryPolicy.DEFAULT, true, "c2",
				Map.of(BlueprintBuildExecutor.PARAM_PLACEMENT_ID,
					placement.placementId.toString())), world.getTime());
		});

		context.runAtTick(950, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			ProfessionData data = rt.professionOf(avatar);
			context.assertTrue(data.xp > 0,
				"盖完一栋验收通过的建筑必须有工程经验，实际 " + data.xp);
			context.assertFalse(data.recentProjects.isEmpty(),
				"这次工程要记进防刷台账，否则连盖十遍还是全额");
			cleanUp(rt, owner);
			context.complete();
		});
	}

	// ================================================== 持久化

	/**
	 * 职业进度跨重启存活，坏字段只毁掉它自己。
	 *
	 * <p>纯 NBT 往返，但 {@code NbtCompound} 那一套要注册表引导，headless 单测起不来，
	 * 所以借这里的环境跑。不碰世界。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 100, batchId = "squire-profession-nbt")
	public void professionProgressSurvivesARestart(TestContext context) {
		SquireProfile original = new SquireProfile();
		original.profession.setProfession(SquireProfession.ENGINEER);
		original.profession.level = 7;
		original.profession.xp = 640;
		original.profession.overflowXp = 30;
		original.profession.stance =
			dev.squire.server.profession.CombatStance.AGGRESSIVE.id();
		original.profession.noteBossKill("minecraft:wither");
		original.profession.noteProject("house|medium|f2|m0", 1234L);
		original.blueprintPresets.put("生存屋",
			ProjectSpec.Template.HOUSE.defaults().blueprintId());

		SquireProfile back = new SquireProfile();
		back.readNbt(original.writeNbt(new NbtCompound()));

		context.assertTrue(back.profession.profession() == SquireProfession.ENGINEER, "职业本身要存活");
		context.assertTrue(back.profession.level == 7, "等级要存活");
		context.assertTrue(back.profession.xp == 640, "经验条要存活");
		context.assertTrue(back.profession.overflowXp == 30, "溢出也要存活");
		context.assertTrue(back.profession.bossKillsOf("minecraft:wither") == 1,
			"Boss 台账不落盘的话，重启就是一次重置衰减的手段");
		context.assertTrue(back.profession.recentProjectCount("house|medium|f2|m0",
			1234L, 72000L) == 1, "同款工程台账要存活");
		context.assertTrue(back.blueprintPresets.containsKey("生存屋"),
			"存过的蓝图预设要存活");

		// 旧存档（根本没有 profession 这一段）读进来是「没有职业」，而不是崩掉。
		SquireProfile legacy = new SquireProfile();
		legacy.roleId = dev.squire.server.profile.Role.GUARDIAN.id();
		SquireProfile migrated = new SquireProfile();
		migrated.readNbt(legacy.writeNbt(new NbtCompound()));
		context.assertFalse(migrated.profession.hasProfession(),
			"旧存档读进来就是没有职业");
		context.assertTrue(migrated.role() == dev.squire.server.profile.Role.GUARDIAN,
			"旧存档的 Role 一个字段都不该丢");

		context.complete();
	}
}
