package dev.squire.server.profession;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 职业等级解锁的一项<b>做法</b>（设计文档 §25）。
 *
 * <h2>等级解锁的是行为，不是数值</h2>
 * <p>整张表里没有一条是「+X% 伤害」。守卫的战斗上限永远由玩家给的装备决定，
 * 等级只回答一个问题：<b>他会不会正确地用这些装备</b>。工程师同理——等级决定他
 * 能控制多复杂的蓝图，而不是让 LLM 去自由发挥。</p>
 *
 * <h2>清单必须与实现对齐</h2>
 * <p>和 {@link dev.squire.server.profile.Ability} 同一条规矩：每一条都必须真的
 * <b>门住</b>服务端在动手之前会检查的某个开关。还没落地的一律
 * {@code available=false}，面板灰显、如实说明——清单上写着而点了没反应，
 * 是这个模组明确要避免的那类伤害。{@code ProfessionAbilityTest} 反过来钉这一点。</p>
 */
public enum ProfessionAbility {

	// ---------------------------------------------------------------- 守卫
	/** Lv.1 已经会的：接近、攻击、吃东西、穿玩家给的装备。 */
	GUARD_BASIC_MELEE("guard.basic_melee", SquireProfession.GUARD, 1, "基础近战",
		"发现敌人就接近并攻击", true),
	/** Lv.2：认得出剑/斧/弓/盾/食物/药水，武器快断了会换备用的。 */
	GUARD_EQUIPMENT_AWARENESS("guard.equipment_awareness", SquireProfession.GUARD, 2,
		"装备意识", "认得出背包里的备用武器，手上的快断了会换一把", true),
	/** Lv.3：不再只打最近的，按威胁分数选目标；并且不为一个杂鱼追出去。 */
	GUARD_THREAT_EVALUATION("guard.threat_evaluation", SquireProfession.GUARD, 3,
		"威胁判断", "按威胁分数选目标，不再只打离得最近的；也不会为杂鱼追远", true),
	/** Lv.4：够远、或者对面在天上，就用弓。 */
	GUARD_BOW_PROFICIENCY("guard.bow_proficiency", SquireProfession.GUARD, 4,
		"弓箭使用", "远处和飞行目标改用弓（箭和耐久照常消耗）", true),
	/** Lv.5：按敌人和距离在近战与弓之间自动切换。 */
	GUARD_WEAPON_SWITCHING("guard.weapon_switching", SquireProfession.GUARD, 5,
		"自动武器切换", "按对面是什么、离多远，自己在剑和弓之间换", true),
	/** Lv.5：知道自己还有多少食物、药水、箭、备用武器。 */
	GUARD_SUPPLY_AWARENESS("guard.supply_awareness", SquireProfession.GUARD, 5,
		"补给意识", "清点自己的食物、药水、箭和备用武器，并如实报给你", true),
	/** Lv.6：举盾挡远程，以及分档的治疗时机。 */
	GUARD_SHIELD_PROFICIENCY("guard.shield_proficiency", SquireProfession.GUARD, 6,
		"盾牌与治疗时机", "有盾就挡箭；先吃饭再喝药，不为掉一点血糟蹋药水", true),
	/** Lv.7：站到敌人和主人中间；主人危急时收缩。 */
	GUARD_INTERCEPT("guard.intercept", SquireProfession.GUARD, 7,
		"拦截与主人应急", "尽量站到敌人和你之间；你危险时立刻收回来护着你", true),
	/** Lv.8：玩家可以指定防守/均衡/进攻。 */
	GUARD_COMBAT_STANCE("guard.combat_stance", SquireProfession.GUARD, 8,
		"战斗姿态", "你可以指定防守 / 均衡 / 进攻，改的是追多远、什么时候收手", true),
	/** Lv.9：认得出打不过的东西。 */
	GUARD_HIGH_THREAT_AWARENESS("guard.high_threat_awareness", SquireProfession.GUARD, 9,
		"高威胁判断", "认得出监守者这类打不过的东西，不主动上，回到你身边", true),
	/** Lv.10：以上全部合成一个状态机。 */
	GUARD_GUARDIAN_PROTOCOL("guard.guardian_protocol", SquireProfession.GUARD, 10,
		"守护协议", "把选目标、换武器、举盾、治疗、护主合成一套连贯的打法", true),

    GUARD_COOPERATIVE_HUNT("guard.cooperative_hunt", SquireProfession.GUARD, 3,
        "狩猎协同", "开启积极且空闲时，协同攻击主人刚攻击的非友方生物", true),
    GUARD_PROTECTIVE_TOTEM("guard.protective_totem", SquireProfession.GUARD, 6,
        "守护图腾", "16格内被动消耗背包中的不死图腾，替主人免死", true),
    GUARD_SELF_SACRIFICE("guard.self_sacrifice", SquireProfession.GUARD, 9,
        "舍身护主", "无可用图腾时闪现舍身救主，给予附魔金苹果效果；阵亡后可冷却召回", true),
    GUARD_IMMORTAL_OATH("guard.immortal_oath", SquireProfession.GUARD, 10,
        "不灭誓约", "舍身时无敌战斗15秒，持续吸引附近怪物，随后阵亡", true),

