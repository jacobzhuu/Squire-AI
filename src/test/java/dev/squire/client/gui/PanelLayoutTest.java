package dev.squire.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.squire.server.gui.SquireScreenHandler;

class PanelLayoutTest {

	@Test
	void panelFitsTheMinimumLogicalMinecraftGui() {
		assertTrue(SquireScreen.PANEL_WIDTH <= 320);
		assertTrue(SquireScreen.PANEL_HEIGHT <= 240);
		assertTrue(SquireScreenHandler.HOTBAR_Y + 18 <= SquireScreen.PANEL_HEIGHT - 4,
			"the item-page hotbar must remain fully inside the panel");
	}

	@Test
	void itemPageOwnsTheFooterSoChatMustBeHiddenThere() {
		PanelLayout.Rect itemSlots = new PanelLayout.Rect(40,
			SquireScreenHandler.PLAYER_GRID_Y - 4, 40 + 9 * 18 + 2,
			SquireScreenHandler.HOTBAR_Y + 18 + 2);
		PanelLayout.Rect chat = new PanelLayout.Rect(8,
			SquireScreen.PANEL_HEIGHT - 23, SquireScreen.PANEL_WIDTH - 8,
			SquireScreen.PANEL_HEIGHT - 4);
		assertTrue(itemSlots.intersects(chat),
			"this overlap documents why the chat footer is hidden on the item page");
	}

	/**
	 * 内容区和聊天页脚<b>严格分开</b>。
	 *
	 * <p>这条是从「一些页面内容已经逼近或覆盖聊天区域」那份反馈里来的：内容一长，
	 * 按钮和阶段清单就直接铺到输入框上面。现在内容区的下边界是一个常量，
	 * 越过它的控件由 positionScrollableWidgets 整个藏掉、文字由 scissor 裁掉，
	 * 而这里钉住那个常量本身不许再往下挪。</p>
	 */
	@Test
	void theContentViewportStopsAboveTheChatFooter() {
		assertTrue(SquireScreen.CONTENT_BOTTOM < SquireScreen.FOOTER_TOP,
			"内容区下边界必须在聊天页脚之上");
		PanelLayout.Rect viewport = new PanelLayout.Rect(0,
			SquireScreenHandler.CONTENT_TOP, SquireScreen.CONTENT_RIGHT,
			SquireScreen.CONTENT_BOTTOM);
		PanelLayout.Rect chat = new PanelLayout.Rect(8,
			SquireScreen.PANEL_HEIGHT - 23, SquireScreen.PANEL_WIDTH - 8,
			SquireScreen.PANEL_HEIGHT - 4);
		assertFalse(viewport.intersects(chat),
			"内容区压到聊天行上了：任何滚动内容和按钮都不许进入页脚");
		assertTrue(chat.top() >= SquireScreen.FOOTER_TOP,
			"聊天行必须整个落在页脚带里");
	}

	/**
	 * 竖排页签栏还排得下，而且它下面留给 IPN 的那条按钮带没有掉进页脚。
	 *
	 * <p>横排页签就是这么烂掉的：加到第七个每格只剩 26px，第八个压到血量读数上，
	 * 而没有任何东西在加之前说一句。现在容量是写出来的，满了这条会先失败。</p>
	 */
	@Test
	void theTabRailStillHasRoomForOneMorePage() {
		assertEquals(SquireScreen.tabKeyCount(), SquireScreen.TAB_COUNT,
			"TAB_COUNT 是喂给 @IPNGuiHint 的编译期常量，加了页签必须一起改");
		assertTrue(SquireScreen.TAB_COUNT <= SquireScreen.TAB_RAIL_CAPACITY,
			"页签栏排不下 " + SquireScreen.TAB_COUNT + " 页了（容量 "
				+ SquireScreen.TAB_RAIL_CAPACITY + "）：给这一栏做滚动，"
				+ "不要把页签越缩越窄。");
		int capacityBottom = SquireScreen.TAB_RAIL_TOP
			+ SquireScreen.TAB_RAIL_CAPACITY * SquireScreen.TAB_RAIL_PITCH
			- SquireScreen.TAB_RAIL_GAP;
		assertTrue(capacityBottom + 4 + IpnCompat.IPN_BUTTON_SIZE
				<= SquireScreen.FOOTER_TOP,
			"页签栏满员之后，它下面那条 IPN 按钮带会掉进聊天页脚："
				+ (capacityBottom + 4 + IpnCompat.IPN_BUTTON_SIZE) + " > "
				+ SquireScreen.FOOTER_TOP);
	}

