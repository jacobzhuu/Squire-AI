package dev.squire.server.combat;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 长期护卫策略（方案 D1）。"保护我"创建的是一条 Policy，而不是最长 24000 tick 的
 * 一次性 Task：它保存在 AgentRecord 里，跨昼夜、区块卸载和服务器重启一直有效，
 * 直到玩家说"停止保护"或显式关闭。
 *
 * <p>Owner 在线且 Avatar 已物化时本地战斗运行时激活；离线时休眠但绝不删除。</p>
 */
public record GuardPolicy(UUID ownerId, UUID agentId, boolean enabled, int radius,
		Set<Rule> rules, long createdAt) {

	/** Policy 在 {@code AgentRecord.persistentPolicies} 中的键。 */
	public static final String KEY = "guard";

	public static final int MIN_RADIUS = 4;
	public static final int MAX_RADIUS = 32;
	public static final int DEFAULT_RADIUS = 16;

	/** 威胁识别规则；默认全开。 */
	public enum Rule {
		/** 半径内的敌对生物。 */
		HOSTILES,
		/** 正在攻击 owner 的中立生物。 */
		OWNER_ATTACKERS,
		/** 朝向 owner 的近程投射物来源。 */
		PROJECTILES
	}

	public static final Set<Rule> ALL_RULES = Set.of(Rule.HOSTILES,
		Rule.OWNER_ATTACKERS, Rule.PROJECTILES);

	public GuardPolicy {
		radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
		rules = rules == null || rules.isEmpty() ? ALL_RULES : Set.copyOf(rules);
	}

	public static GuardPolicy enabled(UUID ownerId, UUID agentId, int radius, long now) {
		return new GuardPolicy(ownerId, agentId, true, radius, ALL_RULES, now);
	}

	public GuardPolicy disable() {
		return new GuardPolicy(ownerId, agentId, false, radius, rules, createdAt);
	}

	public boolean covers(Rule rule) {
		return rules.contains(rule);
	}

	/**
	 * 序列化为 AgentRecord 的策略串：{@code guard:<enabled>:<radius>:<rules>:<createdAt>}。
	 */
	public String serialize() {
		StringBuilder ruleNames = new StringBuilder();
		for (Rule rule : Rule.values()) {
			if (rules.contains(rule)) {
				if (ruleNames.length() > 0) {
					ruleNames.append('+');
				}
				ruleNames.append(rule.name());
			}
		}
		return KEY + ":" + enabled + ":" + radius + ":" + ruleNames + ":" + createdAt;
	}

	/**
	 * 解析策略串。兼容工作包 B 期间写下的旧格式 {@code guard:<radius>}（视为已启用、
	 * 全规则），因为迁移必须保守而不是丢掉玩家已经下达的长期指令。
	 */
	public static GuardPolicy parse(String raw, UUID ownerId, UUID agentId) {
		if (raw == null || !raw.startsWith(KEY + ":")) {
			return null;
		}
		String[] parts = raw.split(":", -1);
		try {
			if (parts.length == 2) { // legacy "guard:16"
				return enabled(ownerId, agentId, Integer.parseInt(parts[1].trim()), 0L);
			}
			boolean on = Boolean.parseBoolean(parts[1].trim());
			int radius = Integer.parseInt(parts[2].trim());
			EnumSet<Rule> parsed = EnumSet.noneOf(Rule.class);
			if (parts.length > 3 && !parts[3].isBlank()) {
				for (String name : parts[3].split("\\+")) {
					try {
						parsed.add(Rule.valueOf(name.trim().toUpperCase(Locale.ROOT)));
					} catch (IllegalArgumentException ignored) {
						// 未知规则来自更新的版本：忽略这一条，不作废整条策略
					}
				}
			}
			long created = parts.length > 4 && !parts[4].isBlank()
				? Long.parseLong(parts[4].trim()) : 0L;
			return new GuardPolicy(ownerId, agentId, on, radius,
				parsed.isEmpty() ? ALL_RULES : parsed, created);
		} catch (NumberFormatException e) {
			return null; // 损坏的一行不应该拖垮整个档案
		}
	}
}
