package dev.squire.server.profession;

import java.util.List;

/**
 * 职业进度的记账与晋升规则（设计文档 §4、§5）。
 *
 * <h2>禁止自动升级</h2>
 * <p>经验条满了<b>只</b>意味着「可以晋升了」。真正升一级必须同时满足三件事：
 * 经验满、玩家交了晋升材料、玩家亲手点了晋升。所以 {@link #award} 永远不会
 * 顺手把等级加上去——它连 {@link ProfessionData#level} 都不碰。</p>
 *
 * <h2>溢出经验</h2>
 * <p>没有晋升材料的那段时间里，经验不该全部白打；但也不能让玩家攒够几级之后
 * 连升三级。所以溢出有上限：下一级需求的 25%。这两句话正好各挡住一种玩家会
 * 真的抱怨的情形。</p>
 *
 * <p>和 {@link GuardXp}、{@link EngineerXp} 一样，这里<b>不认识任何 Minecraft
 * 类型</b>：材料只是 id 字符串，扣物品由调用方负责。整套规则因此能被普通单测盖住。</p>
 */
public final class ProfessionService {

	private final ProfessionConfig config;

	public ProfessionService(ProfessionConfig config) {
		this.config = config == null ? ProfessionConfig.defaults() : config;
	}

	public ProfessionConfig config() {
		return config;
	}

	// ------------------------------------------------------------------ 记账

	/** 一次入账的结果，够调用方决定要不要发聊天提示。 */
	public record XpOutcome(int requested, int intoBar, int intoOverflow, int xp,
			int needed, int overflow, boolean wasReady, boolean nowReady,
			int wasted) {

		public int credited() {
			return intoBar + intoOverflow;
		}

		public boolean anythingHappened() {
			return credited() > 0;
		}

		/** 刚刚从「还差一点」变成「可以晋升了」——这是唯一值得打断玩家的时刻。 */
		public boolean justBecameReady() {
			return nowReady && !wasReady;
		}

		/** 有经验因为溢出上限被丢掉了吗。要如实说，否则玩家以为经验没进账。 */
		public boolean overflowed() {
			return wasted > 0;
		}
	}

	/**
	 * 记一笔职业经验。<b>不会</b>改变等级。
	 *
	 * @param amount 已经过完各自职业防刷规则之后的最终经验
	 */
	public XpOutcome award(ProfessionData data, int amount) {
		if (data == null || !data.hasProfession() || amount <= 0) {
			return new XpOutcome(Math.max(0, amount), 0, 0,
				data == null ? 0 : data.xp, data == null ? 0 : xpNeeded(data),
				data == null ? 0 : data.overflowXp, false, false, 0);
		}
		boolean wasReady = data.xpFull(config);
		if (data.isMaxLevel()) {
			// 满级之后经验没有去处。如实返回「一分没记」，而不是悄悄丢掉。
			return new XpOutcome(amount, 0, 0, data.xp, 0, data.overflowXp,
				true, true, amount);
		}
		int needed = xpNeeded(data);
		int room = Math.max(0, needed - data.xp);
		int intoBar = Math.min(amount, room);
		data.xp += intoBar;

		int rest = amount - intoBar;
		int intoOverflow = 0;
		int wasted = 0;
		if (rest > 0) {
			int cap = overflowCap(data);
			int space = Math.max(0, cap - data.overflowXp);
			intoOverflow = Math.min(rest, space);
			data.overflowXp += intoOverflow;
			wasted = rest - intoOverflow;
		}
		return new XpOutcome(amount, intoBar, intoOverflow, data.xp, needed,
			data.overflowXp, wasReady, data.xpFull(config), wasted);
	}

	/** 这一级的经验条上限。 */
	public int xpNeeded(ProfessionData data) {
		return data == null ? 0 : data.xpNeeded(config);
	}

	/**
	 * 溢出经验的上限：<b>下一级</b>需求的 {@code overflowXpRatio}。
	 *
	 * <p>用下一级而不是当前级，是因为溢出最终要转进下一级的经验条——按目的地算，
	 * 玩家看到的「保留了 200」和晋升后看到的「200 / 800」才是同一个数。</p>
	 */
	public int overflowCap(ProfessionData data) {
		if (data == null || data.isMaxLevel()) {
			return 0;
		}
		int nextRequirement = config.xpToNext(data.level + 1);
		return (int) Math.floor(nextRequirement * config.overflowXpRatio);
	}

	// ------------------------------------------------------------------ 转职

	/**
	 * 能不能转职成这个职业。{@code ok} 为假时 {@code reason} 就是给玩家的那句话。
	 *
	 * <p>面板按钮和 {@code /squire profession} 走的是<b>同一个</b>判断。两处各写一遍
	 * 的话，迟早出现「按钮灰着但命令能过」这种玩家完全无法理解的状态。</p>
	 */
	public record ChoiceCheck(boolean ok, String reason, int trainingXp,
			int trainingRequired) { }

