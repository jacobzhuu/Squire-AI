package dev.squire.server.profession;

import java.util.List;
import java.util.Locale;

/**
 * Four basic activities before choosing a profession. BUILD is retained for
 * saved history; construction practice belongs after choosing Engineer.
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
	/** Optional post-profession practice; retained for save compatibility. */
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

	/** Total attainable XP before choosing a profession. */
	public static int totalXp() {
		int total = 0;
		for (TrainingMilestone milestone : required()) {
			total += milestone.xp;
		}
		return total;
	}

	public static List<TrainingMilestone> required() {
		return List.of(OPEN_PANEL, EQUIP, ORDER, KILL);
	}

	public static List<TrainingMilestone> all() {
		return List.of(values());
	}
}
