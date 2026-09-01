package dev.squire.server.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;

/**
 * 「指令」页那张<b>常用动作</b>清单，以及快捷指令绑定的动作目录。
 *
 * <h2>为什么要有这张表</h2>
 * <p>指挥页原来把十四个按钮一次全铺出来：一个还没转职的 Lv.0 侍从看到「保护我」
 * 和「下界合金套装」并排摆着，会以为自己养了一个万能侍从——而职业系统的整个意义
 * 就是他<b>不是</b>。这张表回答的是「这只侍从<em>现在</em>能做什么」，输入是职业、
 * 等级、已解锁能力和当前状态四项，输出是一份真实可点的清单。没解锁的一律
 * <b>不画</b>，只在下面留一行「下一相关能力 Lv.X 解锁」。</p>
 *
 * <h2>它同时是快捷指令的动作目录</h2>
 * <p>快捷指令存的是 {@link Entry#id()} + 参数，而不是一句自然语言：点一下走的是
 * 服务端动作，不经过模型。执行前 {@link #lockOf} 会拿<b>服务端</b>的职业档案再判一次，
 * 所以一条因为转职/掉级而失效的快捷不会绕过等级闸——它照样留在面板上，
 * 只是显示成锁着的，点下去得到一句说明。</p>
 *
 * <p><b>这张表不判权限。</b>权限属于「允不允许做」，判据在 {@code PermissionManager}，
 * 由动作自己那条运行时路径去问——两处各判一半只会让面板和执行说的话不一样。</p>
 */
public final class CommandCatalog {

	/** 点下去之后跳到哪一页。{@link #NONE} 表示这一格是真的动作，不是导航。 */
	public enum Nav {
		NONE,
		/** 工程页（基础施工与模板库都在那儿）。 */
		PROJECT,
		/** 工程页的材料配置子页。 */
		PROJECT_MATERIALS,
		/** 工程页的蓝图参数子页。 */
		PROJECT_DESIGN,
		/** 职业页（训练进度、转职、晋升）。 */
		PROFESSION
	}

	/** 除职业与能力之外，这一格还要求的当前状态。 */
	public enum Requires {
		NONE,
		/** 手上有一件正在施工的工程。 */
		PROJECT_ACTIVE,
		/** 手上有一份正在放置的蓝图预览。 */
		PLACEMENT_ACTIVE,
		/** 还没转职（训练进度只对 Lv.0 有意义）。 */
		NO_PROFESSION
	}

	/**
	 * 一格动作的一个档位：参数值 → 真正要点的那个按钮。
	 *
	 * <p>「要 16 / 64 / 256 个」是<b>同一件事的三个档</b>，不是三件事——所以它们是
	 * 一格里的三个 variant，而不是三个和「保护我」一样大的按钮。快捷指令存的
	 * {@code arg} 就是这里的 {@link #arg()}。</p>
	 */
	public record Variant(String arg, String labelKey, int actionId) {
		public Variant {
			arg = arg == null ? "" : arg;
		}
	}

	/**
	 * 门槛：谁、在什么状态下能做这件事。
	 *
	 * @param profession 必须是这个职业；null 表示与职业无关
	 * @param ability    必须已解锁这项能力（它自带职业和等级）；null 表示不看能力
	 * @param requires   还要求的当前状态
	 */
	public record Gate(SquireProfession profession, ProfessionAbility ability,
			Requires requires) {

		public Gate {
			requires = requires == null ? Requires.NONE : requires;
			// 能力自带职业：两处各写一遍迟早会不一致，以能力为准。
			if (ability != null) {
				profession = ability.profession();
			}
		}

		/** 谁都能做，什么状态都能做。 */
		public static final Gate COMMON = new Gate(null, null, Requires.NONE);

		public static Gate of(Requires requires) {
			return new Gate(null, null, requires);
		}

		public static Gate of(SquireProfession profession) {
			return new Gate(profession, null, Requires.NONE);
		}

		public static Gate of(SquireProfession profession, Requires requires) {
			return new Gate(profession, null, requires);
		}

		public static Gate of(ProfessionAbility ability) {
			return new Gate(null, ability, Requires.NONE);
		}

		public static Gate of(ProfessionAbility ability, Requires requires) {
			return new Gate(null, ability, requires);
		}

		/** 这一格最低要几级。没有职业门槛时是 0。 */
		public int minLevel() {
			if (ability != null) {
				return ability.unlockLevel();
			}
			return profession == null ? 0 : SquireProfession.MIN_LEVEL;
		}
	}

