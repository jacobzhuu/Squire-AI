package dev.squire.server.profession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 职业系统的<b>全部可调数字</b>（设计文档 §26）。
 *
 * <h2>为什么全都在这里</h2>
 * <p>设计文档对配置化的要求不是「以后再说」，而是一条硬约束：等级曲线、晋升材料、
 * 怪物威胁分档、经验、防刷窗口、治疗阈值、追击距离、蓝图尺寸上限——任何一条要平衡，
 * 都不该回去改 Java。所以行为代码<b>只</b>从这里读数字，一个也不许自己写死。</p>
 *
 * <h2>失败一律回默认值</h2>
 * <p>配置文件缺失、坏掉、或者某一项写错类型，都退回该项的默认值并记一条日志，
 * 绝不半配置化——一个只加载了一半的平衡表，比完全没有这个文件危险得多。</p>
 *
 * <p>这个类<b>不认识任何 Minecraft 类型</b>（怪物用 {@code minecraft:zombie} 这样的
 * id 字符串表示），于是整套平衡规则可以被普通单测盖住。</p>
 */
public final class ProfessionConfig {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ProfessionConfig.class);

	/** 一条晋升材料要求。 */
	public record ItemRequirement(String itemId, int count) {
		public ItemRequirement {
			if (itemId == null || itemId.isBlank()) {
				throw new IllegalArgumentException("promotion item needs an id");
			}
			count = Math.max(1, count);
		}
	}

	// ------------------------------------------------------------------ 通用

	/**
	 * 每一级<b>独立</b>的经验条上限。下标 0 是 1→2，下标 8 是 9→10。
	 *
	 * <p>刻意不是巨大的累计总量：玩家看的是「这一条还差多少」，
	 * 而不是一个五位数在缓慢爬。</p>
	 */
	public final int[] xpRequiredPerLevel;

	/** 经验条满了但还没晋升时，最多能存下一级需求的这个比例。 */
	public final double overflowXpRatio;

	/** 转职门槛：默认是四项基础训练的总和，旧配置自动限制到可达的经验上限。 */
	public final int trainingXpRequired;

	/** 目标等级 → 晋升材料。Lv.10 见 {@link #masterPromotionItems}。 */
	public final Map<Integer, List<ItemRequirement>> promotionItems;

	/** 职业专属的大师晋升材料（目标等级 10）。 */
	public final Map<SquireProfession, List<ItemRequirement>> masterPromotionItems;

	// ------------------------------------------------------------------ 守卫

	/** 下标 0 是 Lv.1 的最大生命。Lv.10 = 30，也就是 15 颗心。 */
	public final int[] guardHpPerLevel;

	/**
	 * 职业附加的近战伤害，下标 0 是 Lv.1。
	 *
	 * <p>数字很小是刻意的：武器决定战斗力，等级决定他会不会用。默认表
	 * （Lv1–4 +0 / Lv5–7 +0.5 / Lv8–9 +1.0 / Lv10 +1.5）来自设计文档里的
	 * 「可选小型补偿」，把它整列配成 0 就等于关掉这一条。</p>
	 */
	public final double[] guardBonusDamagePerLevel;

	/** 威胁分档 1..4 的基础经验。 */
	public final int[] guardTierBaseXp;

	/** 实体 id → 威胁分档（1..4）。表里没有的按 {@link #guardDefaultTier} 算。 */
	public final Map<String, Integer> guardMobTier;

	/** 表里没有的敌对生物算第几档。 */
	public final int guardDefaultTier;

	/** 实体 id → Boss 经验。命中这张表的<b>不</b>走分档，另走 Boss 重复衰减。 */
	public final Map<String, Integer> guardBossXp;

	/** 同类怪物重复击杀的统计窗口（tick）。 */
	public final long guardRepeatKillWindowTicks;

	/** 重复击杀的数量分界，必须升序，长度比 {@link #guardRepeatKillMultipliers} 少一。 */
	public final int[] guardRepeatKillThresholds;

	/** 与分界对应的经验倍率。 */
	public final double[] guardRepeatKillMultipliers;

	/** 同一类 Boss 第 1/2/3+ 次击杀的倍率。 */
	public final double[] guardBossRepeatMultipliers;

	/** 目标正在威胁主人时的经验倍率。 */
	public final double guardProtectionXpBonus;

	/** 没有最后一击时，至少要打掉目标最大生命的这个比例才算真的参与了战斗。 */
	public final double guardEffectiveDamageFraction;

	/**
	 * 血量比例低于它就该吃东西。
	 *
	 * <p>默认值刻意是通用自救的那个 0.84，而不是设计文档里的 0.65：让一个练到
	 * Lv.6 的守卫比没有职业的随从<b>更晚</b>吃东西，是把成长做成了退步。
	 * 想要设计文档那条更省粮的曲线，把这一项调成 0.65 即可。</p>
	 */
	public final double guardFoodHealThreshold;

	/** 血量比例低于它才允许喝治疗药水。高于它一律先吃饭，不许糟蹋药水。 */
	public final double guardPotionHealThreshold;

	/** 血量比例低于它就脱战、靠回主人身边。 */
	public final double guardRetreatThreshold;

	/** 两次主动使用消耗品之间的最短间隔（tick）。 */
	public final int guardConsumableCooldownTicks;
    public final double engineerWeaponDamageFactor;
    public final double engineerAttackIntervalFactor;
    public final double engineerSelfDefenceRadius;
    public final double guardRescueRadius;
    public final double guardOathTauntRadius;
    public final int guardOathDurationTicks;


	/** 姿态 → 追击上限系数（乘在护卫半径上）。 */
	public final Map<CombatStance, Double> guardChaseFactorByStance;

	/** 举盾之后至少保持这么多 tick，避免每拍抖动。 */
	public final int guardShieldHoldTicks;

	/** 高威胁目标（Warden / Wither / 末影龙 …）：识别为「打不过」，不主动接战。 */
	public final java.util.Set<String> guardHighThreatMobs;

	/** 主人血量比例低于它就进入「主人危急」。 */
	public final double guardOwnerEmergencyHealthFraction;

	/** 有这么多敌人锁定主人也算「主人危急」。 */
	public final int guardOwnerEmergencyThreatCount;

	// ------------------------------------------------------------------ 工程师

	/** 工程分档 1..5 的基础经验。 */
	public final int[] engineerProjectBaseXp;

	/** 规模档位 id → 倍率。 */
	public final Map<String, Double> engineerSizeMultiplier;

	/** 规模倍率的绝对上限。 */
	public final double engineerMaxSizeMultiplier;

	/** 使用了高级结构 Variant 时的倍率。 */
	public final double engineerStructuralVariantMultiplier;

	/** 层数 → 倍率。表里没有的按最大层数那一档算。 */
	public final Map<Integer, Double> engineerFloorMultiplier;

	/** 每个附属模块的加成。 */
	public final double engineerModuleMultiplier;

	/** 模块加成的上限。 */
	public final double engineerMaxModuleBonus;

	/** 短时间内同类工程第 1..5+ 次的倍率。 */
	public final double[] engineerRepeatProjectMultipliers;

	/** 同类工程的统计窗口（tick）。 */
	public final long engineerRepeatWindowTicks;

	/** 下标 0 是 Lv.1 允许的最大足印边长。 */
	public final int[] engineerMaxFootprintByLevel;

	/** 模板 id → 至少要几级才能用。表里没有的一律 Lv.1 就能用。 */
	public final Map<String, Integer> engineerTemplateMinLevel;

	/** 模板 id → 自身工程档位（1..5）。表里没有的算第 2 档「普通项目」。 */
	public final Map<String, Integer> engineerTemplateTier;

	/** 模板自身档位的兜底。 */
	public final int engineerDefaultTemplateTier;

	// ------------------------------------------------------------------ 构造

	private ProfessionConfig(Builder b) {
		this.xpRequiredPerLevel = b.xpRequiredPerLevel.clone();
		this.overflowXpRatio = b.overflowXpRatio;
		// Clamp legacy configurations (previously 100) to attainable basic training XP.
		this.trainingXpRequired = Math.min(b.trainingXpRequired, TrainingMilestone.totalXp());
		this.promotionItems = Map.copyOf(b.promotionItems);
		this.masterPromotionItems = Map.copyOf(b.masterPromotionItems);
		this.guardHpPerLevel = b.guardHpPerLevel.clone();
		this.guardBonusDamagePerLevel = b.guardBonusDamagePerLevel.clone();
		this.guardTierBaseXp = b.guardTierBaseXp.clone();
		this.guardMobTier = Map.copyOf(b.guardMobTier);
		this.guardDefaultTier = b.guardDefaultTier;
		this.guardBossXp = Map.copyOf(b.guardBossXp);
		this.guardRepeatKillWindowTicks = b.guardRepeatKillWindowTicks;
		this.guardRepeatKillThresholds = b.guardRepeatKillThresholds.clone();
		this.guardRepeatKillMultipliers = b.guardRepeatKillMultipliers.clone();
		this.guardBossRepeatMultipliers = b.guardBossRepeatMultipliers.clone();
		this.guardProtectionXpBonus = b.guardProtectionXpBonus;
		this.guardEffectiveDamageFraction = b.guardEffectiveDamageFraction;
		this.guardFoodHealThreshold = b.guardFoodHealThreshold;
		this.guardPotionHealThreshold = b.guardPotionHealThreshold;
		this.guardRetreatThreshold = b.guardRetreatThreshold;
		this.guardConsumableCooldownTicks = b.guardConsumableCooldownTicks;
        this.engineerWeaponDamageFactor = b.engineerWeaponDamageFactor;
        this.engineerAttackIntervalFactor = b.engineerAttackIntervalFactor;
        this.engineerSelfDefenceRadius = b.engineerSelfDefenceRadius;
        this.guardRescueRadius = b.guardRescueRadius;
        this.guardOathTauntRadius = b.guardOathTauntRadius;
        this.guardOathDurationTicks = b.guardOathDurationTicks;

		this.guardChaseFactorByStance = Map.copyOf(b.guardChaseFactorByStance);
		this.guardShieldHoldTicks = b.guardShieldHoldTicks;
		this.guardHighThreatMobs = java.util.Set.copyOf(b.guardHighThreatMobs);
		this.guardOwnerEmergencyHealthFraction = b.guardOwnerEmergencyHealthFraction;
		this.guardOwnerEmergencyThreatCount = b.guardOwnerEmergencyThreatCount;
		this.engineerProjectBaseXp = b.engineerProjectBaseXp.clone();
		this.engineerSizeMultiplier = Map.copyOf(b.engineerSizeMultiplier);
		this.engineerMaxSizeMultiplier = b.engineerMaxSizeMultiplier;
		this.engineerStructuralVariantMultiplier = b.engineerStructuralVariantMultiplier;
		this.engineerFloorMultiplier = Map.copyOf(b.engineerFloorMultiplier);
		this.engineerModuleMultiplier = b.engineerModuleMultiplier;
		this.engineerMaxModuleBonus = b.engineerMaxModuleBonus;
		this.engineerRepeatProjectMultipliers = b.engineerRepeatProjectMultipliers.clone();
		this.engineerRepeatWindowTicks = b.engineerRepeatWindowTicks;
		this.engineerMaxFootprintByLevel = b.engineerMaxFootprintByLevel.clone();
		this.engineerTemplateMinLevel = Map.copyOf(b.engineerTemplateMinLevel);
		this.engineerTemplateTier = Map.copyOf(b.engineerTemplateTier);
		this.engineerDefaultTemplateTier = b.engineerDefaultTemplateTier;
	}

	// ------------------------------------------------------------------ 查询

	/**
	 * 从 {@code level} 升到 {@code level + 1} 需要多少经验。
	 *
	 * <p>已经满级返回 0——满级的经验条不该显示成「差一点」。</p>
	 */
	public int xpToNext(int level) {
		int index = SquireProfession.clampLevel(level) - 1;
		return index < 0 || index >= xpRequiredPerLevel.length ? 0
			: xpRequiredPerLevel[index];
	}

	/** 晋升到 {@code targetLevel} 要交的材料；不需要材料时返回空表。 */
	public List<ItemRequirement> promotionCost(SquireProfession profession,
			int targetLevel) {
		if (targetLevel <= SquireProfession.MIN_LEVEL
				|| targetLevel > SquireProfession.MAX_LEVEL) {
			return List.of();
		}
		if (targetLevel == SquireProfession.MAX_LEVEL && profession != null) {
			List<ItemRequirement> master = masterPromotionItems.get(profession);
			if (master != null) {
				return master;
			}
		}
		return promotionItems.getOrDefault(targetLevel, List.of());
	}

	/** 这一级的最大生命。 */
	public int guardMaxHealth(int level) {
		int index = SquireProfession.clampLevel(level) - 1;
		return index < guardHpPerLevel.length ? guardHpPerLevel[index]
			: guardHpPerLevel[guardHpPerLevel.length - 1];
	}

	/** 这一级的职业附加近战伤害。 */
	public double guardBonusDamage(int level) {
		int index = SquireProfession.clampLevel(level) - 1;
		return index < guardBonusDamagePerLevel.length
			? guardBonusDamagePerLevel[index]
			: guardBonusDamagePerLevel[guardBonusDamagePerLevel.length - 1];
	}

	/** 这个实体属于第几档威胁。 */
	public int mobTier(String entityId) {
		Integer tier = entityId == null ? null
			: guardMobTier.get(entityId.toLowerCase(Locale.ROOT));
		return tier == null ? guardDefaultTier
			: Math.max(1, Math.min(guardTierBaseXp.length, tier));
	}

	/** 分档基础经验。 */
	public int tierBaseXp(int tier) {
		int index = Math.max(1, Math.min(guardTierBaseXp.length, tier)) - 1;
		return guardTierBaseXp[index];
	}

	/** 这是 Boss 吗；是就返回它的基础经验，不是返回 0。 */
	public int bossXp(String entityId) {
		Integer xp = entityId == null ? null
			: guardBossXp.get(entityId.toLowerCase(Locale.ROOT));
		return xp == null ? 0 : xp;
	}

	/** 这只怪该被识别成「打不过，别主动上」吗。 */
	public boolean isHighThreat(String entityId) {
		return entityId != null
			&& guardHighThreatMobs.contains(entityId.toLowerCase(Locale.ROOT));
	}

	/** 姿态对应的追击系数。 */
	public double chaseFactor(CombatStance stance) {
		Double factor = guardChaseFactorByStance.get(
			stance == null ? CombatStance.BALANCED : stance);
		return factor == null ? CombatStance.BALANCED.chaseFactor() : factor;
	}

	/** 这一级允许的最大足印边长。 */
	public int maxFootprint(int level) {
		int index = SquireProfession.clampLevel(level) - 1;
		return index < engineerMaxFootprintByLevel.length
			? engineerMaxFootprintByLevel[index]
			: engineerMaxFootprintByLevel[engineerMaxFootprintByLevel.length - 1];
	}

	/** 这个模板至少要几级。 */
	public int templateMinLevel(String templateId) {
		Integer min = templateId == null ? null
			: engineerTemplateMinLevel.get(templateId.toLowerCase(Locale.ROOT));
		return min == null ? SquireProfession.MIN_LEVEL
			: SquireProfession.clampLevel(min);
	}

	/** 规模档位倍率，超过绝对上限时截断。 */
	public double sizeMultiplier(String sizeClass) {
		Double value = sizeClass == null ? null
			: engineerSizeMultiplier.get(sizeClass.toLowerCase(Locale.ROOT));
		return Math.min(engineerMaxSizeMultiplier, value == null ? 1.0 : value);
	}

	/** 层数倍率。 */
	public double floorMultiplier(int floors) {
		Double exact = engineerFloorMultiplier.get(Math.max(1, floors));
		if (exact != null) {
			return exact;
		}
		double best = 1.0;
		for (Map.Entry<Integer, Double> entry : engineerFloorMultiplier.entrySet()) {
			if (entry.getKey() <= floors && entry.getValue() > best) {
				best = entry.getValue();
			}
		}
		return best;
	}

	/** 这个模板自身算第几档工程。 */
	public int templateTier(String templateId) {
		Integer tier = templateId == null ? null
			: engineerTemplateTier.get(templateId.toLowerCase(Locale.ROOT));
		int value = tier == null ? engineerDefaultTemplateTier : tier;
		return Math.max(1, Math.min(engineerProjectBaseXp.length, value));
	}

	/** 工程分档基础经验。 */
	public int projectBaseXp(int tier) {
		int index = Math.max(1, Math.min(engineerProjectBaseXp.length, tier)) - 1;
		return engineerProjectBaseXp[index];
	}

    private static double finiteRange(double value, double min, double max, double fallback) {
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }

	// ------------------------------------------------------------------ 默认值

	private static final ProfessionConfig DEFAULTS = new Builder().build();

	/** 内置平衡表。配置文件没写到的每一项都用它。 */
	public static ProfessionConfig defaults() {
		return DEFAULTS;
	}

	/**
	 * 从 {@code config/squire/profession.json} 读一份覆盖。
	 *
	 * <p>文件不存在是正常状态（绝大多数存档不会去调平衡），返回默认值且不记日志。</p>
	 */
	public static ProfessionConfig load(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return defaults();
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file));
			if (!parsed.isJsonObject()) {
				LOG.warn("[squire-profession] profession.json 不是一个 JSON 对象，用默认平衡表");
				return defaults();
			}
			return fromJson(parsed.getAsJsonObject());
		} catch (RuntimeException | java.io.IOException bad) {
			LOG.warn("[squire-profession] profession.json 读不了（{}），用默认平衡表",
				bad.toString());
			return defaults();
		}
	}

	/** 覆盖式解析：只有真的写了的键才盖掉默认值。 */
	public static ProfessionConfig fromJson(JsonObject root) {
		Builder b = new Builder();
        JsonObject combat = obj(root, "combat");
        b.engineerWeaponDamageFactor = finiteRange(number(combat, "engineerWeaponDamageFactor", b.engineerWeaponDamageFactor), 0, 1, b.engineerWeaponDamageFactor);
        b.engineerAttackIntervalFactor = finiteRange(number(combat, "engineerAttackIntervalFactor", b.engineerAttackIntervalFactor), 1, 10, b.engineerAttackIntervalFactor);
        b.engineerSelfDefenceRadius = finiteRange(number(combat, "engineerSelfDefenceRadius", b.engineerSelfDefenceRadius), 1, 64, b.engineerSelfDefenceRadius);
        b.guardRescueRadius = finiteRange(number(combat, "guardRescueRadius", b.guardRescueRadius), 1, 64, b.guardRescueRadius);
        b.guardOathTauntRadius = finiteRange(number(combat, "guardOathTauntRadius", b.guardOathTauntRadius), 1, 64, b.guardOathTauntRadius);
        b.guardOathDurationTicks = (int) finiteRange(number(combat, "guardOathDurationTicks", b.guardOathDurationTicks), 1, 1200, b.guardOathDurationTicks);

		JsonObject general = obj(root, "general");
		b.xpRequiredPerLevel = intArray(general, "xpRequiredPerLevel", b.xpRequiredPerLevel);
		b.overflowXpRatio = clamp01(number(general, "overflowXpRatio", b.overflowXpRatio));
		b.trainingXpRequired = Math.max(0,
			(int) number(general, "trainingXpRequired", b.trainingXpRequired));
		Map<Integer, List<ItemRequirement>> costs = promotionMap(general, "promotionItems");
		if (costs != null) {
			b.promotionItems = costs;
		}
		JsonObject master = obj(general, "masterPromotionItems");
		if (master != null) {
			Map<SquireProfession, List<ItemRequirement>> parsed = new LinkedHashMap<>();
			for (SquireProfession profession : SquireProfession.values()) {
				List<ItemRequirement> items = itemList(master.get(profession.id()));
				parsed.put(profession, items != null ? items
					: b.masterPromotionItems.get(profession));
			}
			b.masterPromotionItems = parsed;
		}

		JsonObject guard = obj(root, "guard");
		b.guardHpPerLevel = intArray(guard, "hpPerLevel", b.guardHpPerLevel);
		b.guardBonusDamagePerLevel = doubleArray(guard, "bonusDamagePerLevel",
			b.guardBonusDamagePerLevel);
		b.guardTierBaseXp = intArray(guard, "tierBaseXp", b.guardTierBaseXp);
		Map<String, Integer> tiers = intMap(guard, "mobThreatTier");
		if (tiers != null) {
			b.guardMobTier = tiers;
		}
		b.guardDefaultTier = (int) number(guard, "defaultTier", b.guardDefaultTier);
		Map<String, Integer> bosses = intMap(guard, "bossXp");
		if (bosses != null) {
			b.guardBossXp = bosses;
		}
		b.guardRepeatKillWindowTicks = (long) number(guard, "repeatKillWindowTicks",
			b.guardRepeatKillWindowTicks);
		b.guardRepeatKillThresholds = intArray(guard, "repeatKillThresholds",
			b.guardRepeatKillThresholds);
		b.guardRepeatKillMultipliers = doubleArray(guard, "repeatKillMultipliers",
			b.guardRepeatKillMultipliers);
		b.guardBossRepeatMultipliers = doubleArray(guard, "bossRepeatMultipliers",
			b.guardBossRepeatMultipliers);
		b.guardProtectionXpBonus = number(guard, "protectionXpBonus",
			b.guardProtectionXpBonus);
		b.guardEffectiveDamageFraction = clamp01(number(guard, "effectiveDamageFraction",
			b.guardEffectiveDamageFraction));
		b.guardFoodHealThreshold = clamp01(number(guard, "foodHealThreshold",
			b.guardFoodHealThreshold));
		b.guardPotionHealThreshold = clamp01(number(guard, "potionHealThreshold",
			b.guardPotionHealThreshold));
		b.guardRetreatThreshold = clamp01(number(guard, "retreatThreshold",
			b.guardRetreatThreshold));
		b.guardConsumableCooldownTicks = (int) number(guard, "consumableUseCooldown",
			b.guardConsumableCooldownTicks);
		JsonObject chase = obj(guard, "chaseDistanceByStance");
		if (chase != null) {
			Map<CombatStance, Double> parsed = new LinkedHashMap<>();
			for (CombatStance stance : CombatStance.values()) {
				parsed.put(stance, boxedNumber(chase, stance.id(),
					b.guardChaseFactorByStance.get(stance)));
			}
			b.guardChaseFactorByStance = parsed;
		}
		b.guardShieldHoldTicks = (int) number(guard, "shieldHoldTicks",
			b.guardShieldHoldTicks);
		List<String> highThreat = stringList(guard, "highThreatMobs");
		if (highThreat != null) {
			b.guardHighThreatMobs = new java.util.LinkedHashSet<>(highThreat);
		}
		b.guardOwnerEmergencyHealthFraction = clamp01(number(guard,
			"ownerEmergencyHealthFraction", b.guardOwnerEmergencyHealthFraction));
		b.guardOwnerEmergencyThreatCount = (int) number(guard,
			"ownerEmergencyThreatCount", b.guardOwnerEmergencyThreatCount);

		JsonObject engineer = obj(root, "engineer");
		b.engineerProjectBaseXp = intArray(engineer, "projectBaseXp",
			b.engineerProjectBaseXp);
		Map<String, Double> sizes = doubleMap(engineer, "sizeMultiplier");
		if (sizes != null) {
			b.engineerSizeMultiplier = sizes;
		}
		b.engineerMaxSizeMultiplier = number(engineer, "maxSizeMultiplier",
			b.engineerMaxSizeMultiplier);
		b.engineerStructuralVariantMultiplier = number(engineer,
			"structuralVariantMultiplier", b.engineerStructuralVariantMultiplier);
		Map<String, Double> floors = doubleMap(engineer, "floorMultiplier");
		if (floors != null) {
			Map<Integer, Double> parsed = new LinkedHashMap<>();
			floors.forEach((key, value) -> {
				try {
					parsed.put(Integer.parseInt(key.trim()), value);
				} catch (NumberFormatException ignored) {
					// 层数键写坏了就跳过这一条，不作废整张表
				}
			});
			if (!parsed.isEmpty()) {
				b.engineerFloorMultiplier = parsed;
			}
		}
		b.engineerModuleMultiplier = number(engineer, "moduleMultiplier",
			b.engineerModuleMultiplier);
		b.engineerMaxModuleBonus = number(engineer, "maxModuleBonus",
			b.engineerMaxModuleBonus);
		b.engineerRepeatProjectMultipliers = doubleArray(engineer,
			"repeatProjectPenalty", b.engineerRepeatProjectMultipliers);
		b.engineerRepeatWindowTicks = (long) number(engineer, "repeatWindowTicks",
			b.engineerRepeatWindowTicks);
		b.engineerMaxFootprintByLevel = intArray(engineer, "maxBlueprintSizeByLevel",
			b.engineerMaxFootprintByLevel);
		Map<String, Integer> templates = intMap(engineer, "templateMinLevel");
		if (templates != null) {
			b.engineerTemplateMinLevel = templates;
		}
		Map<String, Integer> tiers2 = intMap(engineer, "templateTier");
		if (tiers2 != null) {
			b.engineerTemplateTier = tiers2;
		}
		b.engineerDefaultTemplateTier = (int) number(engineer, "defaultTemplateTier",
			b.engineerDefaultTemplateTier);
		return b.build();
	}

	// ------------------------------------------------------------------ JSON helpers

	private static JsonObject obj(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static double number(JsonObject parent, String key, double fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return parent.get(key).getAsDouble();
		} catch (RuntimeException bad) {
			return fallback;
		}
	}

	/** 装箱版：{@code null} 兜底也要能传进来（姿态表可能缺某一档）。 */
	private static Double boxedNumber(JsonObject parent, String key, Double fallback) {
		double value = number(parent, key,
			fallback == null ? Double.NaN : fallback.doubleValue());
		return Double.isNaN(value) ? fallback : Double.valueOf(value);
	}

	private static int[] intArray(JsonObject parent, String key, int[] fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
			return fallback;
		}
		JsonArray array = parent.getAsJsonArray(key);
		if (array.isEmpty()) {
			return fallback;
		}
		int[] out = new int[array.size()];
		for (int i = 0; i < array.size(); i++) {
			try {
				out[i] = array.get(i).getAsInt();
			} catch (RuntimeException bad) {
				return fallback; // 一项坏掉就整条回退，不产生半张表
			}
		}
		return out;
	}

	private static double[] doubleArray(JsonObject parent, String key, double[] fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
			return fallback;
		}
		JsonArray array = parent.getAsJsonArray(key);
		if (array.isEmpty()) {
			return fallback;
		}
		double[] out = new double[array.size()];
		for (int i = 0; i < array.size(); i++) {
			try {
				out[i] = array.get(i).getAsDouble();
			} catch (RuntimeException bad) {
				return fallback;
			}
		}
		return out;
	}

	private static Map<String, Integer> intMap(JsonObject parent, String key) {
		JsonObject source = obj(parent, key);
		if (source == null) {
			return null;
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String name : source.keySet()) {
			try {
				out.put(name.toLowerCase(Locale.ROOT), source.get(name).getAsInt());
			} catch (RuntimeException ignored) {
				// 坏掉的一行跳过
			}
		}
		return out.isEmpty() ? null : out;
	}

	private static Map<String, Double> doubleMap(JsonObject parent, String key) {
		JsonObject source = obj(parent, key);
		if (source == null) {
			return null;
		}
		Map<String, Double> out = new LinkedHashMap<>();
		for (String name : source.keySet()) {
			try {
				out.put(name.toLowerCase(Locale.ROOT), source.get(name).getAsDouble());
			} catch (RuntimeException ignored) {
				// 坏掉的一行跳过
			}
		}
		return out.isEmpty() ? null : out;
	}

	private static List<String> stringList(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
			return null;
		}
		List<String> out = new ArrayList<>();
		for (JsonElement element : parent.getAsJsonArray(key)) {
			try {
				out.add(element.getAsString().trim().toLowerCase(Locale.ROOT));
			} catch (RuntimeException ignored) {
				// 坏掉的一项跳过
			}
		}
		return out.isEmpty() ? null : out;
	}

	private static Map<Integer, List<ItemRequirement>> promotionMap(JsonObject parent,
			String key) {
		JsonObject source = obj(parent, key);
		if (source == null) {
			return null;
		}
		Map<Integer, List<ItemRequirement>> out = new LinkedHashMap<>();
		for (String name : source.keySet()) {
			List<ItemRequirement> items = itemList(source.get(name));
			if (items == null) {
				continue;
			}
			try {
				out.put(Integer.parseInt(name.trim()), items);
			} catch (NumberFormatException ignored) {
				// 目标等级键写坏了就跳过这一条
			}
		}
		return out.isEmpty() ? null : out;
	}

	/** {@code [{"item":"minecraft:iron_ingot","count":8}, ...]} */
	private static List<ItemRequirement> itemList(JsonElement element) {
		if (element == null || !element.isJsonArray()) {
			return null;
		}
		List<ItemRequirement> out = new ArrayList<>();
		for (JsonElement entry : element.getAsJsonArray()) {
			if (!entry.isJsonObject()) {
				continue;
			}
			JsonObject item = entry.getAsJsonObject();
			try {
				out.add(new ItemRequirement(item.get("item").getAsString().trim(),
					item.has("count") ? item.get("count").getAsInt() : 1));
			} catch (RuntimeException ignored) {
				// 坏掉的一条跳过
			}
		}
		return List.copyOf(out); // 空表是合法的：允许把某一级配成不要材料
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	// ------------------------------------------------------------------ builder

	/** 只在这个文件内部用，用来把「默认值」和「JSON 覆盖」写成同一段代码。 */
	private static final class Builder {

		int[] xpRequiredPerLevel = {100, 200, 350, 550, 800, 1100, 1500, 2000, 2800};
		double overflowXpRatio = 0.25;
		int trainingXpRequired = TrainingMilestone.totalXp();
		Map<Integer, List<ItemRequirement>> promotionItems = defaultPromotionItems();
		Map<SquireProfession, List<ItemRequirement>> masterPromotionItems =
			defaultMasterItems();

		int[] guardHpPerLevel = {20, 20, 22, 22, 24, 24, 26, 26, 28, 30};
		double[] guardBonusDamagePerLevel =
			{0, 0, 0, 0, 0.5, 0.5, 0.5, 1.0, 1.0, 1.5};
		int[] guardTierBaseXp = {2, 4, 8, 20};
		Map<String, Integer> guardMobTier = defaultMobTiers();
		int guardDefaultTier = 1;
		Map<String, Integer> guardBossXp = defaultBossXp();
		long guardRepeatKillWindowTicks = 6000L;          // 5 分钟
		int[] guardRepeatKillThresholds = {10, 25, 50};
		double[] guardRepeatKillMultipliers = {1.0, 0.5, 0.2, 0.05};
		double[] guardBossRepeatMultipliers = {1.0, 0.5, 0.25};
		double guardProtectionXpBonus = 1.25;
		double guardEffectiveDamageFraction = 0.20;
		double guardFoodHealThreshold = 0.84;
		double guardPotionHealThreshold = 0.35;
		double guardRetreatThreshold = 0.20;
		int guardConsumableCooldownTicks = 60;
        double engineerWeaponDamageFactor = 0.45;
        double engineerAttackIntervalFactor = 1.5;
        double engineerSelfDefenceRadius = 4.0;
        double guardRescueRadius = 16.0;
        double guardOathTauntRadius = 16.0;
        int guardOathDurationTicks = 300;

		Map<CombatStance, Double> guardChaseFactorByStance = defaultChaseFactors();
		int guardShieldHoldTicks = 20;
		java.util.Set<String> guardHighThreatMobs = new java.util.LinkedHashSet<>(List.of(
			"minecraft:warden", "minecraft:wither", "minecraft:ender_dragon"));
		double guardOwnerEmergencyHealthFraction = 0.40;
		int guardOwnerEmergencyThreatCount = 3;

		int[] engineerProjectBaseXp = {20, 40, 70, 110, 180};
		Map<String, Double> engineerSizeMultiplier = defaultSizeMultipliers();
		double engineerMaxSizeMultiplier = 2.0;
		double engineerStructuralVariantMultiplier = 1.10;
		Map<Integer, Double> engineerFloorMultiplier =
			new LinkedHashMap<>(Map.of(1, 1.0, 2, 1.15, 3, 1.25));
		double engineerModuleMultiplier = 0.10;
		double engineerMaxModuleBonus = 0.40;
		double[] engineerRepeatProjectMultipliers = {1.0, 0.75, 0.5, 0.25, 0.10};
		long engineerRepeatWindowTicks = 72000L;          // 1 小时
		int[] engineerMaxFootprintByLevel = {9, 13, 13, 17, 21, 21, 21, 32, 32, 48};
		Map<String, Integer> engineerTemplateMinLevel = defaultTemplateLevels();
		Map<String, Integer> engineerTemplateTier = defaultTemplateTiers();
		int engineerDefaultTemplateTier = 2;

		ProfessionConfig build() {
			return new ProfessionConfig(this);
		}
	}

	private static Map<Integer, List<ItemRequirement>> defaultPromotionItems() {
		Map<Integer, List<ItemRequirement>> out = new LinkedHashMap<>();
		out.put(2, List.of(new ItemRequirement("minecraft:iron_ingot", 8)));
		out.put(3, List.of(new ItemRequirement("minecraft:gold_ingot", 8)));
		out.put(4, List.of(new ItemRequirement("minecraft:lapis_lazuli", 16)));
		out.put(5, List.of(new ItemRequirement("minecraft:diamond", 2)));
		out.put(6, List.of(new ItemRequirement("minecraft:emerald", 12)));
		out.put(7, List.of(new ItemRequirement("minecraft:diamond", 4)));
		out.put(8, List.of(new ItemRequirement("minecraft:netherite_scrap", 4)));
		out.put(9, List.of(new ItemRequirement("minecraft:diamond", 8)));
		return out;
	}

	private static Map<SquireProfession, List<ItemRequirement>> defaultMasterItems() {
		Map<SquireProfession, List<ItemRequirement>> out = new LinkedHashMap<>();
		out.put(SquireProfession.GUARD,
			List.of(new ItemRequirement("minecraft:nether_star", 1)));
		out.put(SquireProfession.ENGINEER,
			List.of(new ItemRequirement("minecraft:beacon", 1)));
		return out;
	}

	private static Map<CombatStance, Double> defaultChaseFactors() {
		Map<CombatStance, Double> out = new LinkedHashMap<>();
		for (CombatStance stance : CombatStance.values()) {
			out.put(stance, stance.chaseFactor());
		}
		return out;
	}

	private static Map<String, Double> defaultSizeMultipliers() {
		Map<String, Double> out = new LinkedHashMap<>();
		out.put("small", 1.00);
		out.put("medium", 1.25);
		out.put("large", 1.50);
		out.put("very_large", 1.75);
		return out;
	}

	private static Map<String, Integer> defaultTemplateLevels() {
		Map<String, Integer> out = new LinkedHashMap<>();
		// Lv.1 学徒只有最基础的一栋房子，其余按模板库逐级放开。
		out.put("shed", 2);
		out.put("small_storage", 2);
		out.put("small_house", 2);
		out.put("large_house", 6);
		out.put("warehouse", 6);
		out.put("watchtower", 6);
		out.put("outpost", 9);
		return out;
	}

	/**
	 * 模板自身的工程档位（设计文档 §14.2 的例子）。
	 *
	 * <p>只是<b>起点</b>：多层、附属模块、复合蓝图会把实际档位往上抬，
	 * 见 {@link EngineerXp#tierOf}。</p>
	 */
	private static Map<String, Integer> defaultTemplateTiers() {
		Map<String, Integer> out = new LinkedHashMap<>();
		out.put("shed", 1);
		out.put("small_storage", 1);
		out.put("small_house", 1);
		out.put("house", 2);
		out.put("watchtower", 2);
		out.put("warehouse", 2);
		out.put("large_house", 3);
		out.put("outpost", 5);
		return out;
	}

	/**
	 * 默认威胁分档（设计文档 §10.2）。
	 *
	 * <p>只列原版：模组生物走 {@code defaultTier}，玩家想给它们单独定档就写配置。
	 * 假装认识所有模组怪，比老实退回第 1 档更糟。</p>
	 */
	private static Map<String, Integer> defaultMobTiers() {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String id : List.of("minecraft:zombie", "minecraft:spider",
				"minecraft:cave_spider", "minecraft:slime", "minecraft:drowned",
				"minecraft:zombie_villager", "minecraft:silverfish",
				"minecraft:endermite", "minecraft:magma_cube", "minecraft:bogged")) {
			out.put(id, 1);
		}
		for (String id : List.of("minecraft:skeleton", "minecraft:stray",
				"minecraft:husk", "minecraft:witch", "minecraft:creeper",
				"minecraft:pillager", "minecraft:zombified_piglin", "minecraft:piglin",
				"minecraft:enderman", "minecraft:guardian", "minecraft:hoglin",
				"minecraft:zoglin", "minecraft:phantom", "minecraft:shulker",
				"minecraft:vex", "minecraft:breeze")) {
			out.put(id, 2);
		}
		for (String id : List.of("minecraft:piglin_brute", "minecraft:ghast",
				"minecraft:blaze", "minecraft:wither_skeleton", "minecraft:vindicator",
				"minecraft:evoker", "minecraft:illusioner")) {
			out.put(id, 3);
		}
		for (String id : List.of("minecraft:ravager", "minecraft:elder_guardian")) {
			out.put(id, 4);
		}
		return out;
	}

	private static Map<String, Integer> defaultBossXp() {
		Map<String, Integer> out = new LinkedHashMap<>();
		out.put("minecraft:warden", 120);
		out.put("minecraft:wither", 180);
		out.put("minecraft:ender_dragon", 250);
		return out;
	}
}