	// ---------------------------------------------------------------- 工程师
	/** Lv.1：基础住宅和完整的预览—确认—施工闭环。 */
	ENGINEER_BASIC_BLUEPRINT("engineer.basic_blueprint", SquireProfession.ENGINEER, 1,
		"基础蓝图", "使用等级允许的外部建筑目录；预览、挪动并确认施工", true),
	/** Lv.2：民居、仓储和装饰入门。 */
	ENGINEER_TEMPLATE_LIBRARY_1("engineer.template_library_1", SquireProfession.ENGINEER, 2,
		"模板库 I", "解锁云杉民居、梁架仓储屋和村庄小喷泉", true),
	/** Lv.3：旋转蓝图，并进入生产建筑。 */
	ENGINEER_BLUEPRINT_ROTATION("engineer.blueprint_rotation", SquireProfession.ENGINEER, 3,
		"蓝图旋转", "四向旋转预览；施工与材料统计使用同一份变换后的蓝图", true),
	/** Lv.4：材料分区，并进入矿业与防御建筑。 */
	ENGINEER_MATERIAL_REGIONS("engineer.material_regions", SquireProfession.ENGINEER, 4,
		"材料主题", "基础材料主题替换；Lv10 可调整全部兼容材料分区", true),
	/** Lv.5：结构变体与更完整的生产/公共建筑。 */
	ENGINEER_STRUCTURAL_VARIANTS("engineer.structural_variants", SquireProfession.ENGINEER, 5,
		"结构变体", "调整屋顶/地基/窗/入口；解锁工匠小屋和旅店", true),
	/** Lv.6：多层和大型防御建筑。 */
	ENGINEER_MULTI_FLOOR("engineer.multi_floor", SquireProfession.ENGINEER, 6,
		"多层建筑", "设计 1/2/3 层建筑；解锁边境守望塔", true),
	/** Lv.7：镜像和高级公共建筑。 */
	ENGINEER_BLUEPRINT_MIRROR("engineer.blueprint_mirror", SquireProfession.ENGINEER, 7,
		"蓝图镜像", "沿 X/Z 轴镜像；解锁学者图书馆", true),
	/** Lv.7：附属模块。 */
	ENGINEER_OPTIONAL_MODULES("engineer.optional_modules", SquireProfession.ENGINEER, 7,
		"附属模块", "门廊、烟囱、储藏侧翼、塔楼、阳台，只能接在合法连接点上", true),
	/** Lv.8：模块化组合和大型仓储。 */
	ENGINEER_MODULAR_BLUEPRINT("engineer.modular_blueprint", SquireProfession.ENGINEER, 8,
		"模块化蓝图", "自由组合主体/侧翼/塔楼；解锁大型云杉仓库", true),
	/** Lv.9：一次预览多栋。 */
	ENGINEER_COMPOUND_BLUEPRINT("engineer.compound_blueprint", SquireProfession.ENGINEER, 9,
		"复合蓝图", "一份蓝图里放下主屋 + 仓库 + 哨塔 + 围栏 + 大门", true),
	/** Lv.9 起可存，Lv.10 是完整的预设库。 */
	ENGINEER_BLUEPRINT_PRESET_LIBRARY("engineer.blueprint_preset_library",
		SquireProfession.ENGINEER, 9, "蓝图预设库",
		"把调好的参数存成一个名字，以后说「再建一个那个」就能直接开预览", true);

	private final String id;
	private final SquireProfession profession;
	private final int unlockLevel;
	private final String displayName;
	private final String summary;
	private final boolean available;

	ProfessionAbility(String id, SquireProfession profession, int unlockLevel,
			String displayName, String summary, boolean available) {
		this.id = id;
		this.profession = profession;
		this.unlockLevel = unlockLevel;
		this.displayName = displayName;
		this.summary = summary;
		this.available = available;
	}

	public String id() {
		return id;
	}

	public SquireProfession profession() {
		return profession;
	}

	/** 本职业练到这一级才会。 */
	public int unlockLevel() {
		return unlockLevel;
	}

	public String displayName() {
		return displayName;
	}

	/** 一句话说清它到底改变了什么行为。 */
	public String summary() {
		return summary;
	}

	/** 已经落地了吗。没落地的绝不假装存在。 */
	public boolean available() {
		return available;
	}

	public String nameKey() {
		return "squire.gui.profession_ability." + id.replace('.', '_');
	}

	public static ProfessionAbility byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (ProfessionAbility ability : values()) {
			if (ability.id.equals(needle)) {
				return ability;
			}
		}
		return null;
	}

	/** 某个职业的全部能力，按解锁等级排好。 */
	public static List<ProfessionAbility> of(SquireProfession profession) {
		return Arrays.stream(values())
			.filter(ability -> ability.profession == profession)
			.sorted(Comparator.comparingInt(ProfessionAbility::unlockLevel)
				.thenComparing(ProfessionAbility::id))
			.toList();
	}

	/** Product-facing list; legacy capability gates and saved IDs remain intact. */
	public static List<ProfessionAbility> playerVisible(SquireProfession profession) {
		var retired = dev.squire.server.blueprint.BuildingContentPolicy.current().retiredAbilities();
		return of(profession).stream().filter(a -> !retired.contains(a.id())).toList();
	}
	public static ProfessionAbility nextPlayerVisibleAfter(SquireProfession profession, int level) {
		return playerVisible(profession).stream().filter(a -> a.unlockLevel() > level).findFirst().orElse(null);
	}

	/** 这一级<b>刚刚</b>解锁的那些（用来在晋升时发提示）。 */
	public static List<ProfessionAbility> unlockedAt(SquireProfession profession,
			int level) {
		return Arrays.stream(values())
			.filter(ability -> ability.profession == profession
				&& ability.unlockLevel == level && ability.available)
			.sorted(Comparator.comparing(ProfessionAbility::id))
			.toList();
	}

	/** 下一个还没解锁的能力；已经全解锁返回 null。 */
	public static ProfessionAbility nextAfter(SquireProfession profession, int level) {
		for (ProfessionAbility ability : of(profession)) {
			if (ability.unlockLevel > level) {
				return ability;
			}
		}
		return null;
	}
}
