package dev.squire.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.squire.server.gui.SquireActions;
import dev.squire.server.gui.SquireScreenHandler;

/**
 * 物品页那条按钮带（目前只有「整理背包」）必须待在槽位之间的空隙里。
 *
 * <p>这一页的版面是槽位网格，按钮是<b>塞进去</b>的，不像别的页有等分行列托着。
 * 仓库里已经有过两次同类翻车：「装备」标题压在页签上、跟随距离按钮压在能力清单上。
 * 空隙只有 27px（伙伴背包底色画到 114，玩家背包标题在 145），任何一次改版面
 * 都可能把它挤掉，所以这里把上下左右四条边都钉死。</p>
 */
class ItemsPageLayoutTest {

	/** 伙伴背包那块底色的下沿：{@code AVATAR_GRID_Y + 4*18 + 2}。 */
	private static final int AVATAR_SECTION_BOTTOM =
		SquireScreenHandler.AVATAR_GRID_Y + 4 * 18 + 2;

	/** 玩家背包的「物品栏」标题所在行：{@code PLAYER_GRID_Y - 11}。 */
	private static final int PLAYER_TITLE_TOP = SquireScreenHandler.PLAYER_GRID_Y - 11;

	/** 装备列底色的右沿。按钮必须整个在它右边，否则会压在装备槽上。 */
	private static final int EQUIPMENT_COLUMN_RIGHT = 30;

	private static final int BUTTON_H = 18;

	@Test
	void theItemsButtonBandFitsBetweenTheTwoGrids() {
		int top = SquireScreen.ITEMS_BUTTON_Y;
		int bottom = top + BUTTON_H;
		assertTrue(top > AVATAR_SECTION_BOTTOM,
			"整理按钮压在伙伴背包上：按钮顶 " + top + " ≤ 格子底色 " + AVATAR_SECTION_BOTTOM);
		assertTrue(bottom <= PLAYER_TITLE_TOP,
			"整理按钮压在「物品栏」标题上：按钮底 " + bottom + " > 标题顶 " + PLAYER_TITLE_TOP);
	}

	/**
	 * 这一带最挤的时候：动作表里的按钮 + 「看背囊」开关 + 两个翻页箭头。
	 *
	 * <p>背着一个 120 格背包、正在看背囊时就是这个样子，右边一格都不能越过内容区。</p>
	 */
	@Test
	void theItemsButtonBandStaysClearOfTheEquipmentColumnAndTheTabRail() {
		int buttons = SquireActions.ofPage(SquireActions.Page.ITEMS).size() + 1; // ＋看背囊
		int left = SquireScreenHandler.GRID_X;
		int right = left + buttons * SquireScreen.ITEMS_BUTTON_W + 4 * (buttons - 1)
			+ 4 + SquireScreen.ITEMS_PAGER_W + 2 + SquireScreen.ITEMS_PAGER_W;
		assertTrue(left > EQUIPMENT_COLUMN_RIGHT,
			"按钮带压在装备列上：按钮左 " + left + " ≤ 装备列右 " + EQUIPMENT_COLUMN_RIGHT);
		assertTrue(right <= SquireScreen.CONTENT_RIGHT,
			"按钮带伸进了页签栏：最右 " + right + " > 内容区右 "
				+ SquireScreen.CONTENT_RIGHT + "。要么收窄 ITEMS_BUTTON_W，"
				+ "要么这一带放不下这么多按钮了。");
	}

	/** 动作表里这一页现在就一个按钮；多加一个之前先回来看看那条空隙还够不够宽。 */
	@Test
	void theItemsPageStillHasExactlyOneTableButton() {
		var actions = SquireActions.ofPage(SquireActions.Page.ITEMS);
		assertEquals(1, actions.size(), "物品页的按钮数变了，请复核这一页的空隙");
		assertEquals(SquireScreenHandler.BUTTON_SORT_ITEMS, actions.get(0).id());
		assertEquals("squire.gui.button.sort_items", actions.get(0).labelKey());
	}

	/**
	 * 背囊那块网格必须和伙伴背包那块<b>完全重合</b>。
	 *
	 * <p>两套槽位靠"同一时刻只亮一套"共用一块地方，坐标一旦错开，玩家会看到
	 * 半透明的双层格子，而且点击落在哪一套上全看槽位列表的顺序。</p>
	 */
	@Test
	void theBackpackGridSitsExactlyOnTheAvatarGrid() {
		assertEquals(SquireScreenHandler.AVATAR_GRID_START
				+ dev.squire.server.body.avatar.AvatarEntity.MAIN_INVENTORY_SIZE + 36,
			SquireScreenHandler.BACKPACK_CONTENTS_START,
			"背囊那 36 格必须排在玩家背包之后：前面任何一段的下标都不许被挤动");
		assertEquals(36, dev.squire.server.gui.AvatarBackpackContentsInventory.PAGE_SIZE,
			"一页正好是伙伴背包那块 9×4 的网格");
	}
}
