package dev.squire.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.squire.server.profile.Ability;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Track;
import dev.squire.server.profile.Trait;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

/**
 * 面板状态包。
 *
 * <p>以前它是五个裸 varint，两端各自按顺序读写；每加一个字段就要在两个文件里对齐
 * 一次，而对不齐时<b>不会报错</b>，只会把后面的字节按错位解释成别的数字。第 2 期
 * 要加职业、等级、槽位、熟练度、特质和自主档，第 3/4 期还要再加——所以现在读写只有
 * 一处，并且带版本号。</p>
 *
 * <p>这组测试守的就是那条底线：版本对不上必须整包丢弃，而不是硬读。</p>
 */
class PanelStateTest {

	private static PacketByteBuf buffer() {
		return new PacketByteBuf(Unpooled.buffer());
	}

	@Test
	void everyFieldSurvivesTheWire() {
		PanelState original = new PanelState(2, 0b1010101010, 13, 20,
			Role.BUILDER.id(), 3, 3, 640L, 760L,
			List.of(Ability.BUILD_FAST.id(), Ability.BUILD_SUBSTITUTE.id()),
			List.of(Trait.DILIGENT.id(), Trait.SWIFT.id()),
			AutonomyLevel.PROACTIVE.id(), "矿井前哨站", "RUNNING",
			List.of("FULFIL_MATERIALS:DONE", "HAUL:BLOCKED"),
			"还差 32 个 oak_planks", 24, List.of("回家吃饭", "备战"), 2,
			"MATERIALS_MISSING", "橡木房", "GHOST",
			"generated_house/oak/7x7x4/gable", List.of("32 × oak_planks"),
			List.of("wall|墙体|minecraft:oak|橡木|minecraft:oak_planks"),
			List.of("开工前会清理 2 个冲突方块"), true,
			List.of("guard.stop\u001f\u001f", "inventory.give\u001f64\u001f",
				"\u001f\u001f把装备穿上"), "豆包", 54, 1, 12,
			new ProfessionView(
				dev.squire.server.profession.SquireProfession.ENGINEER.id(), 6, 870,
				1100, 30, true,
				dev.squire.server.profession.CombatStance.AGGRESSIVE.id(),
				100, 100, List.of("open_panel", "equip"),
				List.of("minecraft:diamond|4|1"),
				List.of("engineer.basic_blueprint"), List.of("engineer.intercept"),
				26, 3, 2, 64, true, true, 1, 21, List.of("house", "shed"),
				List.of("shed|1", "house|3", "outpost|9")));
		PacketByteBuf buf = buffer();
		original.write(buf);

		PanelState back = PanelState.read(buf);
		assertEquals(original, back);
		assertEquals(Role.BUILDER, back.role());
		assertEquals(AutonomyLevel.PROACTIVE, back.autonomyLevel());
		assertEquals(List.of(Ability.BUILD_FAST, Ability.BUILD_SUBSTITUTE),
			back.equippedAbilities());
		assertTrue(back.hasProject(), "the project half round-trips too");
		assertEquals(1, back.stagesDone(), "one stage is done");
		assertEquals("还差 32 个 oak_planks", back.blockedReason(),
			"阻塞原因是玩家唯一能据此行动的东西，不能在路上丢掉");
		// 快捷指令绑的是哪个动作必须一起过来：面板要靠它画出「他会做什么」那行字，
		// 也要靠它判出这一条现在是不是锁着的。只有名字的话两件事都做不到。
		assertEquals("guard.stop", back.shortcutEntryId(0));
		assertEquals("inventory.give", back.shortcutEntryId(1));
		assertEquals("64", back.shortcutArg(1));
		// 老存档里那种自然语言快捷照旧读得出来，并且认得出它是老的。
		assertEquals("把装备穿上", back.shortcutPhrase(2));
		assertTrue(back.shortcutIsLegacy(2));
		assertFalse(back.shortcutIsLegacy(0), "绑定式的不是老快捷");
		assertEquals("", back.shortcutEntryId(9), "越界不许抛，给空串");
		assertEquals("", back.shortcutPhrase(9), "越界不许抛，给空串");
		assertEquals("豆包", back.agentName(),
			"面板标题要跟着改名走，不能停在开界面那一刻的名字");
		assertEquals(12, back.amethystShards(),
			"洗练按钮必须使用服务端统计的两边背包总数");
		// 职业页整块：面板要显示「工程师 Lv6 / 870 of 1100 / 可以晋升」，加上训练、
		// 材料、补给、蓝图上限。一个都不能在路上丢掉，否则玩家看到的是错的数字。
		ProfessionView job = back.profession();
		assertEquals(dev.squire.server.profession.SquireProfession.ENGINEER,
			job.profession());
		assertEquals(6, job.level());
		assertEquals(870, job.xp());
		assertEquals(1100, job.xpNeeded());
		assertTrue(job.promotionReady());
		assertEquals(dev.squire.server.profession.CombatStance.AGGRESSIVE, job.stance());
		assertEquals(100, job.trainingXp());
		assertTrue(job.trainingComplete());
		assertEquals(21, job.maxFootprint());
		assertEquals(List.of("house", "shed"), job.templates());
		// 锁着的模板也必须过来，否则面板只能画出已经走过的那一段成长线。
		assertEquals(3, job.templateLibrary().size());
		assertEquals("outpost", job.templateLibrary().get(2).templateId());
		assertEquals(9, job.templateLibrary().get(2).minLevel());
		assertFalse(job.templateLibrary().get(2).unlockedAt(job.level()));
		assertTrue(job.templateLibrary().get(1).unlockedAt(job.level()));
		// 材料是「id|要几个|有几个」，客户端拆开之后要能算出「还差」。
		assertEquals(1, job.materials().size());
		assertEquals("minecraft:diamond", job.materials().get(0).itemId());
		assertEquals(4, job.materials().get(0).need());
		assertEquals(1, job.materials().get(0).have());
		assertFalse(job.materials().get(0).enough());
		assertFalse(job.canPromote(), "经验满了但材料不够，晋升按钮不该亮");
	}

