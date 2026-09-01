package dev.squire.server.profession;

/**
 * 守卫经验的<b>全部</b>计算规则（设计文档 §10）。
 *
 * <h2>为什么它不认识 Minecraft</h2>
 * <p>输入只有实体 id 字符串、几个布尔和两个计数，于是整套防刷规则可以被普通单测
 * 逐条盖住——刷怪塔的行为、Boss 重复击杀、保护主人加成，都不需要开一个服务器才能验。</p>
 *
 * <h2>三道闸</h2>
 * <ol>
 *   <li><b>真的参与了战斗</b>：最后一击，或者至少打掉目标最大生命的 20%。
 *       玩家杀怪、随从站旁边看，一分不给。</li>
 *   <li><b>同类怪短窗口衰减</b>：5 分钟内同一种怪杀到第 11 只开始打折，
 *       第 51 只只剩 5%。正常探索完全感觉不到，挂机刷怪塔升不动级。</li>
 *   <li><b>Boss 重复衰减</b>：同一种 Boss 第二次只有一半，第三次起四分之一。</li>
 * </ol>
 */
public final class GuardXp {

	private GuardXp() {
	}

	/**
	 * 一次结算的明细。留着每一项是为了能如实告诉玩家「为什么只有 1 点」——
	 * 一个说不清来源的经验数字，玩家只会当成 bug。
	 */
	public record Award(int baseXp, double protectionBonus, double repeatMultiplier,
			double bossMultiplier, int finalXp, boolean boss) {

		public boolean anything() {
			return finalXp > 0;
		}

		/** 有任何一项打了折吗（面板/聊天据此决定要不要解释）。 */
		public boolean penalised() {
			return repeatMultiplier < 1.0 || bossMultiplier < 1.0;
		}
	}

	/** 一分不给。 */
	public static final Award NOTHING = new Award(0, 1.0, 1.0, 1.0, 0, false);

	/**
	 * 这次击杀算不算随从「真的参与了」。
	 *
	 * @param lastHit        最后一击是随从打的
	 * @param damageDealt    随从对这个目标累计造成的伤害
	 * @param targetMaxHealth 目标的最大生命
	 */
	public static boolean participated(ProfessionConfig config, boolean lastHit,
			double damageDealt, double targetMaxHealth) {
		if (lastHit) {
			return true;
		}
		if (targetMaxHealth <= 0) {
			return false;
		}
		return damageDealt >= targetMaxHealth * config.guardEffectiveDamageFraction;
	}

	/**
	 * 算一次击杀给多少经验。
	 *
	 * @param entityId          被击杀者的实体 id，例如 {@code minecraft:zombie}
	 * @param protectingOwner   这个目标当时正在威胁主人
	 * @param killsInWindow     含这一次在内，窗口内已经杀了几只<b>同种</b>怪（1 起）
	 * @param priorBossKills    这只随从之前打倒过几次<b>同种</b> Boss（不含这一次）
	 */
	public static Award award(ProfessionConfig config, String entityId,
			boolean protectingOwner, int killsInWindow, int priorBossKills) {
		int bossBase = config.bossXp(entityId);
		boolean boss = bossBase > 0;
		int base = boss ? bossBase : config.tierBaseXp(config.mobTier(entityId));
		if (base <= 0) {
			return NOTHING;
		}
		double protection = protectingOwner ? config.guardProtectionXpBonus : 1.0;
		// Boss 走自己那条终身衰减，不再叠短窗口衰减——否则打一次凋灵还要看
		// 「这五分钟里杀了几只凋灵」，那是一条永远不会触发的规则。
		double repeat = boss ? 1.0 : repeatMultiplier(config, killsInWindow);
		double bossMultiplier = boss ? bossRepeatMultiplier(config, priorBossKills) : 1.0;
		double raw = base * protection * repeat * bossMultiplier;
		return new Award(base, protection, repeat, bossMultiplier,
			(int) Math.max(0L, Math.round(raw)), boss);
	}

	/**
	 * 窗口内第 {@code rank} 只同种怪的倍率。
	 *
	 * <p>分界是<b>闭区间</b>：默认表下第 10 只仍是 100%，第 11 只才开始打折。</p>
	 */
	public static double repeatMultiplier(ProfessionConfig config, int rank) {
		double[] multipliers = config.guardRepeatKillMultipliers;
		if (multipliers.length == 0) {
			return 1.0;
		}
		int index = 0;
		for (int threshold : config.guardRepeatKillThresholds) {
			if (rank > threshold) {
				index++;
			}
		}
		return multipliers[Math.min(index, multipliers.length - 1)];
	}

	/** 同种 Boss 的第 {@code priorKills + 1} 次击杀的倍率。 */
	public static double bossRepeatMultiplier(ProfessionConfig config, int priorKills) {
		double[] multipliers = config.guardBossRepeatMultipliers;
		if (multipliers.length == 0) {
			return 1.0;
		}
		return multipliers[Math.min(Math.max(0, priorKills), multipliers.length - 1)];
	}

	/** 这只怪该被识别成「打不过」吗（Lv.9 高威胁判断用）。 */
	public static boolean highThreat(ProfessionConfig config, String entityId) {
		return config.isHighThreat(entityId);
	}
}
