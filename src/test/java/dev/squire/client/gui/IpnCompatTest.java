package dev.squire.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.squire.server.gui.SquireScreenHandler;

/**
 * 「一键背包整理 Next」（IPN）注进来的那排按钮，必须落在面板的空白处。
 *
 * <p>这条测试守的是一个具体的翻车现场：IPN 默认把按钮放在界面右上角，正好压住
 * 模式读数。挪走之后没有任何东西提醒后来的人「面板右下那块空白是留给别人的」——
 * 改一次 {@code PANEL_HEIGHT} 或给右列加一行按钮，按钮就又压上来了，而且只有
 * 装了 IPN 的玩家看得见。所以这里把面板里<b>已经被占用的每一块矩形</b>都列出来，
 * 和 IPN 按钮条求交集。</p>
 *
 * <p>坐标一律是面板局部坐标（相对界面左上角），和 {@code drawForeground} 里一致。</p>
 */
class IpnCompatTest {

	/** 面板里已经有东西的矩形：{名字, 左, 上, 右, 下}。 */
	private static List<Object[]> occupiedRegions() {
		List<Object[]> regions = new ArrayList<>();
		// 名字/职业/血量那一行读数，加下面的状态操作条：整条顶部横带。
		regions.add(new Object[] {"顶部读数与状态条", 0, 0,
			SquireScreen.PANEL_WIDTH, SquireScreenHandler.CONTENT_TOP});
		// 中央内容区（drawBackground 里那块底色）。
		regions.add(new Object[] {"中央内容区", 0, SquireScreenHandler.CONTENT_TOP,
			SquireScreen.CONTENT_RIGHT, SquireScreen.CONTENT_BOTTOM});
		// 右侧竖排页签栏。
		regions.add(new Object[] {"页签栏", SquireScreen.TAB_RAIL_X,
			SquireScreen.TAB_RAIL_TOP,
			SquireScreen.TAB_RAIL_X + SquireScreen.TAB_RAIL_W,
			SquireScreen.TAB_RAIL_BOTTOM});
		// 槽位三块：装备列、伙伴背包、玩家背包+快捷栏（含底色的 4px 外扩）。
		regions.add(new Object[] {"装备列", 4, SquireScreenHandler.EQUIP_Y - 4, 30,
			SquireScreenHandler.EQUIP_Y + 6 * 18 + 2});
		regions.add(new Object[] {"伙伴背包", 40, SquireScreenHandler.AVATAR_GRID_Y - 4,
			40 + 9 * 18 + 2, SquireScreenHandler.AVATAR_GRID_Y + 4 * 18 + 2});
		regions.add(new Object[] {"玩家背包", 40, SquireScreenHandler.PLAYER_GRID_Y - 15,
			40 + 9 * 18 + 2, SquireScreenHandler.HOTBAR_Y + 18 + 2});
		// 底部聊天页脚（分隔线 + 输入框 + 发送按钮）。
		regions.add(new Object[] {"聊天页脚", 0, SquireScreen.FOOTER_TOP - 1,
			SquireScreen.PANEL_WIDTH, SquireScreen.PANEL_HEIGHT});
		return regions;
	}

	@Test
	void sortButtonStripStaysInsideThePanel() {
		assertTrue(IpnCompat.stripLeft() >= 0, "按钮条不能越出面板左边");
		assertTrue(IpnCompat.stripRight() <= SquireScreen.PANEL_WIDTH,
			"按钮条不能越出面板右边");
		assertTrue(IpnCompat.stripTop() >= 0, "按钮条不能越出面板上边");
		assertTrue(IpnCompat.stripBottom() <= SquireScreen.PANEL_HEIGHT,
			"按钮条不能越出面板下边");
	}

	@Test
	void sortButtonStripDoesNotCoverAnythingWeDraw() {
		for (Object[] region : occupiedRegions()) {
			boolean overlaps = IpnCompat.stripLeft() < (int) region[3]
				&& IpnCompat.stripRight() > (int) region[1]
				&& IpnCompat.stripTop() < (int) region[4]
				&& IpnCompat.stripBottom() > (int) region[2];
			assertFalse(overlaps, "IPN 的排序按钮条压在「" + region[0] + "」上："
				+ "按钮条 [" + IpnCompat.stripLeft() + "," + IpnCompat.stripTop() + ","
				+ IpnCompat.stripRight() + "," + IpnCompat.stripBottom() + "]，"
				+ "占用区 [" + region[1] + "," + region[2] + "," + region[3] + ","
				+ region[4] + "]。改 IpnCompat.STRIP_TOP / STRIP_RIGHT 让开。");
		}
	}

	/** 按钮条和页签栏同宽同边：不是好看，是让玩家一眼看出它属于哪一栏。 */
	@Test
	void sortButtonStripLinesUpWithTheTabRail() {
		assertEquals(SquireScreen.TAB_RAIL_X + SquireScreen.TAB_RAIL_W,
			IpnCompat.stripRight(), "按钮条右边界应与页签栏对齐");
		assertTrue(IpnCompat.stripLeft() >= SquireScreen.TAB_RAIL_X,
			"IPN 按钮条应完整落在页签栏内部");
	}

	/**
	 * 注解上的数必须就是 {@link IpnCompat} 算出来的那两个。
	 *
	 * <p>注解值是编译期常量，一旦有人图省事在注解里直接写死数字，上面那两条
	 * 几何测试就再也管不住它了。</p>
	 */
	@Test
	void screenCarriesTheHintsWeComputed() throws ReflectiveOperationException {
		Class<?> screen = Class.forName("dev.squire.client.gui.SquireScreen", false,
			IpnCompatTest.class.getClassLoader());

		assertNotNull(annotationNamed(screen, "IPNPlayerSideOnly"),
			"面板必须带 @IPNPlayerSideOnly，否则 IPN 会去排序侍从的装备栏");

		Annotation hints = annotationNamed(screen, "IPNGuiHints");
		assertNotNull(hints, "面板必须带 @IPNGuiHints");
		Object[] values = (Object[]) hints.annotationType().getMethod("value")
			.invoke(hints);

		Map<String, int[]> byButton = new HashMap<>();
		for (Object hint : values) {
			Class<?> type = ((Annotation) hint).annotationType();
			String button = type.getMethod("button").invoke(hint).toString();
			byButton.put(button, new int[] {
				(int) type.getMethod("horizontalOffset").invoke(hint),
				(int) type.getMethod("bottom").invoke(hint),
				(boolean) type.getMethod("hide").invoke(hint) ? 1 : 0});
		}

		for (String button : List.of("SORT", "SORT_COLUMNS", "SORT_ROWS")) {
			int[] hint = byButton.get(button);
			assertNotNull(hint, button + " 缺少位置修正");
			assertEquals(IpnCompat.SORT_HORIZONTAL_OFFSET, hint[0],
				button + " 的 horizontalOffset 必须来自 IpnCompat");
			assertEquals(IpnCompat.SORT_BOTTOM, hint[1],
				button + " 的 bottom 必须来自 IpnCompat");
			assertEquals(0, hint[2], button + " 不该被藏起来");
		}
		for (String button : List.of("MOVE_TO_CONTAINER", "MOVE_TO_PLAYER")) {
			int[] hint = byButton.get(button);
			assertNotNull(hint, button + " 缺少 hide 声明");
			assertEquals(1, hint[2], button + " 必须藏掉");
		}
	}

	private static Annotation annotationNamed(Class<?> type, String simpleName) {
		for (Annotation annotation : type.getAnnotations()) {
			if (annotation.annotationType().getSimpleName().equals(simpleName)) {
				return annotation;
			}
		}
		return null;
	}
}