	/**
	 * 常用动作里的一格。
	 *
	 * @param id           快捷指令存下来的那个字符串，永远不要改（改了就是让玩家
	 *                     存好的快捷指向一个不存在的动作）
	 * @param displayName  聊天里说的中文名；界面用 {@link #labelKey()}
	 * @param gate         什么时候出现
	 * @param nav          点一下跳到哪一页；{@link Nav#NONE} 表示直接执行
	 * @param variants     可执行的档位。空表示这一格只能导航；多于一个时面板画成
	 *                     一排紧凑小格。<b>导航格也可以有 variants</b>——面板上它是
	 *                     一个入口，但快捷指令可以直接绑定其中一个档位
	 *                     （「基础施工」就是这样：面板跳工程页，快捷直接开工）
	 */
	public record Entry(String id, String displayName, Gate gate, Nav nav,
			List<Variant> variants) {

		public Entry {
			variants = List.copyOf(variants);
		}

		public String labelKey() {
			return "squire.gui.command." + id.replace('.', '_');
		}

		/** 面板上这一格是导航还是动作。有 nav 就以 nav 为准，规则只有这一条。 */
		public boolean navigates() {
			return nav != Nav.NONE;
		}

		/** 面板上画成「说明在左、一排小格在右」的紧凑行。 */
		public boolean compact() {
			return !navigates() && variants.size() > 1;
		}

		/** 能被快捷指令绑定吗。纯导航格（没有任何档位）不行。 */
		public boolean bindable() {
			return !variants.isEmpty();
		}

		/** 认不出的参数回 {@code null}；只有一个档位时参数可以是空串。 */
		public Variant variant(String arg) {
			String needle = arg == null ? "" : arg;
			for (Variant variant : variants) {
				if (variant.arg().equals(needle)) {
					return variant;
				}
			}
			return needle.isEmpty() && variants.size() == 1 ? variants.get(0) : null;
		}

		/** 默认档位：面板新建快捷时先替玩家选上的那个。 */
		public Variant defaultVariant() {
			return variants.isEmpty() ? null : variants.get(0);
		}
	}

	private static Variant variant(String arg, String labelKey, int actionId) {
		return new Variant(arg, labelKey, actionId);
	}

	/** 只有一个档位的动作：参数是空串，面板画成一个普通按钮。 */
	private static List<Variant> single(String labelKey, int actionId) {
		return List.of(new Variant("", labelKey, actionId));
	}

	/**
	 * 常用动作的<b>全表</b>。顺序即面板上从上到下的顺序。
	 *
	 * <p>每一格的 {@code gate} 必须对应服务端真的会检查的东西：「保护我」是守卫的
	 * 主动能力，「解除护卫」任何时候都要能按下去（否则一个转职失败的玩家会被
	 * 永久护卫着），「救我 / 自己回血」保留原有语义、和职业无关。</p>
	 */
	public static final List<Entry> ENTRIES = List.of(
		// —— 战斗与救援
		new Entry("guard.start", "保护我", Gate.of(SquireProfession.GUARD), Nav.NONE,
			single("squire.gui.button.guard_start",
				SquireScreenHandler.BUTTON_GUARD_START)),
		// 停止永远是 COMMON：能开始的东西必须随时能停，哪怕开始它的那个职业没了。
		new Entry("guard.stop", "解除护卫", Gate.COMMON, Nav.NONE,
			single("squire.gui.button.guard_stop",
				SquireScreenHandler.BUTTON_GUARD_STOP)),
		new Entry("aid.owner", "救我", Gate.COMMON, Nav.NONE,
			single("squire.gui.button.aid_owner",
				SquireScreenHandler.BUTTON_AID_OWNER)),
		new Entry("heal.self", "自己回血", Gate.COMMON, Nav.NONE,
			single("squire.gui.button.heal_self",
				SquireScreenHandler.BUTTON_HEAL_SELF)),

		// —— 物资。数量是一个选择，不是三件事。
		new Entry("inventory.give", "给我物品", Gate.COMMON, Nav.NONE, List.of(
			variant("16", "squire.gui.button.give_16",
				SquireScreenHandler.BUTTON_GIVE_16),
			variant("64", "squire.gui.button.give_64",
				SquireScreenHandler.BUTTON_GIVE_64),
			variant("256", "squire.gui.button.give_256",
				SquireScreenHandler.BUTTON_GIVE_256))),
		new Entry("equip.self", "自动装备", Gate.of(SquireProfession.GUARD), Nav.NONE,
			List.of(
				variant("iron", "squire.gui.button.equip_iron",
					SquireScreenHandler.BUTTON_EQUIP_IRON),
				variant("diamond", "squire.gui.button.equip_diamond",
					SquireScreenHandler.BUTTON_EQUIP_DIAMOND),
				variant("netherite", "squire.gui.button.equip_netherite",
					SquireScreenHandler.BUTTON_EQUIP_NETHERITE))),
		// 巡逻档位在顶部状态条上，这里管的是巡逻<b>点</b>——两件不同的事。
		new Entry("patrol.points", "巡逻点", Gate.COMMON, Nav.NONE, List.of(
			variant("add", "squire.gui.button.patrol_add",
				SquireScreenHandler.BUTTON_PATROL_ADD),
			variant("clear", "squire.gui.button.patrol_clear",
				SquireScreenHandler.BUTTON_PATROL_CLEAR))),

		// —— 施工与工程。面板上是一个入口，快捷可以直接绑到某个模板上。
		new Entry("build.basic", "基础施工", Gate.COMMON, Nav.PROJECT, List.of(
			variant("oak_house", "squire.gui.button.project_house_wood",
				SquireScreenHandler.BUTTON_PROJECT_HOUSE_WOOD),
			variant("stone_house", "squire.gui.button.project_house_stone",
				SquireScreenHandler.BUTTON_PROJECT_HOUSE_STONE),
			variant("mine_outpost", "squire.gui.button.project_mine",
				SquireScreenHandler.BUTTON_PROJECT_MINE),
			variant("watchtower", "squire.gui.button.project_watchtower",
				SquireScreenHandler.BUTTON_PROJECT_WATCHTOWER),
			variant("storage_shed", "squire.gui.button.project_storage",
				SquireScreenHandler.BUTTON_PROJECT_STORAGE))),
		new Entry("project.resume", "继续工程", Gate.of(Requires.PROJECT_ACTIVE),
			Nav.NONE, single("squire.gui.button.project_resume",
				SquireScreenHandler.BUTTON_PROJECT_RESUME)),
		new Entry("project.materials", "材料配置", Gate.of(Requires.PLACEMENT_ACTIVE),
			Nav.PROJECT_MATERIALS, List.of()),
		new Entry("blueprint.open", "打开蓝图", Gate.of(SquireProfession.ENGINEER),
			Nav.PROJECT, List.of()),
		// 「当前已解锁的蓝图操作」就是参数子页本身：它一格一格照能力表灰着或亮着，
		// 那份清单不该在指令页上再抄一遍（抄一份的代价是两页迟早说的不一样）。
		new Entry("blueprint.design", "蓝图参数",
			Gate.of(ProfessionAbility.ENGINEER_BASIC_BLUEPRINT,
				Requires.PLACEMENT_ACTIVE),
			Nav.PROJECT_DESIGN, List.of()),

		// —— Lv.0：这条线通向哪儿，必须在他还没转职的时候就看得见。
		new Entry("training.progress", "查看训练进度", Gate.of(Requires.NO_PROFESSION),
			Nav.PROFESSION, List.of()));

