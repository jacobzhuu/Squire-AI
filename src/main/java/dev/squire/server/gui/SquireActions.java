package dev.squire.server.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.runtime.SquireRuntime;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 面板按钮的<b>唯一事实源</b>：id、文案 key、页面、布局、点击行为全在这张表里。
 *
 * <p>在此之前按钮被硬编码了两遍——客户端 {@code SquireScreen} 写死标签和坐标，
 * 服务端 {@code SquireScreenHandler#onButtonClick} 写死 {@code switch(id)}，
 * 中间靠 17 个手工对齐的 {@code BUTTON_*} 常量连接，没有任何测试盯着。
 * 两边各改一半不会报错，只会出现"按钮写着 X、点下去做 Y"的静默漂移。
 * 现在两边都查这张表，{@code SquireActionsTest} 盯着 id 唯一、文案存在、
 * 与旧常量对齐。</p>
 *
 * <p>布局字段 {@code (row, col, cols)} 正好是客户端
 * {@code addPageButton(label, id, col, cols, row)} 的形状；右列常驻按钮
 * 用 {@link Page#SIDE}，客户端按表内顺序放到固定 y 偏移上。</p>
 */
public final class SquireActions {

	/** 按钮所在区域。SIDE 是右列常驻列，其余是中央内容区的页。 */
	public enum Page {
		/** 顶部状态条上常驻的三个高频档位：跟随 / 待命 / 巡逻。 */
		SIDE,
		/** 状态条按下「更多」之后换上来的低频动作：回家 / 设家 / 遣散。 */
		MORE,
		ITEMS, COMMAND, PERMISSION, PROFILE, PROFESSION, PROJECT, MATERIAL,
		DESIGN, SHORTCUT
	}

	/**
	 * 蓝图参数页每个按钮对应的能力（决定它在哪一级解锁）。
	 *
	 * <p>放在动作表里而不是客户端：客户端只是<b>画</b>那把锁，能不能真的动手仍然由
	 * 服务端在执行时判。两边查同一张表，就不会出现「按钮亮着但服务端拒绝」。</p>
	 */
	public static final java.util.Map<Integer,
			dev.squire.server.profession.ProfessionAbility> DESIGN_GATES = designGates();

	private static java.util.Map<Integer,
			dev.squire.server.profession.ProfessionAbility> designGates() {
		var gates = new LinkedHashMap<Integer,
			dev.squire.server.profession.ProfessionAbility>();
		var ability = dev.squire.server.profession.ProfessionAbility.class;
		gates.put(SquireScreenHandler.BUTTON_DESIGN_FLOORS,
			dev.squire.server.profession.ProfessionAbility.ENGINEER_MULTI_FLOOR);
		for (int id : new int[] {SquireScreenHandler.BUTTON_DESIGN_ROOF,
				SquireScreenHandler.BUTTON_DESIGN_FOUNDATION,
				SquireScreenHandler.BUTTON_DESIGN_WINDOW,
				SquireScreenHandler.BUTTON_DESIGN_ENTRANCE}) {
			gates.put(id, dev.squire.server.profession.ProfessionAbility
				.ENGINEER_STRUCTURAL_VARIANTS);
		}
		gates.put(SquireScreenHandler.BUTTON_DESIGN_MIRROR,
			dev.squire.server.profession.ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR);
		for (int i = 0; i < dev.squire.server.blueprint.ProjectSpec.Module.values().length;
				i++) {
			gates.put(SquireScreenHandler.BUTTON_DESIGN_MODULE_BASE + i,
				dev.squire.server.profession.ProfessionAbility
					.ENGINEER_OPTIONAL_MODULES);
		}
		gates.put(SquireScreenHandler.BUTTON_DESIGN_PRESET_SAVE,
			dev.squire.server.profession.ProfessionAbility
				.ENGINEER_BLUEPRINT_PRESET_LIBRARY);
		gates.put(SquireScreenHandler.BUTTON_DESIGN_PRESET_LOAD,
			dev.squire.server.profession.ProfessionAbility
				.ENGINEER_BLUEPRINT_PRESET_LIBRARY);
		return java.util.Map.copyOf(gates);
	}

	/** 服务端点击路径：玩家已通过 owner/admin 校验。 */
	@FunctionalInterface
	public interface Handler {
		void run(ServerPlayerEntity player, AvatarEntity avatar);
	}

	/**
	 * 一个按钮。
	 *
	 * @param id           走原版 {@code ButtonClickC2SPacket} 的按钮号
	 * @param labelKey     文案 key（en/zh 都必须有，测试盯着）
	 * @param page         所在区域
	 * @param row          页内行号；SIDE 页表示右列第几个位置
	 * @param col/cols     页内列号与总列数（客户端用来等分宽度）
	 * @param closesScreen 点击后客户端要不要关面板（「能做啥」要把清单发到聊天，面板挡着看）
	 * @param node         权限页按钮对应的节点；其它页为 null
	 * @param handler      服务端行为
	 */
	public record Action(int id, String labelKey, Page page, int row, int col, int cols,
			boolean closesScreen, String node, Handler handler) { }

	public static final List<Action> ALL = buildAll();

	// ------------------------------------------------------------------ 指令页

	/**
	 * 「指令」页的常用动作清单搬到了 {@link CommandCatalog}。
	 *
	 * <p>原来这里有一张 {@code COMMAND_GROUPS}：十四个按钮按「打架 / 拿东西 /
	 * 盖房子 / 去哪儿」分四组，<b>一次全铺出来</b>。分组解决的是「这些按钮长得一样」，
	 * 没有解决更要命的那一件——一个还没转职的 Lv.0 侍从看到「保护我」和
	 * 「下界合金套装」并排摆着，会以为自己养了一个万能侍从。清单现在按职业、等级、
	 * 已解锁能力和当前状态算出来，没解锁的一格也不画。</p>
	 *
	 * <p>{@code SquireActionsTest} 盯着 {@link Page#COMMAND} 的每个按钮都被目录里的
	 * 某个档位用到——漏掉一个就是玩家再也点不到它。</p>
	 */
	public static final List<CommandCatalog.Entry> COMMAND_ENTRIES =
		CommandCatalog.ENTRIES;

	// ------------------------------------------------------------------ 权限页分组

	/**
	 * 一个权限有多危险。<b>只影响面板怎么画</b>——真正的判定在 PermissionManager，
	 * 这里改不了任何一个节点的语义。
	 */
	public enum Risk {
		/** 日常授权，收回了他就干不了活。 */
		NORMAL,
		/** 会改动世界或消耗你的东西，给出去之前该想一下。 */
		HIGH,
		/** 世界编辑：一次能改一大片地形，没有撤销。 */
		DANGER
	}

	/** 权限页的一组。顺序即画出来的顺序，节点必须来自 TOGGLEABLE_NODES。 */
	public record PermissionGroup(String labelKey, List<String> nodes) {
		public PermissionGroup {
			nodes = List.copyOf(nodes);
		}
	}

	/**
	 * 权限页的版面：三组两列的权限卡，而不是十三个一模一样的灰按钮。
	 *
	 * <p>{@code SquireActionsTest} 盯着这三组<b>恰好</b>覆盖 TOGGLEABLE_NODES
	 * 一次——漏一个就是玩家在面板上永远看不到那一项，多一个就是画了一个
	 * 点下去没有对应节点的开关。</p>
	 */
	public static final List<PermissionGroup> PERMISSION_GROUPS = List.of(
		new PermissionGroup("squire.gui.permgroup.basic", List.of(
			dev.squire.server.security.PermissionNodes.USE,
			dev.squire.server.security.PermissionNodes.TASK_FOLLOW,
			dev.squire.server.security.PermissionNodes.TASK_GUARD,
			dev.squire.server.security.PermissionNodes.TASK_ACQUIRE,
			dev.squire.server.security.PermissionNodes.TASK_BUILD)),
		new PermissionGroup("squire.gui.permgroup.world", List.of(
			dev.squire.server.security.PermissionNodes.WORLD_BREAK,
			dev.squire.server.security.PermissionNodes.WORLD_PLACE,
			dev.squire.server.security.PermissionNodes.WORLD_EDIT)),
		new PermissionGroup("squire.gui.permgroup.command", List.of(
			dev.squire.server.security.PermissionNodes.COMMAND_GIVE,
			dev.squire.server.security.PermissionNodes.COMMAND_EFFECT,
			dev.squire.server.security.PermissionNodes.COMMAND_WORLD,
			dev.squire.server.security.PermissionNodes.COMMAND_TELEPORT,
			dev.squire.server.security.PermissionNodes.AUTOMATION)));

	/**
	 * 这一档 hover 时说的话。
	 *
	 * <p>和 {@link #riskOf} 放在一起而不是放在客户端：颜色能告诉玩家「这一项不一样」，
	 * 但说不出<b>哪里不一样</b>，也说不出「授权之后是不是就随便他了」。后面这句话
	 * 是有事实依据的——高风险工具即使已授权，模型发起的调用仍要过
	 * {@code ToolGateway} 那一次确认。措辞和判据必须待在同一个文件里。</p>
	 */
	public static String riskTooltipKey(Risk risk) {
		return switch (risk == null ? Risk.HIGH : risk) {
			case DANGER -> "squire.gui.permissions.tip_danger";
			case HIGH -> "squire.gui.permissions.tip_high";
			case NORMAL -> "squire.gui.permissions.tip_normal";
		};
	}

	/** 这个节点画成什么颜色。认不出的节点按最保守的算（当高风险）。 */
	public static Risk riskOf(String node) {
		if (node == null) {
			return Risk.HIGH;
		}
		if (dev.squire.server.security.PermissionNodes.WORLD_EDIT.equals(node)) {
			return Risk.DANGER;
		}
		return node.startsWith("squire.task.") || node.equals(
			dev.squire.server.security.PermissionNodes.USE) ? Risk.NORMAL : Risk.HIGH;
	}

	private static List<Action> buildAll() {
		List<Action> all = new ArrayList<>();

		// —— 顶部状态条：任何页都能点，摆在标题下面那条横带上。
		// 只留三个<b>高频档位</b>。回家/设家/遣散是低频动作，收进「更多」——
		// 把七个长得一样的按钮并排铺在右边，玩家分不出哪个是「他现在什么状态」、
		// 哪个是「按下去会发生一件事」，而遣散就挤在它们中间。
		all.add(new Action(SquireScreenHandler.BUTTON_FOLLOW, "squire.gui.button.follow",
			Page.SIDE, 0, 0, 1, false, null, (p, a) -> report(p,
				SquireRuntime.get().executeControl(p,
					SquireRuntime.ControlIntent.FOLLOW))));
		all.add(new Action(SquireScreenHandler.BUTTON_STAY, "squire.gui.button.stay",
			Page.SIDE, 1, 0, 1, false, null, (p, a) -> report(p,
				SquireRuntime.get().executeControl(p,
					SquireRuntime.ControlIntent.STAY))));
		all.add(new Action(SquireScreenHandler.BUTTON_PATROL, "squire.gui.button.patrol",
			Page.SIDE, 2, 0, 1, false, null, (p, a) -> {
				// 面板和聊天必须给出一模一样的结果（ADR-046），
				// 包括「最近一次命令优先」这一条。
				int cancelled = SquireRuntime.get().ownerOverride(a);
				a.setPatrolMode();
				SquireRuntime.get().persistSnapshot(a);
				say(p, "开始在这附近巡逻。"
					+ (cancelled > 0 ? "（手上的 " + cancelled + " 件活先放下了）" : ""));
			}));

		// —— 「更多」里的低频动作。和上面三个共用同一条横带，点「更多」整条换过来。
		//
		// 「能做啥」不在这里，也不在别处：它原来是右列的第五个按钮，点一下关面板、
		// 往聊天里刷一屏清单。现在职业页有成长路线、权限页有权限清单，都在面板里，
		// 而聊天里问一句「你能做什么」那条路一直都在（SquireRuntime#describeCapabilities）。
		all.add(new Action(SquireScreenHandler.BUTTON_HOME, "squire.gui.button.home",
			Page.MORE, 0, 0, 1, false, null,
			(p, a) -> report(p, SquireRuntime.get().executeControl(p,
				SquireRuntime.ControlIntent.HOME_RETURN))));
		all.add(new Action(SquireScreenHandler.BUTTON_HOME_SET,
			"squire.gui.button.home_set", Page.MORE, 1, 0, 1, false, null,
			(p, a) -> report(p, SquireRuntime.get().setHome(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_DISMISS,
			"squire.gui.button.dismiss", Page.MORE, 2, 0, 1, true, null,
			(p, a) -> report(p, SquireRuntime.get().executeControl(p,
				SquireRuntime.ControlIntent.DISMISS))));

		// —— 物品页：一个「整理背包」。这一页的版面是槽位网格，(row, col, cols) 那套
		// 等分不适用，客户端把它固定摆在伙伴背包和玩家背包之间那条空带上。
		all.add(new Action(SquireScreenHandler.BUTTON_SORT_ITEMS,
			"squire.gui.button.sort_items", Page.ITEMS, 0, 0, 1, false, null, (p, a) -> {
				int freed = a.items().sort();
				say(p, freed > 0
					? "背包整理好了，空出 " + freed + " 格。"
					: "背包已经是整理过的样子了。");
			}));

		// —— 指挥页：战斗与救援 / 盖房子 / 要物资 / 自我武装。
		all.add(new Action(SquireScreenHandler.BUTTON_GUARD_START,
			"squire.gui.button.guard_start", Page.COMMAND, 0, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().startGuard(p, 16, true))));
		all.add(new Action(SquireScreenHandler.BUTTON_GUARD_STOP,
			"squire.gui.button.guard_stop", Page.COMMAND, 0, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().stopGuard(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_AID_OWNER,
			"squire.gui.button.aid_owner", Page.COMMAND, 1, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().startAidOwner(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_HEAL_SELF,
			"squire.gui.button.heal_self", Page.COMMAND, 1, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().startHealSelf(p))));
		// 「盖木屋 / 盖石屋」这两个按钮已经从面板上撤掉：它们走的是不带预览、
		// 不带确认的旧建造路径，而工程页的「基础施工」同样谁都点得动，还多了
		// 幽灵预览、缺料清单和确认这一步。聊天里说「盖个木屋」那条路没有动，
		// SquireRuntime#buildHouse 也照旧在。
		all.add(new Action(SquireScreenHandler.BUTTON_GIVE_16, "squire.gui.button.give_16",
			Page.COMMAND, 5, 0, 3, false, null, (p, a) -> requestHeldItem(p, 16)));
		all.add(new Action(SquireScreenHandler.BUTTON_GIVE_64, "squire.gui.button.give_64",
			Page.COMMAND, 5, 1, 3, false, null, (p, a) -> requestHeldItem(p, 64)));
		all.add(new Action(SquireScreenHandler.BUTTON_GIVE_256, "squire.gui.button.give_256",
			Page.COMMAND, 5, 2, 3, false, null, (p, a) -> requestHeldItem(p, 256)));
		all.add(new Action(SquireScreenHandler.BUTTON_EQUIP_IRON,
			"squire.gui.button.equip_iron", Page.COMMAND, 7, 0, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().equipSelfSet(p, "iron", false))));
		all.add(new Action(SquireScreenHandler.BUTTON_EQUIP_DIAMOND,
			"squire.gui.button.equip_diamond", Page.COMMAND, 7, 1, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().equipSelfSet(p, "diamond", false))));
		all.add(new Action(SquireScreenHandler.BUTTON_EQUIP_NETHERITE,
			"squire.gui.button.equip_netherite", Page.COMMAND, 7, 2, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().equipSelfSet(p, "netherite", false))));
		all.add(new Action(SquireScreenHandler.BUTTON_PATROL_ADD,
			"squire.gui.button.patrol_add", Page.COMMAND, 9, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().addPatrolPoint(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_PATROL_CLEAR,
			"squire.gui.button.patrol_clear", Page.COMMAND, 9, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().clearPatrolPoints(p))));

		// —— 随从页：跟随传送距离。点一下换下一档，按钮上直接写着当前值。
		// 做成循环按钮而不是滑块，是因为这块面板本来就是按钮驱动的；
		// 六个预设覆盖了「寸步不离」到「让他自己走路」的全部实际需求。
		// 放在最后一行（7）：能力清单和性格那几行文字是<b>变长的</b>，放在第 5 行时
		// 装了两三个能力就会被按钮压住。最后一行是这一页唯一高度确定的位置。
		all.add(new Action(SquireScreenHandler.BUTTON_FOLLOW_DISTANCE,
			"squire.gui.button.follow_distance", Page.PROFILE, 7, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().cycleFollowTeleportDistance(p))));
		// 打法开关。「手动」不是能点出来的档位——它是你把武器拖进主手之后的状态，
		// 点一下就回到自动。有了这个按钮，就不需要靠一句口令来解锁了。
		all.add(new Action(SquireScreenHandler.BUTTON_COMBAT_MODE,
			"squire.gui.button.combat_mode", Page.PROFILE, 7, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().cycleCombatMode(p))));
		// —— 随从页：四个自主档位。职业选择、晋升和职业能力统一放在职业页；
		// 这里不再暴露旧 Role/Track 的第二套“职业等级”。
		for (var level : dev.squire.server.profile.AutonomyLevel.values()) {
			final var target = level;
			all.add(new Action(SquireScreenHandler.BUTTON_AUTONOMY_BASE + level.ordinal(),
				"squire.gui.autonomy." + level.id(), Page.PROFILE,
				2 + level.ordinal() / 2, level.ordinal() % 2, 2, false, null,
				(p, a) -> report(p, SquireRuntime.get().setAutonomy(p, target.id()))));
		}


		// —— 职业页：转职 / 晋升 / 战斗姿态。
		//
		// 这三件事和上面的「职业与能力槽只展示，不做成按钮」<b>不是</b>同一类：
		// Role 是随时可切、带冷却的取舍，做成按钮会让专精退化成下拉菜单；而职业转职
		// 一辈子只做一次、晋升要交材料、姿态是战斗中随手要调的档位——它们全都必须
		// 在面板上够得着，否则一个不看命令的玩家永远走不完这条成长线。
		//
		// 每一个 handler 都<b>不重新判断条件</b>，而是转交给 SquireRuntime 上那条和
		// 命令共用的路径：条件只在一处判，面板灰不灰只是它的显示。
		for (var profession : dev.squire.server.profession.SquireProfession.values()) {
			final var target = profession;
			all.add(new Action(
				SquireScreenHandler.BUTTON_PROFESSION_CHOOSE_BASE + profession.ordinal(),
				"squire.gui.button.choose_" + profession.id(), Page.PROFESSION,
				0, profession.ordinal(), 2, false, null,
				(p, a) -> report(p, SquireRuntime.get().setProfession(p, target.id()))));
		}
		all.add(new Action(SquireScreenHandler.BUTTON_PROFESSION_PROMOTE,
			"squire.gui.button.promote", Page.PROFESSION, 1, 0, 1, false, null,
			(p, a) -> report(p, SquireRuntime.get().promoteProfession(p))));
		for (var stance : dev.squire.server.profession.CombatStance.values()) {
			final var target = stance;
			all.add(new Action(SquireScreenHandler.BUTTON_STANCE_BASE + stance.ordinal(),
				"squire.gui.stance." + stance.id(), Page.PROFESSION,
				2, stance.ordinal(), 3, false, null,
				(p, a) -> report(p, SquireRuntime.get().setCombatStance(p, target.id()))));
		}

		// —— 蓝图参数页：工程师逐级解锁的那一串参数，全部做成「点一下换下一档」。
		//
		// 按钮包只带得动一个 id、带不了值，所以每个参数都是循环按钮而不是输入框。
		// 每一个 handler 都转交给 SquireEngineerService 上带等级闸的那条路径——
		// 客户端把没解锁的灰掉只是<b>显示</b>，真正的判断在服务端，改包也绕不过去。
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_TEMPLATE,
			"squire.gui.button.design_template", Page.DESIGN, 0, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleTemplate(p, 1))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_FLOORS,
			"squire.gui.button.design_floors", Page.DESIGN, 0, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleFloors(p, 1))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_SIZE_DOWN,
			"squire.gui.button.design_smaller", Page.DESIGN, 1, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleSize(p, -1))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_SIZE_UP,
			"squire.gui.button.design_bigger", Page.DESIGN, 1, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleSize(p, 1))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_ROOF,
			"squire.gui.button.design_roof", Page.DESIGN, 2, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleRoof(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_FOUNDATION,
			"squire.gui.button.design_foundation", Page.DESIGN, 2, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleFoundation(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_WINDOW,
			"squire.gui.button.design_window", Page.DESIGN, 3, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleWindow(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_ENTRANCE,
			"squire.gui.button.design_entrance", Page.DESIGN, 3, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleEntrance(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_MIRROR,
			"squire.gui.button.design_mirror", Page.DESIGN, 4, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designCycleMirror(p))));
		// 模块一个按钮一个：让玩家看得见「门廊」和「塔楼」是两件不同的事，
		// 而不是一个循环按钮点五下才知道有哪些。
		var modules = dev.squire.server.blueprint.ProjectSpec.Module.values();
		for (int i = 0; i < modules.length; i++) {
			final int index = i;
			all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_MODULE_BASE + i,
				"squire.gui.module." + modules[i].id(), Page.DESIGN,
				5 + i / 3, i % 3, 3, false, null,
				(p, a) -> report(p, SquireRuntime.get().designCycleModule(p, index))));
		}
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_PRESET_SAVE,
			"squire.gui.button.design_preset_save", Page.DESIGN, 7, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designPresetSaveAuto(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_DESIGN_PRESET_LOAD,
			"squire.gui.button.design_preset_load", Page.DESIGN, 7, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().designPresetLoadNext(p))));

		// —— 工程页：选择 → 参数 → 幽灵调整 → 确认 → 进度控制，全程不必打字。
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_HOUSE_WOOD,
			"squire.gui.button.project_house_wood", Page.PROJECT, 0, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectStart(p,
				dev.squire.server.blueprint.HouseSpec.defaults().blueprintId()))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_HOUSE_STONE,
			"squire.gui.button.project_house_stone", Page.PROJECT, 0, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectStart(p,
				dev.squire.server.blueprint.HouseSpec.stoneDefaults().blueprintId()))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_MINE,
			"squire.gui.button.project_mine", Page.PROJECT, 1, 0, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectStart(p, "mine_outpost"))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_WATCHTOWER,
			"squire.gui.button.project_watchtower", Page.PROJECT, 1, 1, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectStart(p, "watchtower"))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_STORAGE,
			"squire.gui.button.project_storage", Page.PROJECT, 1, 2, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectStart(p, "storage_shed"))));
		all.add(new Action(SquireScreenHandler.BUTTON_BLUEPRINT_ROTATE,
			"squire.gui.button.blueprint_rotate", Page.PROJECT, 3, 0, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintRotate(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_BLUEPRINT_FORWARD,
			"squire.gui.button.blueprint_forward", Page.PROJECT, 3, 1, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintNudge(p, 1, 0))));
		all.add(new Action(SquireScreenHandler.BUTTON_BLUEPRINT_BACK,
			"squire.gui.button.blueprint_back", Page.PROJECT, 3, 2, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintNudge(p, -1, 0))));
		all.add(new Action(SquireScreenHandler.BUTTON_BLUEPRINT_LEFT,
			"squire.gui.button.blueprint_left", Page.PROJECT, 4, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintNudge(p, 0, -1))));
		all.add(new Action(SquireScreenHandler.BUTTON_BLUEPRINT_RIGHT,
			"squire.gui.button.blueprint_right", Page.PROJECT, 4, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintNudge(p, 0, 1))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_CONFIRM,
			"squire.gui.button.project_confirm", Page.PROJECT, 5, 0, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectConfirm(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_TRANSFER,
			"squire.gui.button.project_transfer", Page.PROJECT, 5, 1, 2, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintTransferMissing(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_PAUSE,
			"squire.gui.button.project_pause", Page.PROJECT, 7, 0, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectPause(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_RESUME,
			"squire.gui.button.project_resume", Page.PROJECT, 7, 1, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().projectResume(p))));
		all.add(new Action(SquireScreenHandler.BUTTON_PROJECT_CANCEL,
			"squire.gui.button.project_cancel", Page.PROJECT, 7, 2, 3, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintCancel(p))));
		for (int i = 0; i < 6; i++) {
			final int slot = i;
			all.add(new Action(SquireScreenHandler.BUTTON_MATERIAL_PREVIOUS_BASE + i,
				"squire.gui.button.material_previous", Page.MATERIAL, i, 0, 2,
				false, null, (p, a) -> report(p,
					SquireRuntime.get().blueprintCycleMaterial(p, slot, -1))));
			all.add(new Action(SquireScreenHandler.BUTTON_MATERIAL_NEXT_BASE + i,
				"squire.gui.button.material_next", Page.MATERIAL, i, 1, 2,
				false, null, (p, a) -> report(p,
					SquireRuntime.get().blueprintCycleMaterial(p, slot, 1))));
		}
		all.add(new Action(SquireScreenHandler.BUTTON_MATERIAL_RESET,
			"squire.gui.button.material_reset", Page.MATERIAL, 7, 0, 1, false, null,
			(p, a) -> report(p, SquireRuntime.get().blueprintResetMaterials(p))));

		// —— 快捷页：八个固定槽位。按钮上的名字来自玩家自己存的那一条，
		// 所以这里的 labelKey 只是「空槽」的占位文案，真正的标签由客户端按
		// PanelState 里的名字覆盖。
		// 每行还有一个「✎ 编辑」，它<b>不在这张表里</b>：那个按钮只切客户端的
		// 编辑态，不产生任何服务端行为，真正的保存走 SHORTCUT_PACKET。
		for (int i = 0; i < dev.squire.server.shortcut.ShortcutStore.MAX_PER_PLAYER;
				i++) {
			final int slot = i;
			all.add(new Action(SquireScreenHandler.BUTTON_SHORTCUT_RUN_BASE + i,
				"squire.gui.button.shortcut_empty", Page.SHORTCUT, i, 0, 2, false, null,
				(p, a) -> reportIfAny(p, SquireRuntime.get().runShortcut(p, slot))));
			all.add(new Action(SquireScreenHandler.BUTTON_SHORTCUT_DELETE_BASE + i,
				"squire.gui.button.shortcut_delete", Page.SHORTCUT, i, 1, 2, false, null,
				(p, a) -> report(p, SquireRuntime.get().deleteShortcutAt(p, slot))));
		}

		// —— 权限页：顺序必须与 TOGGLEABLE_NODES 一致，它同时是 permissionMask 的位序。
		List<String> nodes = SquireScreenHandler.TOGGLEABLE_NODES;
		for (int i = 0; i < nodes.size(); i++) {
			String node = nodes.get(i);
			int row = i / 2 + 1; // 第 0 行留给图例文字
			all.add(new Action(SquireScreenHandler.BUTTON_PERMISSION_BASE + i,
				nodeLabelKey(node), Page.PERMISSION, row, i % 2, 2, false, node,
				(p, a) -> toggleNode(p, node)));
		}
		return List.copyOf(all);
	}

	private static final Map<Integer, Action> BY_ID = new LinkedHashMap<>();
	static {
		for (Action action : ALL) {
			BY_ID.put(action.id(), action);
		}
	}

	/** {@code null} 表示面板没有这个按钮号。 */
	public static Action byId(int id) {
		return BY_ID.get(id);
	}

	public static List<Action> ofPage(Page page) {
		return ALL.stream().filter(a -> a.page() == page).toList();
	}

	/** 权限节点的文案 key：{@code squire.world.break} → {@code squire.gui.node.world_break}。 */
	public static String nodeLabelKey(String node) {
		return "squire.gui.node."
			+ node.substring("squire.".length()).replace('.', '_');
	}

	private static void toggleNode(ServerPlayerEntity player, String node) {
		var runtime = SquireRuntime.get();
		boolean had = runtime.permissions().has(player, node);
		if (had) {
			runtime.permissions().revoke(player.getUuid(), node);
		} else {
			runtime.permissions().grant(player.getUuid(), node);
		}
		say(player, (had ? "已收回权限：" : "已授予权限：") + node);
	}

	/**
	 * 「照着我手上这个再来 N 个」。取物是最高频的操作，但打字要报物品名——
	 * 手里拿着样品点一下，物品 id 由服务端直接读，既不用打字也不会认错东西。
	 */
	private static void requestHeldItem(ServerPlayerEntity player, int count) {
		var held = player.getMainHandStack();
		if (held.isEmpty()) {
			say(player, "先把想要的东西拿在手上，再点这个按钮。");
			return;
		}
		var id = net.minecraft.registry.Registries.ITEM.getId(held.getItem());
		report(player, SquireRuntime.get().giveByCommand(player, id.toString(), count, false));
	}

	static void say(ServerPlayerEntity player, String message) {
		player.sendMessage(net.minecraft.text.Text.literal("[Squire] " + message), false);
	}

	/** 把运行时的执行结果原样转给玩家——和在聊天栏说同一句话得到的回执完全一致。 */
	static void report(ServerPlayerEntity player,
			SquireRuntime.ExecutionResult result) {
		player.sendMessage(net.minecraft.text.Text.literal(result.message()), false);
	}

	/**
	 * 同上，但空回执<b>不发</b>。
	 *
	 * <p>快捷指令把活转交给另一个动作时，那个动作自己已经对玩家说过话了；这里再
	 * {@code report} 一次就是往聊天里插一行空白。</p>
	 */
	static void reportIfAny(ServerPlayerEntity player,
			SquireRuntime.ExecutionResult result) {
		if (result != null && !result.message().isBlank()) {
			report(player, result);
		}
	}

	private SquireActions() {
	}
}