	@Test
	void aProfessionlessSnapshotUsesTheSingleVisibleLevelZero() {
		PanelState state = PanelState.of(1, 0, 20, 20, new SquireProfile());
		ProfessionView job = state.profession();
		assertFalse(job.hasProfession());
		assertEquals(0, job.level());
		assertEquals(job.level(), state.level(),
			"the legacy role field must not expose a second level");
		assertEquals(0, job.xpNeeded());
		assertFalse(job.promotionReady());
		assertFalse(job.trainingComplete(), "刚召唤出来训练一项都没做");
		assertFalse(job.canPromote());
	}

	@Test
	void theProfessionBarComesFromTheServer() {
		SquireProfile profile = new SquireProfile();
		profile.profession.setProfession(
			dev.squire.server.profession.SquireProfession.GUARD);
		profile.profession.level = 4;
		profile.profession.xp = 275;
		ProfessionView job = PanelState.of(0, 0, 20, 20, profile).profession();
		assertEquals(job.level(), PanelState.of(0, 0, 20, 20, profile).level(),
			"profile and profession pages must use the same authoritative level");
		assertEquals(275, job.xp());
		assertEquals(550, job.xpNeeded(), "Lv4 → Lv5 是 550");
		assertEquals(0.5, job.bar(), 1e-9);
		assertFalse(job.promotionReady());
	}

	@Test
	void theTrainingChecklistRidesTheWire() {
		SquireProfile profile = new SquireProfile();
		profile.profession.completeTraining(
			dev.squire.server.profession.TrainingMilestone.OPEN_PANEL);
		profile.profession.completeTraining(
			dev.squire.server.profession.TrainingMilestone.KILL);
		ProfessionView job = PanelState.of(0, 0, 20, 20, profile).profession();

		assertEquals(40, job.trainingXp());
		assertTrue(job.hasTrained(
			dev.squire.server.profession.TrainingMilestone.OPEN_PANEL));
		assertFalse(job.hasTrained(
			dev.squire.server.profession.TrainingMilestone.BUILD));
		assertFalse(job.trainingComplete(), "40 / 100 还不能转职");
	}

	@Test
	void aVersionMismatchIsDiscardedRatherThanMisread() {
		PacketByteBuf buf = buffer();
		buf.writeVarInt(PanelState.VERSION + 1);
		buf.writeVarInt(999); // 后面全是按旧/新格式写的字节
		buf.writeVarInt(999);
		assertSame(PanelState.EMPTY, PanelState.read(buf),
			"读不懂就整包丢弃：显示成「未知」远好过显示成一个错的等级");
	}

	@Test
	void aRolelessSnapshotIsHonestAboutHavingNoRole() {
		PanelState state = PanelState.of(1, 0, 20, 20, new SquireProfile());
		assertFalse(state.hasRole());
		assertEquals(0, state.level());
		assertEquals(Role.BASE_SLOTS, state.slots());
		assertTrue(state.equipped().isEmpty());
	}

	@Test
	void aMissingProfileDoesNotBlowUpThePanel() {
		PanelState state = PanelState.of(0, 0, 7, 20, null);
		assertFalse(state.hasRole());
		assertEquals(7, state.health());
		assertEquals(AutonomyLevel.STANDARD, state.autonomyLevel());
	}

	@Test
	void aSnapshotCarriesTheProgressTowardsTheNextMilestone() {
		SquireProfile profile = new SquireProfile();
		profile.roleId = Role.EXCAVATOR.id();
		profile.proficiency.put(Track.EXCAVATE.id(), 200L);
		PanelState state = PanelState.of(0, 0, 20, 20, profile);
		assertEquals(200L, state.trackTotal());
		assertEquals(300L, state.trackRemaining(), "下一档是 500");
		assertEquals(0, state.level(),
			"legacy track progress must not become a second player-visible level");
	}

	@Test
	void listsAreBounded() {
		List<String> tooMany = new java.util.ArrayList<>();
		for (int i = 0; i < 30; i++) {
			tooMany.add("ability." + i);
		}
		PacketByteBuf buf = buffer();
		new PanelState(0, 0, 20, 20, "", 1, 2, 0L, 0L, tooMany, tooMany,
			AutonomyLevel.STANDARD.id(), "", "", tooMany, "", 12, tooMany, 3,
			"", "", "", "", tooMany, tooMany, tooMany, false, tooMany, "", 0, 0, 0,
			ProfessionView.EMPTY)
			.write(buf);
		PanelState back = PanelState.read(buf);
		assertEquals(16, back.equipped().size(),
			"写入端就截断，读取端也截断——网络输入永远有上界");
		assertEquals(16, back.traits().size());
		assertEquals(16, back.stages().size());
	}

	@Test
	void theEmptyStateIsSafeToRenderBeforeTheFirstSync() {
		assertFalse(PanelState.EMPTY.hasRole());
		assertEquals(20, PanelState.EMPTY.maxHealth(), "别拿 0 当分母");
		assertEquals(AutonomyLevel.STANDARD, PanelState.EMPTY.autonomyLevel());
	}
}