	private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

	static {
		for (Entry entry : ENTRIES) {
			BY_ID.put(entry.id(), entry);
		}
	}

	/** {@code null} 表示这个 id 不是（或者不再是）一个动作。 */
	public static Entry byId(String id) {
		return id == null ? null : BY_ID.get(id.trim());
	}

	// ------------------------------------------------------------------ 判定

	/**
	 * 判定用的<b>全部事实</b>。客户端从状态包装，服务端从职业档案装，两边同一套判据。
	 */
	public record Context(SquireProfession profession, int level,
			Set<String> abilities, boolean hasProject, boolean hasPlacement) {

		public Context {
			abilities = abilities == null ? Set.of() : Set.copyOf(abilities);
			level = Math.max(0, level);
		}

		public boolean hasProfession() {
			return profession != null;
		}

		/** 客户端：状态包里的每一项都是服务端算好的，客户端一项也不自己推断。 */
		public static Context of(PanelState state) {
			if (state == null) {
				return new Context(null, 0, Set.of(), false, false);
			}
			ProfessionView view = state.profession();
			return new Context(view.profession(), view.level(),
				Set.copyOf(view.unlockedAbilities()), state.hasProject(),
				state.hasPlacement());
		}

		/** 服务端：执行快捷指令之前拿真实档案再判一次的那一份。 */
		public static Context of(ProfessionData data, boolean hasProject,
				boolean hasPlacement) {
			if (data == null) {
				return new Context(null, 0, Set.of(), hasProject, hasPlacement);
			}
			Set<String> unlocked = new LinkedHashSet<>();
			for (ProfessionAbility ability : ProfessionAbility.values()) {
				if (data.can(ability)) {
					unlocked.add(ability.id());
				}
			}
			return new Context(data.profession(), data.level, unlocked, hasProject,
				hasPlacement);
		}
	}

	/** 为什么点不了。 */
	public enum Reason {
		/** 职业不对。 */
		PROFESSION,
		/** 职业对了但等级不够。 */
		LEVEL,
		/** 手上没有正在施工的工程。 */
		PROJECT,
		/** 手上没有正在放置的蓝图。 */
		PLACEMENT,
		/** 已经转职了，这一格只对 Lv.0 有意义。 */
		TRAINED
	}

