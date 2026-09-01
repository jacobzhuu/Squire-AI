package dev.squire.server.profession;

/**
 * 威胁评分（设计文档 §9 Lv.3）。Lv.3 之前守卫只会打<b>离主人最近的那只</b>，
 * 于是一只正在射你的骷髅会输给一只刚好站得更近、还没盯上任何人的僵尸。
 *
 * <p>评分是纯函数：输入全是数字和布尔，一条规则也不藏在实体里。于是「他为什么先打
 * 那一只」这个问题永远能被复现和被测试。</p>
 *
 * <h2>为什么有一项「已经在打的加分」</h2>
 * <p>没有它，两只分数几乎相同的怪会让守卫每次重扫都换一个目标，表现成原地抽搐、
 * 谁也打不死。这一项是<b>迟滞</b>，不是偏好。</p>
 */
public final class ThreatEvaluator {

	private ThreatEvaluator() {
	}

	/** 正在攻击主人（主人身上的 attacker 就是它）。压倒性的一项。 */
	public static final double ATTACKING_OWNER_BONUS = 100.0;
	/** 已经锁定主人，但还没打到。 */
	public static final double TARGETING_OWNER_BONUS = 60.0;
	/** 离主人越近权重越高，满值。 */
	public static final double OWNER_PROXIMITY_WEIGHT = 40.0;
	/** 离守卫越近权重越高，满值。够得着的先打，省得跑来跑去。 */
	public static final double SELF_PROXIMITY_WEIGHT = 12.0;
	/** 每一档威胁的分值。 */
	public static final double TIER_WEIGHT = 10.0;
	/** 已经在打它——迟滞项。 */
	public static final double CURRENT_TARGET_BONUS = 15.0;

	/**
	 * 一个候选目标的全部输入。
	 *
	 * @param attackingOwner  主人刚刚被它打了
	 * @param targetingOwner  它锁定的是主人
	 * @param distanceToOwner 到主人的距离（格）
	 * @param distanceToGuard 到守卫的距离（格）
	 * @param tier            威胁分档 1..4
	 * @param currentTarget   守卫这一刻打的就是它
	 */
	public record Candidate(boolean attackingOwner, boolean targetingOwner,
			double distanceToOwner, double distanceToGuard, int tier,
			boolean currentTarget) { }

	/**
	 * 算一个候选目标的威胁分。分越高越该先打。
	 *
	 * @param radius 护卫半径，用来把距离归一化；&lt;= 0 时距离项不参与
	 */
	public static double score(Candidate candidate, double radius) {
		if (candidate == null) {
			return Double.NEGATIVE_INFINITY;
		}
		double score = 0.0;
		if (candidate.attackingOwner()) {
			score += ATTACKING_OWNER_BONUS;
		} else if (candidate.targetingOwner()) {
			score += TARGETING_OWNER_BONUS;
		}
		if (radius > 0) {
			score += OWNER_PROXIMITY_WEIGHT * nearness(candidate.distanceToOwner(), radius);
			score += SELF_PROXIMITY_WEIGHT * nearness(candidate.distanceToGuard(), radius);
		}
		score += TIER_WEIGHT * Math.max(0, candidate.tier());
		if (candidate.currentTarget()) {
			score += CURRENT_TARGET_BONUS;
		}
		return score;
	}

	/** 0（在半径边缘或更远）到 1（贴脸）。 */
	private static double nearness(double distance, double radius) {
		if (radius <= 0) {
			return 0.0;
		}
		return Math.max(0.0, 1.0 - Math.max(0.0, distance) / radius);
	}

	/**
	 * 追击上限：超过这个距离（离主人）就放弃，回去护航。
	 *
	 * <p>Lv.3 之前是一个写死的 1.5 倍；Lv.8 之后由玩家选的姿态说了算。</p>
	 */
	public static double chaseLimit(ProfessionConfig config, double radius,
			CombatStance stance) {
		return radius * config.chaseFactor(stance);
	}

	/**
	 * 主人现在算「危急」吗（Lv.7 主人应急 / Lv.10 守护协议的进入条件）。
	 *
	 * @param ownerHealthFraction 主人当前血量比例
	 * @param threatsOnOwner      正锁定主人的敌人数量
	 */
	public static boolean ownerInDanger(ProfessionConfig config,
			double ownerHealthFraction, int threatsOnOwner) {
		return ownerHealthFraction <= config.guardOwnerEmergencyHealthFraction
			|| threatsOnOwner >= config.guardOwnerEmergencyThreatCount;
	}
}
