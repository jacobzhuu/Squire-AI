package dev.squire.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.gui.ProfessionView;
import dev.squire.server.gui.SquireActions;
import dev.squire.server.gui.SquireScreenHandler;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profession.TrainingMilestone;
import dev.squire.server.runtime.SquireProfessionService;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * <b>一个不知道任何命令的新玩家，能不能只靠面板走完整条成长线。</b>
 *
 * <h2>这一条测试在守什么</h2>
 * <p>不是「机制对不对」——那些各有各的测试。这里守的是<b>够得着</b>：
 * 召唤 → 训练 → 转职 → 攒经验 → 备料 → 晋升 → 用职业设置，
 * 每一步都必须存在一条<b>不打字</b>的路径。</p>
 *
 * <p>所以每一步要么按面板按钮（{@link SquireActions} 里那张表，和玩家点的是同一段
 * 代码），要么走玩家在世界里本来就会做的事（打怪、下命令）。<b>一次
 * {@code /squire} 都不许出现</b>——这一条如果被后来的改动破坏，就说明这个系统又
 * 退回成一个只有作者会用的东西了。</p>
 */
public final class M21NewPlayerWalkthroughGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	/** 按面板上的一个按钮。 */
	private static void press(int buttonId, FakePlayer owner, AvatarEntity avatar) {
		SquireActions.Action action = SquireActions.byId(buttonId);
		if (action == null) {
			throw new IllegalStateException("面板上没有 id=" + buttonId + " 的按钮");
		}
		action.handler().run(owner, avatar);
	}

	/** 面板此刻推给客户端的那份职业快照。 */
	private static ProfessionView panel(SquireRuntime rt, FakePlayer owner,
			AvatarEntity avatar) {
		return ProfessionView.of(rt.professionOf(avatar), rt.professionConfig(),
			itemId -> SquireProfessionService.countMaterial(owner, avatar, itemId))
			.withBuildingCatalog(rt.blueprints().registry().catalog(), "", rt.blueprints().registry().revision());
	}

	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-walkthrough")
	public void aNewPlayerReachesEngineerLevelTwoWithoutTypingACommand(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = FakePlayer.get(world, new GameProfile(
			UUID.nameUUIDFromBytes("walkthrough".getBytes()), "walkthrough"));

		context.runAtTick(5, () -> {
			// ── 1. 召唤 ────────────────────────────────────────────────
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			Vec3d feet = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(2, 2, 2)));
			avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0f, 0f);
			ProfessionData data = rt.professionOf(avatar);

			context.assertFalse(panel(rt, owner, avatar).hasProfession(),
				"刚召唤出来应该是 Lv.0 无职业");
			context.assertFalse(panel(rt, owner, avatar).trainingComplete(),
				"训练也该是空的");

			// ── 2. 训练：全都是玩家本来就会做的事 ──────────────────────
			// 打开面板（服务端在同步状态时自己记）
			rt.noteTraining(avatar, TrainingMilestone.OPEN_PANEL);
			// 给他一件装备
			avatar.items().insert(new ItemStack(Items.IRON_SWORD));
			avatar.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND,
				new ItemStack(Items.IRON_SWORD));
			rt.noteTraining(avatar, TrainingMilestone.EQUIP);
			// 下一次站位命令——面板右列那几个常驻按钮之一
			press(SquireScreenHandler.BUTTON_STAY, owner, avatar);
			// 一起打倒一只敌对生物
			var zombie = EntityType.ZOMBIE.create(world);
			context.assertTrue(zombie != null, "test zombie");
			Vec3d at = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(3, 2, 3)));
			zombie.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
			world.spawnEntity(zombie);
			SquireRuntime.onAvatarKill(avatar, zombie);
			zombie.discard();
			// Construction must not be a prerequisite for choosing Engineer.
			context.assertFalse(data.hasTrained(TrainingMilestone.BUILD), "No construction before choosing a profession");
			ProfessionData restored = new ProfessionData();
			restored.readNbt(data.writeNbt());
			var legacyConfig = dev.squire.server.profession.ProfessionConfig.fromJson(
				com.google.gson.JsonParser.parseString("{\"general\":{\"trainingXpRequired\":100}}")
					.getAsJsonObject());
			context.assertTrue(restored.trainingComplete(legacyConfig),
				"Saved four-step progress must unlock professions with legacy configuration");

			ProfessionView afterTraining = panel(rt, owner, avatar);
			context.assertTrue(afterTraining.trainingComplete(),
				"四项基础训练完成，应该可以转职：" + afterTraining.trainingXp() + " / "
					+ afterTraining.trainingRequired());

			// ── 3. 转职：面板按钮 ──────────────────────────────────────
			press(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
				+ SquireProfession.ENGINEER.ordinal(), owner, avatar);
			context.assertTrue(data.profession() == SquireProfession.ENGINEER,
				"点了「转职为工程师」就该真的转过去");

			// ── 4. 攒经验：盖房子。这里直接给，施工那条路 M18 已经守着了。────
			rt.professions().award(data, rt.professionConfig().xpToNext(1));
			ProfessionView ready = panel(rt, owner, avatar);
			context.assertTrue(ready.promotionReady(), "经验满了面板要说可以晋升");
			context.assertFalse(ready.canPromote(),
				"材料还没准备，晋升按钮这时候不该亮");

			// ── 5. 备料：面板要如实说缺什么、缺几个 ────────────────────
			context.assertTrue(ready.materials().size() == 1, "Lv1→2 是一种材料");
			var need = ready.materials().get(0);
			context.assertTrue("minecraft:iron_ingot".equals(need.itemId()),
				"面板要点名要什么，实际 " + need.itemId());
			context.assertTrue(need.have() == 0 && need.need() == 8,
				"缺料数字要准：有 " + need.have() + " / 要 " + need.need());
			owner.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 8));
			context.assertTrue(panel(rt, owner, avatar).canPromote(),
				"料齐了晋升按钮就该亮");

			// ── 6. 晋升：面板按钮 ──────────────────────────────────────
			press(SquireScreenHandler.BUTTON_PROFESSION_PROMOTE, owner, avatar);
			context.assertTrue(data.level == 2, "点了晋升就该到 Lv2");
			context.assertTrue(owner.getInventory().count(Items.IRON_INGOT) == 0,
				"材料该被扣掉");

			// ── 7. 用职业设置：Lv2 解锁的模板库 ────────────────────────
			ProfessionView atTwo = panel(rt, owner, avatar);
			context.assertTrue(atTwo.maxFootprint() > afterTraining.maxFootprint(),
				"Lv2 的尺寸上限该比 Lv1 大：" + afterTraining.maxFootprint()
					+ " → " + atTwo.maxFootprint());
			context.assertTrue(atTwo.blueprintLibrary().size() == 72, "family catalog is accessible from the panel");

			// 蓝图参数页：换模板 → 换尺寸，全是按钮。
			var screen = new SquireScreenHandler(82, owner.getInventory(), avatar.items().mainInventory(),
				new dev.squire.server.gui.AvatarEquipmentInventory(avatar, SquireScreenHandler.EQUIPMENT_ORDER), avatar.backpackSlotInventory(), avatar);
			screen.syncState(owner);
			screen.selectBlueprint(owner, "squire:keepitlevel/residence", screen.state().profession().catalogVersion());
			screen.selectBlueprint(owner, "keepitlevel_residence", screen.state().profession().catalogVersion());
			context.assertTrue(rt.blueprints().activeOf(owner.getUuid()).isPresent(),
				"family then variant buttons create a zero-write preview");
			String before = rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
				.blueprintId;
			press(SquireScreenHandler.BUTTON_DESIGN_SIZE_UP, owner, avatar);
			String after = rt.blueprints().activeOf(owner.getUuid()).orElseThrow()
				.blueprintId;
			context.assertTrue(before.equals(after), "retired size controls cannot reshape fixed imported content");

			// 而 Lv2 还够不着的那些，点了必须没反应（服务端拒绝）。
			String guarded = after;
			press(SquireScreenHandler.BUTTON_DESIGN_ROOF, owner, avatar);
			press(SquireScreenHandler.BUTTON_DESIGN_MIRROR, owner, avatar);
			context.assertTrue(guarded.equals(rt.blueprints()
					.activeOf(owner.getUuid()).orElseThrow().blueprintId),
				"Lv2 改动了本该锁着的结构变体/镜像");

			rt.blueprints().activeOf(owner.getUuid())
				.ifPresent(p -> rt.blueprints().remove(p.placementId));
			rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}
}
