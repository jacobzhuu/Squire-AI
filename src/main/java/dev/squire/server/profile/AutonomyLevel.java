package dev.squire.server.profile;

import java.util.Locale;

/**
 * 自主程度：他在你没开口的时候会做多少事。
 *
 * <p>刻意不是「智能等级」，而是<b>有效权限集合 + 行为阈值</b>的一个档位。
 * 细粒度的权限节点覆盖优先于档位——和 {@code PermissionManager} 现有的
 * 「revoked 永远赢」是同一套语义，不然玩家会遇到「我明明关掉了这一项，
 * 他还是做了」。</p>
 *
 * <p>{@link #AUTONOMOUS} 本期只留常量，设置时如实拒绝并说明原因：它是唯一
 * 真正危险的一档（自主消耗玩家材料、自主改动世界）。先把前三档跑稳，
 * 比先摆出一个不敢用的开关强。</p>
 */
public enum AutonomyLevel {

	/** 只做你明说的事。不主动施救、不主动接战、不主动整理。 */
	CONSERVATIVE("conservative", "保守", "只做你明说的事", true),
	/** 默认。会自卫、会在你受伤时来救你、会接着做没做完的任务。 */
	STANDARD("standard", "标准", "会自卫、会来救你", true),
	/** 还会主动做收尾工作：收工存料、回家换装、发现威胁主动报告。 */
	PROACTIVE("proactive", "积极", "主动收尾与预警；守卫Lv3可协同狩猎", true),
	/** 未开放：自主消耗材料、自主改动世界。 */
	AUTONOMOUS("autonomous", "完全自动", "未开放", false);

	private final String id;
	private final String displayName;
	private final String summary;
	private final boolean available;

	AutonomyLevel(String id, String displayName, String summary, boolean available) {
		this.id = id;
		this.displayName = displayName;
		this.summary = summary;
		this.available = available;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public String summary() {
		return summary;
	}

	public boolean available() {
		return available;
	}

	public String nameKey() {
		return "squire.gui.autonomy." + id;
	}

	/** 至少到这一档吗。用于「保守就不主动施救」这类判断。 */
	public boolean atLeast(AutonomyLevel other) {
		return ordinal() >= other.ordinal();
	}

	public static AutonomyLevel byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (AutonomyLevel level : values()) {
			if (level.id.equals(needle) || level.name().equals(raw.trim()
					.toUpperCase(Locale.ROOT))) {
				return level;
			}
		}
		return null;
	}

	/** 认不出就回默认档，绝不因为一个坏字段让随从变成完全自动。 */
	public static AutonomyLevel byIdOrDefault(String raw) {
		AutonomyLevel found = byId(raw);
		return found == null || !found.available() ? STANDARD : found;
	}
}
