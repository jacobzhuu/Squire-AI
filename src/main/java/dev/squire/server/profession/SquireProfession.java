package dev.squire.server.profession;

import java.util.Locale;

/**
 * 正式职业。第一版只有守卫和工程师两个（设计文档 §3）。
 *
 * <p>它<b>不是</b> {@link dev.squire.server.profile.Role} 的替代品。Role 决定
 * 「他能装哪些能力、有几个槽」，是一条里程碑式的横向系统；职业决定的是
 * 「他在自己的本行里已经学会了多少种<b>做法</b>」，是一条 1–10 级、要玩家交
 * 晋升材料才能往上走的纵向系统。两者互不覆盖：没选职业的随从今天会做的事，
 * 明天一件也不会少。</p>
 *
 * <h2>为什么只有两个</h2>
 * <p>设计文档明确把 Miner / Farmer / Hunter 推到以后。每多一个职业就要多一整套
 * 「等级真的解锁了什么行为」的实现，而清单和实现对不上的伤害，比没有这个职业大。</p>
 */
public enum SquireProfession {

	/** 解决世界里的<b>实体威胁</b>。成长方向是「越来越会打」，不是「越来越像 Boss」。 */
	GUARD("guard", "守卫", dev.squire.server.profile.Role.GUARDIAN),
	/** 解决世界里的<b>模板建筑与蓝图工程</b>。成长方向是「越来越会用蓝图系统」。 */
	ENGINEER("engineer", "工程师", dev.squire.server.profile.Role.BUILDER);

	/** 职业等级下界。刚选定职业就是 1 级，不存在 0 级。 */
	public static final int MIN_LEVEL = 1;
	/** 职业等级上界。 */
	public static final int MAX_LEVEL = 10;

	private final String id;
	private final String displayName;
	private final dev.squire.server.profile.Role kindredRole;

	SquireProfession(String id, String displayName,
			dev.squire.server.profile.Role kindredRole) {
		this.id = id;
		this.displayName = displayName;
		this.kindredRole = kindredRole;
	}

	public String id() {
		return id;
	}

	/** 聊天里用的中文名；GUI 用 {@link #nameKey()}，两者由 LangFilesTest 钉在一起。 */
	public String displayName() {
		return displayName;
	}

	/**
	 * 同源的旧职业。选职业时顺手把 Role 也定下来，玩家不必把同一件事说两遍。
	 *
	 * <p>反过来不成立：当了护卫不等于自动成为守卫职业——职业要玩家明确挑一次，
	 * 因为它后面会持续问玩家要晋升材料。</p>
	 */
	public dev.squire.server.profile.Role kindredRole() {
		return kindredRole;
	}

	public String nameKey() {
		return "squire.gui.profession." + id;
	}

	/** 认不出的 id 一律回 null——读档时一个坏字段不该把整份档案拖垮。 */
	public static SquireProfession byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (SquireProfession profession : values()) {
			if (profession.id.equals(needle)) {
				return profession;
			}
		}
		return null;
	}

	/** 把等级夹回 [1, 10]。读档和配置都会走这里。 */
	public static int clampLevel(int level) {
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
	}
}
