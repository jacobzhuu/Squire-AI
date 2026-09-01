package dev.squire.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import dev.squire.server.gui.SquireActions.Action;
import dev.squire.server.gui.SquireActions.Page;

/**
 * 面板按钮表的一致性。
 *
 * <p>在此之前按钮被客户端和服务端各硬编码一遍，中间靠手工对齐的常量连接，
 * 全仓没有任何测试盯着——两边各改一半不会报错，只会出现"按钮写着 X、点下去做 Y"的
 * 静默漂移。按钮表现在只有一个事实源（{@link SquireActions}），这些测试守住它
 * 不能再烂回去：id 唯一、每个按钮有行为、每条文案两个语言都有、
 * 权限按钮与权限节点的顺序严格一致。</p>
 */
class SquireActionsTest {

	private static Map<String, String> lang(String path) {
		try (var in = SquireActionsTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "missing lang resource " + path);
			return JsonParser.parseString(
					new String(in.readAllBytes(), StandardCharsets.UTF_8))
				.getAsJsonObject().entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey,
					e -> e.getValue().getAsString()));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void everyActionIdIsUnique() {
		Set<Integer> seen = new HashSet<>();
		for (Action action : SquireActions.ALL) {
			assertTrue(seen.add(action.id()),
				"duplicate button id " + action.id() + " (" + action.labelKey() + ")");
		}
	}

	@Test
	void everyActionHasAHandler() {
		for (Action action : SquireActions.ALL) {
			assertNotNull(action.handler(),
				"button " + action.labelKey() + " has no handler: clicking it would "
					+ "silently do nothing");
		}
	}

	@Test
	void lookupByIdFindsEveryAction() {
		for (Action action : SquireActions.ALL) {
			assertEquals(action, SquireActions.byId(action.id()),
				"byId(" + action.id() + ") does not return " + action.labelKey());
		}
		assertNull(SquireActions.byId(9999),
			"unknown button ids must be rejected, not guessed");
	}

	@Test
	void everyActionHasEnAndZhLangKey() {
		Map<String, String> en = lang("/assets/squire/lang/en_us.json");
		Map<String, String> zh = lang("/assets/squire/lang/zh_cn.json");
		for (Action action : SquireActions.ALL) {
			String key = action.labelKey();
			assertTrue(en.containsKey(key) && !en.get(key).isBlank(),
				"missing en label for " + key);
			assertTrue(zh.containsKey(key) && !zh.get(key).isBlank(),
				"missing zh label for " + key);
		}
		for (CommandCatalog.Entry entry : CommandCatalog.ENTRIES) {
			assertTrue(en.containsKey(entry.labelKey())
				&& !en.get(entry.labelKey()).isBlank(), entry.labelKey());
			assertTrue(zh.containsKey(entry.labelKey())
				&& !zh.get(entry.labelKey()).isBlank(), entry.labelKey());
			for (CommandCatalog.Variant variant : entry.variants()) {
				assertTrue(en.containsKey(variant.labelKey()), variant.labelKey());
				assertTrue(zh.containsKey(variant.labelKey()), variant.labelKey());
			}
		}
		for (SquireActions.PermissionGroup group : SquireActions.PERMISSION_GROUPS) {
			assertTrue(en.containsKey(group.labelKey()), group.labelKey());
			assertTrue(zh.containsKey(group.labelKey()), group.labelKey());
		}
	}

	/**
	 * 指令页的每一个按钮<b>恰好</b>被目录里的一个档位用到一次。
	 *
	 * <p>这一页的版面现在来自 {@link CommandCatalog}，不再来自 {@code (row, col, cols)}。
	 * 漏掉一个按钮的表现是它在面板上凭空消失——没有报错，也没有空位，玩家只会以为
	 * 这个功能被删了。</p>
	 */
	@Test
	void everyCommandButtonIsUsedByExactlyOneCatalogVariant() {
		Set<Integer> placed = new HashSet<>();
		for (CommandCatalog.Entry entry : CommandCatalog.ENTRIES) {
			for (CommandCatalog.Variant variant : entry.variants()) {
				assertNotNull(SquireActions.byId(variant.actionId()),
					"目录里 " + entry.id() + " 的档位 " + variant.actionId()
						+ " 不在动作表里");
				assertTrue(placed.add(variant.actionId()),
					"按钮 " + variant.actionId() + " 被两个档位共用了");
			}
		}
		for (Action action : SquireActions.ofPage(Page.COMMAND)) {
			assertTrue(placed.contains(action.id()),
				action.labelKey() + " 没有被目录里的任何一格用到，玩家在面板上点不到它");
		}
	}

	/**
	 * 「盖木屋 / 盖石屋」已经从面板上撤掉：它们走的是不带预览、不带确认的旧建造
	 * 路径，而工程页的「基础施工」谁都点得动，还多了幽灵预览和缺料清单。
	 *
	 * <p>这一条钉的是「撤掉」这件事本身——留一个没有任何页面引用的动作在表里，
	 * 就是一个永远点不到、却看起来还在的功能。</p>
	 */
	@Test
	void theOldOneShotHouseButtonsAreGoneFromThePanel() {
		assertNull(SquireActions.byId(SquireScreenHandler.BUTTON_BUILD_WOOD),
			"盖木屋改走工程页的基础施工，面板上不该再有这个按钮");
		assertNull(SquireActions.byId(SquireScreenHandler.BUTTON_BUILD_STONE),
			"盖石屋同上");
	}

	/** 权限分组必须<b>恰好</b>覆盖 TOGGLEABLE_NODES 一次，而且每一项有风险分级。 */
	@Test
	void permissionGroupsCoverEveryToggleableNodeOnce() {
		Set<String> placed = new HashSet<>();
		for (SquireActions.PermissionGroup group : SquireActions.PERMISSION_GROUPS) {
			for (String node : group.nodes()) {
				assertTrue(SquireScreenHandler.TOGGLEABLE_NODES.contains(node),
					node + " 不是可切换权限，不该出现在权限页上");
				assertTrue(placed.add(node), node + " 被排进了两组");
				assertNotNull(SquireActions.riskOf(node), node + " 没有风险分级");
			}
		}
		for (String node : SquireScreenHandler.TOGGLEABLE_NODES) {
			assertTrue(placed.contains(node),
				node + " 没有出现在权限页的任何一组里，玩家永远看不到它");
		}
		assertEquals(SquireActions.Risk.DANGER, SquireActions.riskOf(
			dev.squire.server.security.PermissionNodes.WORLD_EDIT),
			"世界编辑一次能改一大片地形且没有撤销，必须画成危险色");
		assertEquals(SquireActions.Risk.NORMAL, SquireActions.riskOf(
			dev.squire.server.security.PermissionNodes.TASK_FOLLOW),
			"跟随是日常授权，不该和世界编辑长得一样");
	}

	/** 状态条只留三个高频档位；低频动作在「更多」里，两边都不许空。 */
	@Test
	void theStateRowKeepsOnlyTheThreeHighTrafficModes() {
		assertEquals(List.of(SquireScreenHandler.BUTTON_FOLLOW,
				SquireScreenHandler.BUTTON_STAY, SquireScreenHandler.BUTTON_PATROL),
			SquireActions.ofPage(Page.SIDE).stream().map(Action::id).toList(),
			"状态条上只该有跟随/待命/巡逻这三个档位");
		assertEquals(List.of(SquireScreenHandler.BUTTON_HOME,
				SquireScreenHandler.BUTTON_HOME_SET,
				SquireScreenHandler.BUTTON_DISMISS),
			SquireActions.ofPage(Page.MORE).stream().map(Action::id).toList(),
			"回家/设家/遣散收在「更多」里");
		assertNull(SquireActions.byId(SquireScreenHandler.BUTTON_HELP),
			"「能做啥」已经从面板上撤掉：职业页有成长路线、权限页有权限清单，"
				+ "聊天里问一句「你能做什么」那条路也还在");
	}

	/**
	 * 权限按钮的位序就是 {@code permissionMask} 的位序，必须与 TOGGLEABLE_NODES
	 * 严格一致——错一位，面板显示的和实际切换的就不是同一个权限。
	 */
	@Test
	void permissionActionsMatchToggleableNodesInOrder() {
		var nodes = SquireScreenHandler.TOGGLEABLE_NODES;
		var actions = SquireActions.ofPage(Page.PERMISSION);
		assertEquals(nodes.size(), actions.size(),
			"permission page must have exactly one action per toggleable node");
		for (int i = 0; i < nodes.size(); i++) {
			Action action = actions.get(i);
			assertEquals(SquireScreenHandler.BUTTON_PERMISSION_BASE + i, action.id(),
				"permission actions must keep TOGGLEABLE_NODES order");
			assertEquals(nodes.get(i), action.node(),
				"permission action " + i + " is wired to the wrong node");
			assertEquals(SquireActions.nodeLabelKey(nodes.get(i)), action.labelKey());
		}
	}

	/** 仍保留的 BUTTON_* 常量必须始终指向表里真实存在的按钮。 */
	@Test
	void legacyButtonConstantsStayWiredToTheTable() {
		int[] legacy = {
			SquireScreenHandler.BUTTON_FOLLOW, SquireScreenHandler.BUTTON_STAY,
			SquireScreenHandler.BUTTON_PATROL,
			SquireScreenHandler.BUTTON_HOME, SquireScreenHandler.BUTTON_GUARD_START,
			SquireScreenHandler.BUTTON_GUARD_STOP, SquireScreenHandler.BUTTON_AID_OWNER,
			SquireScreenHandler.BUTTON_HEAL_SELF,
			SquireScreenHandler.BUTTON_AUTO_EQUIP_BEST_ARMOR };
		for (int id : legacy) {
			assertNotNull(SquireActions.byId(id),
				"BUTTON_* constant " + id + " no longer matches any action");
		}
		for (int removedId : new int[] { 11, 12, 13, 15, 16 }) {
			assertNull(SquireActions.byId(removedId),
				"removed give/set-generation button is still routable: " + removedId);
		}
	}

	/** 布局字段直接决定客户端画在哪；越界就是画到面板外面去。 */
	@Test
	void pageActionsKeepTheirColumnsInsideTheirRow() {
		for (Action action : SquireActions.ALL) {
			if (action.page() == Page.SIDE || action.page() == Page.MORE) {
				continue; // 状态条按 row 摆放，不走 (col, cols) 网格
			}
			assertTrue(action.col() >= 0 && action.col() < action.cols(),
				action.labelKey() + ": col " + action.col() + " outside " + action.cols());
			assertTrue(action.row() >= 0, action.labelKey() + ": negative row");
		}
	}

	/**
	 * 同一页里两个按钮不能占同一格。
	 *
	 * <p>这条是从一次真实的返工里来的：「跟随传送距离」当初被放在随从页第 5 行，
	 * 而那几行画的是长度不定的能力清单和性格文字，装上两三个能力之后按钮就压在
	 * 字上面。格子撞车是这类问题里唯一能在数据层看出来的一半，先把它钉死。</p>
	 */
	@Test
	void noTwoButtonsShareTheSameCellOnAPage() {
		Map<String, String> taken = new java.util.HashMap<>();
		for (Action action : SquireActions.ALL) {
			if (action.page() == Page.SIDE || action.page() == Page.MORE) {
				continue; // 状态条按 row 摆放，本来就一行
			}
			String cell = action.page() + "@" + action.row() + ":" + action.col()
				+ "/" + action.cols();
			String previous = taken.put(cell, action.labelKey());
			assertNull(previous, () -> "两个按钮占了同一格 " + cell + "："
				+ previous + " 和 " + action.labelKey());
		}
	}

	/**
	 * 每页的行数必须放得下。内容区从 {@code CONTENT_TOP} 起、到聊天框为止，
	 * 面板内容区支持滚动；这里限制异常的大行号，避免按钮因数据错误被放到
	 * 几千像素之外。
	 */
	@Test
	void noPageNeedsMoreRowsThanTheContentAreaHas() {
		for (Action action : SquireActions.ALL) {
			if (action.page() == Page.SIDE || action.page() == Page.MORE) {
				continue;
			}
			assertTrue(action.row() <= MAX_CONTENT_ROW,
				action.labelKey() + ": row " + action.row()
					+ " 超过内容区能放下的最后一行 " + MAX_CONTENT_ROW);
		}
	}

	/** 内容区最后一个可用行号（客户端 rowTop 的上限，见 SquireScreen）。 */
	private static final int MAX_CONTENT_ROW = 16;
}
