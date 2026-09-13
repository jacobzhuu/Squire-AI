package dev.squire.client.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.gui.PanelState;
import dev.squire.server.gui.SquireActions;
import dev.squire.server.gui.SquireScreenHandler;
import dev.squire.server.registry.SquireScreens;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.anti_ad.mc.ipn.api.IPNButton;
import org.anti_ad.mc.ipn.api.IPNGuiHint;
import org.anti_ad.mc.ipn.api.IPNGuiHints;
import org.anti_ad.mc.ipn.api.IPNPlayerSideOnly;

/**
 * 侍从控制台。目标是让玩家<b>不必打开聊天栏</b>就能完成日常操作。
 *
 * <p>版面按固定矩形分区，各区互不侵入：</p>
 * <pre>
 *   顶部      名字 · 职业/等级 · 血量 · 当前状态                （一行读数）
 *   状态条    跟随 / 待命 / 巡逻 / 更多                          （更多里是回家、设家、遣散）
 *   内容区    当前页专属区域，可滚动，右边界收在页签栏之前
 *   页签栏    右侧竖排，加一页就是加一行，容量见 TAB_RAIL_CAPACITY
 *   页脚      聊天输入框 + 发送                                  （内容区绝不越过 FOOTER_TOP）
 * </pre>
 *
 * <h2>为什么页签是竖着的</h2>
 * <p>横着排到第七个就已经每格 26px、英文得缩成四个字母；第八个只能压到血量读数上。
 * 竖排之后一页 44px 宽、加一页只是 {@code TAB_KEYS} 多一条，容量由
 * {@link #TAB_RAIL_CAPACITY} 声明，{@code PanelLayoutTest} 在满了的时候会说出来。
 * 腾出来的那条横带正好给状态条——原来右边那一列七个长得一模一样的按钮
 * 把「他现在是什么状态」和「按下去会发生一件事」混在了一起，遣散还夹在中间。</p>
 *
 * <p><b>切页时槽位是真正关掉的，不是拿遮罩盖住。</b>原版把槽位和物品画在按钮
 * <em>之后</em>，而且 {@code drawItem} 把物品推到 z=232，任何 fill 都盖不住——
 * 先前"指挥/权限页里还能看到一堆剑和木头压在按钮上"就是这么来的。
 * {@code drawSlots} 与命中检测都会跳过 {@code isEnabled()==false} 的槽位，
 * 所以可见性交给 {@link SquireScreenHandler#setSlotsVisible}。</p>
 *
 * <p>类上那几个 {@code @IPN*} 注解是给「一键背包整理 Next」看的：不装那个模组时它们
 * 只是几个没人读的注解，装了就会把它的按钮从我们的右上角挪走、并禁止它翻侍从的
 * 装备栏。数字的来历和守护测试见 {@link IpnCompat}。</p>
 */
@IPNPlayerSideOnly
@IPNGuiHints({
	@IPNGuiHint(button = IPNButton.SORT,
		horizontalOffset = IpnCompat.SORT_HORIZONTAL_OFFSET,
		bottom = IpnCompat.SORT_BOTTOM),
	@IPNGuiHint(button = IPNButton.SORT_COLUMNS,
		horizontalOffset = IpnCompat.SORT_HORIZONTAL_OFFSET,
		bottom = IpnCompat.SORT_BOTTOM),
	@IPNGuiHint(button = IPNButton.SORT_ROWS,
		horizontalOffset = IpnCompat.SORT_HORIZONTAL_OFFSET,
		bottom = IpnCompat.SORT_BOTTOM),
	// 这三个在玩家侧模式下本来就不出现；万一 IPN 换了判定又把它们放出来，
	// 也不许它们落在标题栏上——直接藏掉，我们自己有 shift+点击和物品页。
	@IPNGuiHint(button = IPNButton.MOVE_TO_CONTAINER, hide = true),
	@IPNGuiHint(button = IPNButton.MOVE_TO_PLAYER, hide = true),
	@IPNGuiHint(button = IPNButton.PROFILE_SELECTOR, hide = true)})
public class SquireScreen extends HandledScreen<SquireScreenHandler> {

	/** Fits inside Minecraft's minimum 320x240 logical GUI while leaving a margin. */
	public static final int PANEL_WIDTH = 312;
	public static final int PANEL_HEIGHT = 236;

	// ------------------------------------------------------------------ 分区

	/** 右侧竖排页签栏的左边界与宽度。 */
	public static final int TAB_RAIL_X = 260;
	public static final int TAB_RAIL_W = 44;
	/** 页签栏从内容区顶端开始，和内容区同一条基线。 */
	public static final int TAB_RAIL_TOP = SquireScreenHandler.CONTENT_TOP;
	public static final int TAB_RAIL_H = 15;
	public static final int TAB_RAIL_GAP = 1;
	public static final int TAB_RAIL_PITCH = TAB_RAIL_H + TAB_RAIL_GAP;

	/**
	 * 页签栏最多排得下几页。
	 *
	 * <p>再多就得给这一栏做滚动，而不是把页签越缩越窄——横排那次就是这么烂掉的。
	 * {@code PanelLayoutTest} 盯着 {@link #TAB_COUNT} 不越过它，也盯着栏底
	 * 加上 IPN 那条按钮带之后仍然在聊天页脚之上。</p>
	 */
	public static final int TAB_RAIL_CAPACITY = 9;

	/** 状态操作条：跟随 / 待命 / 巡逻 / 更多。占原来页签条那条横带。 */
	public static final int STATE_ROW_Y = 18;
	public static final int STATE_ROW_H = 16;

	/** 中央内容区（不含页签栏）。 */
	private static final int CONTENT_LEFT = 4;
	/** 内容区右边界。页签栏在它右边 6px 开外。 */
	public static final int CONTENT_RIGHT = TAB_RAIL_X - 6;
	private static final int CONTENT_MARGIN = 8;

	/**
	 * 内容区的下边界。<b>任何滚动内容和按钮都不许越过它</b>——底下是聊天页脚。
	 *
	 * <p>越界的控件由 {@link #positionScrollableWidgets()} 整个藏掉（藏掉的控件
	 * 也不再接受点击），文字由 {@code enableScissor} 裁掉。</p>
	 */
	public static final int CONTENT_BOTTOM = 206;

	/** 聊天页脚的上沿。内容区与它之间有一条分隔线。 */
	public static final int FOOTER_TOP = 209;

	// ------------------------------------------------------------------ 页签

	private static final int TAB_ITEMS = 0;
	private static final int TAB_BEHAVIOUR = 1;
	private static final int TAB_PROFESSION = 2;
	private static final int TAB_PROJECT = 3;
	private static final int TAB_PERMISSIONS = 4;
	/** Saved, user-defined shortcuts only; built-in actions live on their owning pages. */
	private static final int TAB_COMMAND = 5;
	private static final String[] TAB_KEYS = {
		"squire.gui.tab.items", "squire.gui.tab.profile",
		"squire.gui.tab.profession", "squire.gui.tab.project",
		"squire.gui.tab.permissions", "squire.gui.tab.command"};

	/**
	 * 现在有几页。
	 *
	 * <p>写成字面量而不是 {@code TAB_KEYS.length}：{@link #TAB_RAIL_BOTTOM} 要经
	 * {@link IpnCompat} 一路喂进 {@code @IPNGuiHint} 的注解值，那里只收编译期常量，
	 * 而数组长度不算。{@code PanelLayoutTest} 盯着这个数和 {@code TAB_KEYS} 对得上。</p>
	 */
	public static final int TAB_COUNT = 6;

	/** 页签数量必须和文案表对得上，否则页签栏会画少一格或多一格。 */
	public static int tabKeyCount() {
		return TAB_KEYS.length;
	}

	/** 页签的文案 key，按画出来的顺序。{@code PanelLayoutTest} 盯着这份导航。 */
	public static String[] tabKeys() {
		return TAB_KEYS.clone();
	}

	/**
	 * 页签栏的下边界。IPN 的排序按钮条要排在它下面，见 {@link IpnCompat}。
	 */
	public static final int TAB_RAIL_BOTTOM =
		TAB_RAIL_TOP + TAB_COUNT * TAB_RAIL_PITCH - TAB_RAIL_GAP;
	/** Leave the first 16 pixels below navigation free for IPN's sorting controls. */
	public static final int RECALL_BUTTON_Y = TAB_RAIL_BOTTOM + 20;
	public static final int EQUIP_BUTTON_Y = RECALL_BUTTON_Y + 22;

	// ------------------------------------------------------------------ 物品页

	/**
	 * 物品页按钮带的 y 与宽度（伙伴背包底色 114 与玩家背包标题 145 之间的空带）。
	 *
	 * <p>宽度从 72 收到 56，是因为这一带现在要放下「整理背包」「看背囊」和两个翻页
	 * 箭头：{@code 44 + 56 + 4 + 56 + 4 + 18 + 2 + 18 = 202}，正好压在内容区右边界
	 * 204 之内。{@code ItemsPageLayoutTest} 盯着这条。</p>
	 */
	public static final int ITEMS_BUTTON_Y = 116;
	public static final int ITEMS_BUTTON_W = 56;
	/** 翻页箭头的宽度。 */
	public static final int ITEMS_PAGER_W = 18;

	// ------------------------------------------------------------------ 版面步长

	/** 普通按钮高度。 */
	private static final int BTN_H = 18;
	/** 紧凑按钮（数量选择器这类）的高度。 */
	private static final int SMALL_BTN_H = 16;
	/** 同一行里两个控件之间的横向间隙。 */
	private static final int GAP = 4;
	/** 一行纯文字占多高。 */
	private static final int LINE_H = 10;
	/** 一条带横线的小标题占多高。 */
	private static final int SECTION_H = 13;
	/** 滚轮一格滚多少。 */
	private static final int SCROLL_STEP = 21;

	// ------------------------------------------------------------------ 配色

	private static final int COLOR_BACKGROUND = 0xFF2B2B2F;
	private static final int COLOR_BORDER = 0xFF6A6A72;
	private static final int COLOR_SLOT = 0xFF14141A;
	private static final int COLOR_SLOT_EDGE = 0xFF4A4A54;
	/** 背包槽的边框：装备列里唯一一个不放装备的格子，得看得出来不一样。 */
	private static final int COLOR_BACKPACK_EDGE = 0xFF8A6A3A;
	private static final int COLOR_SECTION = 0xFF3A3A42;
	private static final int COLOR_CONTENT = 0xFF232329;
	private static final int COLOR_HEADER = 0xFF26262B;
	private static final int COLOR_FOOTER = 0xFF1E1E23;
	private static final int COLOR_CARD = 0xFF2A2A31;
	private static final int COLOR_DIVIDER = 0xFF4A4A54;
	private static final int COLOR_RULE = 0xFF3A3A42;

	/** 语义色：绿＝当前选择，金＝可晋升，灰＝锁定，红＝缺料/危险，黄＝高风险。 */
	private static final int COLOR_ACTIVE = 0xFF3C7A3C;
	private static final int TEXT_PRIMARY = 0xFFE8E8F0;
	private static final int TEXT_LABEL = 0xFFC8C8D0;
	private static final int TEXT_MUTED = 0xFF9090A0;
	private static final int TEXT_OK = 0xFF80D080;
	private static final int TEXT_GOLD = 0xFFD8C070;
	private static final int TEXT_WARN = 0xFFE0C060;
	private static final int TEXT_DANGER = 0xFFE06060;
	private static final int TEXT_LOCK = 0xFF808090;

	// ------------------------------------------------------------------ 状态

	private int tab = TAB_ITEMS;
    private final List<ButtonWidget> rosterButtons = new ArrayList<>();
    private final List<ButtonWidget> navigationButtons = new ArrayList<>();
    private String rosterSignature = "";
    private boolean professionHomeSelected;
    private static final java.util.Map<String,Integer> REMEMBERED_SCROLL = new java.util.HashMap<>();
    private static final java.util.Map<String,Integer> REMEMBERED_TABS = new java.util.HashMap<>();