	/**
	 * 等分列的最右一列不许伸出内容区。
	 *
	 * <p>整数除法余下的那一两个像素是这类版面唯一会<b>静默</b>出界的地方：
	 * 240 分成 3 列时每列 77，三列加两条 4px 间隙是 247——差一点就压到滚动条上。
	 * 面板上一到四列全用得着（权限两列、姿态三列、自主四列），一条一条钉住。</p>
	 */
	@Test
	void everyColumnLayoutStaysInsideTheContentBox() {
		for (int count = 1; count <= 5; count++) {
			int right = SquireScreen.columnX(count - 1, count)
				+ SquireScreen.columnWidth(count);
			assertTrue(right <= SquireScreen.contentRightEdge(),
				count + " 列时最右一列伸出内容区：右缘 " + right + " > "
					+ SquireScreen.contentRightEdge());
			assertTrue(SquireScreen.columnWidth(count) > 0, count + " 列宽成了 0");
			// 相邻两列之间必须真的有缝，否则两个按钮会边贴边糊在一起。
			if (count > 1) {
				assertTrue(SquireScreen.columnX(1, count)
						> SquireScreen.columnX(0, count)
							+ SquireScreen.columnWidth(count),
					count + " 列时相邻两列贴在一起了");
			}
		}
		assertTrue(SquireScreen.contentRightEdge() <= SquireScreen.CONTENT_RIGHT,
			"内容区里的东西不许压到内容区自己的右边界上");
	}

	/** 状态条：跟随/待命/巡逻/更多 这一行必须整条落在面板里。 */
	@Test
	void theStateRowFitsAcrossThePanel() {
		int count = 4;
		int right = 8 + count * SquireScreen.stateRowButtonWidth(count)
			+ (count - 1) * 4;
		assertTrue(right <= SquireScreen.PANEL_WIDTH - 8,
			"状态条伸出面板右边：" + right);
		assertTrue(SquireScreen.STATE_ROW_Y + SquireScreen.STATE_ROW_H
				<= SquireScreenHandler.CONTENT_TOP - 3,
			"状态条压到内容区上了");
		assertTrue(SquireScreen.stateRowButtonWidth(count) >= 40,
			"状态条按钮窄到放不下「巡逻」两个字了");
	}

	/**
	 * 页签栏现在是<b>六页</b>：物品 / 行为 / 职业 / 工程 / 权限 / 指令。
	 *
	 * <p>「指挥」和「快捷」合并成了「指令」。那两页问的是同一个问题的两半——
	 * 「系统让我做什么」和「我自己常让它做什么」——分成两页的代价是快捷页孤零零
	 * 占着一整格页签，而指挥页把十四个按钮一次全铺出来。</p>
	 */
	@Test
	void theTabRailIsTheSixMergedPages() {
		assertEquals(6, SquireScreen.TAB_COUNT,
			"指挥页和快捷页合并成「指令」之后只剩六页");
		assertEquals(java.util.List.of("squire.gui.tab.items", "squire.gui.tab.profile",
				"squire.gui.tab.profession", "squire.gui.tab.project",
				"squire.gui.tab.permissions", "squire.gui.tab.command"),
			java.util.List.of(SquireScreen.tabKeys()),
			"页签顺序就是玩家看到的顺序；「指令」在最后一格");
	}

	@Test
	void scrollingClampsAtBothEnds() {
		assertEquals(0, PanelLayout.scrollLimit(180, 208));
		assertEquals(72, PanelLayout.scrollLimit(280, 208));
		assertEquals(0, PanelLayout.scrollBy(0, -1, 21, 72));
		assertEquals(72, PanelLayout.scrollBy(63, 1, 21, 72));
		assertEquals(42, PanelLayout.scrollBy(21, 1, 21, 72));
	}
}