	public ChoiceCheck checkChoice(ProfessionData data, SquireProfession wanted) {
		int have = data == null ? 0 : data.trainingXp();
		int need = config.trainingXpRequired;
		if (data == null) {
			return new ChoiceCheck(false, "读不到他的档案。", have, need);
		}
		if (wanted == null) {
			return new ChoiceCheck(false, "没有这个职业。", have, need);
		}
		if (data.hasProfession()) {
			return new ChoiceCheck(false, "他已经是"
				+ data.profession().displayName() + " Lv" + data.level
				+ " 了。先卸任才能改行。", have, need);
		}
		if (!data.trainingComplete(config)) {
			StringBuilder reason = new StringBuilder("训练还没做完（")
				.append(have).append(" / ").append(need).append("）。还差：");
			for (TrainingMilestone milestone : data.remainingTraining()) {
				reason.append("\n  · ").append(milestone.displayName());
			}
			return new ChoiceCheck(false, reason.toString(), have, need);
		}
		return new ChoiceCheck(true, "", have, need);
	}

	// ------------------------------------------------------------------ 晋升

	/** 晋升前的检查结论。{@code ok} 为假时 {@link #reason} 就是给玩家的那句话。 */
	public record PromotionCheck(boolean ok, String reason, int targetLevel,
			List<ProfessionConfig.ItemRequirement> cost) { }

	/**
	 * 材料<b>之外</b>的全部前置条件。物品够不够由调用方查背包/玩家身上，
	 * 因为那件事必须认识 Minecraft。
	 */
	public PromotionCheck checkPromotion(ProfessionData data) {
		if (data == null || !data.hasProfession()) {
			return new PromotionCheck(false, "还没有选职业。", 0, List.of());
		}
		if (data.isMaxLevel()) {
			return new PromotionCheck(false, "已经是 Lv."
				+ SquireProfession.MAX_LEVEL + "，到顶了。", 0, List.of());
		}
		int target = data.level + 1;
		List<ProfessionConfig.ItemRequirement> cost =
			config.promotionCost(data.profession(), target);
		if (!data.xpFull(config)) {
			return new PromotionCheck(false, "经验还不够：" + data.xp + " / "
				+ xpNeeded(data) + "，还差 " + (xpNeeded(data) - data.xp) + "。",
				target, cost);
		}
		return new PromotionCheck(true, "", target, cost);
	}

	/** 晋升的结果。 */
	public record PromotionOutcome(boolean promoted, int levelBefore, int levelAfter,
			int carriedOverflow, int newNeeded, List<ProfessionAbility> unlocked) { }

	/**
	 * 真的升一级。<b>调用方必须先扣掉材料</b>——这里只负责等级与经验条。
	 *
	 * <p>溢出经验原样转进新的经验条：它在入账时就已经按新一级的需求封过顶，
	 * 所以这里不会出现「一晋升又满了」的连升。</p>
	 */
	public PromotionOutcome promote(ProfessionData data) {
		PromotionCheck check = checkPromotion(data);
		if (!check.ok()) {
			int level = data == null ? SquireProfession.MIN_LEVEL : data.level;
			return new PromotionOutcome(false, level, level, 0, xpNeeded(data), List.of());
		}
		int before = data.level;
		data.level = SquireProfession.clampLevel(before + 1);
		int newNeeded = xpNeeded(data);
		int carried = data.isMaxLevel() ? 0
			: Math.min(data.overflowXp, Math.max(0, newNeeded));
		data.xp = carried;
		data.overflowXp = 0;
		return new PromotionOutcome(true, before, data.level, carried, newNeeded,
			ProfessionAbility.unlockedAt(data.profession(), data.level));
	}

	// ------------------------------------------------------------------ 展示

	/** 经验条的填充比例，0..1。满级永远是 1。 */
	public double barFraction(ProfessionData data) {
		if (data == null || !data.hasProfession() || data.isMaxLevel()) {
			return 1.0;
		}
		int needed = xpNeeded(data);
		return needed <= 0 ? 1.0 : Math.min(1.0, data.xp / (double) needed);
	}

	/** 十格文本进度条，聊天里用。 */
	public String barText(ProfessionData data) {
		int filled = (int) Math.round(barFraction(data) * 10);
		return "█".repeat(filled) + "░".repeat(10 - filled);
	}

	/** 把一份材料清单写成人话。 */
	public static String describeCost(List<ProfessionConfig.ItemRequirement> cost) {
		if (cost == null || cost.isEmpty()) {
			return "不需要材料";
		}
		StringBuilder text = new StringBuilder();
		for (ProfessionConfig.ItemRequirement item : cost) {
			if (text.length() > 0) {
				text.append("、");
			}
			text.append(item.itemId()).append(" ×").append(item.count());
		}
		return text.toString();
	}
}
