package dev.squire.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.squire.server.gui.CommandCatalog.Context;
import dev.squire.server.gui.CommandCatalog.Entry;
import dev.squire.server.gui.CommandCatalog.Nav;
import dev.squire.server.gui.CommandCatalog.Reason;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.SquireProfession;

/**
 * 「指令」页那张常用动作清单。
 *
 * <p>这一层要守的就一件事：<b>面板说的话和服务端做的事是同一件事</b>。清单按职业、
 * 等级、能力和当前状态算出来，一个还没转职的侍从不该在面板上看到守卫的主动能力；
 * 而一条因为转职而失效的快捷，必须在<b>服务端</b>被拦下来，不能因为客户端画了个
 * 亮按钮就放行。</p>
 */
class CommandCatalogTest {

	/** Lv.0：还没转职的侍从。 */
	private static Context untrained() {
		return new Context(null, 0, Set.of(), false, false);
	}

	/** 某个职业练到某一级：那一级该会的能力全给上，和服务端算出来的一致。 */
	private static Context at(SquireProfession profession, int level) {
		return at(profession, level, false, false);
	}

	private static Context at(SquireProfession profession, int level,
			boolean hasProject, boolean hasPlacement) {
		Set<String> unlocked = new LinkedHashSet<>();
		for (ProfessionAbility ability : ProfessionAbility.of(profession)) {
			if (ability.unlockLevel() <= level) {
				unlocked.add(ability.id());
			}
		}
		return new Context(profession, level, unlocked, hasProject, hasPlacement);
	}

	private static List<String> idsOf(Context context) {
		return CommandCatalog.visible(context).stream().map(Entry::id).toList();
	}

	// ------------------------------------------------------------------ 清单本身

	@Test
	void everyEntryIdIsUniqueAndLooksUpAgain() {
		Set<String> seen = new HashSet<>();
		for (Entry entry : CommandCatalog.ENTRIES) {
			assertTrue(seen.add(entry.id()), "重复的动作 id：" + entry.id());
			assertEquals(entry, CommandCatalog.byId(entry.id()));
		}
		assertNull(CommandCatalog.byId("nope.not.a.thing"),
			"认不出的 id 必须回 null，不能猜一个");
		assertNull(CommandCatalog.byId(null));
	}

	/**
	 * 每一格都得有出路：要么跳到某一页，要么真的能执行。
	 *
	 * <p>两样都没有的一格就是一个点下去什么也不发生的按钮——这正是这个模组
	 * 明确要避免的那类伤害。</p>
	 */
	@Test
	void everyEntryEitherNavigatesOrExecutes() {
		for (Entry entry : CommandCatalog.ENTRIES) {
			assertTrue(entry.navigates() || entry.bindable(),
				entry.id() + " 既不跳页也没有可执行的档位，点下去什么都不会发生");
			assertFalse(entry.displayName().isBlank(),
				entry.id() + " 没有中文名，聊天回执会显示成一段空白");
		}
	}

	// ------------------------------------------------------------------ 职业边界

	/**
	 * Guard / Engineer / Lv.0 看到的是<b>三份不同的</b>清单。
	 *
	 * <p>这一条是整次改版的目的：职业系统已经划好了能力边界，面板就不该再展示
	 * 一个万能侍从。三份清单相同，说明 UI 又把职业这件事抹平了。</p>
	 */
	@Test
	void guardEngineerAndUntrainedSeeDifferentLists() {
		List<String> guard = idsOf(at(SquireProfession.GUARD, 1));
		List<String> engineer = idsOf(at(SquireProfession.ENGINEER, 1));
		List<String> untrained = idsOf(untrained());
		assertNotEquals(guard, engineer);
		assertNotEquals(guard, untrained);
		assertNotEquals(engineer, untrained);

		// 守卫的主动能力只有守卫有。
		assertTrue(guard.contains("guard.start"), "守卫看得到「保护我」");
		assertFalse(engineer.contains("guard.start"), "工程师不是护卫");
		assertFalse(untrained.contains("guard.start"),
			"还没转职就看到守卫的主动能力，等于告诉玩家职业系统不存在");
		assertTrue(guard.contains("equip.self"));
		assertFalse(untrained.contains("equip.self"));

		// 工程师的那一半同理。
		assertTrue(engineer.contains("blueprint.open"));
		assertFalse(guard.contains("blueprint.open"));

		// Lv.0 的出路：训练进度只对还没转职的人有意义。
		assertTrue(untrained.contains("training.progress"));
		assertFalse(guard.contains("training.progress"));
		assertFalse(engineer.contains("training.progress"));
	}

