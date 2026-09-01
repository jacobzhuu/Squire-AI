package dev.squire.server.profession;

import java.util.List;
import java.util.Locale;

/**
 * 转职之前的新手训练项目（Lv.0）。
 *
 * <h2>为什么要有这一段</h2>
 * <p>直接让玩家一召唤出来就选职业，等于让他在<b>什么都还没见过</b>的时候做一个
 * 会影响之后几小时的选择。这五项训练不是关卡，是一份「你已经和他一起做过这些事」
 * 的清单——走完它，玩家自然知道守卫和工程师各自意味着什么。</p>
 *
 * <h2>每一项都必须是玩家真的做过的事</h2>
 * <p>不设「等 5 分钟」这类纯计时项。每一条都挂在一个已经存在的服务端事实上：
 * 开过面板、给过装备、下过站位命令、一起打倒过一只怪、盖过一次蓝图。
 * 于是训练进度天然不可刷——它就是玩家的实际足迹。</p>
 */
public enum TrainingMilestone {

	/** 开一次面板。第一项刻意最容易，让进度条立刻动起来。 */
	OPEN_PANEL("open_panel", "打开一次他的面板", 20),
	/** 给他穿上任意一件装备。 */
	EQUIP("equip", "给他穿上一件装备", 20),
	/** 下达一次站位命令（跟随 / 待命 / 巡逻 / 回家）。 */
	ORDER("order", "给他下一次站位命令", 20),
	/** 一起打倒一只敌对生物。 */
	KILL("kill", "和他一起打倒一只敌对生物", 20),
	/** 完成一次蓝图施工。 */
	BUILD("build", "让他按蓝图盖成一次东西", 20);

	private final String id;
	private final String displayName;
	private final int xp;

	TrainingMilestone(String id, String displayName, int xp) {
		this.id = id;
		this.displayName = displayName;
		this.xp = xp;
	}

	public String id() {
		return id;
	}

	/** 聊天里用的中文名；GUI 用 {@link #nameKey()}。 */
	public String displayName() {
		return displayName;
	}

	public int xp() {
		return xp;
	}

	public String nameKey() {
		return "squire.gui.training." + id;
	}

	public static TrainingMilestone byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (TrainingMilestone milestone : values()) {
			if (milestone.id.equals(needle)) {
				return milestone;
			}
		}
		return null;
	}

	/** 五项全做完正好 100。转职门槛就是这个数。 */
	public static int totalXp() {
		int total = 0;
		for (TrainingMilestone milestone : values()) {
			total += milestone.xp;
		}
		return total;
	}

	public static List<TrainingMilestone> all() {
		return List.of(values());
	}
}
