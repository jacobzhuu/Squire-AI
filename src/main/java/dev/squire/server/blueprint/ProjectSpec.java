package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 工程师蓝图的<b>全部可调参数</b>：模板、尺寸、层数、结构变体、附属模块、镜像。
 *
 * <h2>为什么是一个可序列化的 id，而不是一堆坐标</h2>
 * <p>玩家和 LLM 只能挑<b>参数</b>，永远不能自由构造方块布局——这条规矩从
 * {@link HouseSpec} 起就没变过，理由也没变：让模型逐块摆放，盖出来的是没有门的实心盒子。
 * 这里只是把可挑的参数从「宽/深/高/材料/屋顶」扩展到设计文档给工程师 Lv.1–Lv.10 的
 * 那一整套，而真正的形状仍然由 {@link ProjectBlueprintFactory} 里的确定性代码生成。</p>
 *
 * <p>整份规格<b>编码进蓝图 id</b>，于是摆放只需要存一个字符串就能跨重启还原；
 * 材料<b>不在</b> id 里——它走 {@link BlueprintPlacement} 的调色板，
 * 这既让「只换颜色」不必重编译，也正好对上防刷规则「换木头不算新工程」。</p>
 */
public record ProjectSpec(Template template, int width, int depth, int wallHeight,
		int floors, Roof roof, Foundation foundation, WindowStyle window,
		Entrance entrance, Set<Module> modules, boolean mirrorX, boolean mirrorZ) {

	/** 生成式工程蓝图的 id 前缀。 */
	public static final String ID_PREFIX = "project/";

	/** 层数上限（设计文档 §13 Lv.6）。 */
	public static final int MAX_FLOORS = 3;
	/** 一层的净高下限——比这更矮人走不进去。 */
	public static final int MIN_WALL_HEIGHT = 3;
	public static final int MAX_WALL_HEIGHT = 6;
	/** 足印下限。再小连门带窗都放不下。 */
	public static final int MIN_FOOTPRINT = 5;

	// ------------------------------------------------------------------ 词汇表

	/** 模板。决定默认尺寸、用途分类，以及它是不是一份复合蓝图。 */
	public enum Template {
		SHED("shed", "棚屋", Blueprint.Category.STORAGE, 5, 5, 3, false),
		SMALL_STORAGE("small_storage", "小仓库", Blueprint.Category.STORAGE, 7, 5, 3, false),
		SMALL_HOUSE("small_house", "小屋", Blueprint.Category.HOUSING, 7, 7, 4, false),
		HOUSE("house", "房屋", Blueprint.Category.HOUSING, 9, 9, 4, false),
		LARGE_HOUSE("large_house", "大宅", Blueprint.Category.HOUSING, 13, 11, 5, false),
		WAREHOUSE("warehouse", "仓库", Blueprint.Category.STORAGE, 15, 11, 5, false),
		WATCHTOWER("watchtower", "哨塔", Blueprint.Category.DEFENCE, 7, 7, 5, false),
		/** 复合蓝图：一次盖主屋 + 仓库 + 哨塔 + 围栏 + 大门。 */
		OUTPOST("outpost", "前哨营地", Blueprint.Category.DEFENCE, 25, 21, 4, true);

		private final String id;
		private final String displayName;
		private final Blueprint.Category category;
		private final int defaultWidth;
		private final int defaultDepth;
		private final int defaultWallHeight;
		private final boolean compound;

		Template(String id, String displayName, Blueprint.Category category,
				int defaultWidth, int defaultDepth, int defaultWallHeight,
				boolean compound) {
			this.id = id;
			this.displayName = displayName;
			this.category = category;
			this.defaultWidth = defaultWidth;
			this.defaultDepth = defaultDepth;
			this.defaultWallHeight = defaultWallHeight;
			this.compound = compound;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public Blueprint.Category category() {
			return category;
		}

		public int defaultWidth() {
			return defaultWidth;
		}

		public int defaultDepth() {
			return defaultDepth;
		}

		public int defaultWallHeight() {
			return defaultWallHeight;
		}

		/** 一份蓝图里放下好几栋建筑（设计文档 §13 Lv.9）。 */
		public boolean compound() {
			return compound;
		}

		public static Template byId(String raw) {
			if (raw == null) {
				return null;
			}
			String needle = raw.trim().toLowerCase(Locale.ROOT);
			for (Template value : values()) {
				if (value.id.equals(needle)) {
					return value;
				}
			}
			return null;
		}

		/** 这个模板的默认规格。 */
		public ProjectSpec defaults() {
			return new ProjectSpec(this, defaultWidth, defaultDepth, defaultWallHeight,
				1, Roof.GABLE, Foundation.NONE, WindowStyle.SMALL, Entrance.CENTER,
				Set.of(), false, false);
		}
	}

	/** 屋顶变体（Lv.5 结构变体）。 */
	public enum Roof {
		FLAT("flat", "平顶"),
		GABLE("gable", "人字顶"),
		HIP("hip", "四坡顶");

		private final String id;
		private final String displayName;

		Roof(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static Roof byId(String raw) {
			for (Roof value : values()) {
				if (value.id.equalsIgnoreCase(raw)) {
					return value;
				}
			}
			return null;
		}
	}

	/** 地基变体。 */
	public enum Foundation {
		NONE("none", "无地基"),
		STONE("stone", "石基"),
		RAISED("raised", "抬高地基");

		private final String id;
		private final String displayName;

		Foundation(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static Foundation byId(String raw) {
			for (Foundation value : values()) {
				if (value.id.equalsIgnoreCase(raw)) {
					return value;
				}
			}
			return null;
		}
	}

	/** 窗户变体。 */
	public enum WindowStyle {
		SMALL("small", "小窗"),
		WIDE("wide", "宽窗"),
		TALL("tall", "高窗");

		private final String id;
		private final String displayName;

		WindowStyle(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static WindowStyle byId(String raw) {
			for (WindowStyle value : values()) {
				if (value.id.equalsIgnoreCase(raw)) {
					return value;
				}
			}
			return null;
		}
	}

	/** 入口变体。 */
	public enum Entrance {
		CENTER("center", "居中入口"),
		SIDE("side", "侧边入口");

		private final String id;
		private final String displayName;

		Entrance(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static Entrance byId(String raw) {
			for (Entrance value : values()) {
				if (value.id.equalsIgnoreCase(raw)) {
					return value;
				}
			}
			return null;
		}
	}

	/**
	 * 附属模块（Lv.7）。
	 *
	 * <p>每一个都只能接在<b>预定义的连接点</b>上——不允许「让 LLM 自行决定任意拼接
	 * 位置」，那是设计文档 §12 明确划掉的方向。连接点写死在
	 * {@link ProjectBlueprintFactory} 里，玩家只能选装或不装。</p>
	 */
	public enum Module {
		PORCH("porch", "门廊"),
		CHIMNEY("chimney", "烟囱"),
		STORAGE_WING("storage_wing", "储藏侧翼"),
		TOWER("tower", "塔楼"),
		BALCONY("balcony", "阳台");

		private final String id;
		private final String displayName;

		Module(String id, String displayName) {
			this.id = id;
			this.displayName = displayName;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public static Module byId(String raw) {
			if (raw == null) {
				return null;
			}
			String needle = raw.trim().toLowerCase(Locale.ROOT);
			for (Module value : values()) {
				if (value.id.equals(needle)) {
					return value;
				}
			}
			return null;
		}
	}

	// ------------------------------------------------------------------ 校验

	public ProjectSpec {
		if (template == null) {
			throw new IllegalArgumentException("project spec needs a template");
		}
		// 尺寸一律夹住而不是抛异常：这些值有一半来自 UI 上的加减按钮，
		// 让一次多点的点击炸掉整个工地是说不过去的。
		width = clampOdd(width);
		depth = clampOdd(depth);
		wallHeight = Math.max(MIN_WALL_HEIGHT, Math.min(MAX_WALL_HEIGHT, wallHeight));
		floors = Math.max(1, Math.min(MAX_FLOORS, floors));
		roof = roof == null ? Roof.GABLE : roof;
		foundation = foundation == null ? Foundation.NONE : foundation;
		window = window == null ? WindowStyle.SMALL : window;
		entrance = entrance == null ? Entrance.CENTER : entrance;
		modules = modules == null ? Set.of()
			: Set.copyOf(new LinkedHashSet<>(modules));
	}

	/**
	 * 足印必须是奇数：门、屋脊和楼梯都按中轴对称摆，偶数宽会让中轴落在两格之间，
	 * 屋脊和门永远差半格。
	 *
	 * <p>偶数一律<b>向下</b>取奇数。向上取会越过刚刚夹好的上限——一个 Lv.8 工程师
	 * 的 32 格上限会变成 33 格，然后在摆放那一步被自己的等级闸拦下来。</p>
	 */
	private static int clampOdd(int value) {
		int clamped = Math.max(MIN_FOOTPRINT,
			Math.min(ProjectBlueprintFactory.MAX_FOOTPRINT, value));
		return clamped % 2 == 0 ? clamped - 1 : clamped;
	}

	// ------------------------------------------------------------------ 派生

	/** 足印里较长的那条边。等级上限和规模档位都看它。 */
	public int footprint() {
		return Math.max(width, depth);
	}

	public boolean compound() {
		return template.compound();
	}

	/** 用了非默认结构变体吗（经验的结构系数看这一条）。 */
	public boolean usesStructuralVariant() {
		return roof != Roof.GABLE || foundation != Foundation.NONE
			|| window != WindowStyle.SMALL || entrance != Entrance.CENTER;
	}

	public boolean mirrored() {
		return mirrorX || mirrorZ;
	}

	public String displayName() {
		StringBuilder text = new StringBuilder(width + "×" + depth + " ")
			.append(template.displayName());
		if (floors > 1) {
			text.append(" ").append(floors).append(" 层");
		}
		if (roof != Roof.GABLE) {
			text.append("·").append(roof.displayName());
		}
		for (Module module : orderedModules()) {
			text.append("+").append(module.displayName());
		}
		return text.toString();
	}

	/** 模块的稳定顺序（枚举序），保证 id 可复现。 */
	public List<Module> orderedModules() {
		List<Module> out = new ArrayList<>();
		for (Module module : Module.values()) {
			if (modules.contains(module)) {
				out.add(module);
			}
		}
		return List.copyOf(out);
	}

	// ------------------------------------------------------------------ 变更

	public ProjectSpec withSize(int nextWidth, int nextDepth) {
		return new ProjectSpec(template, nextWidth, nextDepth, wallHeight, floors, roof,
			foundation, window, entrance, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec withWallHeight(int next) {
		return new ProjectSpec(template, width, depth, next, floors, roof, foundation,
			window, entrance, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec withFloors(int next) {
		return new ProjectSpec(template, width, depth, wallHeight, next, roof, foundation,
			window, entrance, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec withRoof(Roof next) {
		return new ProjectSpec(template, width, depth, wallHeight, floors, next,
			foundation, window, entrance, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec withFoundation(Foundation next) {
		return new ProjectSpec(template, width, depth, wallHeight, floors, roof, next,
			window, entrance, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec withWindow(WindowStyle next) {
		return new ProjectSpec(template, width, depth, wallHeight, floors, roof,
			foundation, next, entrance, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec withEntrance(Entrance next) {
		return new ProjectSpec(template, width, depth, wallHeight, floors, roof,
			foundation, window, next, modules, mirrorX, mirrorZ);
	}

	public ProjectSpec toggleModule(Module module) {
		Set<Module> next = new LinkedHashSet<>(modules);
		if (!next.remove(module)) {
			next.add(module);
		}
		return new ProjectSpec(template, width, depth, wallHeight, floors, roof,
			foundation, window, entrance, next, mirrorX, mirrorZ);
	}

	public ProjectSpec withMirror(boolean nextX, boolean nextZ) {
		return new ProjectSpec(template, width, depth, wallHeight, floors, roof,
			foundation, window, entrance, modules, nextX, nextZ);
	}

	// ------------------------------------------------------------------ id 编解码

	/**
	 * 编码成蓝图 id。
	 *
	 * <p>格式固定六段，缺一段就整个作废——一个只解析出一半的规格会盖出一栋玩家
	 * 没要求过的房子，那比「这份蓝图认不出来」糟糕得多。</p>
	 */
	public String blueprintId() {
		StringBuilder id = new StringBuilder(ID_PREFIX);
		id.append(template.id()).append('/')
			.append(width).append('x').append(depth).append('x').append(wallHeight)
			.append('/').append('f').append(floors).append('/')
			.append(roof.id()).append('-').append(foundation.id()).append('-')
			.append(window.id()).append('-').append(entrance.id()).append('/');
		List<Module> ordered = orderedModules();
		if (ordered.isEmpty()) {
			id.append("none");
		} else {
			for (int i = 0; i < ordered.size(); i++) {
				if (i > 0) {
					id.append('+');
				}
				id.append(ordered.get(i).id());
			}
		}
		id.append('/').append(mirrorX ? "x" : "").append(mirrorZ ? "z" : "")
			.append(!mirrorX && !mirrorZ ? "-" : "");
		return id.toString();
	}

	/** 从蓝图 id 解回规格；认不出就返回空（绝不猜）。 */
	public static Optional<ProjectSpec> parse(String raw) {
		String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (!id.startsWith(ID_PREFIX)) {
			return Optional.empty();
		}
		String[] parts = id.substring(ID_PREFIX.length()).split("/");
		if (parts.length != 6) {
			return Optional.empty();
		}
		Template template = Template.byId(parts[0]);
		if (template == null) {
			return Optional.empty();
		}
		String[] size = parts[1].split("x");
		String[] variants = parts[3].split("-");
		if (size.length != 3 || variants.length != 4 || !parts[2].startsWith("f")) {
			return Optional.empty();
		}
		Roof roof = Roof.byId(variants[0]);
		Foundation foundation = Foundation.byId(variants[1]);
		WindowStyle window = WindowStyle.byId(variants[2]);
		Entrance entrance = Entrance.byId(variants[3]);
		if (roof == null || foundation == null || window == null || entrance == null) {
			return Optional.empty();
		}
		Set<Module> modules = new LinkedHashSet<>();
		if (!"none".equals(parts[4])) {
			for (String name : parts[4].split("\\+")) {
				Module module = Module.byId(name);
				if (module == null) {
					return Optional.empty();
				}
				modules.add(module);
			}
		}
		try {
			return Optional.of(new ProjectSpec(template,
				Integer.parseInt(size[0]), Integer.parseInt(size[1]),
				Integer.parseInt(size[2]),
				Integer.parseInt(parts[2].substring(1)), roof, foundation, window,
				entrance, modules,
				parts[5].contains("x"), parts[5].contains("z")));
		} catch (IllegalArgumentException bad) {
			return Optional.empty();
		}
	}
}