	/** 谁都能做的那几件事，三种侍从都看得到。停止尤其重要——开得了就得停得掉。 */
	@Test
	void theCommonActionsAreVisibleToEveryone() {
		for (Context context : List.of(untrained(), at(SquireProfession.GUARD, 1),
				at(SquireProfession.ENGINEER, 10))) {
			List<String> ids = idsOf(context);
			assertTrue(ids.contains("guard.stop"),
				"「解除护卫」必须永远点得动，否则一个转错职的玩家会被永久护卫着");
			assertTrue(ids.contains("aid.owner"));
			assertTrue(ids.contains("heal.self"));
			assertTrue(ids.contains("inventory.give"));
			assertTrue(ids.contains("build.basic"),
				"基础施工是固定模板，谁都点得动");
		}
	}

	/**
	 * 状态门：没工程就没有「继续工程」，没蓝图就没有「材料配置 / 蓝图参数」。
	 *
	 * <p>这几格不是按职业藏的，是按<b>当前状态</b>藏的——一个点了没反应的
	 * 「继续工程」比没有这个按钮更让人困惑。</p>
	 */
	@Test
	void stateGatedEntriesAppearOnlyWhenThatStateExists() {
		assertFalse(idsOf(at(SquireProfession.ENGINEER, 6)).contains("project.resume"));
		assertTrue(idsOf(at(SquireProfession.ENGINEER, 6, true, false))
			.contains("project.resume"));

		assertFalse(idsOf(at(SquireProfession.ENGINEER, 6))
			.contains("project.materials"));
		List<String> placing = idsOf(at(SquireProfession.ENGINEER, 6, false, true));
		assertTrue(placing.contains("project.materials"));
		assertTrue(placing.contains("blueprint.design"),
			"「当前已解锁的蓝图操作」就是参数子页本身，有蓝图在放的时候才通得过去");

		// 状态门先判：一个守卫点「材料配置」时该看到的是「没有蓝图在放」，
		// 而不是一句和他处境无关的职业要求。
		var lock = CommandCatalog.lockOf(CommandCatalog.byId("project.materials"),
			at(SquireProfession.GUARD, 3));
		assertNotNull(lock);
		assertEquals(Reason.PLACEMENT, lock.reason());
	}

	// ------------------------------------------------------------------ 锁

	/** 「需要 工程师 Lv.7」这句话，面板上和拒绝执行时说的必须是同一件事。 */
	@Test
	void anAbilityGateLocksWithTheProfessionAndTheLevelItNeeds() {
		Entry design = CommandCatalog.byId("blueprint.design");
		assertNotNull(design);
		// 守卫：职业就不对，说的是职业。
		var wrongJob = CommandCatalog.lockOf(design,
			at(SquireProfession.GUARD, 5, false, true));
		assertNotNull(wrongJob);
		assertEquals(Reason.PROFESSION, wrongJob.reason());
		assertEquals(SquireProfession.ENGINEER, wrongJob.profession());
		assertTrue(wrongJob.describe().contains(
			SquireProfession.ENGINEER.displayName()));
	}

	/**
	 * 等级不够时说的是等级，而且那个等级<b>来自能力表</b>，不是手写的。
	 *
	 * <p>用一个假造的、能力表说要 Lv.7 的门验：手写等级的做法迟早和能力表对不上，
	 * 而玩家看到的会是「练到写着的那一级，按钮还是灰的」。</p>
	 */
	@Test
	void aLevelLockQuotesTheAbilityTableRatherThanAHandWrittenNumber() {
		ProfessionAbility mirror = ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR;
		Entry probe = new Entry("probe", "试验格",
			CommandCatalog.Gate.of(mirror), Nav.NONE, List.of());
		var lock = CommandCatalog.lockOf(probe,
			at(SquireProfession.ENGINEER, mirror.unlockLevel() - 1));
		assertNotNull(lock);
		assertEquals(Reason.LEVEL, lock.reason());
		assertEquals(mirror.unlockLevel(), lock.level());
		assertEquals("需要" + SquireProfession.ENGINEER.displayName()
			+ " Lv." + mirror.unlockLevel(), lock.describe());
		assertNull(CommandCatalog.lockOf(probe,
			at(SquireProfession.ENGINEER, mirror.unlockLevel())),
			"练到那一级就该真的解锁");
	}

	/** 能力自带职业，门里不必（也不该）再写一遍——写两遍就会有对不上的那一天。 */
	@Test
	void anAbilityGateInheritsItsProfession() {
		CommandCatalog.Gate gate = CommandCatalog.Gate.of(
			ProfessionAbility.GUARD_INTERCEPT);
		assertEquals(SquireProfession.GUARD, gate.profession());
		assertEquals(ProfessionAbility.GUARD_INTERCEPT.unlockLevel(), gate.minLevel());
	}

	// ------------------------------------------------------------------ 快捷绑定