    private String currentAgentKey() {
        return handler.state().roster().stream().map(dev.squire.server.gui.PanelState::decodeRosterEntry)
            .flatMap(java.util.Optional::stream).filter(e -> e.current()).map(e -> e.agentId()).findFirst().orElse("");
    }
    private void refreshRoster() {
        String signature = handler.state().roster().toString()+handler.inventoryAccessible+handler.bodyAvailable;
        if (signature.equals(rosterSignature)) return;
        rosterSignature = signature;
        if (professionHomeSelected) applyTab();
        handler.setSlotsVisible(tab==TAB_ITEMS && handler.inventoryAccessible);
        rosterButtons.forEach(this::remove); rosterButtons.clear();
        var entries = handler.state().roster();
        for(int i=0;entries.size()>1 && i<Math.min(2,entries.size());i++) {
            var entry = dev.squire.server.gui.PanelState.decodeRosterEntry(entries.get(i)).orElse(null);
            if(entry==null) continue;
            Text label=Text.literal((entry.current()?"\u25b6 ":"")+entry.name()+" \u00b7 ")
                .append(Text.translatable(entry.professionId().isBlank()?"squire.gui.profession.untrained":"squire.gui.profession."+entry.professionId()));
            final java.util.UUID target=java.util.UUID.fromString(entry.agentId());
            var button=ButtonWidget.builder(label,b -> {
                var packet=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                REMEMBERED_SCROLL.put(currentAgentKey()+"/"+tab,pageScroll);
                packet.writeVarInt(handler.syncId);packet.writeUuid(target);
                ClientPlayNetworking.send(SquireScreens.SWITCH_PANEL_PACKET,packet);
            }).dimensions(x+8+i*146,y+1,142,15).build();
            button.active=!entry.current();
            button.visible=true;
            button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(label.copy().append(entry.active()?"":" \u00b7 Offline")));
            rosterButtons.add(addDrawableChild(button));
        }
        var profession=handler.state().profession().profession();
        if(!professionHomeSelected && !currentAgentKey().isEmpty()) {
            professionHomeSelected=true;
            tab=REMEMBERED_TABS.getOrDefault(currentAgentKey(),profession==null?TAB_PROFESSION:TAB_PROJECT);
            pageScroll=REMEMBERED_SCROLL.getOrDefault(currentAgentKey()+"/"+tab,0);
            applyTab();
        }
        if(navigationButtons.size()>TAB_PROJECT) {
            navigationButtons.get(TAB_PROJECT).visible=true;
            navigationButtons.get(TAB_PROFESSION).setMessage(Text.translatable("squire.gui.panel.growth"));
            navigationButtons.get(TAB_PROJECT).setMessage(Text.translatable(guardPanel()?"squire.gui.panel.guard":"squire.gui.panel.engineer"));
        }
    }
	/** 状态条现在显示的是「更多」那一组。 */
	private boolean stateRowExpanded;
	private TextFieldWidget chatField;
	private final List<ClickableWidget> pageWidgets = new ArrayList<>();
	private final Map<ClickableWidget, Integer> pageWidgetBaseY =
		new IdentityHashMap<>();
	private final List<ClickableWidget> stateRowWidgets = new ArrayList<>();
	private int pageScroll;
	private int maxPageScroll;
	private ButtonWidget chatSendButton;
	private boolean confirmingDismiss;
	private final List<ButtonWidget> permissionButtons = new ArrayList<>();
	/** 权限卡对应 TOGGLEABLE_NODES 的下标；下标就是 permissionMask 的位序。 */
	private final List<Integer> permissionIndexShown = new ArrayList<>();
	private final List<ButtonWidget> autonomyButtons = new ArrayList<>();
	private final List<ButtonWidget> projectButtons = new ArrayList<>();
	/** Button ids are kept beside the compact, state-dependent project controls. */
	private final List<Integer> projectButtonIds = new ArrayList<>();
	private final List<ButtonWidget> materialButtons = new ArrayList<>();
	private ButtonWidget materialConfigButton;
	private ButtonWidget forceCancelProjectButton;
	private boolean editingMaterials;
	/** 跟随传送距离按钮：标签上要写着当前值，所以单独拿着它。 */
	private ButtonWidget followDistanceButton;
	/** 打法按钮：标签就是当前档位，每帧按最新状态刷。 */
	private ButtonWidget combatModeButton;
	/** 建这一页时用的那份名字列表，用来发现「服务端刚存了一条」。 */
	private List<String> shortcutNamesShown = List.of();
	/** 建这一页时常用动作那半张长什么样，用来发现「转职了 / 开工了」。 */
	private String commandSignatureShown = "";
	/** 编辑态里选中的动作 id；空串表示还没选。 */
	private String editorEntryId = "";
	/** 编辑态里选中的档位参数。 */
	private String editorArg = "";
	/** 动作选择器里的按钮，和 {@link #pickerEntryIds} 一一对应（画选中框用）。 */
	private final List<ButtonWidget> pickerButtons = new ArrayList<>();
	private final List<String> pickerEntryIds = new ArrayList<>();
	/** 档位选择器里的按钮，和 {@link #variantArgs} 一一对应。 */
	private final List<ButtonWidget> variantButtons = new ArrayList<>();
	private final List<String> variantArgs = new ArrayList<>();
	/** 已经锁住的快捷槽：槽号 → 那把锁。画锁和拒绝理由用的是同一份判定。 */
	private final Map<Integer, dev.squire.server.gui.CommandCatalog.Lock>
		shortcutLocks = new java.util.LinkedHashMap<>();
	/**
	 * 正在编辑第几个快捷槽；-1 表示在看列表。
	 *
	 * <p>点一下槽位就进编辑态，是这一页最大的改动。原来这一页底下只有一个输入框，
	 * 提示写着「名字=要说的话」——玩家看不出等号是干什么的、哪半边是触发词，
	 * 而八个槽位又只能点「执行」。现在槽位本身就是入口。</p>
	 */
	private int editingShortcut = -1;
	private TextFieldWidget shortcutNameField;
	/** Identity editor in the profile content area. */
	private TextFieldWidget nameField;
	private ButtonWidget confirmNameButton;
	private final NameEditState nameEdit = new NameEditState();
	private boolean restoreNameFocus;

	/** Personality reroll confirmation is deliberately one local, reversible step. */
	private boolean confirmingPersonalityReroll;
	private ButtonWidget rerollPersonalityButton;
	private String personalitySignatureShown = "";

	/** 中间那块网格现在显示的是背囊。切换只在客户端发生，见 setShowingBackpack。 */
	private boolean showingBackpack;
	/** 搭这一页时服务端说的背囊格数；变了就得重搭（按钮的有无取决于它）。 */
	private int backpackSlotsShown = -1;

	/**
	 * 正在确认转职到哪个职业；null 表示不在确认态。
	 *
	 * <p>确认是<b>客户端的一步</b>，真正改档案的仍然是那个走服务端校验的按钮。
	 * 这样既挡住了误触，也没有把「他是什么职业」这件事交给客户端决定。</p>
	 */
	private dev.squire.server.profession.SquireProfession confirmingProfession;

	/** 职业页和蓝图参数页上那些「这一级还不会」的按钮，画锁用。 */
	private final List<ButtonWidget> lockedButtons = new ArrayList<>();

	/** 在蓝图参数子页里。和 {@code editingMaterials} 一样是工程页的一个子状态。 */
	private boolean editingDesign;

	// ------------------------------------------------------------------ 版面产物

	/**
	 * 页面搭好的时候<b>顺手记下</b>的一行静态文字。
	 *
	 * <p>小标题、说明、提示原来散在 {@code draw*Page} 里，坐标由人肉和按钮对齐，
	 * 而按钮的坐标又来自另一套行号——两边错开一格就是「文字压在按钮上」，
	 * 仓库里已经这么翻过三次车。现在文字和按钮在同一趟布局里排出来，
	 * 用的是同一个游标，不可能错位。</p>
	 */
	private record PageText(Text text, int left, int top, int color, boolean rule) { }

	/** 页面里的一块底色（卡片、风险色条）。画在控件<b>之下</b>，见 drawBackground。 */
	private record Deco(int left, int top, int right, int bottom, int color) { }

	private final List<PageText> pageTexts = new ArrayList<>();
	private final List<Deco> pageDecos = new ArrayList<>();

	/** 布局游标：下一个元素画在这个 y（面板局部坐标，未计滚动）。 */
	private int layoutY;

	public SquireScreen(SquireScreenHandler handler, PlayerInventory inventory,
			Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = PANEL_WIDTH;
		this.backgroundHeight = PANEL_HEIGHT;
		this.playerInventoryTitleY = SquireScreenHandler.PLAYER_GRID_Y - 11;
	}

	@Override
	protected void init() {
		super.init();
        navigationButtons.clear(); rosterSignature="";
		nameField = null;
		confirmNameButton = null;
		this.titleX = 8;
		this.titleY = 5;

		// 右侧竖排页签栏。位置全部由 TAB_RAIL_* 算出来，加一页不用改任何数。
		for (int i = 0; i < TAB_KEYS.length; i++) {
			final int index = i;
			navigationButtons.add(addDrawableChild(ButtonWidget.builder(Text.translatable(TAB_KEYS[i]),
					b -> selectTab(index))
				.dimensions(x + TAB_RAIL_X, y + TAB_RAIL_TOP + i * TAB_RAIL_PITCH,
					TAB_RAIL_W, TAB_RAIL_H).build()));
		}

		buildStateRow();
        addDrawableChild(ButtonWidget.builder(Text.translatable("squire.gui.panel.recall"),b -> {
            var packet=PacketByteBufs.create();packet.writeVarInt(handler.syncId);
            ClientPlayNetworking.send(SquireScreens.RECALL_PANEL_PACKET,packet);
        }).dimensions(x+TAB_RAIL_X,y+RECALL_BUTTON_Y,TAB_RAIL_W,18).build());

		chatField = new TextFieldWidget(textRenderer, x + 8, y + PANEL_HEIGHT - 22,
			PANEL_WIDTH - 60, 16, Text.translatable("squire.gui.chat.placeholder"));
		chatField.setMaxLength(SquireScreens.MAX_CHAT_LENGTH);
		chatField.setPlaceholder(Text.translatable("squire.gui.chat.hint"));
		addDrawableChild(chatField);

		chatSendButton = addDrawableChild(ButtonWidget.builder(
				Text.translatable("squire.gui.chat.send"), b -> sendChat())
			.dimensions(x + PANEL_WIDTH - 48, y + PANEL_HEIGHT - 23, 40, 18).build());


		applyTab();
	}

	// ------------------------------------------------------------------ 状态条

	/**
	 * 顶部状态条。
	 *
	 * <p>常驻只有三个高频档位；回家 / 设家 / 遣散在「更多」里，点一下整条换过去，
	 * 再点「返回」换回来。<b>不做浮层</b>：浮层要压在内容区的按钮上面，而原版的
	 * 点击是按控件加入顺序命中的，压不住——换一条比让玩家点到底下那个按钮强。</p>
	 */
	private void buildStateRow() {
		stateRowWidgets.forEach(this::remove);
		stateRowWidgets.clear();
		confirmingDismiss = false;

		var actions = SquireActions.ofPage(stateRowExpanded
			? SquireActions.Page.MORE : SquireActions.Page.SIDE);
		int count = actions.size() + 1; // ＋「更多」/「返回」
		int width = stateRowButtonWidth(count);
		int left = 8;
		for (SquireActions.Action action : actions) {
			final int id = action.id();
			ButtonWidget button = ButtonWidget.builder(
					Text.translatable(action.labelKey()), b -> {
						if (id == SquireScreenHandler.BUTTON_DISMISS
								&& !confirmingDismiss) {
							confirmingDismiss = true;
							b.setMessage(Text.translatable(
								"squire.gui.button.dismiss_confirm"));
							return;
						}
						click(id);
						if (action.closesScreen()) {
							close();
						}
					})
				.dimensions(x + left, y + STATE_ROW_Y, width, STATE_ROW_H).build();
			stateRowWidgets.add(addDrawableChild(button));
			left += width + GAP;
		}
		stateRowWidgets.add(addDrawableChild(ButtonWidget.builder(
				Text.translatable(stateRowExpanded
					? "squire.gui.button.more_back" : "squire.gui.button.more"),
				b -> {
					stateRowExpanded = !stateRowExpanded;
					buildStateRow();
				})
			.dimensions(x + left, y + STATE_ROW_Y, width, STATE_ROW_H).build()));
	}

	// ------------------------------------------------------------------ 切页

	private void selectTab(int next) {
		REMEMBERED_SCROLL.put(currentAgentKey()+"/"+tab,pageScroll);
        tab = next;
        if (!currentAgentKey().isEmpty()) REMEMBERED_TABS.put(currentAgentKey(), tab);
		pageScroll = REMEMBERED_SCROLL.getOrDefault(currentAgentKey()+"/"+tab,0);
		editingShortcut = -1; // 切页就退出编辑态，免得回来时停在半截
		editorEntryId = "";
		editorArg = "";
		editingMaterials = false;
		editingDesign = false;
		confirmingProfession = null;
		confirmingPersonalityReroll = false;
		applyTab();
	}

	@Override
	public void handledScreenTick() {
		super.handledScreenTick();
        refreshRoster();
        syncNameEditor();
        if (restoreNameFocus) {
            restoreNameFocus = false;
            if (nameField != null && nameField.visible) {
                setFocused(nameField);
                nameField.setFocused(true);
            }
        }
		// 刚存下的快捷指令要立刻变成一个按钮。状态是服务端推过来的，客户端这边
		// 只有重建这一页才能看到——否则玩家得切一次页签才发现自己存成功了。
		// 编辑态里不重建：那会把玩家正在打的字冲掉。
		if (tab == TAB_COMMAND && editingShortcut < 0
				&& (!shortcutNamesShown.equals(handler.state().shortcuts())
					|| !commandSignatureShown.equals(commandSignature()))) {
			applyTab();
		}
		if (tab == TAB_PROJECT && editingMaterials
				&& handler.state().materialChoices().isEmpty()) {
			editingMaterials = false;
			applyTab();
		}
		// 「看背囊」这个按钮要不要出现，取决于服务端推过来的背囊格数——而界面是在
		// 第一个状态包到达<b>之前</b>搭好的。不重建的话，刚打开面板时那个按钮不存在，
		// 玩家得切一次页签才发现它。背包被摘掉/换成更大的那个也是同一条路。
		if (tab == TAB_ITEMS && backpackSlotsShown != handler.state().backpackSlots()) {
			applyTab();
		}
		// 转职、晋升、训练做完——这三件事都会换掉页面上该有哪些按钮，而它们发生在
		// <b>服务端</b>。不重建的话，玩家点完「确认转职」还会看着两个转职按钮，
		// 以为没生效。职业签名把这几件事压成一个字符串，变了就重建。
		String signature = professionSignature();
		if (!signature.equals(professionShown)) {
			professionShown = signature;
			// 指令页的上半张<b>就是</b>职业的函数：转职、晋升之后必须重排，
			// 否则玩家点了「确认转职」还会看着一份 Lv.0 的动作清单。
			if (tab == TAB_PROFESSION || tab == TAB_PROJECT
					|| (tab == TAB_COMMAND && editingShortcut < 0)) {
				applyTab();
			}
		}
		String projectSignature = projectSignature();
		if (!projectSignature.equals(projectShown)) {
			projectShown = projectSignature;
			if (tab == TAB_PROJECT) {
				applyTab();
			}
		}
		String personalitySignature = personalitySignature();
		if (tab == TAB_BEHAVIOUR
				&& !personalitySignature.equals(personalitySignatureShown)) {
			applyTab();
		}
	}

	/**
	 * 「页面该长什么样」的指纹。
	 *
	 * <p>版面现在是按<b>行数</b>顺次排下来的，所以清单长度也得进指纹：材料从两条
	 * 变成三条而不重排，下面那一整块就会整体错位一行。</p>
	 */
	private String professionSignature() {
		var view = handler.state().profession();
		return view.professionId() + "/" + view.level() + "/" + view.trainingComplete()
			+ "/" + view.canPromote() + "/" + view.materials().size()
			+ "/" + view.next().size() + "/" + view.templateLibrary().size();
	}

	private String professionShown = "";

	/** State that changes which project controls are present or enabled. */
	private String projectSignature() {
		var state = handler.state();
		return state.hasProject() + "/" + state.hasPlacement() + "/"
			+ state.projectState() + "/" + state.blockerCode() + "/"
			+ state.blockedReason() + "/" + state.siteExecutable() + "/"
			+ state.placementBlueprintId() + "/" + state.materialChoices() + "/"
			+ state.stages().size() + "/" + state.materialLines() + "/"
			+ state.siteIssues() + "/" + state.profession().catalogVersion();
	}

	private String projectShown = "";

	private String personalitySignature() {
		return handler.state().traits().toString() + "/"
			+ handler.state().amethystShards() + "/" + confirmingPersonalityReroll;
	}

	/**
	 * 切页：只保留当前页的控件，并把槽位可见性交给 handler。
	 * 两件事必须一起做，否则要么控件泄漏，要么物品泄漏。
	 */
	private void applyTab() {
		restoreNameFocus |= nameField != null && nameField.isFocused();
		setFocused(null);
		nameField = null;
		confirmNameButton = null;
		pageWidgets.forEach(this::remove);
		pageWidgets.clear();
		permissionButtons.clear();
		permissionIndexShown.clear();
		autonomyButtons.clear();
		projectButtons.clear();
		projectButtonIds.clear();
		materialButtons.clear();
		lockedButtons.clear();
		pickerButtons.clear();
		pickerEntryIds.clear();
		variantButtons.clear();
		variantArgs.clear();
		shortcutLocks.clear();
		pageTexts.clear();
		pageDecos.clear();
		materialConfigButton = null;
		forceCancelProjectButton = null;
		promoteButton = null;
		followDistanceButton = null;
		combatModeButton = null;
		shortcutNameField = null;
		rerollPersonalityButton = null;
		pageWidgetBaseY.clear();
		beginPage();
		handler.setSlotsVisible(tab == TAB_ITEMS && handler.inventoryAccessible);
		// 离开物品页时背囊视图必须一起关掉：那两套槽位在同一个位置上，
		// 留着的话回来时会看到"两层格子"。
		handler.setShowingBackpack(tab == TAB_ITEMS && showingBackpack);
		if (tab == TAB_ITEMS) {
			buildItemsPage();
            if(!handler.inventoryAccessible) addNote(Text.translatable("squire.gui.panel.remote_items"),TEXT_WARN);
		} else if (tab == TAB_COMMAND) {
			buildCommandPage();
		} else if (tab == TAB_PERMISSIONS) {
			buildPermissionsPage();
		} else if (tab == TAB_BEHAVIOUR) {
			buildBehaviourPage();
		} else if (tab == TAB_PROFESSION) {
			buildProfessionPage();
		} else if (tab == TAB_PROJECT) {
            if (guardPanel()) {buildGuardDashboard();}
			else if (editingDesign) {
				buildDesignPage();
			} else if (editingMaterials) {
				buildMaterialPage();
			} else {
				buildProjectPage();
                buildRecoveryActions();
			}
		}
		boolean showChat = tab != TAB_ITEMS;
		chatField.visible = showChat;
		chatField.setEditable(showChat);
		if (chatSendButton != null) {
			chatSendButton.visible = showChat;
		}
		captureScrollableLayout();
        if(!handler.bodyAvailable) {
            pageWidgets.forEach(w -> w.active=false);
            stateRowWidgets.forEach(w -> w.active=false);
            chatField.setEditable(false);chatSendButton.active=false;
        } else {stateRowWidgets.forEach(w -> w.active=true);chatSendButton.active=true;}
        if (forceCancelProjectButton != null) forceCancelProjectButton.active = true;
	}

	// ------------------------------------------------------------------ 布局游标

	private void beginPage() {
		layoutY = SquireScreenHandler.CONTENT_TOP + 4;
	}

	/** 取走 {@code height} 像素，返回这一块的顶端。 */
	private int take(int height) {
		int top = layoutY;
		layoutY += height;
		return top;
	}

	private void gap(int height) {
		layoutY += height;
	}

	/** 一条带横线的小标题。横线从文字右边一直画到内容区右缘。 */
	private void addSection(Text label) {
		pageTexts.add(new PageText(label, CONTENT_MARGIN, take(SECTION_H) + 2,
			TEXT_LABEL, true));
	}

	private void addSection(String labelKey) {
		addSection(Text.translatable(labelKey));
	}

	/** 一行说明文字。 */
	private void addNote(Text text, int color) {
		pageTexts.add(new PageText(text, CONTENT_MARGIN, take(LINE_H), color, false));
	}

	/**
	 * 会折行的说明文字。
	 *
	 * <p>同一句话中文二十来个字就到头，英文往往是它的三倍长——按单行排版的说明
	 * 在中文下好好的，切到英文就直接横穿出内容区。折行在<b>排版期</b>做，
	 * 这样占几行是算进游标的，下面的东西不会被挤。</p>
	 */
	private void addWrappedNote(Text text, int color) {
		for (var line : textRenderer.getTextHandler().wrapLines(text, contentWidth(),
				net.minecraft.text.Style.EMPTY)) {
			pageTexts.add(new PageText(Text.literal(line.getString()), CONTENT_MARGIN,
				take(LINE_H), color, false));
		}
	}

	/**
	 * 内容区里可以摆东西的宽度，以及等分成 n 列之后每一列的宽和左边。
	 *
	 * <p>是 {@code static} 的，好让 {@code PanelLayoutTest} 直接拿真正的这三个函数
	 * 去验「最右一列不许伸出内容区」——整数除法余下的那一两个像素在哪一边，
	 * 是这类版面唯一会静默出界的地方。</p>
	 */
	public static int contentWidth() {
		return TAB_RAIL_X - CONTENT_LEFT - CONTENT_MARGIN * 2;
	}

	public static int columnWidth(int count) {
		return (contentWidth() - GAP * (count - 1)) / count;
	}

	public static int columnX(int index, int count) {
		return CONTENT_MARGIN + index * (columnWidth(count) + GAP);
	}

	/** 内容区里一行东西的右边界。 */
	public static int contentRightEdge() {
		return CONTENT_MARGIN + contentWidth();
	}

	/** 状态条上等分成 n 个按钮时每个多宽。整条横跨面板，左右各留 8px。 */
	public static int stateRowButtonWidth(int count) {
		return (PANEL_WIDTH - 16 - GAP * (count - 1)) / count;
	}

	/** 一行等分按钮里的第 {@code index} 个。 */
	private ButtonWidget addRowButton(Text label, int buttonId, int index, int count,
			int top) {
		return addPageWidget(ButtonWidget.builder(label, b -> click(buttonId))
			.dimensions(x + columnX(index, count), y + top, columnWidth(count), BTN_H)
			.build());
	}

	private <T extends ClickableWidget> T addPageWidget(T widget) {
		pageWidgets.add(addDrawableChild(widget));
		return widget;
	}

	private void addCard(int left, int top, int right, int bottom, int color) {
		pageDecos.add(new Deco(left, top, right, bottom, color));
	}

	// ------------------------------------------------------------------ 滚动


	private void captureScrollableLayout() {
		if (tab == TAB_ITEMS) {
			pageScroll = 0;
			maxPageScroll = 0;
			return;
		}
		int contentBottom = layoutY + 4;
		for (ClickableWidget widget : pageWidgets) {
			pageWidgetBaseY.put(widget, widget.getY());
			contentBottom = Math.max(contentBottom,
				widget.getY() - y + widget.getHeight() + 4);
		}
		maxPageScroll = PanelLayout.scrollLimit(contentBottom, CONTENT_BOTTOM);
		pageScroll = Math.min(pageScroll, maxPageScroll);
		positionScrollableWidgets();
	}

	private void positionScrollableWidgets() {
		int top = y + SquireScreenHandler.CONTENT_TOP;
		int bottom = y + CONTENT_BOTTOM;
		for (ClickableWidget widget : pageWidgets) {
			Integer base = pageWidgetBaseY.get(widget);
			if (base == null) {
				continue;
			}
			widget.setY(base - pageScroll);
			widget.visible = widget.getY() >= top
				&& widget.getY() + widget.getHeight() <= bottom;
            if (!widget.visible && widget.isFocused()) setFocused(null);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (tab != TAB_ITEMS && maxPageScroll > 0
				&& mouseX >= x + CONTENT_LEFT && mouseX < x + CONTENT_RIGHT
				&& mouseY >= y + SquireScreenHandler.CONTENT_TOP
				&& mouseY < y + CONTENT_BOTTOM) {
			int next = PanelLayout.scrollBy(pageScroll,
				-(int) Math.signum(amount), SCROLL_STEP, maxPageScroll);
			if (next != pageScroll) {
				pageScroll = next;
				positionScrollableWidgets();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void removed() {
		// 关界面时把槽位恢复可见：handler 实例可能被复用，别把状态带出去。
		handler.setSlotsVisible(true);
		handler.setShowingBackpack(false);
		super.removed();
	}

	// ------------------------------------------------------------------ 物品页

	/**
	 * 物品页的按钮，目前只有「整理背包」和「看背囊」。
	 *
	 * <p>不走等分网格——那套是给纯按钮页用的，物品页的版面由槽位决定。这里固定摆在
	 * 伙伴背包和玩家背包之间那条空带上：伙伴格子的底色画到 {@code y=114}，
	 * 玩家背包的标题在 {@code y=145}，中间正好塞得下一个 18 高的按钮。</p>
	 */
	private void buildItemsPage() {
        if (guardPanel()) {
            var equip = addPageWidget(ButtonWidget.builder(Text.translatable("squire.gui.items.equip"),
                b -> click(SquireScreenHandler.BUTTON_AUTO_EQUIP_BEST_ARMOR))
                .dimensions(x + TAB_RAIL_X, y + EQUIP_BUTTON_Y, TAB_RAIL_W, BTN_H).build());
            equip.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.translatable("squire.gui.button.auto_equip_best_armor")));
        }
		int index = 0;
		for (SquireActions.Action action : SquireActions.ofPage(SquireActions.Page.ITEMS)) {
			final int id = action.id();
			addPageWidget(ButtonWidget.builder(
					Text.translatable(action.labelKey()), b -> click(id))
				.dimensions(x + SquireScreenHandler.GRID_X
						+ index * (ITEMS_BUTTON_W + 4),
					y + ITEMS_BUTTON_Y, ITEMS_BUTTON_W, BTN_H)
				.build());
			index++;
		}
		backpackSlotsShown = handler.state().backpackSlots();
		// 背囊：背着背包才有这个按钮——没有背包的人不该看到一个点了没反应的按钮。
		if (handler.state().backpackSlots() <= 0) {
			showingBackpack = false;
			handler.setShowingBackpack(false);
			return;
		}
		int toggleX = x + SquireScreenHandler.GRID_X + index * (ITEMS_BUTTON_W + 4);
		addPageWidget(ButtonWidget.builder(
				Text.translatable(showingBackpack
					? "squire.gui.button.show_own_bag" : "squire.gui.button.show_backpack"),
				b -> toggleBackpackView())
			.dimensions(toggleX, y + ITEMS_BUTTON_Y, ITEMS_BUTTON_W, BTN_H).build());
		// 翻页只在装不下一页时才出现（27/54 格的背包一页就够）。
		if (showingBackpack && handler.state().backpackSlots()
				> dev.squire.server.gui.AvatarBackpackContentsInventory.PAGE_SIZE) {
			int pagerX = toggleX + ITEMS_BUTTON_W + 4;
			addPageWidget(ButtonWidget.builder(Text.literal("◀"),
					b -> click(SquireScreenHandler.BUTTON_BACKPACK_PREV))
				.dimensions(pagerX, y + ITEMS_BUTTON_Y, ITEMS_PAGER_W, BTN_H).build());
			addPageWidget(ButtonWidget.builder(Text.literal("▶"),
					b -> click(SquireScreenHandler.BUTTON_BACKPACK_NEXT))
				.dimensions(pagerX + ITEMS_PAGER_W + 2, y + ITEMS_BUTTON_Y,
					ITEMS_PAGER_W, BTN_H).build());
		}
	}

	/**
	 * 切换中间那块网格显示谁。
	 *
	 * <p>纯客户端动作：服务端两套槽位始终有效，客户端点哪一格就送哪个下标过来，
	 * 两边的槽位列表是同一份，所以不会错位（和页签切换是同一套办法）。</p>
	 */
	private void toggleBackpackView() {
		showingBackpack = !showingBackpack;
		handler.setShowingBackpack(showingBackpack);
		applyTab();
	}

	// ------------------------------------------------------------------ 指令页

	/** Custom shortcuts and their editor; no duplicate built-in action dashboard. */
	private void buildCommandPage() {
		shortcutNamesShown = List.copyOf(handler.state().shortcuts());
		commandSignatureShown = commandSignature();
		if (editingShortcut >= 0) {
			buildShortcutEditor();
			return;
		}
		addWrappedNote(Text.translatable("squire.gui.shortcuts.purpose"), TEXT_MUTED);
		gap(4);
		buildShortcutGrid();
	}

	/** Refresh shortcut locks when profession or execution prerequisites change. */
	private String commandSignature() {
		var context = dev.squire.server.gui.CommandCatalog.Context.of(handler.state());
		StringBuilder out = new StringBuilder();
		for (var entry : dev.squire.server.gui.CommandCatalog.visible(context)) {
			out.append(entry.id()).append(',');
		}
		var next = dev.squire.server.gui.CommandCatalog.nextUnlock(context);
		return out.append('|').append(next == null ? "" : next.id()).toString();
	}

	/**
	 * 说明在左、一排小格子在右。同一动作有多个合法档位时在这里紧凑显示。
	 *
	 * <p>格子宽度<b>按最长的那个标签算</b>，不是写死的 34px；否则较长的本地化文案
	 * 会直接糊出格子。整条放不下就把说明单独占一行、下面一行等分，
	 * 两种语言都不会溢出。</p>
	 */
	private void addCompactEntry(dev.squire.server.gui.CommandCatalog.Entry entry) {
		var variants = entry.variants();
		Text label = Text.translatable(entry.labelKey());
		int cellWidth = 34;
		for (var variant : variants) {
			cellWidth = Math.max(cellWidth,
				textRenderer.getWidth(Text.translatable(variant.labelKey())) + 8);
		}
		int stripWidth = variants.size() * cellWidth + (variants.size() - 1) * 2;
		boolean sameLine = textRenderer.getWidth(label) + 8 + stripWidth
			<= contentWidth();
		if (!sameLine) {
			addNote(label, TEXT_LABEL);
		}
		int top = take(SMALL_BTN_H + 3);
		if (sameLine) {
			pageTexts.add(new PageText(label, CONTENT_MARGIN, top + 4, TEXT_LABEL, false));
		}
		for (int i = 0; i < variants.size(); i++) {
			final int id = variants.get(i).actionId();
			int left = sameLine
				? CONTENT_MARGIN + contentWidth() - stripWidth + i * (cellWidth + 2)
				: columnX(i, variants.size());
			int width = sameLine ? cellWidth : columnWidth(variants.size());
			addPageWidget(ButtonWidget.builder(
					Text.translatable(variants.get(i).labelKey()), b -> click(id))
				.dimensions(x + left, y + top, width, SMALL_BTN_H).build());
		}
	}

	/**
	 * 下半张：八格快捷，2×4。
	 *
	 * <p>空槽<b>只有一件事</b>可做——原来它同时摆着「新建」「✎」「✕」和一行
	 * 「还没有内容」，四个东西说同一句话，其中两个还是灰的。已存的一条显示名字、
	 * 一行「他会做什么」、改和删。失效的那条<b>留着</b>，显示成锁着的：删掉玩家
	 * 自己攒的东西，比让他看到一把锁糟糕得多。</p>
	 */
	private void buildShortcutGrid() {
		addSection("squire.gui.command.shortcuts");
		shortcutGridY = layoutY;
		var state = handler.state();
		var context = dev.squire.server.gui.CommandCatalog.Context.of(state);
		var names = shortcutNamesShown;
		int slots = dev.squire.server.shortcut.ShortcutStore.MAX_PER_PLAYER;
		int cardWidth = columnWidth(2);
		int narrow = 14;
		int runWidth = cardWidth - (narrow + 2) * 2;
		for (int slot = 0; slot < slots; slot++) {
			int column = slot % 2;
			if (column == 0) {
				shortcutRowTop = take(SHORTCUT_CARD_H + 3);
			}
			int left = columnX(column, 2);
			int top = shortcutRowTop;
			final int index = slot;
			if (slot >= names.size()) {
				addPageWidget(ButtonWidget.builder(
						Text.translatable("squire.gui.button.shortcut_empty"),
						b -> editShortcut(index))
					.dimensions(x + left, y + top, cardWidth, BTN_H).build());
				continue;
			}
			addCard(left, top, left + cardWidth, top + SHORTCUT_CARD_H, COLOR_CARD);
			var entry = dev.squire.server.gui.CommandCatalog.byId(
				state.shortcutEntryId(slot));
			var lock = entry == null ? null
				: dev.squire.server.gui.CommandCatalog.lockOf(entry, context);
			if (lock != null) {
				shortcutLocks.put(slot, lock);
			}
			SquireActions.Action run = SquireActions.byId(
				SquireScreenHandler.BUTTON_SHORTCUT_RUN_BASE + slot);
			// 锁着的按钮<b>照样能按</b>：按下去由服务端说出理由，而不是让玩家
			// 对着一个灰按钮猜。真正的闸在服务端，客户端灰不灰都绕不过去。
			addPageWidget(ButtonWidget.builder(
					Text.literal((lock == null ? "" : "🔒 ") + names.get(slot))
						.styled(style -> style.withColor(
							(lock == null ? TEXT_PRIMARY : TEXT_LOCK) & 0xFFFFFF)),
					run == null ? b -> { } : b -> click(run.id()))
				.dimensions(x + left, y + top, runWidth, BTN_H).build());
			addPageWidget(ButtonWidget.builder(Text.literal("✎"),
					b -> editShortcut(index))
				.dimensions(x + left + runWidth + 2, y + top, narrow, BTN_H).build());
			SquireActions.Action delete = SquireActions.byId(
				SquireScreenHandler.BUTTON_SHORTCUT_DELETE_BASE + slot);
			addPageWidget(ButtonWidget.builder(Text.literal("✕"),
					delete == null ? b -> { } : b -> click(delete.id()))
				.dimensions(x + left + runWidth + narrow + 4, y + top, narrow, BTN_H)
				.build());
		}
	}

	// ------------------------------------------------------------------ 权限页

	/**
	 * 权限页：三组两列的权限卡。
	 *
	 * <p>原来是十三个等宽灰按钮铺在半张页面上，右半边全是空的，而「跟随」和
	 * 「世界编辑」长得一模一样——后者一次能改一大片地形，还没有撤销。现在按
	 * <b>影响范围</b>分组，卡片按风险上色：普通白、高风险黄、危险红，
	 * 已授权的加一圈绿框。分组和风险都来自动作表，客户端不自己判。</p>
	 */
	private void buildPermissionsPage() {
		addWrappedNote(Text.translatable("squire.gui.permissions.legend"), TEXT_MUTED);
		gap(2);
		var perms = SquireActions.ofPage(SquireActions.Page.PERMISSION);
		for (SquireActions.PermissionGroup group : SquireActions.PERMISSION_GROUPS) {
			addSection(group.labelKey());
			int column = 0;
			int top = 0;
			for (String node : group.nodes()) {
				if (column == 0) {
					top = take(SMALL_BTN_H + 2);
				}
				SquireActions.Action action = null;
				for (SquireActions.Action candidate : perms) {
					if (node.equals(candidate.node())) {
						action = candidate;
						break;
					}
				}
				int bit = SquireScreenHandler.TOGGLEABLE_NODES.indexOf(node);
				if (action == null || bit < 0) {
					continue; // 分组里写了一个表里没有的节点：宁可少画，也不画个假开关
				}
				final int id = action.id();
				int width = columnWidth(2);
				ButtonWidget button = addPageWidget(ButtonWidget.builder(
						Text.translatable(action.labelKey()), b -> click(id))
					.dimensions(x + columnX(column, 2), y + top, width, SMALL_BTN_H)
					.build());
				// 风险色条：一眼看出这一格属于哪一档，不必去读颜色很淡的文字。
				addCard(columnX(column, 2), top, columnX(column, 2) + 2,
					top + SMALL_BTN_H, riskBarColor(SquireActions.riskOf(node)));
				// hover 说明。风险分级本身只是颜色，颜色不解释「为什么危险」，
				// 也不解释「授权之后是不是就随便他了」——那两句必须写出来。
				button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
					Text.translatable(SquireActions.nodeLabelKey(node))
						.append("\n")
						.append(Text.translatable(SquireActions.riskTooltipKey(
							SquireActions.riskOf(node))))));
				permissionButtons.add(button);
				permissionIndexShown.add(bit);
				column = (column + 1) % 2;
			}
			gap(4);
		}
	}

	/** 卡片左边那条色条。普通权限用一条不抢眼的灰，风险的才亮起来。 */
	private static int riskBarColor(SquireActions.Risk risk) {
		return switch (risk) {
			case DANGER -> TEXT_DANGER;
			case HIGH -> TEXT_WARN;
			default -> COLOR_DIVIDER;
		};
	}

	/**
	 * 卡片上文字的颜色。
	 *
	 * <p>和色条<b>不是</b>同一个函数：普通权限的色条是深灰，文字要是也用那个色，
	 * 十三项里有五项直接读不出来了。风险两档才让文字跟着变色。</p>
	 */
	private static int riskTextColor(SquireActions.Risk risk) {
		return switch (risk) {
			case DANGER -> TEXT_DANGER;
			case HIGH -> TEXT_WARN;
			default -> 0xFFD8D8E0;
		};
	}

	// ------------------------------------------------------------------ 行为页

	/**
	 * 行为页（页签原来叫「随从」）。
	 *
	 * <p>改名是因为这一页从来就不是「他是谁」：它调的是<b>他在你不开口的时候做多少事</b>、
	 * 跟多远、用什么打法，以及性格养成。职业、等级、能力清单都在职业页，这里不再抄一份——
	 * 抄一份的代价是两页说的话迟早不一样。</p>
	 */
	private void buildBehaviourPage() {
		buildNameEditor();
		addSection("squire.gui.profile.autonomy");
		int top = take(BTN_H + 3);
		var levels = dev.squire.server.profile.AutonomyLevel.values();
		var autonomyActions = new ArrayList<SquireActions.Action>();
		for (SquireActions.Action action : SquireActions.ofPage(
				SquireActions.Page.PROFILE)) {
			if (action.id() >= SquireScreenHandler.BUTTON_AUTONOMY_BASE
					&& action.id() < SquireScreenHandler.BUTTON_AUTONOMY_BASE
						+ levels.length) {
				autonomyActions.add(action);
			}
		}
		for (int i = 0; i < autonomyActions.size(); i++) {
			SquireActions.Action action = autonomyActions.get(i);
			autonomyButtons.add(addRowButton(Text.translatable(action.labelKey()),
				action.id(), i, autonomyActions.size(), top));
		}
		autonomyNoteY = take(LINE_H * AUTONOMY_NOTE_LINES + 4);

		addSection("squire.gui.behaviour.tuning");
		top = take(BTN_H + 3);
		for (SquireActions.Action action : SquireActions.ofPage(
				SquireActions.Page.PROFILE)) {
			if (action.id() == SquireScreenHandler.BUTTON_FOLLOW_DISTANCE) {
				followDistanceButton = addRowButton(followDistanceLabel(), action.id(),
					0, 2, top);
			} else if (action.id() == SquireScreenHandler.BUTTON_COMBAT_MODE) {
				combatModeButton = addRowButton(combatModeLabel(), action.id(),
					1, 2, top);
			}
		}
		addWrappedNote(Text.translatable("squire.gui.behaviour.teleport_note"),
			TEXT_MUTED);
		gap(2);

		addSection("squire.gui.profile.patrol");
		addCompactEntry(dev.squire.server.gui.CommandCatalog.byId("patrol.points"));
		gap(4);
		addSection("squire.gui.personality.title");
		personalitySignatureShown = personalitySignature();
		var traits = new ArrayList<dev.squire.server.profile.Trait>();
		for (String id : handler.state().traits()) {
			var trait = dev.squire.server.profile.Trait.byId(id);
			if (trait != null) {
				traits.add(trait);
			}
		}
		if (traits.isEmpty()) {
			addNote(Text.translatable("squire.gui.profile.no_traits"), TEXT_MUTED);
		} else {
			for (var trait : traits) {
				buildPersonalityCard(trait);
			}
		}
		gap(2);
		buildPersonalityReroll();
	}

	/** 自主档位说明画在哪、留几行。档位随时会变，文字每帧现取，所以位置排死。 */
	private int autonomyNoteY;
	private static final int AUTONOMY_NOTE_LINES = 2;
	private void buildPersonalityCard(dev.squire.server.profile.Trait trait) {
		int top = layoutY;
		int at = top + 4;
		pageTexts.add(new PageText(Text.translatable(trait.nameKey()),
			CONTENT_MARGIN + 5, at, TEXT_GOLD, false));
		at += LINE_H + 2;
		int innerWidth = contentWidth() - 12;
		for (var line : textRenderer.getTextHandler().wrapLines(
				Text.translatable(trait.descriptionKey()), innerWidth,
				net.minecraft.text.Style.EMPTY)) {
			pageTexts.add(new PageText(Text.literal(line.getString()),
				CONTENT_MARGIN + 5, at, TEXT_PRIMARY, false));
			at += LINE_H;
		}
		at += 1;
		for (int i = 0; i < trait.effectLineCount(); i++) {
			Text effect = Text.literal("• ").append(Text.translatable(trait.effectKey(i)));
			for (var line : textRenderer.getTextHandler().wrapLines(effect, innerWidth,
					net.minecraft.text.Style.EMPTY)) {
				pageTexts.add(new PageText(Text.literal(line.getString()),
					CONTENT_MARGIN + 5, at, TEXT_OK, false));
				at += LINE_H;
			}
		}
		int bottom = at + 4;
		addCard(CONTENT_MARGIN, top, CONTENT_MARGIN + contentWidth(), bottom,
			COLOR_CARD);
		layoutY = bottom + 4;
	}

	private void buildPersonalityReroll() {
		int have = handler.state().amethystShards();
		int need = dev.squire.server.runtime.SquirePersonalityService.REROLL_COST;
		Text itemName = Text.translatable(
			dev.squire.server.runtime.SquirePersonalityService.rerollItem()
				.getTranslationKey());
		addNote(Text.translatable("squire.gui.personality.cost", itemName, have, need),
			have >= need ? TEXT_LABEL : TEXT_DANGER);
		if (have < need) {
			addNote(Text.translatable("squire.gui.personality.missing", itemName,
				need - have), TEXT_DANGER);
		}
		if (confirmingPersonalityReroll) {
			addWrappedNote(Text.translatable("squire.gui.personality.confirm"), TEXT_WARN);
			int top = take(BTN_H + 3);
			ButtonWidget confirm = addPageWidget(ButtonWidget.builder(
					Text.translatable("squire.gui.personality.confirm_button"),
					b -> confirmPersonalityReroll())
				.dimensions(x + columnX(0, 2), y + top, columnWidth(2), BTN_H)
				.build());
			confirm.active = have >= need;
			addPageWidget(ButtonWidget.builder(
					Text.translatable("squire.gui.personality.cancel_button"), b -> {
						confirmingPersonalityReroll = false;
						applyTab();
					})
				.dimensions(x + columnX(1, 2), y + top, columnWidth(2), BTN_H)
				.build());
			return;
		}
		int top = take(BTN_H + 3);
		rerollPersonalityButton = addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.personality.reroll"), b -> {
					confirmingPersonalityReroll = true;
					applyTab();
				})
			.dimensions(x + CONTENT_MARGIN, y + top, contentWidth(), BTN_H).build());
		rerollPersonalityButton.active = have >= need;
	}

	private Text followDistanceLabel() {
		return Text.translatable("squire.gui.button.follow_distance",
			handler.state().followTeleport());
	}

	/** 打法按钮的标签就是当前档位本身，玩家不用点开才知道现在是什么模式。 */
	private Text combatModeLabel() {
		var modes = dev.squire.server.combat.CombatStyle.Mode.values();
		int index = Math.max(0, Math.min(modes.length - 1,
			handler.state().combatMode()));
		return Text.translatable("squire.gui.combat_mode."
			+ modes[index].name().toLowerCase(java.util.Locale.ROOT));
	}

	// ------------------------------------------------------------------ 职业页

	/** 职业页上那些位置要在画的时候用到，全部在搭页面的时候一次算好。 */
	private int professionHeaderY;
	private int professionBarY;
	private int professionXpY;
	private int professionPromoteY;
	private int professionMaterialsY;
	private int professionNextY;
	private int professionPipsY;
	private int professionRouteY;
	private int professionExtraY;
	private int trainingBarY;
	private int trainingListY;
	/** 晋升按钮。条件全满足时要给它加一圈金框，所以单独拿着。 */
	private ButtonWidget promoteButton;
	/** 晋升按钮的宽度。右边那段状态文字按它起算，两处必须是同一个数。 */
	private static final int PROMOTE_BUTTON_W = 104;

	/**
	 * 职业页。同一个页面在三种状态下长得不一样：
	 *
	 * <ul>
	 *   <li><b>Lv.0</b>——训练清单 + 两个转职按钮（训练没做完就灰着）；</li>
	 *   <li><b>确认态</b>——某个职业的说明 + 确认/取消；</li>
	 *   <li><b>已转职</b>——等级、经验条、晋升、成长路线、以及本职业专属的那一块。</li>
	 * </ul>
	 */
	private void buildProfessionPage() {
		var view = handler.state().profession();

		if (confirmingProfession != null) {
			buildProfessionConfirm();
			return;
		}
		if (!view.hasProfession()) {
			buildTrainingPage(view);
			return;
		}
		professionHeaderY = take(LINE_H + 2);
		professionBarY = take(7);
		professionXpY = take(LINE_H + 3);

		if (!view.isMaxLevel()) {
			professionPromoteY = take(BTN_H + 5);
			for (SquireActions.Action action : SquireActions.ofPage(
					SquireActions.Page.PROFESSION)) {
				if (action.id() != SquireScreenHandler.BUTTON_PROFESSION_PROMOTE) {
					continue;
				}
				ButtonWidget promote = addPageWidget(ButtonWidget.builder(
						Text.translatable(action.labelKey())
							.append(" Lv." + (view.level() + 1)),
						b -> click(action.id()))
					.dimensions(x + CONTENT_MARGIN, y + professionPromoteY,
						PROMOTE_BUTTON_W, BTN_H)
					.build());
				promote.active = view.canPromote();
				promoteButton = promote;
			}
			addSection("squire.gui.profession.materials");
			professionMaterialsY = take(Math.max(1, view.materials().size()) * LINE_H + 3);
		} else {
			professionPromoteY = -1;
			professionMaterialsY = -1;
		}

		if (!view.next().isEmpty()) {
			addSection(Text.translatable("squire.gui.profession.next_unlock",
				view.level() + 1));
			professionNextY = take(view.next().size() * LINE_H + 3);
		} else {
			professionNextY = -1;
		}

		addSection("squire.gui.profession.route");
		professionPipsY = take(11);
		int abilities = dev.squire.server.profession.ProfessionAbility
			.of(view.profession()).size();
		professionRouteY = take((abilities + 1) / 2 * 11 + 4);

		buildProfessionExtra(view);
	}

	/** Guard work page: stance, combat, recovery and supply status. */
    private void buildGuardDashboard() {
        addSection("squire.gui.panel.guard");
        var view=handler.state().profession();
        addSection(view.can(dev.squire.server.profession.ProfessionAbility.GUARD_COMBAT_STANCE)
            ? Text.translatable("squire.gui.profession.stance")
            : Text.translatable("squire.gui.profession.stance_locked",
                dev.squire.server.profession.ProfessionAbility.GUARD_COMBAT_STANCE.unlockLevel()));
        int top=take(BTN_H+4);
        var stances=dev.squire.server.profession.CombatStance.values();
        for(var stance:stances) {
            var action=SquireActions.byId(SquireScreenHandler.BUTTON_STANCE_BASE+stance.ordinal());
            var button=addRowButton(Text.translatable(action.labelKey()),action.id(),stance.ordinal(),stances.length,top);
            button.active=view.can(dev.squire.server.profession.ProfessionAbility.GUARD_COMBAT_STANCE);
            if (!button.active) lockedButtons.add(button);
        }
        addSection("squire.gui.work.combat");
        addActionRows(new int[]{SquireScreenHandler.BUTTON_GUARD_ATTACK,
            SquireScreenHandler.BUTTON_TASK_STOP, SquireScreenHandler.BUTTON_GUARD_START,
            SquireScreenHandler.BUTTON_GUARD_STOP});
        buildRecoveryActions();
        addSection("squire.gui.profession.supplies");
        addWrappedNote(Text.translatable("squire.gui.profession.supply_counts",view.food(),view.potions(),view.arrows(),view.backupWeapons()),TEXT_LABEL);
        addWrappedNote(Text.translatable("squire.gui.work.supplies_hint"),TEXT_MUTED);
    }
    private void addActionRows(int[] actions) {
        for (int i = 0; i < actions.length; i += 2) {
            int top = take(BTN_H + 4);
            for (int j = 0; j < 2 && i + j < actions.length; j++) {
                var action = SquireActions.byId(actions[i + j]);
                addRowButton(Text.translatable(action.labelKey()), action.id(), j, 2, top);
            }
        }
    }

    private void buildRecoveryActions() {
        gap(4);
        addSection("squire.gui.work.recovery");
        addActionRows(new int[]{SquireScreenHandler.BUTTON_AID_OWNER, SquireScreenHandler.BUTTON_HEAL_SELF});
    }

    private boolean guardPanel() {return handler.state().profession().profession()==dev.squire.server.profession.SquireProfession.GUARD;}

	private void buildProfessionExtra(dev.squire.server.gui.ProfessionView view) {
        addSection("squire.gui.profile.capability_summary");
        professionExtraY = take(LINE_H + 3);
    }

	/** Lv.0：训练清单 + 两个转职按钮。 */
	private void buildTrainingPage(dev.squire.server.gui.ProfessionView view) {
		professionHeaderY = take(LINE_H + 2);
		trainingBarY = take(7);
		professionXpY = take(LINE_H + 4);
		addSection("squire.gui.profession.training_list");
		trainingListY = take(dev.squire.server.profession.TrainingMilestone.required()
			.size() * LINE_H + 4);
		professionExtraY = take(LINE_H + 4);

		int top = take(BTN_H);
		int chooseBase = SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE;
		var professions = dev.squire.server.profession.SquireProfession.values();
		for (SquireActions.Action action : SquireActions.ofPage(
				SquireActions.Page.PROFESSION)) {
			int id = action.id();
			if (id < chooseBase || id >= chooseBase + professions.length) {
				continue;
			}
			final var target = professions[id - chooseBase];
			boolean taken = handler.state().roster().stream()
				.map(PanelState::decodeRosterEntry).flatMap(java.util.Optional::stream)
				.anyMatch(entry -> !entry.current()
					&& target.id().equals(entry.professionId()));
			// 点转职<b>不直接发包</b>：先进确认态。这一步纯客户端，服务端那一侧
			// 仍然会把条件重新验一遍。
			ButtonWidget button = addPageWidget(ButtonWidget.builder(
					Text.translatable(action.labelKey()), b -> {
						confirmingProfession = target;
						applyTab();
					})
				.dimensions(x + columnX(id - chooseBase, professions.length), y + top,
					columnWidth(professions.length), BTN_H).build());
			button.active = view.trainingComplete() && !taken;
			if (!button.active) {
				lockedButtons.add(button);
			}
		}
	}

	/** 确认页：说清楚这条成长线是什么，再给确认/取消。 */
	private void buildProfessionConfirm() {
		var target = confirmingProfession;
		professionHeaderY = take(LINE_H + 4);
		int rows = 0;
		for (int level : new int[] {1, 5, 10}) {
			rows += dev.squire.server.profession.ProfessionAbility
				.unlockedAt(target, level).size();
		}
		professionRouteY = take(rows * 21 + 4);
		professionExtraY = take(LINE_H * 2 + 4);
		int top = take(BTN_H);
		addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.button.confirm_choice"),
				b -> {
					confirmingProfession = null;
					click(SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE
						+ target.ordinal());
				})
			.dimensions(x + columnX(0, 2), y + top, columnWidth(2), BTN_H).build());
		addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.button.cancel_choice"),
				b -> {
					confirmingProfession = null;
					applyTab();
				})
			.dimensions(x + columnX(1, 2), y + top, columnWidth(2), BTN_H).build());
	}

	// ------------------------------------------------------------------ 工程页

	/** 阻塞原因最多画几行。版面预留的和真正画出来的必须是同一个数。 */
	private static final int BLOCKED_REASON_LINES = 3;

	private int projectStatusY;
	private int projectStageY;
	private int projectLibraryY;
	private int projectLibraryRows;

	/**
	 * 工程页 = 蓝图库。
	 *
	 * <p>原来这一页是「五个模板大按钮 + 三行文字」，一半是空的，而且看不出
	 * 「他现在在盖什么」和「我还能盖什么」是两件事。现在三段：<b>当前工程</b>
	 * （有就是进度和控制，没有就一行字）、<b>快速开工</b>、<b>模板库</b>
	 * （含没解锁的，挂着锁和等级要求——只列已解锁的那几个，玩家不会知道后面还有）。</p>
	 */
	private void buildProjectPage() {
		var state = handler.state();
		projectShown = projectSignature();
		// 有工程/有预览时这一页不画模板库；行数留着旧值的话，工程刚开工那一帧
		// 会在进度上面多出几块空卡片底色。
		projectLibraryRows = 0;
		addSection("squire.gui.project.current");

		if (state.hasProject()) {
			buildForceCancelProjectButton();
			projectStatusY = take(LINE_H + 2);
			projectStageY = take(state.stages().size() * LINE_H + 4);
			if (!state.blockedReason().isEmpty()) {
				// 阻塞原因是一段变长的人话（可能列着一串缺料）。预留三行，
				// 画的时候也只画三行——多出来的用省略号收住，绝不铺到按钮上。
				gap(LINE_H * BLOCKED_REASON_LINES + 2);
			}
			if ("MATERIALS_MISSING".equals(state.blockerCode())
					&& !state.materialLines().isEmpty()) {
				gap(LINE_H * state.materialLines().size() + 2);
			}
			boolean needsRetry = !state.blockerCode().isEmpty()
				|| state.stages().stream().anyMatch(line -> line.endsWith(":BLOCKED")
					|| line.endsWith(":FAILED"));
			int primaryId;
			Text primaryLabel;
			if (needsRetry) {
				primaryId = SquireScreenHandler.BUTTON_PROJECT_RESUME;
				primaryLabel = Text.translatable("MATERIALS_MISSING".equals(
					state.blockerCode()) ? "squire.gui.button.project_retry_materials"
						: "squire.gui.button.project_retry");
			} else if ("RUNNING".equals(state.projectState())) {
				primaryId = SquireScreenHandler.BUTTON_PROJECT_PAUSE;
				primaryLabel = Text.translatable("squire.gui.button.project_pause");
			} else {
				primaryId = SquireScreenHandler.BUTTON_PROJECT_RESUME;
				primaryLabel = Text.translatable("squire.gui.button.project_resume");
			}
			int top = take(BTN_H + 4);
			addProjectButton(primaryLabel, primaryId, 0, 2, top);
			addProjectButton(Text.translatable("squire.gui.button.project_cancel"),
				SquireScreenHandler.BUTTON_PROJECT_CANCEL, 1, 2, top);
			return;
		}

		if (state.hasPlacement()) {
			projectStatusY = take(LINE_H
				* (2 + Math.max(1, state.siteIssues().size())) + 4);
			if (dev.squire.server.blueprint.TerrainLeveling.parse(state.placementBlueprintId()).isPresent()) {
				buildTerrainControls();
				return;
			}
			addSection("squire.gui.project.adjust");
			addWrappedNote(Text.translatable("squire.gui.project.direction_hint"),
				TEXT_MUTED);
			int top = take(BTN_H + 3);
			// A small D-pad: forward is physically above back, and left/right are
			// physically beside it. Rotate stays adjacent without pretending to be a
			// direction. This is much easier to read than two arbitrary button rows.
			addProjectButton(Text.translatable("squire.gui.button.blueprint_forward"),
				SquireScreenHandler.BUTTON_BLUEPRINT_FORWARD, 1, 3, top);
			addProjectButton(Text.translatable("squire.gui.button.blueprint_rotate"),
				SquireScreenHandler.BUTTON_BLUEPRINT_ROTATE, 2, 3, top);
			top = take(BTN_H + 4);
			addProjectButton(Text.translatable("squire.gui.button.blueprint_left"),
				SquireScreenHandler.BUTTON_BLUEPRINT_LEFT, 0, 3, top);
			addProjectButton(Text.translatable("squire.gui.button.blueprint_back"),
				SquireScreenHandler.BUTTON_BLUEPRINT_BACK, 1, 3, top);
			addProjectButton(Text.translatable("squire.gui.button.blueprint_right"),
				SquireScreenHandler.BUTTON_BLUEPRINT_RIGHT, 2, 3, top);
			if (state.siteIssues().stream().anyMatch(s -> s.startsWith("水域："))) {
				top = take(BTN_H + 4);
				addProjectButton(Text.translatable("squire.gui.button.water_mode"), SquireScreenHandler.BUTTON_WATER_MODE, 0, 3, top);
				addProjectButton(Text.translatable("squire.gui.button.water_source"), SquireScreenHandler.BUTTON_WATER_SOURCE, 1, 3, top);
				addProjectButton(Text.translatable("squire.gui.button.water_supplied"), SquireScreenHandler.BUTTON_WATER_SUPPLIED, 2, 3, top);
			}

			top = take(BTN_H + 4);
			materialConfigButton = addPageWidget(ButtonWidget.builder(
					Text.translatable("squire.gui.button.material_config"), b -> {
						editingMaterials = true;
						applyTab();
					})
				.dimensions(x + columnX(0, 2), y + top, columnWidth(2), BTN_H).build());
			materialConfigButton.active = !state.materialChoices().isEmpty();
			// Blueprint design is a profession feature, not a different project page.
			if (state.profession().profession()
					== dev.squire.server.profession.SquireProfession.ENGINEER
					&& dev.squire.server.blueprint.ProjectSpec.parse(state.placementBlueprintId()).isPresent()) {
				addPageWidget(ButtonWidget.builder(
						Text.translatable("squire.gui.button.design_open"), b -> {
							editingDesign = true;
							applyTab();
						})
					.dimensions(x + columnX(1, 2), y + top, columnWidth(2), BTN_H)
					.build());
			}
			top = take(BTN_H + 4);
			addProjectButton(Text.translatable("squire.gui.button.project_confirm"),
				SquireScreenHandler.BUTTON_PROJECT_CONFIRM, 0, 2, top);
			addProjectButton(Text.translatable("squire.gui.button.project_transfer"),
				SquireScreenHandler.BUTTON_PROJECT_TRANSFER, 1, 2, top);
			top = take(BTN_H + 4);
			addProjectButton(Text.translatable(
				"squire.gui.button.project_cancel_preview"),
				SquireScreenHandler.BUTTON_PROJECT_CANCEL, 0, 1, top);
			return;
		}

		projectStatusY = take(LINE_H + 4);
		if (state.profession().profession()
				!= dev.squire.server.profession.SquireProfession.ENGINEER) {
			addWrappedNote(Text.translatable("squire.gui.project.engineer_only"), TEXT_LOCK);
			return;
		}
		// Player content is the family/Tier catalog; procedural recovery stays backend-only.
		addRowButton(Text.translatable("squire.gui.terrain.open"), SquireScreenHandler.BUTTON_TERRAIN_BASE, 0, 1, take(BTN_H + 4));
		buildBlueprintLibrary();
	}

	private void buildTerrainControls() {
		addWrappedNote(Text.translatable("squire.gui.terrain.hint"), TEXT_MUTED);
		var actions = dev.squire.server.runtime.TerrainLevelingService.Action.values();
		for (int i = 1; i < actions.length; i += 2) {
			int top = take(BTN_H + 3);
			for (int col = 0; col < 2 && i + col < actions.length; col++) {
				var action = actions[i + col];
				addProjectButton(Text.translatable("squire.gui.terrain." + action.name().toLowerCase(java.util.Locale.ROOT)),
					SquireScreenHandler.BUTTON_TERRAIN_BASE + action.ordinal(), col, 2, top);
			}
		}
		int top = take(BTN_H + 4);
		addProjectButton(Text.translatable("squire.gui.button.project_confirm"), SquireScreenHandler.BUTTON_PROJECT_CONFIRM, 0, 2, top);
		addProjectButton(Text.translatable("squire.gui.button.project_cancel_preview"), SquireScreenHandler.BUTTON_PROJECT_CANCEL, 1, 2, top);
	}

	private int projectQuickRowTop;
	private String catalogCategory = "";
	private boolean catalogUnlockedOnly;
	private int catalogTier;

	/** Dynamic fixed/external catalog sent by the server after every data-pack reload. */
	private void buildBlueprintLibrary() {
		addSection("squire.gui.project.blueprint_catalog");
		addWrappedNote(Text.translatable("squire.gui.project.blueprint_catalog_note"), TEXT_MUTED);
		addWrappedNote(Text.literal(handler.state().profession().constructionGrowth()), TEXT_MUTED);
		var library = handler.state().profession().blueprintLibrary();
		int filterTop = take(SMALL_BTN_H + 2);
		addPageWidget(ButtonWidget.builder(catalogCategory.isEmpty() ? Text.translatable("squire.gui.catalog.all_categories")
			: Text.translatable("squire.gui.catalog.category", Text.literal(catalogCategory)), b -> {
			var categories = library.stream().map(dev.squire.server.gui.ProfessionView.BlueprintEntry::category).distinct().toList();
			int next = categories.indexOf(catalogCategory) + 1;
			catalogCategory = next >= categories.size() ? "" : categories.get(next); applyTab();
		}).dimensions(x + columnX(0, 2), y + filterTop, columnWidth(2), SMALL_BTN_H).build());
		addPageWidget(ButtonWidget.builder(Text.translatable(catalogUnlockedOnly ? "squire.gui.catalog.unlocked" : "squire.gui.catalog.all_levels"), b -> {
			catalogUnlockedOnly = !catalogUnlockedOnly; applyTab();
		}).dimensions(x + columnX(1, 2), y + filterTop, columnWidth(2), SMALL_BTN_H).build());
		if (library.stream().anyMatch(e -> !"family".equals(e.kind()))) {
			int tierTop = take(SMALL_BTN_H + 2);
			addPageWidget(ButtonWidget.builder(Text.literal(catalogTier == 0 ? "全部 Tier / 组件" : "Tier " + catalogTier), b -> {
				catalogTier = (catalogTier + 1) % 6; applyTab();
			}).dimensions(x + CONTENT_MARGIN, y + tierTop, contentWidth(), SMALL_BTN_H).build());
		}
		String category = "";
		int item = 0;
		for (int i = 0; i < library.size(); i++) {
			var entry = library.get(i);
			if (!"family".equals(entry.kind()) && catalogTier > 0 && entry.tier() != catalogTier) continue;
			if (!catalogCategory.isEmpty() && !catalogCategory.equals(entry.category()) || catalogUnlockedOnly && !entry.allowed()) continue;
			if (!entry.category().equals(category)) {
				category = entry.category();
				item = 0;
				addNote(Text.literal(category), TEXT_GOLD);
			}
			if (item % 2 == 0) projectQuickRowTop = take(SMALL_BTN_H + 2);
			boolean unlocked = "family".equals(entry.kind()) || entry.unlockedAt(handler.state().profession().level());
			Text label = Text.literal("family".equals(entry.kind()) ? "" : unlocked ? "✓ " : "🔒 ")
				.append(Text.literal(entry.displayName()))
				.append("family".equals(entry.kind()) ? Text.literal(" ›") : entry.minLevel() > 10 ? Text.translatable("squire.gui.catalog.over_limit") : Text.literal(" · Lv." + entry.minLevel()));
			ButtonWidget button = addPageWidget(ButtonWidget.builder(label,
				b -> {
					if ("family".equals(entry.kind())) { catalogCategory = ""; catalogTier = 0; }
					var packet = PacketByteBufs.create(); packet.writeVarInt(handler.syncId);
					packet.writeString(entry.id(), 512); packet.writeLong(handler.state().profession().catalogVersion());
					ClientPlayNetworking.send(SquireScreens.BLUEPRINT_SELECT_PACKET, packet);
					editingDesign = "parametric".equals(entry.kind());
				})
				.dimensions(x + columnX(item % 2, 2), y + projectQuickRowTop,
					columnWidth(2), SMALL_BTN_H).build());
			button.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				("family".equals(entry.kind()) ? "建筑家族" : Text.translatable("parametric".equals(entry.kind()) ? "squire.gui.catalog.parametric" : "squire.gui.catalog.fixed").getString()) + "\n"
				+ ("family".equals(entry.kind()) ? "" : entry.width() + "×" + entry.height() + "×" + entry.depth() + "\n")
				+ entry.author() + " · " + entry.style() + "\n" + entry.license() + "\n" + entry.source()
				+ (entry.reason().isEmpty() ? "" : "\n" + entry.reason()))));
			button.active = unlocked;
			if (!unlocked) lockedButtons.add(button);
			item++;
		}
	}

	/**
	 * 工程师专属的那一半：参数化蓝图库。
	 *
	 * <p>和上面的资源蓝图库分开：上面是按 metadata 等级解锁的成品建筑；这里是旧版
	 * 参数化设计器，保留旋转、多层、结构变体、模块化和预设等既有功能。</p>
	 */
	private void buildTemplateLibrary() {
		addSection("squire.gui.project.library");
		var library = handler.state().profession().templateLibrary();
		if (library.isEmpty()) {
			addWrappedNote(Text.translatable("squire.gui.project.library_locked"),
				TEXT_LOCK);
			projectLibraryRows = 0;
			return;
		}
		projectLibraryRows = (library.size() + 1) / 2;
		projectLibraryY = layoutY;
		int level = handler.state().profession().level();
		for (int row = 0; row < projectLibraryRows; row++) {
			int top = take(SMALL_BTN_H + 2);
			for (int column = 0; column < 2; column++) {
				int index = row * 2 + column;
				if (index >= library.size()) {
					break;
				}
				var entry = library.get(index);
				var template = dev.squire.server.blueprint.ProjectSpec.Template
					.byId(entry.templateId());
				Text label = Text.literal(entry.unlockedAt(level) ? "✔ " : "🔒 ")
					.append(Text.translatable("squire.gui.template." + entry.templateId()))
					.append(Text.literal(" · Lv." + entry.minLevel()));
				ButtonWidget button = addPageWidget(ButtonWidget.builder(label, b -> {
					editingDesign = true;
					if (template != null) click(SquireScreenHandler.BUTTON_DESIGN_TEMPLATE_BASE
						+ template.ordinal());
					applyTab();
				}).dimensions(x + columnX(column, 2), y + top, columnWidth(2),
					SMALL_BTN_H).build());
				button.active = template != null && entry.unlockedAt(level);
				if (!button.active) lockedButtons.add(button);
			}
		}
		gap(2);
		addWrappedNote(Text.translatable("squire.gui.project.library_hint"), TEXT_MUTED);
	}

	private void buildForceCancelProjectButton() {
		boolean[] confirming = {false};
		forceCancelProjectButton = addPageWidget(ButtonWidget.builder(
			Text.translatable("squire.gui.button.project_force_cancel"), button -> {
				if (!confirming[0]) {
					confirming[0] = true;
					button.setMessage(Text.translatable("squire.gui.button.project_force_cancel_confirm"));
					return;
				}
				click(SquireScreenHandler.BUTTON_PROJECT_FORCE_CANCEL);
				confirming[0] = false;
				button.setMessage(Text.translatable("squire.gui.button.project_force_cancel"));
			}).dimensions(x + CONTENT_MARGIN, y + take(BTN_H + 4), contentWidth(), BTN_H).build());
		forceCancelProjectButton.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
			Text.translatable("squire.gui.project.force_cancel_note")));
		addWrappedNote(Text.translatable("squire.gui.project.force_cancel_note"), TEXT_WARN);
	}

	private void addProjectButton(Text label, int buttonId, int col, int cols, int top) {
		projectButtons.add(addRowButton(label, buttonId, col, cols, top));
		projectButtonIds.add(buttonId);
	}

	private static boolean isProjectChooser(int id) {
		return id >= SquireScreenHandler.BUTTON_PROJECT_HOUSE_WOOD
			&& id <= SquireScreenHandler.BUTTON_PROJECT_STORAGE;
	}

	/** 固定结构的材料配置页。每一行只切换一个语义槽位，不改变建筑几何。 */
	private void buildMaterialPage() {
		addSection("squire.gui.material.title");
		int count = Math.min(6, handler.state().materialChoices().size());
		materialFirstRowY = layoutY;
		for (int i = 0; i < count; i++) {
			final int slot = i;
			int top = take(BTN_H + 3);
			addCard(CONTENT_MARGIN, top, CONTENT_MARGIN + contentWidth(), top + BTN_H,
				COLOR_CARD);
			materialButtons.add(addPageWidget(ButtonWidget.builder(Text.literal("‹"),
					b -> click(SquireScreenHandler.BUTTON_MATERIAL_PREVIOUS_BASE + slot))
				.dimensions(x + CONTENT_MARGIN, y + top, 22, BTN_H).build()));
			materialButtons.add(addPageWidget(ButtonWidget.builder(Text.literal("›"),
					b -> click(SquireScreenHandler.BUTTON_MATERIAL_NEXT_BASE + slot))
				.dimensions(x + CONTENT_MARGIN + contentWidth() - 22, y + top, 22,
					BTN_H).build()));
		}
		addWrappedNote(Text.translatable("squire.gui.material.hint"), TEXT_MUTED);
		gap(2);
		int top = take(BTN_H);
		materialButtons.add(addRowButton(
			Text.translatable("squire.gui.button.material_reset"),
			SquireScreenHandler.BUTTON_MATERIAL_RESET, 0, 2, top));
		materialButtons.add(addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.button.material_back"), b -> {
					editingMaterials = false;
					applyTab();
				})
			.dimensions(x + columnX(1, 2), y + top, columnWidth(2), BTN_H).build()));
	}

	private int materialFirstRowY;

	/**
	 * 蓝图参数页：工程师逐级解锁的那一串参数。
	 *
	 * <p>没解锁的按钮<b>画出来但灰掉</b>，旁边挂一把锁。全部隐藏的话，一个 Lv.1 的
	 * 玩家永远不知道这条线后面还有什么，也就没有练下去的理由。</p>
	 *
	 * <p>灰掉只是显示。服务端在执行每一个按钮时会按等级<b>重新判一次</b>——
	 * 改客户端包绕不过去。</p>
	 */
	private void buildDesignPage() {
		var view = handler.state().profession();
		var spec = dev.squire.server.blueprint.ProjectSpec
			.parse(handler.state().placementBlueprintId()).orElse(null);
		addSection("squire.gui.design.title");
		// Three live readout lines make every cycle button observable. ProjectSpec's
		// short display name omits foundation/window/entrance/mirror, which made those
		// clicks look like no-ops even though the server had accepted them.
		designSpecY = take(LINE_H * 3 + 4);
		var actions = SquireActions.ofPage(SquireActions.Page.DESIGN);
		int row = -1;
		int top = 0;
		for (SquireActions.Action action : actions) {
			if (action.row() != row) {
				row = action.row();
				top = take(BTN_H + 3);
			}
			ButtonWidget button = addRowButton(designButtonLabel(action, spec),
				action.id(), action.col(), action.cols(), top);
			var gate = SquireActions.DESIGN_GATES.get(action.id());
			if (gate != null && !view.can(gate)) {
				button.active = false;
				lockedButtons.add(button);
			}
		}
		gap(4);
		addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.button.design_back"), b -> {
					editingDesign = false;
					applyTab();
				})
			.dimensions(x + CONTENT_MARGIN, y + take(BTN_H), contentWidth(), BTN_H)
			.build());
	}

	private int designSpecY;

	/** A cycle control says both what it changes and the value currently selected. */
	private static Text designButtonLabel(SquireActions.Action action,
			dev.squire.server.blueprint.ProjectSpec spec) {
		if (spec == null) {
			return Text.translatable(action.labelKey());
		}
		int id = action.id();
		if (id == SquireScreenHandler.BUTTON_DESIGN_TEMPLATE) {
			return Text.translatable("squire.gui.design.value.template",
				Text.translatable("squire.gui.template." + spec.template().id()));
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_FLOORS) {
			return Text.translatable("squire.gui.design.value.floors", spec.floors());
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_SIZE_DOWN) {
			return Text.translatable("squire.gui.design.value.smaller", spec.width(),
				spec.depth());
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_SIZE_UP) {
			return Text.translatable("squire.gui.design.value.bigger", spec.width(),
				spec.depth());
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_ROOF) {
			return Text.translatable("squire.gui.design.value.roof",
				designOption("roof", spec.roof().id()));
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_FOUNDATION) {
			return Text.translatable("squire.gui.design.value.foundation",
				designOption("foundation", spec.foundation().id()));
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_WINDOW) {
			return Text.translatable("squire.gui.design.value.window",
				designOption("window", spec.window().id()));
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_ENTRANCE) {
			return Text.translatable("squire.gui.design.value.entrance",
				designOption("entrance", spec.entrance().id()));
		}
		if (id == SquireScreenHandler.BUTTON_DESIGN_MIRROR) {
			return Text.translatable("squire.gui.design.value.mirror", mirrorName(spec));
		}
		int moduleIndex = id - SquireScreenHandler.BUTTON_DESIGN_MODULE_BASE;
		var modules = dev.squire.server.blueprint.ProjectSpec.Module.values();
		if (moduleIndex >= 0 && moduleIndex < modules.length) {
			boolean selected = spec.modules().contains(modules[moduleIndex]);
			return Text.literal(selected ? "✓ " : "+ ")
				.append(Text.translatable(action.labelKey()));
		}
		return Text.translatable(action.labelKey());
	}

	private static Text designOption(String group, String id) {
		return Text.translatable("squire.gui.design." + group + "." + id);
	}

	private static Text mirrorName(dev.squire.server.blueprint.ProjectSpec spec) {
		String state = spec.mirrorX() && spec.mirrorZ() ? "xz"
			: spec.mirrorX() ? "x" : spec.mirrorZ() ? "z" : "none";
		return Text.translatable("squire.gui.design.mirror." + state);
	}

	// ------------------------------------------------------------------ 快捷编辑

	private int shortcutGridY;

	/** 一张快捷卡的高度：一行按钮 + 一行「他会做什么」。 */
	private static final int SHORTCUT_CARD_H = BTN_H + 12;
	private int shortcutRowTop;

	/**
	 * 编辑态：名称一栏 + <b>动作选择器</b>。
	 *
	 * <p>原来这里是一个自由输入框，写着「就写你平时对他说的那一句」——存下来的是
	 * 一句自然语言，点一下要重新送回输入网关，也就是<b>可能再走一次模型</b>。
	 * 那意味着同一条快捷两次点出不同结果，离线时整个不能用，而且它绕开了面板按钮
	 * 那条「职业 → 能力 → 权限」的判定。现在只能从<b>现在真的点得动</b>的那几个
	 * 动作里挑一个，存下来的是动作 id 和档位。</p>
	 */
	private void buildShortcutEditor() {
		int slot = editingShortcut;
		var state = handler.state();
		String name = slot < state.shortcuts().size() ? state.shortcuts().get(slot) : "";
		if (editorEntryId.isEmpty()) {
			editorEntryId = state.shortcutEntryId(slot);
			editorArg = state.shortcutArg(slot);
		}

		addSection("squire.gui.shortcut.name_label");
		int top = take(20);
		shortcutNameField = addPageWidget(new TextFieldWidget(textRenderer,
			x + CONTENT_MARGIN, y + top, contentWidth(), 16,
			Text.translatable("squire.gui.shortcut.name_label")));
		shortcutNameField.setMaxLength(
			dev.squire.server.shortcut.ShortcutStore.MAX_NAME_LENGTH);
		shortcutNameField.setPlaceholder(
			Text.translatable("squire.gui.shortcut.name_placeholder"));
		shortcutNameField.setText(name);
		gap(2);

		// 老存档里的自然语言快捷：说清楚它还在，但重新绑一次才不会再走模型。
		if (state.shortcutIsLegacy(slot)) {
			addWrappedNote(Text.translatable("squire.gui.shortcut.legacy",
				state.shortcutPhrase(slot)), TEXT_WARN);
			gap(2);
		}

		addSection("squire.gui.shortcut.action_label");
		var context = dev.squire.server.gui.CommandCatalog.Context.of(state);
		var choices = dev.squire.server.gui.CommandCatalog.bindable(context);
		if (choices.isEmpty()) {
			addWrappedNote(Text.translatable("squire.gui.shortcut.no_action"),
				TEXT_MUTED);
		}
		int pickerTop = 0;
		for (int i = 0; i < choices.size(); i++) {
			if (i % 2 == 0) {
				pickerTop = take(SMALL_BTN_H + 2);
			}
			var entry = choices.get(i);
			final String id = entry.id();
			ButtonWidget button = addPageWidget(ButtonWidget.builder(
					Text.translatable(entry.labelKey()), b -> chooseEntry(id))
				.dimensions(x + columnX(i % 2, 2), y + pickerTop, columnWidth(2),
					SMALL_BTN_H).build());
			pickerButtons.add(button);
			pickerEntryIds.add(id);
		}

		// 档位只在选中的动作真的有几个档时才出现——只有一个档的动作再画一排
		// 「唯一选项」，纯粹是让玩家多看一行。
		var selected = dev.squire.server.gui.CommandCatalog.byId(editorEntryId);
		if (selected != null && selected.variants().size() > 1) {
			gap(2);
			addSection("squire.gui.shortcut.arg_label");
			// 两列而不是把 n 个档位挤在一行：「基础施工」有五个模板，五等分之后
			// 每格 44px，中文的「矿井前哨」和英文的 Netherite set 都会糊出格子。
			var variants = selected.variants();
			int argTop = 0;
			for (int i = 0; i < variants.size(); i++) {
				if (i % 2 == 0) {
					argTop = take(SMALL_BTN_H + 2);
				}
				final String arg = variants.get(i).arg();
				ButtonWidget button = addPageWidget(ButtonWidget.builder(
						Text.translatable(variants.get(i).labelKey()),
						b -> chooseArg(arg))
					.dimensions(x + columnX(i % 2, 2), y + argTop, columnWidth(2),
						SMALL_BTN_H).build());
				variantButtons.add(button);
				variantArgs.add(arg);
			}
		}

		gap(4);
		top = take(BTN_H);
		ButtonWidget save = addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.shortcut.save"), b -> saveShortcut())
			.dimensions(x + columnX(0, 2), y + top, columnWidth(2), BTN_H).build());
		save.active = selected != null;
		addPageWidget(ButtonWidget.builder(
				Text.translatable("squire.gui.shortcut.cancel"), b -> {
					editingShortcut = -1;
					editorEntryId = "";
					editorArg = "";
					applyTab();
				})
			.dimensions(x + columnX(1, 2), y + top, columnWidth(2), BTN_H).build());
	}

	/** 选一个动作。换动作就把档位复位到那个动作的第一个档，不留下上一个的参数。 */
	private void chooseEntry(String entryId) {
		editorEntryId = entryId;
		var entry = dev.squire.server.gui.CommandCatalog.byId(entryId);
		var first = entry == null ? null : entry.defaultVariant();
		editorArg = first == null ? "" : first.arg();
		applyTab();
	}

	private void chooseArg(String arg) {
		editorArg = arg;
		applyTab();
	}

	/**
	 * 指令页的文字：常用动作下面那行提示，以及每张快捷卡的第二行。
	 *
	 * <p>卡片上那行大字是「你怎么叫它」，光看它玩家分不出「回家吃饭」到底会让他
	 * 干什么——这一页最早的反馈就是这两半分不清。名字在按钮上，做的事在下面一行；
	 * 锁着的那条，下面一行写的是<b>为什么</b>点不动。</p>
	 */
	private void drawCommandPage(DrawContext context) {
		if (editingShortcut >= 0) {
			drawShortcutEditor(context);
			return;
		}
		var state = handler.state();
		for (int slot = 0; slot < dev.squire.server.shortcut.ShortcutStore
				.MAX_PER_PLAYER; slot++) {
			if (slot >= state.shortcuts().size()) {
				continue; // 空槽上一个字也不写，那张卡自己就是一句「点这里新建」
			}
			int top = shortcutGridY + slot / 2 * (SHORTCUT_CARD_H + 3);
			int left = columnX(slot % 2, 2);
			var lock = shortcutLocks.get(slot);
			Text line = lock != null ? lockText(lock) : shortcutSummary(state, slot);
			drawClipped(context, line, left + 3, top + BTN_H + 2, columnWidth(2) - 6,
				lock != null ? TEXT_WARN : TEXT_MUTED);
		}
	}

	/** 一条快捷「他会做什么」：绑定式写动作名（带档位），老快捷写那句原话。 */
	private Text shortcutSummary(dev.squire.server.gui.PanelState state, int slot) {
		var entry = dev.squire.server.gui.CommandCatalog.byId(
			state.shortcutEntryId(slot));
		if (entry == null) {
			String phrase = state.shortcutPhrase(slot);
			return phrase.isEmpty()
				? Text.translatable("squire.gui.shortcut.unknown_action")
				: Text.literal("→ " + phrase);
		}
		MutableText out = Text.literal("→ ").append(Text.translatable(entry.labelKey()));
		var variant = entry.variant(state.shortcutArg(slot));
		if (variant != null && entry.variants().size() > 1) {
			out.append(" ").append(Text.translatable(variant.labelKey()));
		}
		return out;
	}

	/** 面板上那把锁说的话，和服务端拒绝执行时说的<b>是同一件事</b>。 */
	private Text lockText(dev.squire.server.gui.CommandCatalog.Lock lock) {
		Text profession = lock.profession() == null ? Text.literal("?")
			: Text.translatable(lock.profession().nameKey());
		return switch (lock.reason()) {
			case PROFESSION -> Text.translatable(lock.labelKey(), profession);
			case LEVEL -> Text.translatable(lock.labelKey(), profession, lock.level());
			default -> Text.translatable(lock.labelKey());
		};
	}

	/** 编辑态：选中的动作加一圈绿框，选中的档位也是。 */
	private void drawShortcutEditor(DrawContext context) {
		for (int i = 0; i < pickerButtons.size() && i < pickerEntryIds.size(); i++) {
			if (pickerEntryIds.get(i).equals(editorEntryId)) {
				outline(context, pickerButtons.get(i));
			}
		}
		for (int i = 0; i < variantButtons.size() && i < variantArgs.size(); i++) {
			if (variantArgs.get(i).equals(editorArg)) {
				outline(context, variantButtons.get(i));
			}
		}
	}

	/** 「这个是当前选中的」那一圈绿框。滚动偏移要自己加回去，这段没有矩阵变换。 */
	private void outline(DrawContext context, ButtonWidget button) {
		context.drawBorder(button.getX() - x - 1,
			button.getY() - y + pageScroll - 1, button.getWidth() + 2,
			button.getHeight() + 2, COLOR_ACTIVE);
	}

	private void editShortcut(int slot) {
		editingShortcut = slot;
		// 空串是「还没读过这一格」的信号，buildShortcutEditor 会去状态包里回填；
		// 不清掉的话点开第二格会看到上一格选中的动作。
		editorEntryId = "";
		editorArg = "";
		applyTab();
		if (shortcutNameField != null) {
			setFocused(shortcutNameField);
			shortcutNameField.setFocused(true);
		}
	}

	/**
	 * 存下这一格：名称 + <b>动作 id + 档位</b>，不是一句自然语言。
	 *
	 * <p>服务端收到之后照样会验一遍这个 id 存不存在；执行的时候还要再拿真实的
	 * 职业档案验一次能不能点。客户端这两句检查只是省掉一趟白跑的包。</p>
	 */
	private void saveShortcut() {
		if (shortcutNameField == null) {
			return;
		}
		String name = shortcutNameField.getText().trim();
		if (name.isEmpty() || editorEntryId.isEmpty()) {
			return; // 服务端也会拒，但这里就不必先发一趟包再挨一句错误
		}
		var buf = PacketByteBufs.create();
		buf.writeVarInt(handler.syncId);
        buf.writeVarInt(editingShortcut);
		buf.writeString(name,
			dev.squire.server.shortcut.ShortcutStore.MAX_NAME_LENGTH);
		buf.writeString(editorEntryId,
			dev.squire.server.shortcut.ShortcutStore.MAX_ENTRY_ID_LENGTH);
		buf.writeString(editorArg,
			dev.squire.server.shortcut.ShortcutStore.MAX_ARG_LENGTH);
		ClientPlayNetworking.send(SquireScreens.SHORTCUT_PACKET, buf);
		editingShortcut = -1;
		editorEntryId = "";
		editorArg = "";
		applyTab();
	}

    private void syncNameEditor() {
        String currentName = handler.state().agentName().isEmpty()
            ? title.getString() : handler.state().agentName();
        nameEdit.sync(currentAgentKey(), currentName);
        if (nameField != null) {
            if (!nameField.getText().equals(nameEdit.draft())) nameField.setText(nameEdit.draft());
            var validation = dev.squire.server.profile.SquireName.validate(nameField.getText());
            nameField.setEditableColor(validation.valid() ? TEXT_PRIMARY : TEXT_DANGER);
            confirmNameButton.active = handler.bodyAvailable && nameEdit.dirty() && validation.valid();
        }
    }

    private void buildNameEditor() {
        syncNameEditor();
        addSection("squire.gui.profile.identity");
        addWrappedNote(Text.translatable("squire.gui.profile.identity_hint"), TEXT_MUTED);
        int top = take(BTN_H + 4);
        var row = PanelLayout.nameEditorRow(CONTENT_MARGIN, top, contentWidth(), BTN_H);
        var input = row.get(0);
        var save = row.get(1);
        var reset = row.get(2);
        nameField = addPageWidget(new TextFieldWidget(textRenderer,
            x + input.left(), y + input.top(), input.right() - input.left(), BTN_H,
            Text.translatable("squire.gui.rename.hint")));
        nameField.setMaxLength(SquireScreens.MAX_NAME_LENGTH);
        nameField.setText(nameEdit.draft());
        nameField.setChangedListener(nameEdit::edit);
        confirmNameButton = addPageWidget(ButtonWidget.builder(
            Text.translatable("squire.gui.rename.save"), b -> sendRename())
            .dimensions(x + save.left(), y + save.top(), save.right() - save.left(), BTN_H).build());
        addPageWidget(ButtonWidget.builder(Text.translatable("squire.gui.rename.reset"),
            b -> cancelNameEdit())
            .dimensions(x + reset.left(), y + reset.top(), reset.right() - reset.left(), BTN_H).build());
        syncNameEditor();
        gap(4);
    }

    private void cancelNameEdit() {
        nameEdit.reset();
        if (nameField != null) {
            nameField.setText(nameEdit.draft());
            nameField.setFocused(false);
        }
        setFocused(null);
    }

	private void sendRename() {
		if (nameField == null || !handler.bodyAvailable || !nameEdit.dirty()) {
			return;
		}
		var validation = dev.squire.server.profile.SquireName.validate(nameField.getText());
		if (!validation.valid()) {
			nameField.setEditableColor(TEXT_DANGER);
			return;
		}
		nameField.setText(validation.value());
		var buf = PacketByteBufs.create();
		buf.writeVarInt(handler.syncId);
		buf.writeString(validation.value(), SquireScreens.MAX_NAME_LENGTH);
		ClientPlayNetworking.send(SquireScreens.RENAME_PACKET, buf);
		// Keep the draft until the server acknowledges the rename.
		nameField.setFocused(false);
		setFocused(null);
	}

	private void confirmPersonalityReroll() {
		var buf = PacketByteBufs.create();
		buf.writeVarInt(handler.syncId);
		ClientPlayNetworking.send(SquireScreens.PERSONALITY_REROLL_PACKET, buf);
		confirmingPersonalityReroll = false;
		applyTab();
	}

	private void click(int buttonId) {
		if (buttonId >= SquireScreenHandler.BUTTON_TERRAIN_BASE && buttonId < SquireScreenHandler.BUTTON_TERRAIN_BASE + 12
				|| !handler.terrainReview.isEmpty() && (buttonId == SquireScreenHandler.BUTTON_PROJECT_CONFIRM
				|| buttonId == SquireScreenHandler.BUTTON_PROJECT_CANCEL || buttonId == SquireScreenHandler.BUTTON_PROJECT_PAUSE
				|| buttonId == SquireScreenHandler.BUTTON_PROJECT_RESUME)) {
			var packet = PacketByteBufs.create(); packet.writeVarInt(handler.syncId); packet.writeVarInt(buttonId);
			packet.writeString(handler.terrainReview, 128);
			ClientPlayNetworking.send(SquireScreens.TERRAIN_ACTION_PACKET, packet);
			return;
		}
		if (client != null && client.interactionManager != null) {
			client.interactionManager.clickButton(handler.syncId, buttonId);
		}
	}

	private void sendChat() {
		if (chatField == null) {
			return;
		}
		String message = chatField.getText().trim();
		if (message.isEmpty()) {
			return;
		}
		var buf = PacketByteBufs.create();
		buf.writeVarInt(handler.syncId);
        buf.writeString(message, SquireScreens.MAX_CHAT_LENGTH);
		ClientPlayNetworking.send(SquireScreens.CHAT_PACKET, buf);
		chatField.setText("");
		close();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// 任意一个输入框获得焦点时，E/K 都不该把界面关掉，否则一打字就退出。
		TextFieldWidget focused = focusedField();
		if (focused != null) {
			if (focused == nameField && keyCode == 256) {
				cancelNameEdit();
				return true;
			}
			if (keyCode == 257 || keyCode == 335) { // Enter / numpad Enter
				submitField(focused);
				return true;
			}
			if (keyCode != 256) { // 除了 Esc，其它按键都交给输入框
				focused.keyPressed(keyCode, scanCode, modifiers);
				return true; // E/K character events must not reach inventory-close bindings.
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private TextFieldWidget focusedField() {
		for (TextFieldWidget field : new TextFieldWidget[] {
				chatField, shortcutNameField, nameField}) {
			if (field != null && field.visible && field.isFocused()) {
				return field;
			}
		}
		return null;
	}

	/** 回车在哪个框里按下就做那个框的事。 */
	private void submitField(TextFieldWidget field) {
		if (field == chatField) {
			sendChat();
		} else if (field == nameField) {
			sendRename();
		} else {
			// 名称填完按回车就存下这一格。动作是从清单里点出来的，
			// 没有第二个输入框可跳——那正是这一页要去掉的东西。
			saveShortcut();
		}
	}

	// ------------------------------------------------------------------ 绘制

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX,
			int mouseY) {
		context.fill(x - 1, y - 1, x + backgroundWidth + 1, y + backgroundHeight + 1,
			COLOR_BORDER);
		context.fill(x, y, x + backgroundWidth, y + backgroundHeight, COLOR_BACKGROUND);

		// 顶部读数 + 状态条自成一带，和内容区用一条线分开。
		context.fill(x + 1, y + 1, x + PANEL_WIDTH - 1,
			y + SquireScreenHandler.CONTENT_TOP - 3, COLOR_HEADER);
		context.fill(x + 4, y + SquireScreenHandler.CONTENT_TOP - 3,
			x + PANEL_WIDTH - 4, y + SquireScreenHandler.CONTENT_TOP - 2, COLOR_DIVIDER);

		int contentTop = y + SquireScreenHandler.CONTENT_TOP;
		int contentBottom = y + (tab == TAB_ITEMS ? PANEL_HEIGHT - 4 : CONTENT_BOTTOM);
		context.fill(x + CONTENT_LEFT, contentTop, x + CONTENT_RIGHT, contentBottom,
			COLOR_CONTENT);

		if (tab != TAB_ITEMS) {
			// 聊天页脚：一条线 + 一块自己的底色，让「这里是打字的地方」自己说出来。
			context.fill(x + 4, y + FOOTER_TOP - 1, x + PANEL_WIDTH - 4,
				y + FOOTER_TOP, COLOR_DIVIDER);
			context.fill(x + 1, y + FOOTER_TOP, x + PANEL_WIDTH - 1,
				y + PANEL_HEIGHT - 1, COLOR_FOOTER);
			drawPageDecorations(context);
			return; // 其余页没有槽位，也就不需要槽位底
		}

		int equipY = SquireScreenHandler.EQUIP_Y;
		// 装备列现在是 7 格：6 个装备 + 1 个背包（最后那格和上面隔开一点，
		// 让"这一格不是护甲"一眼看得出来）。
		context.fill(x + 4, y + equipY - 4, x + 30,
			y + equipY + SquireScreenHandler.EQUIPMENT_COLUMN_SLOTS * 18 + 2,
			COLOR_SECTION);
		int avatarY = SquireScreenHandler.AVATAR_GRID_Y;
		context.fill(x + 40, y + avatarY - 4, x + 40 + 9 * 18 + 2,
			y + avatarY + 4 * 18 + 2, COLOR_SECTION);
		int playerY = SquireScreenHandler.PLAYER_GRID_Y;
		context.fill(x + 40, y + playerY - 4, x + 40 + 9 * 18 + 2,
			y + SquireScreenHandler.HOTBAR_Y + 18 + 2, COLOR_SECTION);
		for (Slot slot : handler.slots) {
			if (!slot.isEnabled()) {
				continue;
			}
			// 背包槽单独一个颜色：它是装备列里唯一一个不放装备的格子，
			// 一个和别人长得一模一样的空格没有任何东西告诉玩家该往里放什么。
			boolean isBackpack = slot.id
				== SquireScreenHandler.BACKPACK_SLOT_INDEX;
			context.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17,
				y + slot.y + 17, isBackpack ? COLOR_BACKPACK_EDGE : COLOR_SLOT_EDGE);
			context.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16,
				COLOR_SLOT);
		}
	}

	/**
	 * 卡片底色。<b>必须画在这里</b>：drawForeground 在控件之后执行，在那里画底色
	 * 就是拿一块色板盖住按钮。裁剪与滚动都自己算，因为这一段没有矩阵变换。
	 */
	private void drawPageDecorations(DrawContext context) {
		if (pageDecos.isEmpty()) {
			return;
		}
		context.enableScissor(x + CONTENT_LEFT, y + SquireScreenHandler.CONTENT_TOP,
			x + CONTENT_RIGHT, y + CONTENT_BOTTOM);
		for (Deco deco : pageDecos) {
			context.fill(x + deco.left(), y + deco.top() - pageScroll,
				x + deco.right(), y + deco.bottom() - pageScroll, deco.color());
		}
		context.disableScissor();
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		drawHeader(context);
		drawTabRail(context);
		drawStateRow(context);

		boolean clippedPage = tab != TAB_ITEMS;
		if (clippedPage) {
			context.enableScissor(x + CONTENT_LEFT,
				y + SquireScreenHandler.CONTENT_TOP,
				x + CONTENT_RIGHT, y + CONTENT_BOTTOM);
			context.getMatrices().push();
			context.getMatrices().translate(0, -pageScroll, 0);
			drawPageTexts(context);
		}
		if (tab == TAB_ITEMS) {
			drawItemsPage(context);
		} else if (tab == TAB_BEHAVIOUR) {
			drawBehaviourPage(context);
		} else if (tab == TAB_PROFESSION) {
			drawProfessionPage(context);
		} else if (tab == TAB_PROJECT) {
            if (guardPanel()) { drawStanceSelection(context, handler.state().profession()); drawLockMarks(context); }
			else if (editingDesign) {
				drawDesignPage(context);
			} else if (editingMaterials) {
				drawMaterialPage(context);
			} else {
				drawProjectPage(context);
			}
		} else if (tab == TAB_PERMISSIONS) {
			drawPermissionsPage(context);
		} else if (tab == TAB_COMMAND) {
			drawCommandPage(context);
		}
		if (clippedPage) {
			context.getMatrices().pop();
			context.disableScissor();
			drawScrollBar(context);
		}
	}

	/** 顶部一行读数：名字在左，职业/等级 · 血量 · 当前状态右对齐。 */
	private void drawHeader(DrawContext context) {
        if(handler.state().roster().size()>1)return;
		var view = handler.state().profession();
		AvatarEntity.MovementMode mode = handler.mode();
		int hp = handler.health();
		int maxHp = handler.maxHealth();
		int hpColor = hp * 3 <= maxHp ? TEXT_DANGER
			: hp * 2 <= maxHp ? TEXT_WARN : TEXT_OK;

		Text modeText = Text.translatable(modeKey(mode));
		Text hpText = Text.literal("♥ " + hp + "/" + maxHp);
		Text jobText = view.hasProfession()
			? Text.translatable(view.profession().nameKey()).append(" ")
				.append(Text.translatable("squire.gui.profession.level", view.level()))
			: Text.translatable("squire.gui.profession.untrained");

		int right = PANEL_WIDTH - 8;
		int modeX = right - textRenderer.getWidth(modeText);
		int hpX = modeX - 8 - textRenderer.getWidth(hpText);
		int jobX = hpX - 8 - textRenderer.getWidth(jobText);
		context.drawText(textRenderer, modeText, modeX, titleY, TEXT_LABEL, false);
		context.drawText(textRenderer, hpText, hpX, titleY, hpColor, false);
		context.drawText(textRenderer, jobText, jobX, titleY,
			view.hasProfession() ? TEXT_GOLD : TEXT_MUTED, false);

		// 标题优先用状态包里的名字：{@code title} 是<b>开界面那一刻</b>定下的，
		// 在面板里改完名它不会动，玩家会以为改名没生效。
		Text liveTitle = (handler.state().agentName().isEmpty() ? title
			: Text.literal(handler.state().agentName())).copy();
		int titleRoom = jobX - titleX - 6;
		net.minecraft.text.OrderedText shownTitle =
			textRenderer.getWidth(liveTitle) <= titleRoom
				? liveTitle.asOrderedText()
				: Text.literal(textRenderer.trimToWidth(liveTitle.getString(),
					Math.max(6, titleRoom - 6)) + "…").asOrderedText();
		context.drawText(textRenderer, shownTitle, titleX, titleY, 0xFFFFFF, false);
	}

	/** 当前档位加一圈绿框。三个按钮长得一样，不加框就看不出他现在是哪一档。 */
	private void drawStateRow(DrawContext context) {
		if (stateRowExpanded) {
			return; // 「更多」里全是一次性动作，没有「当前」可言
		}
		int activeIndex = switch (handler.mode()) {
			case FOLLOW -> 0;
			case STAY -> 1;
			case PATROL -> 2;
			case IDLE -> -1;
		};
		if (activeIndex < 0 || activeIndex >= stateRowWidgets.size()) {
			return;
		}
		ClickableWidget button = stateRowWidgets.get(activeIndex);
		context.drawBorder(button.getX() - x - 1, button.getY() - y - 1,
			button.getWidth() + 2, button.getHeight() + 2, COLOR_ACTIVE);
	}

	private void drawTabRail(DrawContext context) {
		context.drawBorder(TAB_RAIL_X - 1,
			TAB_RAIL_TOP + tab * TAB_RAIL_PITCH - 1, TAB_RAIL_W + 2, TAB_RAIL_H + 2,
			COLOR_ACTIVE);
	}

	/** 搭页面时记下的那些静态文字。小标题右边补一条横线。 */
	private void drawPageTexts(DrawContext context) {
		for (PageText entry : pageTexts) {
			context.drawText(textRenderer, entry.text(), entry.left(), entry.top(),
				entry.color(), false);
			if (entry.rule()) {
				int from = entry.left() + textRenderer.getWidth(entry.text()) + 6;
				int to = CONTENT_MARGIN + contentWidth();
				if (from < to) {
					context.fill(from, entry.top() + 4, to, entry.top() + 5, COLOR_RULE);
				}
			}
		}
	}

	private void drawItemsPage(DrawContext context) {
		context.drawText(textRenderer, playerInventoryTitle,
			SquireScreenHandler.GRID_X, playerInventoryTitleY, TEXT_LABEL, false);
		// 上面那块网格现在是谁的、翻到第几页——不写的话玩家分不清自己在看哪一个。
		int backpackSlots = handler.state().backpackSlots();
		if (showingBackpack && backpackSlots > 0) {
			int pageSize =
				dev.squire.server.gui.AvatarBackpackContentsInventory.PAGE_SIZE;
			int pages = Math.max(1, (backpackSlots + pageSize - 1) / pageSize);
			context.drawText(textRenderer,
				Text.translatable("squire.gui.items.backpack_page",
					handler.state().backpackPage() + 1, pages, backpackSlots),
				SquireScreenHandler.GRID_X + 76, playerInventoryTitleY,
				TEXT_GOLD, false);
		}
	}

	private void drawScrollBar(DrawContext context) {
		if (maxPageScroll <= 0) {
			return;
		}
		int top = SquireScreenHandler.CONTENT_TOP + 2;
		int bottom = CONTENT_BOTTOM - 2;
		int track = bottom - top;
		int thumb = Math.max(18, track * track / (track + maxPageScroll));
		int travel = track - thumb;
		int offset = maxPageScroll == 0 ? 0
			: (int) Math.round(travel * (pageScroll / (double) maxPageScroll));
		context.fill(CONTENT_RIGHT - 5, top, CONTENT_RIGHT - 3, bottom, COLOR_RULE);
		context.fill(CONTENT_RIGHT - 6, top + offset, CONTENT_RIGHT - 2,
			top + offset + thumb, 0xFF8A8A96);
	}

	// ------------------------------------------------------------------ 权限页绘制

	private void drawPermissionsPage(DrawContext context) {
		// 勾选状态每帧刷；风险色来自动作表，客户端不自己判哪个权限危险。
		for (int i = 0; i < permissionButtons.size()
				&& i < permissionIndexShown.size(); i++) {
			int bit = permissionIndexShown.get(i);
			String node = SquireScreenHandler.TOGGLEABLE_NODES.get(bit);
			boolean granted = handler.hasPermission(bit);
			ButtonWidget button = permissionButtons.get(i);
			MutableText label = Text.literal(granted ? "✔ " : "✘ ")
				.append(Text.translatable(SquireActions.nodeLabelKey(node)));
			button.setMessage(label.styled(style -> style.withColor(
				riskTextColor(SquireActions.riskOf(node)) & 0xFFFFFF)));
			if (granted) {
				context.drawBorder(button.getX() - x - 1,
					button.getY() - y + pageScroll - 1, button.getWidth() + 2,
					button.getHeight() + 2, COLOR_ACTIVE);
			}
		}
	}

	// ------------------------------------------------------------------ 行为页绘制

	private void drawBehaviourPage(DrawContext context) {
		var state = handler.state();
		// 自主档位：当前那一档加框，未开放的置灰，下面写清楚这一档意味着什么。
		var levels = dev.squire.server.profile.AutonomyLevel.values();
		var current = state.autonomyLevel();
		for (int i = 0; i < autonomyButtons.size() && i < levels.length; i++) {
			autonomyButtons.get(i).active = levels[i].available();
			if (levels[i] == current) {
				ButtonWidget button = autonomyButtons.get(i);
				context.drawBorder(button.getX() - x - 1,
					button.getY() - y + pageScroll - 1,
					button.getWidth() + 2, button.getHeight() + 2, COLOR_ACTIVE);
			}
		}
		var note = textRenderer.wrapLines(
			Text.translatable("squire.gui.autonomy.note." + current.id()),
			contentWidth());
		for (int i = 0; i < note.size() && i < AUTONOMY_NOTE_LINES; i++) {
			context.drawText(textRenderer, note.get(i), CONTENT_MARGIN,
				autonomyNoteY + i * LINE_H, TEXT_MUTED, false);
		}

		// 这两个按钮的标签<b>就是</b>当前值，玩家不必点开才知道现在是什么状态。
		if (followDistanceButton != null) {
			followDistanceButton.setMessage(followDistanceLabel());
		}
		if (combatModeButton != null) {
			combatModeButton.setMessage(combatModeLabel());
		}

	}

	// ------------------------------------------------------------------ 职业页绘制

	/** 职业页的文字。三种状态各画各的，按钮由 {@link #buildProfessionPage} 摆好。 */
	private void drawProfessionPage(DrawContext context) {
		var view = handler.state().profession();
		if (confirmingProfession != null) {
			drawProfessionConfirm(context);
			return;
		}
		if (!view.hasProfession()) {
			drawTrainingPage(context, view);
			return;
		}
		MutableText header = Text.translatable(view.profession().nameKey()).append(" ")
			.append(Text.translatable("squire.gui.profession.level", view.level()));
		context.drawText(textRenderer, header, CONTENT_MARGIN, professionHeaderY,
			TEXT_GOLD, false);
		// 右上角那枚徽章只在<b>真的能动手</b>时才亮：经验满了但材料不够也写
		// 「可以晋升」的话，玩家会去点一个灰着的按钮，然后以为界面坏了。
		if (view.canPromote()) {
			Text badge = Text.translatable("squire.gui.profession.ready_level",
				view.level() + 1);
			context.drawText(textRenderer, badge,
				contentRightEdge() - textRenderer.getWidth(badge), professionHeaderY,
				TEXT_OK, false);
		} else if (view.isMaxLevel()) {
			Text badge = Text.translatable("squire.gui.profession.maxed");
			context.drawText(textRenderer, badge,
				contentRightEdge() - textRenderer.getWidth(badge), professionHeaderY,
				TEXT_GOLD, false);
		}
		drawBar(context, professionBarY, view.bar(),
			view.promotionReady() ? TEXT_OK : TEXT_GOLD);
		MutableText xpLine = view.xpNeeded() > 0
			? Text.translatable("squire.gui.profession.xp", view.xp(), view.xpNeeded())
			: Text.translatable("squire.gui.profession.maxed");
		context.drawText(textRenderer, xpLine, CONTENT_MARGIN, professionXpY,
			view.promotionReady() ? TEXT_OK : TEXT_MUTED, false);
		// 「还差多少」写在经验数字后面，而不是让玩家自己做减法。
		if (view.xpNeeded() > 0 && !view.promotionReady()) {
			context.drawText(textRenderer,
				Text.translatable("squire.gui.profession.xp_remaining",
					Math.max(0, view.xpNeeded() - view.xp())),
				CONTENT_MARGIN + textRenderer.getWidth(xpLine) + 6, professionXpY,
				TEXT_MUTED, false);
		}

		if (professionPromoteY > 0) {
			drawPromotionStatus(context, view);
		}
		if (professionMaterialsY > 0) {
			// 晋升材料：逐条写「要几个 / 有几个」，够了绿、不够红。玩家不必去猜
			// 那个灰着的晋升按钮到底缺什么。
			var materials = view.materials();
			int at = professionMaterialsY;
			if (materials.isEmpty()) {
				context.drawText(textRenderer,
					Text.translatable("squire.gui.profession.no_materials"),
					CONTENT_MARGIN + 4, at, TEXT_MUTED, false);
			}
			for (var material : materials) {
				drawClipped(context, Text.literal(material.enough() ? "✔ " : "✘ ")
						.append(itemName(material.itemId()))
						.append("  " + material.have() + " / " + material.need()),
					CONTENT_MARGIN + 4, at, contentWidth() - 4,
					material.enough() ? TEXT_OK : TEXT_DANGER);
				at += LINE_H;
			}
		}
		if (professionNextY > 0) {
			int at = professionNextY;
			for (var ability : view.next()) {
				drawClipped(context,
					Text.literal("· ").append(Text.translatable(ability.nameKey())),
					CONTENT_MARGIN + 4, at, contentWidth() - 4, 0xFFB0D0B0);
				at += LINE_H;
			}
		}
		drawGrowthRoute(context, view);
		if (view.profession() == dev.squire.server.profession.SquireProfession.GUARD) {
            drawClipped(context, Text.translatable("squire.gui.profile.guard_growth", view.guardMaxHealth()),
                CONTENT_MARGIN, professionExtraY, contentWidth(), TEXT_LABEL);
        } else {
            drawEngineerBlock(context, view);
        }
		drawLockMarks(context);
	}

	/**
	 * Lv.1→Lv.10 的成长路线。
	 *
	 * <p>上面一排格子是等级本身（走过的绿、当前亮、后面的灰），下面两列把每一级
	 * 会解锁什么写出来。原来这一页只说「下一级解锁 X」——玩家看得到脚下一级，
	 * 看不到这条线通向哪儿，也就没有理由练下去。</p>
	 */
	private void drawGrowthRoute(DrawContext context,
			dev.squire.server.gui.ProfessionView view) {
		int max = dev.squire.server.profession.SquireProfession.MAX_LEVEL;
		int pipGap = 2;
		int pipWidth = (contentWidth() - (max - 1) * pipGap) / max;
		for (int level = 1; level <= max; level++) {
			int left = CONTENT_MARGIN + (level - 1) * (pipWidth + pipGap);
			int colour = level < view.level() ? 0xFF4C8A4C
				: level == view.level() ? 0xFF70D070 : 0xFF3A3A42;
			context.fill(left, professionPipsY, left + pipWidth, professionPipsY + 6,
				colour);
		}
		var abilities = dev.squire.server.profession.ProfessionAbility
			.of(view.profession());
		int rows = (abilities.size() + 1) / 2;
		for (int i = 0; i < abilities.size(); i++) {
			var ability = abilities.get(i);
			int column = i / rows;
			int row = i % rows;
			boolean owned = ability.unlockLevel() <= view.level();
			drawClipped(context,
				Text.literal((owned ? "✔ Lv." : "· Lv.") + ability.unlockLevel() + " ")
					.append(Text.translatable(ability.nameKey())),
				CONTENT_MARGIN + column * (columnWidth(2) + GAP),
				professionRouteY + row * 11, columnWidth(2), owned ? TEXT_OK : TEXT_LOCK);
		}
	}

	/**
	 * 画一行<b>绝不越出给定宽度</b>的文字：放不下就截断加省略号。
	 *
	 * <p>lang 文件里的固定文案有排版期的折行兜着，但面板上还有一类文字是
	 * <b>运行时拼出来</b>的——补给清单、工程名、性格串、能力名。它们的长度取决于
	 * 玩家的存档和当前语言，任何一条都可能比内容区宽，而没有任何测试拦得住。
	 * 凡是这类行一律走这里。</p>
	 */
	private void drawClipped(DrawContext context, Text text, int left, int top,
			int width, int color) {
		String raw = text.getString();
		context.drawText(textRenderer,
			Text.literal(textRenderer.getWidth(raw) <= width ? raw
				: textRenderer.trimToWidth(raw, Math.max(6, width - 6)) + "…"),
			left, top, color, false);
	}

	/** 一条进度条。经验、训练共用。 */
	private void drawBar(DrawContext context, int top, double fraction, int colour) {
		int barWidth = contentWidth();
		int filled = (int) Math.round(barWidth * Math.max(0, Math.min(1, fraction)));
		context.fill(CONTENT_MARGIN, top, CONTENT_MARGIN + barWidth, top + 5,
			0xFF303040);
		if (filled > 0) {
			context.fill(CONTENT_MARGIN, top, CONTENT_MARGIN + filled, top + 5, colour);
		}
	}

	/**
	 * 当前姿态加一个绿框。
	 *
	 * <p>没有这一条，三个姿态按钮长得一模一样，玩家点完不知道自己现在是哪一档——
	 * 而这三档改的是「他追多远、什么时候收手」，看不出当前值等于这个开关不存在。</p>
	 */
	private void drawStanceSelection(DrawContext context,
			dev.squire.server.gui.ProfessionView view) {
		var stances = dev.squire.server.profession.CombatStance.values();
		for (var widget : pageWidgets) {
			if (!(widget instanceof ButtonWidget button)) {
				continue;
			}
			for (var stance : stances) {
				if (button.getMessage().equals(
						Text.translatable("squire.gui.stance." + stance.id()))
						&& stance == view.stance()) {
					context.drawBorder(button.getX() - x - 1,
						button.getY() - y + pageScroll - 1,
						button.getWidth() + 2, button.getHeight() + 2, COLOR_ACTIVE);
				}
			}
		}
	}

	/**
	 * 灰掉的按钮上画一把锁，旁边写清楚要几级。
	 *
	 * <p>灰按钮已经表示「点不了」，但没说<b>为什么</b>。锁 +「Lv.8」一起，
	 * 玩家才知道这不是坏了，是还没练到。</p>
	 */
	private void drawLockMarks(DrawContext context) {
		for (ButtonWidget button : lockedButtons) {
			if (button.active) {
				continue;
			}
			context.drawText(textRenderer, Text.literal("🔒"),
				button.getX() - x + button.getWidth() - 10,
				button.getY() - y + pageScroll + 5, TEXT_LOCK, false);
		}
	}

	/** Lv.0：训练清单。已完成打勾，未完成灰着——玩家一眼看得出还差哪一项。 */
	private void drawTrainingPage(DrawContext context,
			dev.squire.server.gui.ProfessionView view) {
		context.drawText(textRenderer,
			Text.translatable("squire.gui.profession.untrained"),
			CONTENT_MARGIN, professionHeaderY, TEXT_PRIMARY, false);
		drawBar(context, trainingBarY, view.trainingBar(),
			view.trainingComplete() ? TEXT_OK : TEXT_GOLD);
		context.drawText(textRenderer,
			Text.translatable("squire.gui.profession.training", view.trainingXp(),
				view.trainingRequired()),
			CONTENT_MARGIN, professionXpY,
			view.trainingComplete() ? TEXT_OK : TEXT_MUTED, false);

		int at = trainingListY;
		for (var milestone : dev.squire.server.profession.TrainingMilestone.required()) {
			boolean done = view.hasTrained(milestone);
			drawClipped(context, Text.literal(done ? "✔ " : "· ")
					.append(Text.translatable(milestone.nameKey())),
				CONTENT_MARGIN, at, contentWidth(), done ? TEXT_OK : TEXT_MUTED);
			at += LINE_H;
		}
		context.drawText(textRenderer,
			Text.translatable(view.trainingComplete()
				? "squire.gui.profession.training_done"
				: "squire.gui.profession.choose_hint"),
			CONTENT_MARGIN, professionExtraY, TEXT_LABEL, false);
		drawLockMarks(context);
	}

	/** 确认页：职业是什么、Lv.1/5/10 各会什么、以及这是一条成长线。 */
	private void drawProfessionConfirm(DrawContext context) {
		var target = confirmingProfession;
		context.drawText(textRenderer,
			Text.translatable("squire.gui.profession.confirm_title",
				Text.translatable(target.nameKey())),
			CONTENT_MARGIN, professionHeaderY, TEXT_GOLD, false);

		int at = professionRouteY;
		// 只挑 Lv.1 / 5 / 10 三条：把十一条全列出来，玩家一条也不会看。
		for (int level : new int[] {1, 5, 10}) {
			for (var ability : dev.squire.server.profession.ProfessionAbility
					.unlockedAt(target, level)) {
				drawClipped(context, Text.literal("Lv" + level + "  ")
						.append(Text.translatable(ability.nameKey())),
					CONTENT_MARGIN, at, contentWidth(), 0xFFB0D0B0);
				drawClipped(context, Text.literal(ability.summary()),
					CONTENT_MARGIN + 8, at + 10, contentWidth() - 8, TEXT_MUTED);
				at += 21;
			}
		}
		int line = professionExtraY;
		for (var wrapped : textRenderer.wrapLines(
				Text.translatable("squire.gui.profession.confirm_note"),
				contentWidth())) {
			context.drawText(textRenderer, wrapped, CONTENT_MARGIN, line, TEXT_LABEL,
				false);
			line += LINE_H;
		}
	}

	/** 工程师块：尺寸上限。模板库在工程页。 */
	private void drawEngineerBlock(DrawContext context,
			dev.squire.server.gui.ProfessionView view) {
		drawClipped(context, Text.literal(view.constructionGrowth()), CONTENT_MARGIN, professionExtraY, contentWidth(), TEXT_LABEL);
	}

	/**
	 * 晋升那一行的三种状态，写成一句人话摆在按钮右边。
	 *
	 * <p>灰着的按钮只说明「现在点不了」。玩家要的是<b>还差什么</b>：差经验就报
	 * 还差多少，差材料就把缺的那几样点出来，两样都够了再把按钮框起来，
	 * 让「可以晋升了」这件事一眼看得见。</p>
	 */
	private void drawPromotionStatus(DrawContext context,
			dev.squire.server.gui.ProfessionView view) {
		int left = CONTENT_MARGIN + PROMOTE_BUTTON_W + 8;
		int top = professionPromoteY + 5;
		int room = contentWidth() - PROMOTE_BUTTON_W - 8;
		if (view.canPromote()) {
			drawClipped(context,
				Text.translatable("squire.gui.profession.promote_ready"), left, top,
				room, TEXT_OK);
			if (promoteButton != null) {
				context.drawBorder(promoteButton.getX() - x - 1,
					promoteButton.getY() - y + pageScroll - 1,
					promoteButton.getWidth() + 2, promoteButton.getHeight() + 2,
					TEXT_GOLD);
			}
			return;
		}
		if (!view.promotionReady()) {
			drawClipped(context,
				Text.translatable("squire.gui.profession.promote_need_xp",
					Math.max(0, view.xpNeeded() - view.xp())),
				left, top, room, TEXT_MUTED);
			return;
		}
		// 经验满了，卡在材料上。把缺的那几样直接写出来，不让玩家自己去对下面的清单。
		StringBuilder missing = new StringBuilder();
		for (var material : view.materials()) {
			if (material.enough()) {
				continue;
			}
			missing.append(missing.length() == 0 ? "" : "、")
				.append(itemName(material.itemId()).getString())
				.append("×").append(material.need() - material.have());
		}
		Text line = missing.length() == 0
			? Text.translatable("squire.gui.profession.promote_ready")
			: Text.translatable("squire.gui.profession.promote_need_materials")
				.append(Text.literal(textRenderer.trimToWidth(missing.toString(),
					Math.max(20, room - 54))));
		drawClipped(context, line, left, top, room,
			missing.length() == 0 ? TEXT_OK : TEXT_DANGER);
	}

	/**
	 * 物品的<b>本地化名字</b>，不是 registry id。
	 *
	 * <p>原来这里只截掉冒号前那一截，于是面板上写着「netherite_scrap 0 / 4」——
	 * 一个不看英文的玩家没法把它和背包里那个「下界合金碎片」对上。原版每个物品
	 * 都自带一条翻译，直接用它。</p>
	 */
	private static MutableText itemName(String itemId) {
		try {
			Identifier id = new Identifier(itemId);
			if (Registries.ITEM.containsId(id)) {
				return Registries.ITEM.get(id).getName().copy();
			}
		} catch (RuntimeException ignored) {
			// 认不出的 id 退回原样，总好过什么都不写
		}
		int colon = itemId.indexOf(':');
		return Text.literal(colon < 0 ? itemId : itemId.substring(colon + 1));
	}

	// ------------------------------------------------------------------ 工程页绘制

	/**
	 * 工程页的文字。每一行都要让玩家看出<b>现在轮到谁</b>：
	 * 卡住的那一步把原因写出来，而不是只标一个「进行中」。
	 */
	private void drawProjectPage(DrawContext context) {
		var state = handler.state();
		for (int i = 0; i < projectButtons.size() && i < projectButtonIds.size(); i++) {
			int id = projectButtonIds.get(i);
			ButtonWidget button = projectButtons.get(i);
			button.active = button.visible;
			if (id == SquireScreenHandler.BUTTON_PROJECT_CONFIRM) {
				boolean materialsReady = state.materialLines().isEmpty();
				button.setMessage(Text.translatable(!state.siteExecutable()
					? "squire.gui.button.project_blocked_site"
					: !materialsReady ? "squire.gui.button.project_blocked_materials"
						: "squire.gui.button.project_confirm"));
				// Keep it clickable when blocked. The server owns the checks and returns the
				// exact reason; a silent disabled button gave the player no route forward.
				if (button.visible && materialsReady && state.siteExecutable()) {
					context.drawBorder(button.getX() - x - 1,
						button.getY() - y + pageScroll - 1, button.getWidth() + 2,
						button.getHeight() + 2, TEXT_OK);
				}
			}
		}
		if (materialConfigButton != null) {
			materialConfigButton.active = materialConfigButton.visible
				&& !state.materialChoices().isEmpty();
		}
		if (state.hasProject()) {
			drawClipped(context, Text.literal(state.projectName() + "  "
					+ state.stagesDone() + "/" + state.stages().size()),
				CONTENT_MARGIN, projectStatusY, contentWidth(), TEXT_PRIMARY);
			int line = projectStageY;
			for (String entry : state.stages()) {
				int colon = entry.indexOf(':');
				String kind = colon < 0 ? entry : entry.substring(0, colon);
				String stageState = colon < 0 ? "" : entry.substring(colon + 1);
				String stageKey = dev.squire.server.blueprint.TerrainLeveling.parse(state.placementBlueprintId()).isPresent()
					? "squire.gui.terrain.stage." : "squire.gui.stage.";
				drawClipped(context, Text.literal(marker(stageState) + " ")
						.append(Text.translatable(stageKey + kind.toLowerCase(java.util.Locale.ROOT))),
					CONTENT_MARGIN, line, contentWidth(), colour(stageState));
				line += LINE_H;
			}
			if (!state.blockedReason().isEmpty()) {
				line += 2;
				var wrapped = textRenderer.wrapLines(
					Text.literal(state.blockedReason()), contentWidth());
				for (int i = 0; i < wrapped.size() && i < BLOCKED_REASON_LINES; i++) {
					boolean last = i == BLOCKED_REASON_LINES - 1
						&& wrapped.size() > BLOCKED_REASON_LINES;
					context.drawText(textRenderer, wrapped.get(i), CONTENT_MARGIN, line,
						0xFFD08040, false);
					if (last) {
						context.drawText(textRenderer, Text.literal("…"),
							CONTENT_MARGIN + contentWidth() - 6, line, 0xFFD08040,
							false);
					}
					line += LINE_H;
				}
			}
			if ("MATERIALS_MISSING".equals(state.blockerCode())) {
				for (int i = 0; i < state.materialLines().size(); i++) {
					drawClipped(context, Text.literal("· " + state.materialLines().get(i)),
						CONTENT_MARGIN, line, contentWidth(), TEXT_WARN);
					line += LINE_H;
				}
			}
			return;
		}
		if (state.hasPlacement()) {
			int line = projectStatusY;
			drawClipped(context, Text.literal(state.placementName()),
				CONTENT_MARGIN, line, contentWidth(), TEXT_PRIMARY);
			line += LINE_H;
			boolean enough = state.materialLines().isEmpty();
			drawClipped(context, enough
					? Text.translatable("squire.gui.project.materials_ready")
					: Text.translatable("squire.gui.project.materials_missing",
						String.join("，", state.materialLines())),
				CONTENT_MARGIN, line, contentWidth(), enough ? TEXT_OK : TEXT_WARN);
			line += LINE_H;
			if (state.siteIssues().isEmpty()) {
				drawClipped(context,
					Text.translatable("squire.gui.project.site_ready"), CONTENT_MARGIN,
					line, contentWidth(), TEXT_OK);
			} else {
				for (String issue : state.siteIssues()) {
					boolean automatic = automaticSiteIssue(issue);
					drawClipped(context,
						Text.literal(automatic ? "↻ " : state.siteExecutable() ? "! " : "✘ ")
							.append(issue),
						CONTENT_MARGIN, line, contentWidth(),
						automatic || state.siteExecutable() ? TEXT_WARN : TEXT_DANGER);
					line += LINE_H;
				}
			}
			return;
		}
		context.drawText(textRenderer, Text.translatable("squire.gui.project.none"),
			CONTENT_MARGIN, projectStatusY, TEXT_MUTED, false);
		drawTemplateLibrary(context);
	}

	/** 模板库已由真实按钮绘制；保留入口以维持工程页渲染结构。 */
	private void drawTemplateLibrary(DrawContext context) {
		// no-op
	}

	private static boolean automaticSiteIssue(String issue) {
		return issue != null && (issue.contains("会自动") || issue.contains("将自动")
			|| issue.startsWith("开工前会"));
	}

	private void drawMaterialPage(DrawContext context) {
		List<String> choices = handler.state().materialChoices();
		for (int i = 0; i < choices.size() && i < 6; i++) {
			var decoded = PanelState.decodeMaterialChoice(choices.get(i));
			if (decoded.isEmpty()) {
				continue;
			}
			var choice = decoded.get();
			int top = materialFirstRowY + i * (BTN_H + 3);
			ItemStack icon = materialIcon(choice.representativeItemId());
			if (!icon.isEmpty()) {
				context.drawItem(icon, CONTENT_MARGIN + 26, top + 1);
			}
			String label = choice.slotName() + " · " + choice.familyName();
			context.drawText(textRenderer,
				Text.literal(textRenderer.trimToWidth(label, contentWidth() - 76)),
				CONTENT_MARGIN + 46, top + 5, 0xFFD8D8E0, false);
		}
	}

	private static ItemStack materialIcon(String rawId) {
		try {
			Identifier id = new Identifier(rawId);
			return Registries.ITEM.containsId(id)
				? Registries.ITEM.get(id).getDefaultStack() : ItemStack.EMPTY;
		} catch (RuntimeException ignored) {
			return ItemStack.EMPTY;
		}
	}

	/** 蓝图参数页的文字：当前这一份规格长什么样，以及锁着的那几项要几级。 */
	private void drawDesignPage(DrawContext context) {
		var spec = dev.squire.server.blueprint.ProjectSpec
			.parse(handler.state().placementBlueprintId()).orElse(null);
		if (spec == null) {
			drawClipped(context, Text.translatable("squire.gui.design.none"),
				CONTENT_MARGIN, designSpecY, contentWidth(), TEXT_MUTED);
		} else {
			drawClipped(context, Text.translatable("squire.gui.design.summary.geometry",
				Text.translatable("squire.gui.template." + spec.template().id()),
				spec.width(), spec.depth(), spec.floors(), spec.wallHeight()),
				CONTENT_MARGIN, designSpecY, contentWidth(), 0xFFB0D0B0);
			drawClipped(context, Text.translatable("squire.gui.design.summary.structure",
				designOption("roof", spec.roof().id()),
				designOption("foundation", spec.foundation().id()),
				designOption("window", spec.window().id()),
				designOption("entrance", spec.entrance().id())),
				CONTENT_MARGIN, designSpecY + LINE_H, contentWidth(), TEXT_LABEL);
			MutableText moduleNames = Text.empty();
			if (spec.orderedModules().isEmpty()) {
				moduleNames.append(Text.translatable("squire.gui.design.modules.none"));
			} else {
				for (var module : spec.orderedModules()) {
					if (!moduleNames.getString().isEmpty()) moduleNames.append(" · ");
					moduleNames.append(Text.translatable("squire.gui.module." + module.id()));
				}
			}
			drawClipped(context, Text.translatable("squire.gui.design.summary.extras",
				mirrorName(spec), moduleNames), CONTENT_MARGIN,
				designSpecY + LINE_H * 2, contentWidth(), TEXT_LABEL);
		}
		// 锁着的按钮旁边写清楚要几级——「灰着」本身不解释任何事。
		for (ButtonWidget button : lockedButtons) {
			var gate = lockGateOf(button);
			if (gate != null) {
				context.drawText(textRenderer,
					Text.translatable("squire.gui.profession.locked",
						gate.unlockLevel()),
					button.getX() - x + 2, button.getY() - y + pageScroll + 5,
					TEXT_LOCK, false);
			}
		}
		drawLockMarks(context);
	}

	/** 这个灰按钮对应哪一项能力。按标签反查，因为按钮本身不带 id。 */
	private dev.squire.server.profession.ProfessionAbility lockGateOf(
			ButtonWidget button) {
		for (SquireActions.Action action : SquireActions.ofPage(
				SquireActions.Page.DESIGN)) {
			if (button.getMessage().equals(Text.translatable(action.labelKey()))) {
				return SquireActions.DESIGN_GATES.get(action.id());
			}
		}
		return null;
	}

	private static String marker(String stageState) {
		return switch (stageState) {
			case "DONE" -> "[x]";
			case "SKIPPED" -> "[-]";
			case "RUNNING" -> "[>]";
			case "BLOCKED" -> "[!]";
			case "FAILED" -> "[X]";
			default -> "[ ]";
		};
	}

	private static int colour(String stageState) {
		return switch (stageState) {
			case "DONE", "SKIPPED" -> TEXT_OK;
			case "RUNNING" -> TEXT_PRIMARY;
			case "BLOCKED" -> 0xFFD08040;
			case "FAILED" -> TEXT_DANGER;
			default -> TEXT_MUTED;
		};
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// 槽位在非物品页已经被 handler 关掉，不会再画出来，也就不需要遮罩，
		// 鼠标坐标可以照常传下去，按钮的悬停反馈才是正常的。
		super.render(context, mouseX, mouseY, delta);
		if (tab == TAB_ITEMS) {
			drawMouseoverTooltip(context, mouseX, mouseY);
		} else if (tab == TAB_PROJECT && editingMaterials) {
			drawMaterialTooltip(context, mouseX, mouseY);
		}
	}

	/** Item previews are not slots, so provide the familiar item-name hover explicitly. */
	private void drawMaterialTooltip(DrawContext context, int mouseX, int mouseY) {
		List<String> choices = handler.state().materialChoices();
		for (int i = 0; i < choices.size() && i < 6; i++) {
			var decoded = PanelState.decodeMaterialChoice(choices.get(i));
			if (decoded.isEmpty()) continue;
			int iconX = x + CONTENT_MARGIN + 26;
			int iconY = y + materialFirstRowY + i * (BTN_H + 3) - pageScroll + 1;
			if (iconY < y + SquireScreenHandler.CONTENT_TOP
					|| iconY + 16 > y + CONTENT_BOTTOM
					|| mouseX < iconX || mouseX >= iconX + 16
					|| mouseY < iconY || mouseY >= iconY + 16) {
				continue;
			}
			ItemStack icon = materialIcon(decoded.get().representativeItemId());
			if (!icon.isEmpty()) {
				context.drawTooltip(textRenderer, icon.getName(), mouseX, mouseY);
			}
			return;
		}
	}

	private static String modeKey(AvatarEntity.MovementMode mode) {
		return switch (mode) {
			case FOLLOW -> "squire.gui.mode.follow";
			case STAY -> "squire.gui.mode.stay";
			case PATROL -> "squire.gui.mode.patrol";
			case IDLE -> "squire.gui.mode.idle";
		};
	}
}
