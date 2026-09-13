package dev.squire.server.profession;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 工程师经验的<b>全部</b>计算规则（设计文档 §14–§16）。
 *
 * <h2>不按方块给经验</h2>
 * <p>「放一格 = 1 经验」会让一栋大房子的经验彻底失控，而且玩家只要反复铺地就能刷级——
 * 更要命的是它奖励的是「摆了多少方块」，而职业要奖励的是「完成了一个工程」。
 * 所以经验只在<b>施工验收通过</b>时一次性结算，取消、失败、中断一律 0。</p>
 *
 * <h2>重复衰减不看材料颜色</h2>
 * <p>橡木屋、云杉屋、白桦屋如果结构完全一样，就是同一个工程。把颜色算进签名，
 * 等于告诉玩家「换一种木头再盖一遍」是刷经验的正解，那正好是要防的行为。</p>
 */
public final class EngineerXp {

	private EngineerXp() {
	}

	/** 规模档位。足印边长落在哪一档由 {@link #sizeClassFor} 决定。 */
	public static final String SIZE_SMALL = "small";
	public static final String SIZE_MEDIUM = "medium";
	public static final String SIZE_LARGE = "large";
	public static final String SIZE_VERY_LARGE = "very_large";

	/**
	 * 一次已完成的工程。
	 *
	 * @param templateId         模板 id
	 * @param sizeClass          规模档位
	 * @param floors             层数（1 起）
	 * @param structuralVariant  用了非默认的结构 Variant
	 * @param modules            附属模块数量
	 * @param compound           这是一份复合蓝图（一次盖好几栋）
	 */
	public record Project(String templateId, String sizeClass, int floors,
			boolean structuralVariant, int modules, boolean compound, int catalogTier, String catalogFamily) {
		public Project(String templateId, String sizeClass, int floors, boolean structuralVariant, int modules, boolean compound) {
			this(templateId, sizeClass, floors, structuralVariant, modules, compound, 0, "");
		}

		public Project {
			templateId = templateId == null || templateId.isBlank() ? "unknown"
				: templateId.trim().toLowerCase(Locale.ROOT);
			sizeClass = sizeClass == null || sizeClass.isBlank() ? SIZE_SMALL
				: sizeClass.trim().toLowerCase(Locale.ROOT);
			floors = Math.max(1, floors);
			modules = Math.max(0, modules);
		}

		/**
		 * 重复衰减用的签名。刻意<b>不含</b>材料：只换颜色不算新工程。
		 *
		 * <p>模块只记数量不记名字，是同一个理由的延伸——把门廊换成烟囱并没有让
		 * 这次施工变成一个新工程。</p>
		 */
		public String signature() {
			if (catalogFamily != null && !catalogFamily.isEmpty()) return "catalog|" + catalogFamily;
			return templateId + "|" + sizeClass + "|f" + floors
				+ (structuralVariant ? "|v" : "") + "|m" + modules
				+ (compound ? "|c" : "");
		}
	}

	/** 一次结算的明细，够如实解释每一个系数。 */
	public record Award(int tier, int baseXp, double sizeMultiplier,
			double structuralMultiplier, double floorMultiplier,
			double moduleMultiplier, double repeatMultiplier, int finalXp) {

		public boolean anything() {
			return finalXp > 0;
		}

		public boolean penalised() {
			return repeatMultiplier < 1.0;
		}
	}

	public static final Award NOTHING =
		new Award(1, 0, 1.0, 1.0, 1.0, 1.0, 1.0, 0);

	/**
	 * 这次工程算第几档（设计文档 §14.2 的例子反推出来的规则）。
	 *
	 * <ul>
	 *   <li>复合蓝图 → 第 5 档</li>
	 *   <li>带附属模块 → 第 4 档</li>
	 *   <li>多层，或者大/超大足印 → 第 3 档</li>
	 *   <li>其余按模板自身的档位（配置里没写的模板算第 2 档「普通项目」）</li>
	 * </ul>
	 */
	public static int tierOf(ProfessionConfig config, Project project) {
		if (project.catalogTier() > 0) return Math.min(5, project.catalogTier());
		if (project.compound()) {
			return 5;
		}
		if (project.modules() > 0) {
			return 4;
		}
		if (project.floors() >= 2 || SIZE_LARGE.equals(project.sizeClass())
				|| SIZE_VERY_LARGE.equals(project.sizeClass())) {
			return 3;
		}
		return config.templateTier(project.templateId());
	}

	/**
	 * 算一次工程给多少经验。
	 *
	 * @param repeatCount 窗口内之前已经完成过几次同签名工程（不含这一次）
	 */
	public static Award award(ProfessionConfig config, Project project,
			int repeatCount) {
		if (project == null) {
			return NOTHING;
		}
		int tier = tierOf(config, project);
		int base = config.projectBaseXp(tier);
		if (base <= 0) {
			return NOTHING;
		}
		double size = config.sizeMultiplier(project.sizeClass());
		double structural = project.structuralVariant()
			? config.engineerStructuralVariantMultiplier : 1.0;
		double floors = config.floorMultiplier(project.floors());
		// 复合蓝图已经用了更高的 Base XP，不再无限叠模块倍率（设计文档 §14.4）。
		double modules = project.compound() ? 1.0
			: 1.0 + Math.min(config.engineerMaxModuleBonus,
				project.modules() * config.engineerModuleMultiplier);
		double repeat = repeatMultiplier(config, repeatCount);
		double raw = base * size * structural * floors * modules * repeat;
		return new Award(tier, base, size, structural, floors, modules, repeat,
			(int) Math.max(0L, Math.round(raw)));
	}

	/** 窗口内第 {@code repeatCount + 1} 次同款工程的倍率。 */
	public static double repeatMultiplier(ProfessionConfig config, int repeatCount) {
		double[] multipliers = config.engineerRepeatProjectMultipliers;
		if (multipliers.length == 0) {
			return 1.0;
		}
		return multipliers[Math.min(Math.max(0, repeatCount), multipliers.length - 1)];
	}

	/**
	 * 足印边长落在哪一档规模。
	 *
	 * <p>用<b>较长的那条边</b>：一栋 32×9 的仓库在玩家眼里就是一栋大建筑，
	 * 按面积开方会把它算成中等。</p>
	 *
	 * <p>分界刻意和 {@code maxBlueprintSizeByLevel} 的台阶对齐（13 / 21 / 32 / 48）：
	 * 玩家刚解锁的那一档尺寸，正好就是他刚够得着的那一档规模倍率。</p>
	 */
	public static String sizeClassFor(int width, int depth) {
		int span = Math.max(width, depth);
		if (span <= 13) {
			return SIZE_SMALL;
		}
		if (span <= 21) {
			return SIZE_MEDIUM;
		}
		if (span <= 32) {
			return SIZE_LARGE;
		}
		return SIZE_VERY_LARGE;
	}

	/** 模块名去重排序后的稳定串；只用于展示，不进签名。 */
	public static String describeModules(List<String> modules) {
		if (modules == null || modules.isEmpty()) {
			return "无";
		}
		return String.join("、", new TreeSet<>(modules));
	}
}
