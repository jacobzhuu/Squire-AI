package dev.squire.server.profession;

import java.util.Locale;

/**
 * 战斗姿态（设计文档 §9 Lv.8）。玩家对「他该多主动」的一次明确表态。
 *
 * <p>姿态只改<b>行为参数</b>——追多远、什么时候收手、要不要主动去拉怪——
 * 一个数值加成都不给。守卫的战斗上限永远由玩家给的装备决定。</p>
 *
 * <p><b>性格不得覆盖玩家明确选择的姿态。</b>这是设计文档写死的一条：特质可以在
 * 玩家<b>没有表态</b>时微调撤退线，一旦玩家自己挑了姿态，姿态说了算。</p>
 */
public enum CombatStance {

	/** 极短追击、优先贴着主人、更早回撤、尽量不主动拉怪。 */
	DEFENSIVE("defensive", "防守", 0.75, 0.10, false),
	/** 默认。行为与没有姿态系统时一致。 */
	BALANCED("balanced", "均衡", 1.50, 0.00, true),
	/** 更长追击、更主动、更晚回撤。 */
	AGGRESSIVE("aggressive", "进攻", 2.50, -0.08, true);

	private final String id;
	private final String displayName;
	private final double chaseFactor;
	private final double retreatDelta;
	private final boolean pullsAggro;

	CombatStance(String id, String displayName, double chaseFactor, double retreatDelta,
			boolean pullsAggro) {
		this.id = id;
		this.displayName = displayName;
		this.chaseFactor = chaseFactor;
		this.retreatDelta = retreatDelta;
		this.pullsAggro = pullsAggro;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	/** 追击上限 = 护卫半径 × 这个系数。均衡档的 1.5 等于姿态系统出现之前的行为。 */
	public double chaseFactor() {
		return chaseFactor;
	}

	/** 加到撤退血量比例上的偏移。防守档更早退，进攻档更晚退。 */
	public double retreatDelta() {
		return retreatDelta;
	}

	/**
	 * 会去主动接战「还没盯上任何人」的敌人吗。
	 *
	 * <p>防守档不会：它只处理已经构成威胁的目标（正在打主人、正在打自己、
	 * 或者已经近到不处理不行）。这就是「更少主动拉怪」真正咬人的地方。</p>
	 */
	public boolean pullsAggro() {
		return pullsAggro;
	}

	public String nameKey() {
		return "squire.gui.stance." + id;
	}

	public static CombatStance byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (CombatStance stance : values()) {
			if (stance.id.equals(needle)) {
				return stance;
			}
		}
		return null;
	}

	public static CombatStance byIdOrDefault(String raw) {
		CombatStance found = byId(raw);
		return found == null ? BALANCED : found;
	}
}