	/**
	 * 新建快捷时能挑的，是<b>现在真的点得动而且真的有动作</b>的那几格。
	 *
	 * <p>纯导航格（「打开蓝图」这种）不在里面：一条「跳到工程页」的快捷指令
	 * 没有任何意义，而且它会让快捷列表里混进一批点了不做事的条目。</p>
	 */
	@Test
	void onlyExecutableAndCurrentlyAvailableEntriesCanBeBound() {
		var bindable = CommandCatalog.bindable(at(SquireProfession.GUARD, 3));
		for (Entry entry : bindable) {
			assertTrue(entry.bindable(), entry.id() + " 没有可执行的档位");
			assertTrue(CommandCatalog.available(entry, at(SquireProfession.GUARD, 3)));
		}
		List<String> ids = bindable.stream().map(Entry::id).toList();
		assertTrue(ids.contains("guard.start"));
		assertTrue(ids.contains("inventory.give"));
		assertTrue(ids.contains("build.basic"),
			"面板上它是个入口，但快捷可以直接绑到某个模板上");
		assertFalse(ids.contains("blueprint.open"),
			"纯导航格不该出现在快捷可绑清单里");
		assertFalse(ids.contains("training.progress"));
	}

	/** 档位参数就是快捷存下来的那个字符串，认不出的一律回 null 而不是猜一个。 */
	@Test
	void variantsResolveByTheirStoredArgument() {
		Entry give = CommandCatalog.byId("inventory.give");
		assertNotNull(give);
		assertEquals(SquireScreenHandler.BUTTON_GIVE_64, give.variant("64").actionId());
		assertEquals(SquireScreenHandler.BUTTON_GIVE_16, give.variant("16").actionId());
		assertNull(give.variant("999"), "认不出的档位必须回 null");
		assertNull(give.variant(""), "多档位的动作不能默认挑一个，那是在替玩家决定");

		// 单档位的动作：参数是空串，老数据缺参数时也认得出来。
		Entry stop = CommandCatalog.byId("guard.stop");
		assertNotNull(stop.variant(""));
		assertEquals(SquireScreenHandler.BUTTON_GUARD_STOP,
			stop.variant(null).actionId());
	}

	/** 每一格的默认档位都得存在，否则面板上那个普通按钮会画不出来。 */
	@Test
	void everyBindableEntryHasADefaultVariant() {
		for (Entry entry : CommandCatalog.ENTRIES) {
			if (entry.bindable()) {
				assertNotNull(entry.defaultVariant(), entry.id());
			}
		}
	}

	@Test
	void describeNamesTheActionAndTheVariant() {
		assertEquals("给我物品 64", CommandCatalog.describe("inventory.give", "64"));
		assertEquals("解除护卫", CommandCatalog.describe("guard.stop", ""));
		assertEquals("", CommandCatalog.describe("gone.away", ""),
			"认不出的动作说空话，而不是编一个名字");
	}

	// ------------------------------------------------------------------ 下一能力

	/** 不画一堆灰按钮，但也不能让玩家以为到此为止。 */
	@Test
	void theNextUnlockHintPointsAtTheNextRealAbility() {
		assertNull(CommandCatalog.nextUnlock(untrained()),
			"还没转职时没有「下一能力」可言，面板改说「选定职业后解锁更多」");
		var next = CommandCatalog.nextUnlock(at(SquireProfession.GUARD, 1));
		assertNotNull(next);
		assertEquals(SquireProfession.GUARD, next.profession());
		assertTrue(next.unlockLevel() > 1);
		assertNull(CommandCatalog.nextUnlock(
			at(SquireProfession.ENGINEER, SquireProfession.MAX_LEVEL)),
			"满级之后没有下一项了");
	}

	// ------------------------------------------------------------------ 两端同一套判据

	/**
	 * 客户端从状态包装出来的判据，和服务端从职业档案装出来的<b>必须一样</b>。
	 *
	 * <p>两边各判一半，就会出现「面板亮着但服务端拒绝」——那正是这一层存在的理由。</p>
	 */
	@Test
	void theClientAndServerContextsAgree() {
		var data = new dev.squire.server.profession.ProfessionData();
		data.setProfession(SquireProfession.ENGINEER);
		data.level = 7;
		Context server = Context.of(data, false, true);

		ProfessionView view = ProfessionView.of(data,
			dev.squire.server.profession.ProfessionConfig.defaults(), itemId -> 0);
		PanelState state = new PanelState(0, 0, 20, 20, "", 7, 3, 0L, 0L, List.of(),
			List.of(), "standard", "", "", List.of(), "", 16, List.of(), 0, "",
			"橡木房", "GHOST", "", List.of(), List.of(), List.of(), true,
			List.of(), "", 0, 0, 0, view);
		Context client = Context.of(state);

		assertEquals(server.profession(), client.profession());
		assertEquals(server.level(), client.level());
		assertEquals(server.abilities(), client.abilities());
		assertEquals(server.hasPlacement(), client.hasPlacement());
		assertEquals(idsOf(server), idsOf(client),
			"同一只侍从，面板上看到的和服务端认可的必须是同一份清单");
	}
}
