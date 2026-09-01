package dev.squire.server.profile;

import java.util.List;
import java.util.Locale;

/**
 * 一项能力。<b>解锁 ≠ 装备</b>——槽位限制才是取舍的来源。
 *
 * <h2>基础与进阶</h2>
 * <p>每个职业的一级能力是<b>基础能力</b>：它们就是今天这只随从已经会做的事
 * （护卫、按蓝图施工、掘进、搬运、送货、记点）。基础能力人人都有，也不占槽——
 * 选一个职业绝不该让伙伴突然不会做他昨天还会做的事。</p>
 *
 * <p>二级以上是<b>进阶能力</b>：必须练到、必须装进槽里，而且<b>一律是纯增益</b>。
 * 取舍不来自「选了这个就不能做那个」，而来自槽位有限，且别的职业的进阶能力也要
 * 挤这几个槽——一只四槽的建筑师装满三个建筑能力之后，就装不下护卫的「集火」了。
 * 这就是逼你召第二只随从的杠杆。</p>
 *
 * <h2>清单必须与实现对齐</h2>
 * <p>这张表是对玩家的承诺，所以每一条都必须真的<b>门住某件事</b>：不是说明文字，
 * 是服务端在动手之前会检查的一个开关。还没落地的条目一律 {@code available=false}，
 * 面板灰显、装备时如实拒绝——面板上摆一个点了没反应的按钮，比没有这个按钮伤害大。
 * {@code AbilityTest} 反过来钉这一点。</p>
 */
public enum Ability {

	// ---------------------------------------------------------------- 护卫
	COMBAT_GUARD("combat.guard", Role.GUARDIAN, 1, "护卫",
		"长期护卫与自动接战", true),
	COMBAT_INTERPOSE("combat.interpose", Role.GUARDIAN, 2, "挡在前面",
		"跟随时站到你和攻击者之间", true),
	COMBAT_FOCUS_FIRE("combat.focus_fire", Role.GUARDIAN, 3, "集火",
		"优先攻击你正在打的目标", true),
	COMBAT_ESCORT("combat.escort", Role.GUARDIAN, 4, "护送",
		"护卫半径扩大一半，离基地再远也不脱队", true),

	// ---------------------------------------------------------------- 建筑师
	BUILD_BLUEPRINT("build.blueprint", Role.BUILDER, 1, "按蓝图施工",
		"开工建造，并清点材料", true),
	BUILD_FAST("build.fast", Role.BUILDER, 2, "双倍工速",
		"每 tick 放置的格数翻倍", true),
	BUILD_SUBSTITUTE("build.substitute", Role.BUILDER, 3, "通用建材",
		"缺料时接受等价替代材料（各种木板互通、圆石与石头互通）", true),
	BUILD_DEMOLISH("build.demolish", Role.BUILDER, 4, "拆改",
		"施工时拆掉挡路的错方块，而不是跳过它", true),

	// ---------------------------------------------------------------- 管家
	LOGISTICS_HAUL("logistics.haul", Role.STEWARD, 1, "搬运与整理",
		"从箱子存取、分类整理", true),
	LOGISTICS_SUPPLY("logistics.supply", Role.STEWARD, 1, "补给",
		"把东西送到你手上", true),
	LOGISTICS_UPKEEP("logistics.upkeep", Role.STEWARD, 2, "装备维护",
		"回到基地时自己换上更好的装备", true),
	LOGISTICS_SITEWORK("logistics.sitework", Role.STEWARD, 4, "工地物流",
		"施工缺料时自己去附近的箱子里取", true),

	// ---------------------------------------------------------------- 掘进工
	EXCAVATE_BLUEPRINT("build.excavate", Role.EXCAVATOR, 1, "按蓝图掘进",
		"挖除蓝图足印内的负空间", true),
	EXCAVATE_LIGHTS("excavate.lights", Role.EXCAVATOR, 2, "照明",
		"挖完之后用自己背包里的火把把坑点亮", true),
	EXCAVATE_DEEP("excavate.deep", Role.EXCAVATOR, 3, "深掘",
		"掘进速度翻倍", true),

	// ---------------------------------------------------------------- 探险家
	EXPLORE_MARK("explore.mark", Role.SCOUT, 1, "记点",
		"记住并回忆地点", true),
	EXPLORE_GUIDE("explore.guide", Role.SCOUT, 2, "引路",
		"回忆地点时他会带你走过去", true),
	EXPLORE_WARN("explore.warn", Role.SCOUT, 3, "预警",
		"巡逻到点时报告附近的威胁和暗处", true);

	private final String id;
	private final Role role;
	private final int unlockLevel;
	private final String displayName;
	private final String summary;
	private final boolean available;

	Ability(String id, Role role, int unlockLevel, String displayName, String summary,
			boolean available) {
		this.id = id;
		this.role = role;
		this.unlockLevel = unlockLevel;
		this.displayName = displayName;
		this.summary = summary;
		this.available = available;
	}

	public String id() {
		return id;
	}

	public Role role() {
		return role;
	}

	/** 本职业练到这个等级才解锁。 */
	public int unlockLevel() {
		return unlockLevel;
	}

	/** 聊天里用的中文名；GUI 用 {@link #nameKey()}。 */
	public String displayName() {
		return displayName;
	}

	/** 一句话说清它到底做什么。面板和 {@code /squire ability} 都用它。 */
	public String summary() {
		return summary;
	}

	/**
	 * 已经落地了吗。未落地的条目面板灰显、装备时如实拒绝——
	 * 清单上写着而点了没反应，是这个模组明确要避免的那类伤害。
	 */
	public boolean available() {
		return available;
	}

	/**
	 * 基础能力：人人都有，不占槽，代表这只随从「本来就会」的事。
	 *
	 * <p>选职业不该让他忘掉已经会的东西，所以门禁只管进阶能力。</p>
	 */
	public boolean basic() {
		return unlockLevel <= 1;
	}

	public String nameKey() {
		return "squire.gui.ability." + id.replace('.', '_');
	}

	public static Ability byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (Ability ability : values()) {
			if (ability.id.equals(needle)) {
				return ability;
			}
		}
		return null;
	}

	/** 某个职业的全部能力，按解锁等级排好。 */
	public static List<Ability> of(Role role) {
		return java.util.Arrays.stream(values())
			.filter(a -> a.role == role)
			.sorted(java.util.Comparator.comparingInt(Ability::unlockLevel)
				.thenComparing(Ability::id))
			.toList();
	}

	/** 会占槽位的那些（进阶且已落地）。 */
	public static List<Ability> equippable() {
		return java.util.Arrays.stream(values())
			.filter(a -> !a.basic() && a.available())
			.toList();
	}
}
