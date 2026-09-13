package dev.squire.server.gui;

import java.util.List;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.registry.SquireScreens;
import dev.squire.server.security.PermissionNodes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 右键侍从打开的操作面板：背包、装备、基础状态、权限、对话入口全在这一个界面里。
 *
 * <p>之所以把交互集中到这里：在此之前所有操作都得靠聊天里打字，玩家既看不到他身上
 * 有什么，也不知道自己能说哪些话。菜单把"他有什么/他在干嘛/我能让他干嘛"一次摊开。</p>
 *
 * <p>模式与权限的按钮走原版的 {@code ButtonClickC2SPacket}（{@link #onButtonClick}），
 * 不需要自定义封包；只有"发送聊天内容"因为要带字符串才另开了一个 C2S 包。</p>
 */
public class SquireScreenHandler extends ScreenHandler {
    public static final int BUTTON_GUARD_ATTACK = 1300;
    public static final int BUTTON_TASK_STOP = 1301;

	/** 装备槽顺序，界面从上到下就按这个排。 */
	public static final List<EquipmentSlot> EQUIPMENT_ORDER = List.of(
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
		EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);

	/** 面板上可切换的权限节点。顺序即按钮顺序，也是 {@link #permissionMask} 的位序。 */
	public static final List<String> TOGGLEABLE_NODES = List.of(
		PermissionNodes.USE,
		PermissionNodes.TASK_FOLLOW,
		PermissionNodes.TASK_GUARD,
		PermissionNodes.TASK_ACQUIRE,
		PermissionNodes.TASK_BUILD,
		PermissionNodes.WORLD_BREAK,
		PermissionNodes.WORLD_PLACE,
		PermissionNodes.WORLD_EDIT,
		PermissionNodes.COMMAND_GIVE,
		PermissionNodes.COMMAND_EFFECT,
		PermissionNodes.COMMAND_WORLD,
		PermissionNodes.COMMAND_TELEPORT,
		PermissionNodes.AUTOMATION);

	/**
	 * 按钮 id。模式一段，指令一段，权限一段，留出空隙方便以后加。
	 *
	 * <p>这些常量只是 id 的出处；按钮的文案、布局与点击行为全部登记在
	 * {@link SquireActions} 的表里，两边的一致性由 {@code SquireActionsTest} 盯着。</p>
	 */
	public static final int BUTTON_FOLLOW = 0;
	public static final int BUTTON_STAY = 1;
	public static final int BUTTON_PATROL = 2;
	public static final int BUTTON_HELP = 3;
	public static final int BUTTON_HOME = 4;
	public static final int BUTTON_GUARD_START = 5;
	public static final int BUTTON_GUARD_STOP = 6;
	public static final int BUTTON_AID_OWNER = 7;
	public static final int BUTTON_HEAL_SELF = 8;
	public static final int BUTTON_BUILD_WOOD = 9;
	public static final int BUTTON_BUILD_STONE = 10;
	/** 仅整理 Squire 已穿戴与自身主背包中的护甲，绝不生成装备。 */
	public static final int BUTTON_AUTO_EQUIP_BEST_ARMOR = 14;
	public static final int BUTTON_PATROL_ADD = 17;
	public static final int BUTTON_PATROL_CLEAR = 18;
	/** 自主档位四个按钮，下标即 {@code AutonomyLevel.ordinal()}。 */
	/** 跟随传送距离：点一下换下一档（8/12/16/24/32/48 格）。 */
	public static final int BUTTON_FOLLOW_DISTANCE = 30;
	/** 打法：自动 / 用弓 / 近战 / 手动（拖武器进主手时自动进入手动）。 */
	public static final int BUTTON_COMBAT_MODE = 31;
	public static final int BUTTON_ROLE_NEXT = 32;
	public static final int BUTTON_ABILITY_EQUIP_NEXT = 33;
	public static final int BUTTON_ABILITY_REMOVE_LAST = 34;
	/**
	 * 物品页的「整理背包」：合并同类、归拢到前面。
	 *
	 * <p>自己做而不是交给「一键背包整理 Next」：那个模组会把装备槽一起卷进去排序，
	 * 见 {@code dev.squire.client.gui.IpnCompat}。这里只动 36 格主背包。</p>
	 */
	public static final int BUTTON_SORT_ITEMS = 35;
	/** 背囊翻页。背包最多 120 格，面板一页 36 格，所以要翻。 */
	public static final int BUTTON_BACKPACK_PREV = 36;
	public static final int BUTTON_BACKPACK_NEXT = 37;
	public static final int BUTTON_HOME_SET = 38;
	public static final int BUTTON_DISMISS = 39;

	/** 快捷指令：第 i 条的「执行」与「删除」。 */
	public static final int BUTTON_SHORTCUT_RUN_BASE = 60;
	public static final int BUTTON_SHORTCUT_DELETE_BASE = 70;

	public static final int BUTTON_AUTONOMY_BASE = 40;
	/** 工程页的三个控制按钮。 */
	public static final int BUTTON_PROJECT_PAUSE = 50;
	public static final int BUTTON_PROJECT_RESUME = 51;
	public static final int BUTTON_PROJECT_CANCEL = 52;
	public static final int BUTTON_PROJECT_HOUSE_WOOD = 53;
	public static final int BUTTON_PROJECT_HOUSE_STONE = 54;
	public static final int BUTTON_PROJECT_MINE = 55;
	public static final int BUTTON_PROJECT_WATCHTOWER = 56;
	public static final int BUTTON_PROJECT_STORAGE = 57;
	public static final int BUTTON_PROJECT_FORCE_CANCEL = 58;
	public static final int BUTTON_HOUSE_WIDTH = 80;
	public static final int BUTTON_HOUSE_DEPTH = 81;
	public static final int BUTTON_HOUSE_HEIGHT = 82;
	public static final int BUTTON_HOUSE_MATERIAL = 83;
	public static final int BUTTON_HOUSE_ROOF = 84;
	public static final int BUTTON_BLUEPRINT_ROTATE = 85;
	public static final int BUTTON_BLUEPRINT_FORWARD = 86;
	public static final int BUTTON_BLUEPRINT_BACK = 87;
	public static final int BUTTON_BLUEPRINT_LEFT = 88;
	public static final int BUTTON_BLUEPRINT_RIGHT = 89;
	public static final int BUTTON_PROJECT_CONFIRM = 90;
	public static final int BUTTON_PROJECT_TRANSFER = 91;
	public static final int BUTTON_WATER_MODE = 92;
	public static final int BUTTON_WATER_SOURCE = 93;
	public static final int BUTTON_WATER_SUPPLIED = 94;
	public static final int BUTTON_PERMISSION_BASE = 100;
	/** Material editor: six fixed semantic slots, each with previous/next controls. */
	public static final int BUTTON_MATERIAL_PREVIOUS_BASE = 150;
	public static final int BUTTON_MATERIAL_NEXT_BASE = 160;
	public static final int BUTTON_MATERIAL_RESET = 170;

	// —— 职业页（Lv.0 转职 / 晋升 / 战斗姿态）。每一个都在服务端重新验一遍条件。
	public static final int BUTTON_PROFESSION_CHOOSE_BASE = 180;
	public static final int BUTTON_PROFESSION_PROMOTE = 185;
	public static final int BUTTON_STANCE_BASE = 190;

	// —— 蓝图参数页（工程师）。每一个都由服务端按等级再验一遍，客户端只负责灰掉。
	public static final int BUTTON_DESIGN_TEMPLATE = 200;
	public static final int BUTTON_DESIGN_SIZE_DOWN = 201;
	public static final int BUTTON_DESIGN_SIZE_UP = 202;
	public static final int BUTTON_DESIGN_FLOORS = 203;
	public static final int BUTTON_DESIGN_ROOF = 204;
	public static final int BUTTON_DESIGN_FOUNDATION = 205;
	public static final int BUTTON_DESIGN_WINDOW = 206;
	public static final int BUTTON_DESIGN_ENTRANCE = 207;
	public static final int BUTTON_DESIGN_MIRROR = 208;
	public static final int BUTTON_DESIGN_MODULE_BASE = 210;
	public static final int BUTTON_DESIGN_PRESET_SAVE = 220;
	public static final int BUTTON_DESIGN_PRESET_LOAD = 221;
	/** 蓝图库卡片：下标与 {@link dev.squire.server.blueprint.ProjectSpec.Template} 一致。 */
	public static final int BUTTON_DESIGN_TEMPLATE_BASE = 230;
	/** 职业页的侍从切换按钮；最多两名。 */
	public static final int BUTTON_ROSTER_BASE = 240;
	/** Reloadable fixed/external blueprint cards, ordered exactly as ProfessionView sends them. */
	public static final int BUTTON_BLUEPRINT_LIBRARY_BASE = 300;
	public static final int BUTTON_BLUEPRINT_LIBRARY_LIMIT = 64;
	public static final int BUTTON_TERRAIN_BASE = 400;
	public String terrainReview = "";
	private boolean verifiedTerrainAction;

	public void terrainAction(ServerPlayerEntity player, int id, String review) {
		boolean adjustment = id >= BUTTON_TERRAIN_BASE && id < BUTTON_TERRAIN_BASE
			+ dev.squire.server.runtime.TerrainLevelingService.Action.values().length;
		if (!adjustment && id != BUTTON_PROJECT_CONFIRM && id != BUTTON_PROJECT_CANCEL
			&& id != BUTTON_PROJECT_PAUSE && id != BUTTON_PROJECT_RESUME) return;
		var placement = dev.squire.server.runtime.SquireRuntime.get().blueprints().activeOf(player.getUuid()).orElse(null);
		String expected = placement == null ? "" : placement.placementId + "/" + placement.terrainReview;
		if (!expected.equals(review) || placement != null && (avatar == null || !avatar.agentId().equals(placement.agentId))) {
			player.sendMessage(net.minecraft.text.Text.literal("平地预览或施工者已变化，请查看更新后的页面再操作。"), false);
			syncState(player); return;
		}
		verifiedTerrainAction = true;
		try { onButtonClick(player, id); } finally { verifiedTerrainAction = false; }
	}

	private static final int AVATAR_INVENTORY_SIZE = AvatarEntity.MAIN_INVENTORY_SIZE;

	/** 装备列的格数：6 个装备槽 + 1 个背包槽。 */
	public static final int EQUIPMENT_COLUMN_SLOTS = EQUIPMENT_ORDER.size() + 1;
	/** 背包槽在 handler 里的下标（紧跟六个装备槽）。 */
	public static final int BACKPACK_SLOT_INDEX = EQUIPMENT_ORDER.size();
	/** 伙伴主背包 36 格的起始下标。 */
	public static final int AVATAR_GRID_START = BACKPACK_SLOT_INDEX + 1;
	/**
	 * 背囊内容那 36 格的起始下标：排在<b>所有</b>其它槽位之后。
	 *
	 * <p>放最后是为了不动前面任何一段的下标——shift+点击的区间、装备槽的位置、
	 * 玩家背包的偏移全都按下标算，中间插一段会一次性全部错位。</p>
	 */
	public static final int BACKPACK_CONTENTS_START =
		AVATAR_GRID_START + AvatarEntity.MAIN_INVENTORY_SIZE + 36;

	// —— 布局常量。改这里就能整体挪版，别再散落在两个类里各写一遍魔数。——
	// 页签占 18..34，槽位区必须整体让到它下面，否则物品图标会压在页签文字上。
	public static final int TAB_Y = 18;
	public static final int TAB_H = 16;
	public static final int CONTENT_TOP = 38;

	public static final int EQUIP_X = 8;
	public static final int EQUIP_Y = 40;
	public static final int GRID_X = 44;
	public static final int AVATAR_GRID_Y = 40;
	public static final int PLAYER_GRID_Y = 148;
	public static final int HOTBAR_Y = 206;

	/**
	 * 槽位是否参与渲染与命中。<b>仅客户端使用</b>：服务端实例永远保持 true。
	 *
	 * <p>非「物品」页必须把槽位真正关掉，而不是盖一层遮罩——原版把槽位和物品画在
	 * 按钮<em>之后</em>，且 {@code drawItem} 会把物品推到 z=232，普通 fill 根本盖不住，
	 * 物品会直接穿透到按钮上面。{@code drawSlots} 会跳过 isEnabled()==false 的槽位，
	 * 命中检测同样跳过，所以这是唯一干净的做法。</p>
	 */
	private boolean slotsVisible = true;

	public void setSlotsVisible(boolean visible) {
		this.slotsVisible = visible;
	}

	public boolean slotsVisible() {
		return slotsVisible;
	}

	/**
	 * 中间那块网格现在显示的是背囊还是伙伴自己的背包。<b>仅客户端使用</b>。
	 *
	 * <p>和 {@link #slotsVisible} 同理：服务端两套格子始终有效，客户端点哪一格就
	 * 送哪一个下标过来，两边的槽位列表是同一份，所以不会错位。</p>
	 */
	private boolean showingBackpack;

	public void setShowingBackpack(boolean showing) {
		this.showingBackpack = showing;
	}

	public boolean showingBackpack() {
		return showingBackpack;
	}

	private final Inventory avatarInventory;
	private final Inventory equipmentInventory;
	private final Inventory backpackInventory;
	/** 背囊当前这一页的内容（服务端穿透到真实背包，客户端是占位）。 */
	private final Inventory backpackContents;
	private final AvatarEntity avatar;
    public java.util.UUID snapshotAgent;
    public boolean inventoryAccessible;
    public boolean bodyAvailable;
	private final PlayerEntity opener;

	/** 客户端侧的展示状态；服务端每次改动后同步过来。 */
	/** 服务端推过来的全部面板事实。客户端不自行推断任何一项（ADR-001）。 */
	private PanelState synced = PanelState.EMPTY;

	/**
	 * 客户端构造：没有真实实体，两个容器都用占位的 SimpleInventory。
	 *
	 * <p>关键是<b>必须有真实的存储</b>：ScreenHandler 的同步是服务端逐槽读、客户端
	 * 逐槽写，客户端槽位如果没有后备容器，同步包就无处可落，界面永远是空的、
	 * 也点不动——之前装备栏不能操作正是这个原因。</p>
	 */
	public SquireScreenHandler(int syncId, PlayerInventory playerInventory) {
		this(syncId, playerInventory, new SimpleInventory(AVATAR_INVENTORY_SIZE),
			new SimpleInventory(EQUIPMENT_ORDER.size()), new SimpleInventory(1), null);
	}

	/** 客户端侧的背囊占位容器；服务端换成真正穿透到背包的那一个。 */
	private static Inventory backpackContentsFor(AvatarEntity avatar) {
		return avatar == null
			? new SimpleInventory(AvatarBackpackContentsInventory.PAGE_SIZE)
			: new AvatarBackpackContentsInventory(avatar);
	}

	public SquireScreenHandler(int syncId, PlayerInventory playerInventory,
			Inventory avatarInventory, Inventory equipmentInventory,
			Inventory backpackInventory, AvatarEntity avatar) {
		super(SquireScreens.SQUIRE, syncId);
		this.avatarInventory = avatarInventory;
		this.equipmentInventory = equipmentInventory;
		this.backpackInventory = backpackInventory;
		this.backpackContents = backpackContentsFor(avatar);
		this.avatar = avatar;
		this.opener = playerInventory.player;

		// 装备槽：左侧一列，护甲槽只接受对应部位的护甲。
		for (int i = 0; i < EQUIPMENT_ORDER.size(); i++) {
			addSlot(new EquipmentTabSlot(equipmentInventory, i, EQUIPMENT_ORDER.get(i),
				EQUIP_X, EQUIP_Y + i * 18));
		}
		// 背包槽：装备列最后一格，只收「能当容器用的物品」。
		addSlot(new BackpackTabSlot(backpackInventory,
			EQUIP_X, EQUIP_Y + EQUIPMENT_ORDER.size() * 18));
		// 伙伴背包 9×4
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new AvatarGridSlot(avatarInventory, col + row * 9,
					GRID_X + col * 18, AVATAR_GRID_Y + row * 18));
			}
		}
		// 玩家背包 9×3 + 快捷栏
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new TabSlot(playerInventory, col + row * 9 + 9,
					GRID_X + col * 18, PLAYER_GRID_Y + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new TabSlot(playerInventory, col, GRID_X + col * 18, HOTBAR_Y));
		}
		// 背囊内容：和伙伴背包那块网格<b>同一个位置</b>，两套格子永远只亮一套。
		// 放在最后是刻意的——前面所有按下标算的区间因此一格都不用改。
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new BackpackContentsSlot(backpackContents, col + row * 9,
					GRID_X + col * 18, AVATAR_GRID_Y + row * 18));
			}
		}
	}

	/** 只在「物品」页出现的槽位；其余页直接不渲染、不可命中。 */
	private class TabSlot extends Slot {
		TabSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean isEnabled() {
			return slotsVisible;
		}
	}

	/** 伙伴自己那 36 格：切到背囊视图时让位（两套格子在同一个位置上）。 */
	private final class AvatarGridSlot extends TabSlot {
		private AvatarGridSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean isEnabled() {
			return super.isEnabled() && !showingBackpack;
		}
	}

	/**
	 * 背囊内容槽：位置和伙伴背包那块网格重合，靠 {@link #showingBackpack} 二选一。
	 *
	 * <p>能不能放进去由那个背包自己说了算（过滤/虚空升级），我们不替它决定。</p>
	 */
	private final class BackpackContentsSlot extends Slot {
		private BackpackContentsSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean isEnabled() {
			if (!slotsVisible || !showingBackpack) {
				return false;
			}
			// 最后一页可能不满：多出来的格子不该显示成"能放东西的空位"。
			return backpackContents instanceof AvatarBackpackContentsInventory contents
				? contents.page() * AvatarBackpackContentsInventory.PAGE_SIZE + getIndex()
					< contents.backpackSize()
				: true;
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return backpackContents instanceof AvatarBackpackContentsInventory contents
				? contents.accepts(getIndex(), stack) : true;
		}
	}

	/**
	 * 背包槽：只收自己声明了「按格子分的物品存储」的东西（「精妙背包」的各级背包）。
	 *
	 * <p>判据不是写死的物品 id，而是问 {@code BackpackCompat}——没装背包模组时
	 * 这一格永远收不下任何东西，等于自动隐身，不会变成一个骗人的空槽。</p>
	 */
	private final class BackpackTabSlot extends TabSlot {
		private BackpackTabSlot(Inventory inventory, int x, int y) {
			super(inventory, 0, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return stack.isEmpty()
				|| dev.squire.server.compat.BackpackCompat.isBackpack(stack);
		}

		@Override
		public int getMaxItemCount() {
			return 1; // 背包是不可堆叠的；写死 1 免得别的模组的可堆叠容器塞一摞进来
		}
	}

	/** 装备槽：页签可见性 + 部位限制。存储由 {@link AvatarEquipmentInventory} 委托到实体。 */
	private final class EquipmentTabSlot extends TabSlot {
		private final EquipmentSlot equipment;

		private EquipmentTabSlot(Inventory inventory, int index, EquipmentSlot equipment,
				int x, int y) {
			super(inventory, index, x, y);
			this.equipment = equipment;
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			if (stack.isEmpty()) {
				return true;
			}
			// 手持槽什么都能拿；护甲槽只收对应部位的护甲。
			return equipment == EquipmentSlot.MAINHAND
				|| equipment == EquipmentSlot.OFFHAND
				|| net.minecraft.entity.LivingEntity.getPreferredEquipmentSlot(stack)
					== equipment;
		}

		@Override
		public int getMaxItemCount() {
			return equipment == EquipmentSlot.MAINHAND
				|| equipment == EquipmentSlot.OFFHAND ? 64 : 1;
		}
	}

	/**
	 * 面板是一个<b>遥控台</b>，不是一个箱子。
	 *
	 * <p>原来这里要求 8 格以内。可玩家一按 K 打开面板，伙伴还在跟着走/巡逻/施工——
	 * 他自己走出八格，服务端就把界面关掉了，玩家看到的是「刚打开就没了」。而面板里
	 * 能做的事（改模式、翻档案、点快捷指令、说话）没有一件需要伸手够得着他。</p>
	 *
	 * <p>所以只保留两条真正的前提：这具身体<b>还在世界里</b>（被卸载或死掉之后槽位
	 * 后面没有真实存储），以及和玩家<b>在同一个维度</b>（跨维度的实体不是同一个实例）。</p>
	 */
    public boolean canExchange(PlayerEntity player) {
        return avatar != null && canUse(player) && avatar.getWorld() == player.getWorld()
            && avatar.squaredDistanceTo(player) <= 64.0;
    }

    @Override
    public void onSlotClick(int slot, int button, net.minecraft.screen.slot.SlotActionType action, PlayerEntity player) {
        if (player.getWorld().isClient || canExchange(player)) super.onSlotClick(slot, button, action, player);
    }

	@Override
	public boolean canUse(PlayerEntity player) {
		return avatar == null || (avatar.isAlive() && !avatar.isRemoved()
			&& (avatar.ownerId().equals(player.getUuid()) || player.hasPermissionLevel(2)));
	}

	/**
	 * Shift+点击的搬运规则：装备/伙伴背包 ↔ 玩家背包。
	 *
	 * <p>必须完整实现，否则 shift 点一下就是一个静默的 no-op，玩家会以为界面坏了。</p>
	 */
	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
        if (!canExchange(player)) return ItemStack.EMPTY;
		// 背包槽夹在装备和伙伴背包之间，所以这三条边界都要算上它；
		// 少算一格的话 shift+点击会把东西塞进错误的区间。
		int equipmentEnd = AVATAR_GRID_START;
		int avatarEnd = equipmentEnd + AVATAR_INVENTORY_SIZE;
		int playerEnd = avatarEnd + 36;

		Slot slot = slots.get(index);
		if (!slot.hasStack()) {
			return ItemStack.EMPTY;
		}
		if (index >= playerEnd && backpackContents instanceof AvatarBackpackContentsInventory contents) {
			return quickMoveBackpackToPlayer(player, slot, contents, avatarEnd, playerEnd);
		}
		ItemStack stack = slot.getStack();
		ItemStack original = stack.copy();

		if (index >= playerEnd) {
			// 背囊里的东西 → 玩家背包。这一段在最后，所以只有它需要单独一条。
			if (!insertItem(stack, avatarEnd, playerEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else if (index < avatarEnd) {
			// 伙伴那边 → 玩家背包
			if (!insertItem(stack, avatarEnd, playerEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else if (!insertItem(stack, equipmentEnd, avatarEnd, false)) {
			// 玩家背包 → 伙伴背包
			return ItemStack.EMPTY;
		}
		// 一律写回，而不是「空了才 setStack、没空就 markDirty」。背囊那一段的
		// getStack() 给的是<b>副本</b>（内容在那个模组的存储里，不是我们的 ItemStack
		// 对象），只 markDirty 的话搬走一半会变成：玩家那边多了一半，背包里一个没少。
		slot.setStack(stack.isEmpty() ? ItemStack.EMPTY : stack);
		return original;
	}

	private ItemStack quickMoveBackpackToPlayer(PlayerEntity player, Slot slot,
			AvatarBackpackContentsInventory contents, int playerStart, int playerEnd) {
		int backpackIndex = slot.getIndex();
		ItemStack original = contents.exactStack(backpackIndex);
		if (original.isEmpty()) return ItemStack.EMPTY;
		ItemStack remaining = original.copy();
		List<ItemStack> playerBefore = new java.util.ArrayList<>(player.getInventory().size());
		for (int i = 0; i < player.getInventory().size(); i++)
			playerBefore.add(player.getInventory().getStack(i).copy());
		// ScreenHandler.insertItem assumes its input is an ordinary-sized stack and
		// inserts at most one max-sized stack per call. Expanded storage may return
		// hundreds of items, so offer it in legal chunks while keeping the entire
		// destination-side simulation inside the inventory snapshot transaction.
		while (!remaining.isEmpty()) {
			ItemStack offered = remaining.split(Math.min(remaining.getCount(),
				remaining.getMaxCount()));
			int offeredCount = offered.getCount();
			insertItem(offered, playerStart, playerEnd, true);
			if (!offered.isEmpty()) {
				remaining.increment(offered.getCount());
				break;
			}
			if (offeredCount <= 0) break;
		}
		int moved = original.getCount() - remaining.getCount();
		if (moved <= 0) return ItemStack.EMPTY;

		ItemStack extracted = contents.extractExact(backpackIndex, moved);
		if (extracted.getCount() != moved || !ItemStack.canCombine(original, extracted)) {
			for (int i = 0; i < playerBefore.size(); i++)
				player.getInventory().setStack(i, playerBefore.get(i));
			player.getInventory().markDirty();
			if (!extracted.isEmpty()) {
				var view = avatar.items().backpack();
				ItemStack remainderAfterRestore = view == null ? extracted : view.insert(extracted);
				if (!remainderAfterRestore.isEmpty())
					dev.squire.SquireMod.LOGGER.error("[Squire] backpack transfer rollback left {} item(s) unreturned", remainderAfterRestore.getCount());
			}
			return ItemStack.EMPTY;
		}
		return original;
	}

	/**
	 * 模式与权限按钮。字符串参数走不了这条路，所以聊天另走一个 C2S 包。
	 *
	 * <p>点击行为全部查 {@link SquireActions} 的表——id、文案、布局、行为都在那一张表里，
	 * 这里只负责 owner/admin 校验和点击后的状态同步。</p>
	 */
	@Override
	public boolean onButtonClick(PlayerEntity player, int id) {
		if (avatar == null && id == BUTTON_PROJECT_FORCE_CANCEL && player instanceof ServerPlayerEntity owner) {
			var runtime = dev.squire.server.runtime.SquireRuntime.get();
			var record = snapshotAgent == null ? null : runtime.agentStore().recordOfAgent(snapshotAgent).orElse(null);
			if (record == null || !record.ownerId.equals(owner.getUuid())) return false;
			runtime.agents().withTarget(owner.getUuid(), snapshotAgent,
				() -> SquireActions.byId(id).handler().run(owner, null));
			syncState(owner);
			return true;
		}
		if (avatar == null || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return false;
		}
		var runtime = dev.squire.server.runtime.SquireRuntime.get();
		if (!runtime.agents().isOwnerOf(player.getUuid(), avatar)
				&& !player.hasPermissionLevel(2)) {
			return false; // 不是主人，别动人家的伙伴
		}
		var terrainPlacement = runtime.blueprints().activeOf(player.getUuid()).orElse(null);
		if (!verifiedTerrainAction && (id >= BUTTON_TERRAIN_BASE && id < BUTTON_TERRAIN_BASE + 12
			|| terrainPlacement != null && dev.squire.server.blueprint.TerrainLeveling.parse(terrainPlacement.blueprintId).isPresent()
				&& (id == BUTTON_PROJECT_CONFIRM || id == BUTTON_PROJECT_CANCEL || id == BUTTON_PROJECT_PAUSE || id == BUTTON_PROJECT_RESUME))) {
			serverPlayer.sendMessage(net.minecraft.text.Text.literal("请使用当前平地预览页面操作。"), false);
			return false;
		}
		// 翻页不在动作表里，因为它<b>不对侍从做任何事</b>——和切页签一样是看的方式。
		// 动作表是"能对他下的命令"的唯一事实源，混进视图控制只会让那张表变模糊。
		if (id == BUTTON_BACKPACK_PREV || id == BUTTON_BACKPACK_NEXT) {
			turnBackpackPage(id == BUTTON_BACKPACK_NEXT ? 1 : -1);
			syncState(serverPlayer);
			return true;
		}
		if (id >= BUTTON_ROSTER_BASE && id < BUTTON_ROSTER_BASE + 2) {
			int index = id - BUTTON_ROSTER_BASE;
			var records = runtime.agentStore().recordsOfOwner(player.getUuid());
			if (index >= records.size()) return false;
			var record = records.get(index);
			runtime.agentStore().setPrimary(record.agentId);
			var target = runtime.agents().resolveByAgentId(record.agentId).orElse(null);
			if (target == null) {
				serverPlayer.sendMessage(net.minecraft.text.Text.literal(
					"[Squire] 这名侍从不在当前维度，请使用绑定给他的召集铃。"), false);
				syncState(serverPlayer);
				return true;
			}
			dev.squire.server.registry.SquireScreens.open(serverPlayer, target);
			return true;
		}
		if (id >= BUTTON_BLUEPRINT_LIBRARY_BASE
				&& id < BUTTON_BLUEPRINT_LIBRARY_BASE + BUTTON_BLUEPRINT_LIBRARY_LIMIT) {
			var library = synced.profession().blueprintLibrary();
			int index = id - BUTTON_BLUEPRINT_LIBRARY_BASE;
			if (index >= library.size()) return false;
			selectBlueprint(serverPlayer, library.get(index).id(), synced.profession().catalogVersion());
			return true;
		}
		SquireActions.Action action = SquireActions.byId(id);
		if (action == null) {
			return false;
		}
		runtime.agents().withTarget(player.getUuid(), avatar.agentId(),
			() -> action.handler().run(serverPlayer, avatar));
		runtime.persistSnapshot(avatar);
		avatar.refreshModeNameplate();
		syncState(serverPlayer);
		return true;
	}

	/**
	 * 职业页那一整块。
	 *
	 * <p>晋升材料的「已有多少」<b>同时数玩家背包和侍从背包</b>：玩家把钻石交给他之后
	 * 再打开面板，看到的必须还是「够了」，否则他会以为自己把材料弄丢了。
	 * 扣料那一步（{@code SquireProfessionService.promote}）数的是同一批位置。</p>
	 */
	private ProfessionView professionView(dev.squire.server.runtime.SquireRuntime runtime,
			ServerPlayerEntity player) {
		if (avatar == null) {
            return runtime.agentStore().recordOfAgent(snapshotAgent).map(r -> ProfessionView.of(r.profile.profession,runtime.professionConfig(), itemId -> 0)).orElse(ProfessionView.EMPTY);
		}
		var data = runtime.professionOf(avatar);
		if (data == null) {
			return ProfessionView.EMPTY;
		}
		ProfessionView view = ProfessionView.of(data, runtime.professionConfig(),
			itemId -> dev.squire.server.runtime.SquireProfessionService
				.countMaterial(player, avatar, itemId))
			.withBuildingCatalog(runtime.blueprints().registry().catalog(), catalogFamily, runtime.blueprints().registry().revision());
		return view.hasProfession()
				&& data.profession() == dev.squire.server.profession
					.SquireProfession.GUARD
			? withSupplies(view) : view;
	}

	/** 守卫页的补给概览。只在真的是守卫时才数一遍背包。 */
	private ProfessionView withSupplies(ProfessionView view) {
		int food = 0;
		int potions = 0;
		int arrows = 0;
		int weapons = 0;
		boolean bow = false;
		boolean shield = false;
		var items = avatar.items();
		for (int slot = 0; slot < items.size(); slot++) {
			net.minecraft.item.ItemStack stack = items.getStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (stack.getItem() instanceof net.minecraft.item.ArrowItem) {
				arrows += stack.getCount();
			} else if (stack.getItem() instanceof net.minecraft.item.BowItem) {
				bow = true;
			} else if (stack.getItem() instanceof net.minecraft.item.ShieldItem) {
				shield = true;
			} else if (stack.getItem() instanceof net.minecraft.item.PotionItem) {
				potions += stack.getCount();
			} else if (dev.squire.server.item.Remedies.classify(stack).isPresent()) {
				food += stack.getCount();
			} else if (dev.squire.server.body.avatar.AvatarInventory
					.attackDamageOf(stack) > 0) {
				weapons++;
			}
		}
		if (items.equipped(net.minecraft.entity.EquipmentSlot.MAINHAND)
				.getItem() instanceof net.minecraft.item.BowItem) {
			bow = true;
		}
		if (items.equipped(net.minecraft.entity.EquipmentSlot.OFFHAND)
				.getItem() instanceof net.minecraft.item.ShieldItem) {
			shield = true;
		}
		return view.withSupplies(food, potions, arrows, bow, shield, weapons);
	}

	/**
	 * 两项能靠「看一眼」就判定的新手训练。
	 *
	 * <p>放在状态同步里轮询，而不是各自去挂一个事件：开面板这件事的事件就是这次同步，
	 * 而「身上有没有装备」根本没有对应的事件——玩家可以拖拽、可以 shift 点击、
	 * 可以让他自己在家换装，挂三个监听不如每秒看一眼装备栏。</p>
	 */
	private void noteTrainingFromPanel(dev.squire.server.runtime.SquireRuntime runtime) {
		if (avatar == null) {
			return;
		}
		runtime.noteTraining(avatar,
			dev.squire.server.profession.TrainingMilestone.OPEN_PANEL);
		for (net.minecraft.entity.EquipmentSlot slot
				: dev.squire.server.body.avatar.AvatarInventory.EQUIPMENT_SLOTS) {
			if (!avatar.items().equipped(slot).isEmpty()) {
				runtime.noteTraining(avatar,
					dev.squire.server.profession.TrainingMilestone.EQUIP);
				break;
			}
		}
	}

	private int stateResyncAge;

	/**
	 * 周期性把状态重推一次。开面板时的第一条状态包有可能比客户端界面先到（那时
	 * 还没有 handler 可以接），只推一次的话模式会一直显示成"空闲"。定期重推让它自愈。
	 */
	@Override
	public void sendContentUpdates() {
		super.sendContentUpdates();
		if (opener instanceof ServerPlayerEntity server
				&& ++stateResyncAge % 20 == 0) {
			syncState(server);
		}
	}

	/** 把模式/权限的当前值刷到客户端。 */
	public void syncState(ServerPlayerEntity player) {
		var runtime = dev.squire.server.runtime.SquireRuntime.get();
		noteTrainingFromPanel(runtime);
		int mask = 0;
		for (int i = 0; i < TOGGLEABLE_NODES.size(); i++) {
			if (runtime.permissions().has(player, TOGGLEABLE_NODES.get(i))) {
				mask |= 1 << i;
			}
		}
		PanelState state = PanelState.of(
			avatar == null ? 0 : avatar.mode().ordinal(), mask,
			avatar == null ? 0 : (int) Math.ceil(avatar.getHealth()),
			avatar == null ? 20 : (int) Math.ceil(avatar.getMaxHealth()),
			avatar == null ? runtime.agentStore().recordOfAgent(snapshotAgent).map(r -> r.profile).orElse(null) : runtime.profileOf(avatar),
			avatar == null ? dev.squire.server.body.avatar.AvatarEntity
				.FOLLOW_TELEPORT_DEFAULT : avatar.followTeleportDistance(),
			runtime.shortcuts().names(player.getUuid()),
			avatar == null ? 0 : dev.squire.server.combat.CombatStyle
				.modeOf(avatar, runtime.weaponGatesOf(avatar)).ordinal(),
			runtime.shortcuts().specs(player.getUuid()),
			avatar == null ? runtime.agentStore().recordOfAgent(snapshotAgent).map(r -> r.displayName).orElse("") : runtime.displayNameOf(avatar),
			avatar == null ? 0
				: dev.squire.server.runtime.SquirePersonalityService
					.countShards(player, avatar),
			professionView(runtime, player));
		java.util.List<String> roster = new java.util.ArrayList<>();
		for (var record : runtime.agentStore().recordsOfOwner(player.getUuid())) {
			boolean active = runtime.agents().resolveByAgentId(record.agentId).isPresent();
			var profession = record.profile.profession.profession();
			roster.add(PanelState.encodeRosterEntry(record.agentId.toString(),
				record.displayName, profession == null ? "" : profession.id(), active,
				record.primary, record.agentId.equals(avatar == null ? snapshotAgent : avatar.agentId())));
		}
		synced = withConstruction(state, player, avatar)
			.withBackpack(backpackSize(), backpackPage()).withRoster(roster);
		SquireScreens.sendState(player, syncId, synced);
	}

	/** 客户端侧：整包替换，没有逐字段拼接也就没有错位。 */
	/**
	 * 把当前工程接到状态包上。阶段编成 {@code KIND:STATE}，客户端拆开后
	 * 查 lang key——文案在 lang 里，不在包里。
	 */
	private PanelState withConstruction(PanelState base,
			ServerPlayerEntity player, AvatarEntity avatar) {
		var runtime = dev.squire.server.runtime.SquireRuntime.get();
		var project = runtime.projects().activeOf(player.getUuid()).orElse(null);
		var placement = runtime.blueprints().activeOf(player.getUuid()).orElse(null);
        var target = avatar == null ? snapshotAgent : avatar.agentId();
        if (target == null || project != null && project.agentId() != null && !project.agentId().equals(target)
            || project == null && placement != null && !placement.agentId.equals(target)) return base;
        if (avatar == null) return recoveryProjectSummary(base, project);
		String liveMaterialReason = null;
		if (placement != null && avatar != null
				&& avatar.agentId().equals(placement.agentId)
				&& avatar.getWorld() instanceof net.minecraft.server.world.ServerWorld world) {
			var blueprint = runtime.blueprints().registry().byId(placement.blueprintId)
				.orElse(null);
			if (blueprint != null) {
				var resolved = runtime.blueprints().preview(placement).orElse(null);
				if (resolved == null) return recoveryProjectSummary(base, project);
				java.util.List<String> materials = new java.util.ArrayList<>();
				boolean activeProject = project != null
					&& project.placementId.equals(placement.placementId);
				var missing = dev.squire.server.blueprint.BlueprintManager
					.missingProjectMaterials(world, resolved, player, avatar,
						activeProject ? project.reservedMaterials() : java.util.Map.of());
				if (activeProject) {
					var escrowMissing = project.missingFrom(dev.squire.server.blueprint.BlueprintManager.requiredProjectMaterials(world, resolved));
					liveMaterialReason = missing.isEmpty()
						? (escrowMissing.isEmpty() ? "工程池材料已齐，可继续施工。" : "材料已备齐，但尚未全部存入工程池；点击“存入本批材料”继续。")
						: "已扣除工程池及双方背包的现有材料，下列为仍需带来的数量；可分批存入。";
				}
				for (var entry : missing.entrySet()) {
					materials.add(entry.getValue() + " × "
						+ runtime.itemAliases().displayName(entry.getKey().toString()));
				}
				java.util.List<String> materialChoices = new java.util.ArrayList<>();
				for (var slot : blueprint.materialSlots()) {
					String familyId = placement.materials().getOrDefault(slot.id(),
						slot.defaultFamilyId());
					var family = runtime.blueprints().registry().materials().byId(familyId)
						.orElse(null);
					if (family != null) {
						String slotName = slot.displayName();
						if (runtime.blueprints().registry().catalog().variant(blueprint.id()).isPresent()) {
							var policy = dev.squire.server.blueprint.BuildingContentPolicy.current();
							slotName += "（Lv" + (blueprint.materialSlots().indexOf(slot) == 0 ? policy.catalogMaterialLevel() : policy.catalogFullMaterialLevel()) + "）";
						}
						materialChoices.add(PanelState.encodeMaterialChoice(slot.id(),
							slotName, family.id(), family.displayName(),
							family.representative(slot.type())));
					}
				}
				var assessment = dev.squire.server.blueprint.SiteAssessment.assess(world,
					resolved, java.util.Set.of(player.getUuid(), avatar.getUuid()));
				var issues = new java.util.ArrayList<>(assessment.issues());
				boolean terrain = dev.squire.server.blueprint.TerrainLeveling.isTerrain(resolved);
				if (terrain) {
					var spec = dev.squire.server.blueprint.TerrainLeveling.parse(placement.blueprintId).orElseThrow();
					issues.add("范围 " + spec.width() + "×" + spec.depth() + "；地面方块 Y=" + placement.origin.getY() + "（顶面 Y=" + (placement.origin.getY() + 1) + "）");
					issues.add("X " + placement.origin.getX() + "～" + (placement.origin.getX() + spec.width() - 1)
						+ "；Z " + placement.origin.getZ() + "～" + (placement.origin.getZ() + spec.depth() - 1));
					issues.add("挖除 " + dev.squire.server.blueprint.BlueprintManager.pendingClear(world, resolved).size()
						+ " 格；填补 " + resolved.toPlace().size() + " 格；高度随实际地形计算");
					for (var entry : dev.squire.server.blueprint.BlueprintManager.requiredProjectMaterials(world, resolved).entrySet())
						issues.add("填料：" + runtime.itemAliases().displayName(entry.getKey().toString()) + " × " + entry.getValue());
				}
				long fillCells = resolved.toPlace().stream().filter(c -> dev.squire.server.blueprint.GroundPreparation.FILL.equals(c.what())).count();
				if (!terrain && fillCells > 0) issues.add("自动地基：" + fillCells + " 格，采样周边自然地面材料；填补与清理已计入工程，无需手动平整。");
				if (!terrain && resolved.costPlan() != null) {
					var cost = resolved.costPlan();
					int real = cost.operations().stream().filter(op -> !op.fluid()).mapToInt(dev.squire.server.blueprint.ConstructionCostPlan.Operation::base).sum();
					int payable = cost.operations().stream().filter(op -> !op.fluid()).mapToInt(dev.squire.server.blueprint.ConstructionCostPlan.Operation::total).sum();
					issues.add("账单 Lv" + cost.level() + "：永久材料 " + real + (payable <= real ? " - 节省 " : " + 损耗 ") + Math.abs(payable - real)
						+ " = 实付 " + payable + "（" + Math.abs(cost.wasteBasisPoints()) / 100.0 + "%）；确认后固定，通道、照明另计。");
					long fills = cost.operations().stream().filter(dev.squire.server.blueprint.ConstructionCostPlan.Operation::fluid).count();
					if (fills > 0) issues.add("另有注液操作 " + fills + " 次；满桶交付或循环取水按供水设置结算，不损耗空桶。");
				}
				resolved.siteRequirements().stream().filter(r -> !r.kind().equals("terrain_surface") && !r.kind().equals("terrain_air") && !r.satisfied(world)).limit(3)
					.forEach(r -> issues.add("原始场地要求 " + r.kind() + "：" + r.pos().toShortString()));
				boolean accessValid = resolved.access() == null || resolved.access().valid();
				if (!terrain && !placement.committed() && resolved.access() == null)
					issues.add("施工通路将在确认开工时检查；移动、旋转预览不再运行寻路。");
				if (terrain) {
					String permissionIssue = dev.squire.server.runtime.TerrainLevelingService.permissionIssue(player, resolved);
					if (!permissionIssue.isEmpty()) { issues.add(permissionIssue); accessValid = false; }
					if (dev.squire.server.blueprint.BlueprintManager.pendingClear(world, resolved).isEmpty()
						&& dev.squire.server.blueprint.BlueprintManager.pendingPlacements(world, resolved).isEmpty()) {
						issues.add("地面已平整，无需创建工程"); accessValid = false;
					}
				}
				if (dev.squire.server.blueprint.ConstructionFluids.hasFluids(resolved) || resolved.siteRequirements().stream().anyMatch(q -> q.kind().equals("fluid"))) {
					issues.add("水域：" + (placement.artificialWater() ? "人工挖池与永久围护已计入" : "沿用原有场地") + "；/squire project water artificial|existing 切换");
					issues.add(placement.waterSource() == null ? "供水：分批交付满桶；看向水源后 /squire project water source 可指定循环取水" : "循环取水：" + placement.waterSource().toShortString() + "；准备至少一个空桶");
					issues.add("液体无等级损耗；每次成功倒满桶返还 1 空桶，返还留在工程物资池；岩浆须交满桶。");
				}
				if (resolved.access() != null && !resolved.access().valid()) {
					if (terrain) issues.addAll(java.util.List.of(resolved.access().failure.split("；")));
					else issues.add(resolved.access().failure);
				}
				if (resolved.access() != null && resolved.access().excavationCount() > 0)
					issues.add("橙色预览：自动开挖 " + resolved.access().excavationCount() + " 格；请准备工具，掉落物保留，通路不自动回填。");
				if (resolved.access() != null && resolved.access().assistedCount() > 0)
					issues.add("需要辅助施工 " + resolved.access().assistedCount() + " 步；会自动安全移动或逐格施工，材料仍按账单扣除。");
				if (resolved.access() != null && resolved.access().assistance)
					issues.add("已启用施工恢复；原始原因：" + resolved.access().recoveryReason + "，恢复 " + resolved.access().recoveries + " 次。");
				if (resolved.access() != null && resolved.access().temporaryCount() > 0)
					issues.add("蓝色预览：临时通道最多外扩 " + dev.squire.server.blueprint.ConstructionAccessPlan.MARGIN + " 格；可回收施工设施 " + resolved.access().temporaryCount()
						+ "（未搭 " + resolved.access().remainingTemporary() + "），已计入备料。");
				base = base.withConstruction("", blueprint.displayName(),
					placement.state().name(), placement.blueprintId, materials,
					materialChoices, issues, assessment.executable() && accessValid);
			}
		}
		if (project == null || avatar == null || project.agentId() == null
				|| !project.agentId().equals(avatar.agentId())) {
			return base;
		}
		java.util.List<String> stages = new java.util.ArrayList<>();
		String pending = project.pendingMutation();
		String reason = pending.isEmpty() ? "" : "结算恢复待核对：" + pending.substring(0, Math.min(120, pending.length())) + "；不能自动重建或退款，材料与记录已保留。";
		for (var stage : project.stages()) {
			stages.add(stage.kind.name() + ":" + stage.state().name());
			if (stage.blockedReason() != null && reason.isEmpty()) {
				reason = stage.blockedReason();
				if (stage.blockerCode() == dev.squire.server.project.Stage.BlockerCode.MATERIALS_MISSING
						&& liveMaterialReason != null) reason = liveMaterialReason;
				base = base.withConstruction(stage.blockerCode().name(),
					base.placementName(), base.placementState(),
					base.placementBlueprintId(), base.materialLines(), base.siteIssues(),
					base.siteExecutable());
			}
		}
		if (reason.isEmpty() && project.state() == dev.squire.server.project.Project.State.RUNNING) {
			var access = runtime.blueprints().placement(project.placementId).flatMap(runtime.blueprints()::resolve)
				.map(dev.squire.server.blueprint.Blueprint.Resolved::access).orElse(null);
			if (access != null && !access.work.isEmpty()) reason = access.cleanup
				? "回收临时通道：剩余 " + access.placed.size() + " 格；仅回收本工程搭建的设施，新工程使用脚手架。"
				: "通道与建筑施工：" + access.cursor + "/" + access.work.size() + " 步；已搭通道 " + access.placed.size() + " 格。";
		}
		return base.withProject(project.name, project.state().name(), stages, reason);
	}

	private static PanelState recoveryProjectSummary(PanelState base, dev.squire.server.project.Project project) {
		if (project == null) return base;
		return base.withProject(project.name, project.state().name(), project.stages().stream()
			.map(stage -> stage.kind.name() + ":" + stage.state().name()).toList(),
			"施工者不可用或施工记录缺失。可强制取消并释放工程；材料账目封存，不自动退款。");
	}

	public void applyState(PanelState state) {
		this.synced = state == null ? PanelState.EMPTY : state;
	}

	private String catalogFamily = "";
	public void selectBlueprint(ServerPlayerEntity player, String id, long version) {
		var runtime = dev.squire.server.runtime.SquireRuntime.get();
		if (avatar == null || !runtime.agents().isOwnerOf(player.getUuid(), avatar)) return;
		var current = professionView(runtime, player);
		var entry = current.catalog().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
		if (version != current.catalogVersion() || entry == null || !entry.allowed() && !"family".equals(entry.kind())) {
			player.sendMessage(net.minecraft.text.Text.literal("建筑目录或等级已变化，请重新选择。"), false); syncState(player); return;
		}
		if ("family".equals(entry.kind())) {
			catalogFamily = "catalog:root".equals(id) ? "" : id;
		} else if ("parametric".equals(entry.kind())) {
			var template = dev.squire.server.blueprint.ProjectSpec.Template.byId(id.substring("template:".length()));
			if (template != null) onButtonClick(player, BUTTON_DESIGN_TEMPLATE_BASE + template.ordinal());
		} else {
			// Bind this panel's squire just like ordinary panel buttons do.
			runtime.agents().withTarget(player.getUuid(), avatar.agentId(), () -> {
				var result = runtime.projectStart(player, id);
				player.sendMessage(net.minecraft.text.Text.literal(result.message()), false);
				return result;
			});
		}
		syncState(player);
	}

	/** 面板要显示的服务端事实。 */
	public PanelState state() {
		return synced;
	}

	public int health() {
		return synced.health();
	}

	public int maxHealth() {
		return synced.maxHealth();
	}

	public AvatarEntity.MovementMode mode() {
		var values = AvatarEntity.MovementMode.values();
		return values[Math.floorMod(synced.mode(), values.length)];
	}

	public boolean hasPermission(int nodeIndex) {
		return (synced.permissions() & (1 << nodeIndex)) != 0;
	}

	public AvatarEntity avatar() {
		return avatar;
	}

	public PlayerEntity opener() {
		return opener;
	}

	/** 伙伴的名字（去掉状态后缀），给界面标题用。 */
	public String avatarName() {
		if (avatar == null) {
			return "Squire";
		}
		var name = avatar.getCustomName();
		return name == null ? "Squire" : name.getString();
	}

	/** 装备槽数量之外的一个便利常量，客户端排版用。 */
	public static int equipmentSlotCount() {
		return EQUIPMENT_ORDER.size();
	}

	/** 伙伴背包（服务端用真实背包，客户端用占位）。 */
	public Inventory avatarInventory() {
		return avatarInventory;
	}

	public Inventory equipmentInventory() {
		return equipmentInventory;
	}

	/** 背包槽（一格）。 */
	public Inventory backpackInventory() {
		return backpackInventory;
	}

	/** 背囊内容（当前这一页）。 */
	public Inventory backpackContents() {
		return backpackContents;
	}

	/** Server-side entity bound to this exact open panel; null on the client. */
	public AvatarEntity avatarEntity() {
		return avatar;
	}

	/**
	 * 翻页。按钮走的是原版的 {@code ButtonClickC2SPacket}，所以页号是<b>服务端</b>状态：
	 * 映射发生在服务端的适配器里，客户端只是收同步过来的那一页内容。
	 */
	public void turnBackpackPage(int delta) {
		if (backpackContents instanceof AvatarBackpackContentsInventory contents) {
			contents.setPage(contents.page() + delta);
		}
	}

	/** 背包一共多少格；没背包就是 0。面板据此决定要不要画翻页按钮。 */
	public int backpackSize() {
		return backpackContents instanceof AvatarBackpackContentsInventory contents
			? contents.backpackSize() : synced.backpackSlots();
	}

	public int backpackPage() {
		return backpackContents instanceof AvatarBackpackContentsInventory contents
			? contents.page() : synced.backpackPage();
	}

	/** 便利：把 AvatarInventory 的主背包暴露成 Inventory 供槽位使用。 */
	public static Inventory mainInventoryOf(AvatarInventory items) {
		return items.mainInventory();
	}
}
