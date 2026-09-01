package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一份 {@link ProjectSpec} 编译成普通蓝图。
 *
 * <h2>这是「确定性建造」那一半</h2>
 * <p>设计文档 §11 把工程师定义成
 * {@code Natural Language → Blueprint Parameters → Deterministic Construction}。
 * LLM 和 UI 只负责挑出上一步的那些参数；从参数到方块的这一步<b>全部</b>在这里，
 * 是普通 Java，可复现、可测试、不会在半路问模型「接下来放什么」。</p>
 *
 * <h2>为什么材料是槽位而不是方块 id</h2>
 * <p>每一条会消耗材料的步骤都写成 {@link BlueprintStep#material}，指向
 * {@code foundation / wall / floor / roof / window / trim} 六个语义槽。于是
 * 「材料分区」（Lv.4）不需要重新编译蓝图——换材料只改摆放的调色板，形状一格不动。
 * 这也正好让防刷签名天然不含颜色。</p>
 *
 * <h2>模块只能接在预定义连接点上</h2>
 * <p>门廊接南墙门口、烟囱接北墙、储藏侧翼接东墙、塔楼接西北角、阳台接二层南墙。
 * 位置写死在代码里，玩家只能选装或不装——设计文档 §12 明确禁止让 LLM 自行决定
 * 任意拼接位置。</p>
 */
public final class ProjectBlueprintFactory {

	/** 单栋建筑的足印上限。等级上限（{@code maxBlueprintSizeByLevel}）只会更严。 */
	public static final int MAX_FOOTPRINT = 48;

	/** 材料槽 id。UI 的材料分区按钮和这里一一对应。 */
	public static final String SLOT_FOUNDATION = "foundation";
	public static final String SLOT_WALL = "wall";
	public static final String SLOT_FLOOR = "floor";
	public static final String SLOT_ROOF = "roof";
	public static final String SLOT_WINDOW = "window";
	public static final String SLOT_TRIM = "trim";

	private ProjectBlueprintFactory() {
	}

	// ------------------------------------------------------------------ 材料槽

	private static List<Blueprint.MaterialSlot> slots() {
		return List.of(
			new Blueprint.MaterialSlot(SLOT_FOUNDATION, "地基",
				MaterialFamily.SlotType.FOUNDATION, "squire:stone_bricks",
				Set.of("block")),
			new Blueprint.MaterialSlot(SLOT_WALL, "墙体",
				MaterialFamily.SlotType.WALL, "squire:oak", Set.of("block")),
			new Blueprint.MaterialSlot(SLOT_FLOOR, "地板",
				MaterialFamily.SlotType.FLOOR, "squire:spruce", Set.of("block")),
			new Blueprint.MaterialSlot(SLOT_ROOF, "屋顶",
				MaterialFamily.SlotType.ROOF, "squire:spruce",
				Set.of("block", "stairs", "slab")),
			new Blueprint.MaterialSlot(SLOT_WINDOW, "窗户",
				MaterialFamily.SlotType.WINDOW, "squire:glass", Set.of("pane")),
			new Blueprint.MaterialSlot(SLOT_TRIM, "装饰梁",
				MaterialFamily.SlotType.FRAME, "squire:dark_oak", Set.of("log")));
	}

	// ------------------------------------------------------------------ 编译

	/** 把规格编译成蓝图。同一份规格永远编译出同一份形状。 */
	public static Blueprint compile(ProjectSpec spec) {
		Builder builder = new Builder();
		if (spec.compound()) {
			compound(builder, spec);
		} else {
			building(builder, spec, 0, 0, "");
			modules(builder, spec, 0, 0);
		}
		List<BlueprintStep> steps = builder.mirror(spec);
		Bounds bounds = Bounds.of(steps);
		Set<String> abilities = builder.digs
			? Set.of(BlueprintRegistry.ABILITY_BUILD, BlueprintRegistry.ABILITY_EXCAVATE)
			: Set.of(BlueprintRegistry.ABILITY_BUILD);
		// 声明尺寸只用于旋转，所以取<b>全部步骤的跨度</b>而不是主体足印：
		// 带侧翼的房子按主体足印旋转，转到东西朝向时侧翼会甩到别处去。
		return new Blueprint(spec.blueprintId(), spec.displayName(),
			Math.max(1, tierOf(spec)), spec.template().category(),
			bounds.width(), bounds.height(), bounds.depth(), steps, abilities, slots());
	}

	/** 蓝图自己的 tier，只用于列表排序与展示。 */
	private static int tierOf(ProjectSpec spec) {
		if (spec.compound()) {
			return 4;
		}
		if (!spec.modules().isEmpty() || spec.floors() > 1) {
			return 3;
		}
		return spec.footprint() > 9 ? 2 : 1;
	}

	// ------------------------------------------------------------------ 主体

	/**
	 * 一栋楼。{@code ox/oz} 是它在整份蓝图里的偏移（复合蓝图会放好几栋），
	 * {@code label} 是报告里用的前缀（复合蓝图靠它区分「主屋墙体」和「哨塔墙体」）。
	 */
	private static void building(Builder b, ProjectSpec spec, int ox, int oz,
			String label) {
		int w = spec.width();
		int d = spec.depth();
		int h = spec.wallHeight();
		int floors = spec.floors();
		int base = foundationTop(spec);

		foundation(b, spec, ox, oz, label);

		for (int floor = 0; floor < floors; floor++) {
			int y = base + floor * (h + 1);
			// 楼板：底层是地板，往上每层是上一层的天花板兼这一层的地板。
			b.material(ox, y, oz, ox + w - 1, y, oz + d - 1, SLOT_FLOOR, "block",
				label + (floor == 0 ? "地板" : "第 " + (floor + 1) + " 层楼板"), false);
			// 楼梯口必须在<b>楼板铺完之后</b>再掏：步骤顺序即覆盖顺序，先掏后铺
			// 等于白掏一次，楼梯会顶死在天花板上。
			if (floor > 0) {
				b.dig(ox + 1, y, oz + 1, ox + 2, y, oz + 2, label + "楼梯口");
				stairs(b, spec, ox, oz, y - h - 1, label);
			}
			// 四面墙：先砌整圈实心，再掏空内部——展平之后就是一层壳，材料账目照旧。
			b.material(ox, y + 1, oz, ox + w - 1, y + h, oz + d - 1, SLOT_WALL, "block",
				label + (floor == 0 ? "墙体" : "第 " + (floor + 1) + " 层墙体"), false);
			b.dig(ox + 1, y + 1, oz + 1, ox + w - 2, y + h, oz + d - 2,
				label + (floor == 0 ? "掏空内部" : "掏空第 " + (floor + 1) + " 层"));
			// 转角立柱：唯一一处装饰梁，同时让「材料分区」有第六个槽可调。
			for (int[] corner : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
				b.material(ox + corner[0], y + 1, oz + corner[1],
					ox + corner[0], y + h, oz + corner[1], SLOT_TRIM, "log",
					label + "转角立柱", true);
			}
			windows(b, spec, ox, oz, y, label);
		}

		entrance(b, spec, ox, oz, base, label);
		roof(b, spec, ox, oz, base + floors * (h + 1) - 1, label);
	}

	/** 地基顶面的 y（也就是一层地板所在的高度）。 */
	private static int foundationTop(ProjectSpec spec) {
		return switch (spec.foundation()) {
			case NONE -> 0;
			case STONE -> 1;
			case RAISED -> 2;
		};
	}

	private static void foundation(Builder b, ProjectSpec spec, int ox, int oz,
			String label) {
		int top = foundationTop(spec);
		if (top <= 0) {
			return;
		}
		b.material(ox, 0, oz, ox + spec.width() - 1, top - 1, oz + spec.depth() - 1,
			SLOT_FOUNDATION, "block", label + spec.foundation().displayName(), false);
	}

	/** 窗。样式决定开在哪几行、每面开几扇。 */
	private static void windows(Builder b, ProjectSpec spec, int ox, int oz, int floorY,
			String label) {
		int w = spec.width();
		int d = spec.depth();
		int h = spec.wallHeight();
		int mid = d / 2;
		int midX = w / 2;
		switch (spec.window()) {
			case SMALL -> {
				b.material(ox, floorY + 2, oz + mid, ox, floorY + 2, oz + mid,
					SLOT_WINDOW, "pane", label + "西窗", true);
				b.material(ox + w - 1, floorY + 2, oz + mid, ox + w - 1, floorY + 2,
					oz + mid, SLOT_WINDOW, "pane", label + "东窗", true);
			}
			case WIDE -> {
				b.material(ox, floorY + 2, oz + mid - 1, ox, floorY + 2, oz + mid + 1,
					SLOT_WINDOW, "pane", label + "西侧宽窗", true);
				b.material(ox + w - 1, floorY + 2, oz + mid - 1, ox + w - 1, floorY + 2,
					oz + mid + 1, SLOT_WINDOW, "pane", label + "东侧宽窗", true);
				b.material(ox + midX - 1, floorY + 2, oz, ox + midX + 1, floorY + 2, oz,
					SLOT_WINDOW, "pane", label + "北侧宽窗", true);
			}
			case TALL -> {
				int top = Math.min(floorY + h, floorY + 3);
				b.material(ox, floorY + 2, oz + mid, ox, top, oz + mid,
					SLOT_WINDOW, "pane", label + "西侧高窗", true);
				b.material(ox + w - 1, floorY + 2, oz + mid, ox + w - 1, top, oz + mid,
					SLOT_WINDOW, "pane", label + "东侧高窗", true);
				b.material(ox + midX, floorY + 2, oz, ox + midX, top, oz,
					SLOT_WINDOW, "pane", label + "北侧高窗", true);
			}
		}
	}

	/**
	 * 门洞。作者按正北写，所以门永远开在南墙——也就是摆放时朝向玩家的那一面。
	 */
	private static void entrance(Builder b, ProjectSpec spec, int ox, int oz, int base,
			String label) {
		int w = spec.width();
		int d = spec.depth();
		int x = spec.entrance() == ProjectSpec.Entrance.CENTER ? w / 2 : 1;
		b.dig(ox + x, base + 1, oz + d - 1, ox + x, base + 2, oz + d - 1, label + "门洞");
	}

	/** 层间楼梯：贴着西北角一级级往上，占地最小且不会挡住门。 */
	private static void stairs(Builder b, ProjectSpec spec, int ox, int oz, int floorY,
			String label) {
		int h = spec.wallHeight();
		for (int step = 0; step <= h; step++) {
			int x = 1 + Math.min(step, spec.width() - 3);
			b.material(ox + x, floorY + 1 + step, oz + 1, ox + x, floorY + 1 + step,
				oz + 1, SLOT_ROOF, "stairs", label + "楼梯", false);
		}
	}

	/** 屋顶。三种变体都由代码生成，绝不让模型自由摆方块。 */
	private static void roof(Builder b, ProjectSpec spec, int ox, int oz, int top,
			String label) {
		int w = spec.width();
		int d = spec.depth();
		switch (spec.roof()) {
			case FLAT -> b.material(ox, top + 1, oz, ox + w - 1, top + 1, oz + d - 1,
				SLOT_ROOF, "slab", label + "平屋顶", false);
			case GABLE -> {
				int ridge = w / 2;
				for (int x = 0; x < w; x++) {
					int rise = Math.min(x, w - 1 - x);
					b.material(ox + x, top + 1 + rise, oz, ox + x, top + 1 + rise,
						oz + d - 1, SLOT_ROOF, x == ridge ? "block" : "stairs",
						label + (x == ridge ? "屋脊" : "斜屋顶"), false);
				}
			}
			case HIP -> {
				// 四坡：每一层向内收一圈，收到中间为止。
				int rings = Math.min(w, d) / 2;
				for (int ring = 0; ring <= rings; ring++) {
					int x1 = ox + ring;
					int z1 = oz + ring;
					int x2 = ox + w - 1 - ring;
					int z2 = oz + d - 1 - ring;
					if (x1 > x2 || z1 > z2) {
						break;
					}
					b.material(x1, top + 1 + ring, z1, x2, top + 1 + ring, z2,
						SLOT_ROOF, ring == rings ? "block" : "slab",
						label + (ring == rings ? "屋脊" : "四坡屋面"), false);
					if (ring < rings) {
						// 只留一圈边，中间交给下一层——不然会砌成一座实心金字塔。
						b.dig(x1 + 1, top + 1 + ring, z1 + 1, x2 - 1, top + 1 + ring,
							z2 - 1, label + "屋面内圈");
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ 模块

	/** 附属模块。每一个的连接点都写死在这里。 */
	private static void modules(Builder b, ProjectSpec spec, int ox, int oz) {
		int w = spec.width();
		int d = spec.depth();
		int h = spec.wallHeight();
		int base = foundationTop(spec);
		int top = base + spec.floors() * (h + 1) - 1;
		for (ProjectSpec.Module module : spec.orderedModules()) {
			switch (module) {
				case PORCH -> {
					int doorX = spec.entrance() == ProjectSpec.Entrance.CENTER ? w / 2 : 1;
					b.material(ox + doorX - 1, base, oz + d, ox + doorX + 1, base,
						oz + d + 1, SLOT_FLOOR, "block", "门廊地面", false);
					for (int[] post : new int[][]{{doorX - 1, d + 1}, {doorX + 1, d + 1}}) {
						b.material(ox + post[0], base + 1, oz + post[1], ox + post[0],
							base + 2, oz + post[1], SLOT_TRIM, "log", "门廊立柱", false);
					}
					b.material(ox + doorX - 1, base + 3, oz + d, ox + doorX + 1, base + 3,
						oz + d + 1, SLOT_ROOF, "slab", "门廊顶", false);
				}
				case CHIMNEY -> b.material(ox + 1, base, oz, ox + 1, top + 4, oz,
					SLOT_FOUNDATION, "block", "烟囱", false);
				case STORAGE_WING -> {
					int wingDepth = Math.max(3, d / 2 | 1);
					int z1 = oz + (d - wingDepth) / 2;
					b.material(ox + w, base, z1, ox + w + 4, base, z1 + wingDepth - 1,
						SLOT_FLOOR, "block", "侧翼地板", false);
					b.material(ox + w, base + 1, z1, ox + w + 4, base + 3,
						z1 + wingDepth - 1, SLOT_WALL, "block", "侧翼墙体", false);
					b.dig(ox + w, base + 1, z1 + 1, ox + w + 3, base + 3,
						z1 + wingDepth - 2, "掏空侧翼");
					b.material(ox + w, base + 4, z1, ox + w + 4, base + 4,
						z1 + wingDepth - 1, SLOT_ROOF, "slab", "侧翼顶", false);
				}
				case TOWER -> {
					b.material(ox - 4, base, oz - 4, ox, base + top + 3, oz,
						SLOT_WALL, "block", "塔身", false);
					b.dig(ox - 3, base + 1, oz - 3, ox - 1, base + top + 3, oz - 1,
						"掏空塔身");
					b.dig(ox - 2, base + 1, oz, ox - 2, base + 2, oz, "塔门");
					b.material(ox - 4, base + top + 4, oz - 4, ox, base + top + 4, oz,
						SLOT_ROOF, "slab", "塔顶", true);
				}
				case BALCONY -> {
					int level = base + Math.min(1, spec.floors() - 1) * (h + 1) + h + 1;
					b.material(ox + 1, level, oz + d, ox + w - 2, level, oz + d + 1,
						SLOT_FLOOR, "block", "阳台地面", false);
					b.material(ox + 1, level + 1, oz + d + 1, ox + w - 2, level + 1,
						oz + d + 1, SLOT_TRIM, "log", "阳台栏杆", true);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 复合蓝图

	/**
	 * 复合蓝图（Lv.9）：主屋 + 仓库 + 哨塔 + 围栏 + 大门，布局由规则给出。
	 *
	 * <p>玩家能调的是整体位置、朝向、尺寸和材料；各栋之间的相对位置<b>不</b>开放，
	 * 那正是「整个布局仍然由预设产生」的意思。</p>
	 */
	private static void compound(Builder b, ProjectSpec spec) {
		int w = spec.width();
		int d = spec.depth();
		int wall = spec.wallHeight();

		// 围栏、大门和一条主路先画，后面的建筑会覆盖掉压到的那几格。
		// 刻意<b>不</b>铺满整块地面：48×48 铺满就是两千多格，光是这一层就会顶穿
		// 单份蓝图 8192 格的上限，而玩家真正想要的只是一条能走的路。
		b.material(w / 2 - 1, 0, 2, w / 2 + 1, 0, d - 1, SLOT_FLOOR, "block",
			"主路", true);
		b.material(0, 1, 0, w - 1, 1, 0, SLOT_TRIM, "log", "北围栏", true);
		b.material(0, 1, d - 1, w - 1, 1, d - 1, SLOT_TRIM, "log", "南围栏", true);
		b.material(0, 1, 0, 0, 1, d - 1, SLOT_TRIM, "log", "西围栏", true);
		b.material(w - 1, 1, 0, w - 1, 1, d - 1, SLOT_TRIM, "log", "东围栏", true);
		b.dig(w / 2 - 1, 1, d - 1, w / 2 + 1, 2, d - 1, "大门");

		// 各栋的尺寸有<b>硬上限</b>：整块营地放大是为了让建筑之间宽敞些，
		// 而不是把主屋也吹成 47×47——那一份蓝图会直接顶穿 8192 格的上限，
		// 玩家在 Lv.10 拿到的就会是一句「太大了盖不了」。
		ProjectSpec main = new ProjectSpec(ProjectSpec.Template.HOUSE,
			clampCompound(w / 2, 15), clampCompound(d / 2, 15), wall,
			Math.min(2, spec.floors()), spec.roof(), spec.foundation(), spec.window(),
			spec.entrance(), Set.of(), false, false);
		building(b, main, 2, 2, "主屋");

		ProjectSpec store = new ProjectSpec(ProjectSpec.Template.WAREHOUSE,
			clampCompound(w / 3, 13), clampCompound(d / 3, 9), 4, 1,
			ProjectSpec.Roof.FLAT, spec.foundation(), ProjectSpec.WindowStyle.SMALL,
			ProjectSpec.Entrance.CENTER, Set.of(), false, false);
		building(b, store, Math.max(2, w - store.width() - 2), 2, "仓库");

		ProjectSpec tower = new ProjectSpec(ProjectSpec.Template.WATCHTOWER, 5, 5, 6, 1,
			ProjectSpec.Roof.FLAT, spec.foundation(), ProjectSpec.WindowStyle.SMALL,
			ProjectSpec.Entrance.CENTER, Set.of(), false, false);
		building(b, tower, Math.max(2, w - 8), Math.max(2, d - 8), "哨塔");
	}

	/** 复合蓝图里单栋建筑的尺寸：至少 7，至多 {@code cap}，而且是奇数。 */
	private static int clampCompound(int wanted, int cap) {
		int value = Math.max(7, Math.min(cap, wanted));
		return value % 2 == 0 ? value - 1 : value;
	}

	// ------------------------------------------------------------------ builder

	/** 收集步骤并按需镜像。 */
	private static final class Builder {
		private final List<BlueprintStep> steps = new ArrayList<>();
		private boolean digs;

		void material(int x1, int y1, int z1, int x2, int y2, int z2, String slot,
				String variant, String what, boolean optional) {
			steps.add(BlueprintStep.material(steps.size(), Math.min(x1, x2),
				Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2),
				Math.max(z1, z2), slot, variant, what, optional));
		}

		void dig(int x1, int y1, int z1, int x2, int y2, int z2, String what) {
			digs = true;
			steps.add(BlueprintStep.dig(steps.size(), Math.min(x1, x2), Math.min(y1, y2),
				Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
				what));
		}

		/**
		 * 镜像（Lv.7）。
		 *
		 * <p>在<b>编译期</b>做，而不是在摆放时做：镜像之后的形状仍然是一份普通蓝图，
		 * 旋转、材料、账目、幽灵预览和撤销全都不用知道镜像这回事。</p>
		 */
		List<BlueprintStep> mirror(ProjectSpec spec) {
			if (!spec.mirrored()) {
				return List.copyOf(steps);
			}
			Bounds bounds = Bounds.of(steps);
			List<BlueprintStep> out = new ArrayList<>(steps.size());
			for (BlueprintStep step : steps) {
				int x1 = spec.mirrorX() ? bounds.flipX(step.x2()) : step.x1();
				int x2 = spec.mirrorX() ? bounds.flipX(step.x1()) : step.x2();
				int z1 = spec.mirrorZ() ? bounds.flipZ(step.z2()) : step.z1();
				int z2 = spec.mirrorZ() ? bounds.flipZ(step.z1()) : step.z2();
				out.add(new BlueprintStep(step.order(), x1, step.y1(), z1, x2,
					step.y2(), z2, step.blockId(), step.what(), step.optional(),
					step.negative(), step.materialSlot(), step.materialVariant(),
					step.properties()));
			}
			return List.copyOf(out);
		}
	}

	/** 全部步骤的并集包围盒（局部坐标）。 */
	private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

		static Bounds of(List<BlueprintStep> steps) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (BlueprintStep step : steps) {
				minX = Math.min(minX, step.x1());
				minY = Math.min(minY, step.y1());
				minZ = Math.min(minZ, step.z1());
				maxX = Math.max(maxX, step.x2());
				maxY = Math.max(maxY, step.y2());
				maxZ = Math.max(maxZ, step.z2());
			}
			return steps.isEmpty() ? new Bounds(0, 0, 0, 0, 0, 0)
				: new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
		}

		int width() {
			return maxX - minX + 1;
		}

		int height() {
			return maxY - minY + 1;
		}

		int depth() {
			return maxZ - minZ + 1;
		}

		int flipX(int x) {
			return minX + maxX - x;
		}

		int flipZ(int z) {
			return minZ + maxZ - z;
		}
	}

	// ------------------------------------------------------------------ 缓存

	/** 编译结果缓存。幽灵预览每 tick 都要解析一次形状，重编译一栋大宅太浪费。 */
	private static final int CACHE_SIZE = 32;

	private static final Map<String, Blueprint> CACHE =
		java.util.Collections.synchronizedMap(
			new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Blueprint> eldest) {
					return size() > CACHE_SIZE;
				}
			});

	/** 按 id 编译（带缓存）；id 认不出就返回空。 */
	public static java.util.Optional<Blueprint> byId(String blueprintId) {
		if (blueprintId == null || !blueprintId.startsWith(ProjectSpec.ID_PREFIX)) {
			return java.util.Optional.empty();
		}
		Blueprint cached = CACHE.get(blueprintId);
		if (cached != null) {
			return java.util.Optional.of(cached);
		}
		return ProjectSpec.parse(blueprintId).map(spec -> {
			Blueprint compiled = compile(spec);
			CACHE.put(blueprintId, compiled);
			return compiled;
		});
	}
}