	/**
	 * 一把锁。面板拿 {@link #labelKey()} 画字，聊天回执拿 {@link #describe()}。
	 *
	 * <p>两套文案都从这里出，是因为「需要 工程师 Lv.7」这句话必须在面板上和
	 * 拒绝执行的那一句里<b>一模一样</b>——玩家看到的锁和被拒的理由说的不是同一件事，
	 * 只会让他以为界面坏了。</p>
	 */
	public record Lock(Reason reason, SquireProfession profession, int level) {

		public String labelKey() {
			return switch (reason) {
				case PROFESSION -> "squire.gui.command.lock_profession";
				case LEVEL -> "squire.gui.command.lock_level";
				case PROJECT -> "squire.gui.command.lock_project";
				case PLACEMENT -> "squire.gui.command.lock_placement";
				case TRAINED -> "squire.gui.command.lock_trained";
			};
		}

		/** 聊天里说的那一句（中文），和面板上那把锁说的是同一件事。 */
		public String describe() {
			return switch (reason) {
				case PROFESSION -> "需要" + professionName();
				case LEVEL -> "需要" + professionName() + " Lv." + level;
				case PROJECT -> "需要手上有一件正在施工的工程";
				case PLACEMENT -> "需要手上有一份正在放置的蓝图";
				case TRAINED -> "已经转职了，训练进度只对未转职的侍从有意义";
			};
		}

		private String professionName() {
			return profession == null ? "对应职业" : profession.displayName();
		}
	}

	/** 现在能不能点。 */
	public static boolean available(Entry entry, Context context) {
		return lockOf(entry, context) == null;
	}

	/**
	 * 锁着的理由；{@code null} 表示现在就能点。
	 *
	 * <p>顺序是刻意的：先看状态再看职业。一个守卫点「材料配置」时该看到的是
	 * 「需要手上有一份正在放置的蓝图」，而不是一句和他的处境无关的职业要求。</p>
	 */
	public static Lock lockOf(Entry entry, Context context) {
		if (entry == null) {
			return null;
		}
		Context ctx = context == null
			? new Context(null, 0, Set.of(), false, false) : context;
		Gate gate = entry.gate();
		switch (gate.requires()) {
			case PROJECT_ACTIVE -> {
				if (!ctx.hasProject()) {
					return new Lock(Reason.PROJECT, null, 0);
				}
			}
			case PLACEMENT_ACTIVE -> {
				if (!ctx.hasPlacement()) {
					return new Lock(Reason.PLACEMENT, null, 0);
				}
			}
			case NO_PROFESSION -> {
				if (ctx.hasProfession()) {
					return new Lock(Reason.TRAINED, null, 0);
				}
			}
			case NONE -> { }
		}
		if (gate.profession() != null && ctx.profession() != gate.profession()) {
			return new Lock(Reason.PROFESSION, gate.profession(), gate.minLevel());
		}
		if (gate.ability() != null && !ctx.abilities().contains(gate.ability().id())) {
			return new Lock(Reason.LEVEL, gate.ability().profession(),
				gate.ability().unlockLevel());
		}
		return null;
	}

	/** 现在真的点得动的那些格，按表里的顺序。 */
	public static List<Entry> visible(Context context) {
		List<Entry> out = new ArrayList<>();
		for (Entry entry : ENTRIES) {
			if (available(entry, context)) {
				out.add(entry);
			}
		}
		return List.copyOf(out);
	}

	/** 新建快捷指令时能挑的那些：现在点得动、而且真的有动作可绑。 */
	public static List<Entry> bindable(Context context) {
		List<Entry> out = new ArrayList<>();
		for (Entry entry : visible(context)) {
			if (entry.bindable()) {
				out.add(entry);
			}
		}
		return List.copyOf(out);
	}

	/**
	 * 「下一相关能力」：本职业下一项还没解锁的能力；已经全解锁或还没转职时是 null。
	 *
	 * <p>指令页下面那一行提示用它。不画一堆灰按钮，但也不能让玩家以为到此为止——
	 * 一行字就够说清楚这条线还有多长。</p>
	 */
	public static ProfessionAbility nextUnlock(Context context) {
		if (context == null || !context.hasProfession()) {
			return null;
		}
		return ProfessionAbility.nextAfter(context.profession(), context.level());
	}

	/** 聊天里描述一条快捷绑定：「给我物品 ×64」。认不出的 id 回空串。 */
	public static String describe(String entryId, String arg) {
		Entry entry = byId(entryId);
		if (entry == null) {
			return "";
		}
		Variant variant = entry.variant(arg);
		if (variant == null || entry.variants().size() <= 1) {
			return entry.displayName();
		}
		return entry.displayName() + " " + variant.arg();
	}

	private CommandCatalog() {
	}
}
