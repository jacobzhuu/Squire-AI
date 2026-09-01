package dev.squire.client.gui;

/**
 * 与「一键背包整理 Next」（Inventory Profiles Next，下称 IPN）共处的那几个数。
 *
 * <p>症状：装了 IPN 之后，侍从面板右上角凭空多出四个别人的按钮，正好压在
 * {@code (BUTTON_X-2, 6)} 那行模式读数上——玩家看不到伙伴在「跟随中」还是「留守」。
 * IPN 认为任何带非玩家槽位的界面都是箱子，于是把「全部搬进去 / 全部搬出来 / 三种排序」
 * 塞进界面顶部右上角（{@code top=5}，右边距 7/19/31/43）。</p>
 *
 * <p>两件事必须一起解决，光挪按钮不够：</p>
 * <ol>
 *   <li><b>排序会翻侍从的装备栏。</b>IPN 把「容器那一侧」看成一整块可排序存储，
 *       而我们那一侧是 6 个装备槽 + 36 格背包。排序会试着把随便什么东西塞进头盔槽，
 *       {@code EquipmentTabSlot.canInsert} 拒收，点击序列就断在半路。</li>
 *   <li><b>非「物品」页的槽位是关掉的</b>（{@code slotsVisible==false}），但服务端不知道，
 *       在权限页点一下排序照样会把看不见的格子搅一遍。</li>
 * </ol>
 *
 * <p>所以 {@link SquireScreen} 上挂 {@code @IPNPlayerSideOnly}：IPN 只整理玩家自己那
 * 36 格，永远不碰侍从的东西。副作用是那两个「全部搬运」按钮消失（我们本来就有
 * shift+点击），剩下的三个排序按钮由 IPN 改挂到界面<b>右下</b>角，再用这里的偏移量
 * 挪到页签栏正下方——空白处，和页签栏同宽同边。</p>
 *
 * <p>数字全部是从 IPN 1.10.20 的 {@code SortingButtonCollectionWidget.reHint()} 里读出来的
 * 默认布局。IPN 换版本改了默认值，这一排按钮会整体偏移，但不会盖住任何东西：
 * {@code IpnCompatTest} 守的就是这条——按钮矩形必须落在面板空白区里。</p>
 */
public final class IpnCompat {

	private IpnCompat() {
	}

	/** IPN 默认把这排按钮的底边放在离界面底边 85px 处（无容器侧时的锚点）。 */
	public static final int IPN_DEFAULT_BOTTOM = 85;

	/** 最靠右那个按钮的右边距；每多一个按钮再往左 {@link #IPN_BUTTON_STEP}。 */
	public static final int IPN_DEFAULT_RIGHT = 7;

	/** IPN 按钮的边长。 */
	public static final int IPN_BUTTON_SIZE = 12;

	/** 相邻两个按钮的间距（含按钮本身）。 */
	public static final int IPN_BUTTON_STEP = 12;

	/** 玩家侧模式下 IPN 会画出来的按钮数：排序 / 按列 / 按行。 */
	public static final int IPN_BUTTON_COUNT = 3;

	/** 这排按钮的目标位置：页签栏下方 4px。 */
	public static final int STRIP_TOP = SquireScreen.TAB_RAIL_BOTTOM + 4;

	/** 目标右边界：和页签栏同一条右边线。 */
	public static final int STRIP_RIGHT = SquireScreen.TAB_RAIL_X + SquireScreen.TAB_RAIL_W;

	/**
	 * 挂到每个排序按钮上的 {@code horizontalOffset}。
	 *
	 * <p>IPN 的右边距是「离界面右边多远」，<b>越大越靠左</b>，所以这里是
	 * 目标右边距减默认右边距。三个按钮加同一个值＝整排平移，相对间距不变。</p>
	 */
	public static final int SORT_HORIZONTAL_OFFSET =
		(SquireScreen.PANEL_WIDTH - STRIP_RIGHT) - IPN_DEFAULT_RIGHT;

	/** 挂到每个排序按钮上的 {@code bottom}：目标底边距减默认底边距。 */
	public static final int SORT_BOTTOM =
		(SquireScreen.PANEL_HEIGHT - (STRIP_TOP + IPN_BUTTON_SIZE)) - IPN_DEFAULT_BOTTOM;

	/** 这排按钮最终占据的矩形（面板局部坐标），给测试和排版复核用。 */
	public static int stripLeft() {
		return STRIP_RIGHT - IPN_BUTTON_COUNT * IPN_BUTTON_STEP;
	}

	public static int stripRight() {
		return STRIP_RIGHT;
	}

	public static int stripTop() {
		return STRIP_TOP;
	}

	public static int stripBottom() {
		return STRIP_TOP + IPN_BUTTON_SIZE;
	}
}
