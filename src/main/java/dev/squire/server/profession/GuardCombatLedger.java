package dev.squire.server.profession;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「这只随从到底参没参与这场战斗」的台账，以及同类怪的短窗口计数。
 *
 * <h2>为什么需要它</h2>
 * <p>如果只认最后一击，玩家帮忙补刀的每一只怪，随从都白打；如果什么都认，
 * 随从站在刷怪塔旁边看玩家杀怪也能升级。设计文档给的判据是二选一：
 * <b>最后一击，或者至少打掉目标最大生命的 20%</b>。后半句要求逐目标累计伤害，
 * 也就是这个台账。</p>
 *
 * <h2>为什么不落盘</h2>
 * <p>这里的两份数据都是<b>短命</b>的：一场没打完的战斗、五分钟的击杀窗口。
 * 重启把它们清空，最坏的结果是玩家少拿一次半场战斗的经验、或者刷怪塔的衰减
 * 重置一次——而后者需要玩家每五分钟重启一次服务器，比老实打怪费劲得多。
 * 真正值得落盘的是 Boss 终身击杀数，那一份在 {@link ProfessionData} 里。</p>
 *
 * <p>全部方法都是 {@code synchronized}：伤害来自实体线程，清扫来自服务端 tick。</p>
 */
public final class GuardCombatLedger {

	/** 单只随从同时追踪的目标上限。超过就淘汰最早的——一场混战不该让台账无限长。 */
	public static final int MAX_TRACKED_TARGETS = 32;
	/** 目标这么久没再挨打就当这场交战结束了（20 tick = 1 秒，这里是 30 秒）。 */
	public static final long ENGAGEMENT_IDLE_TICKS = 600L;

	/** 随从对一个目标的累计参与。 */
	public record Engagement(UUID targetId, String entityId, double maxHealth,
			double damageDealt, boolean threatenedOwner, long lastTick) {

		Engagement plus(double amount, boolean nowThreatening, long tick) {
			return new Engagement(targetId, entityId, maxHealth, damageDealt + amount,
				threatenedOwner || nowThreatening, tick);
		}
	}

	private final Map<UUID, Map<UUID, Engagement>> engagements = new LinkedHashMap<>();
	private final Map<UUID, Map<String, Deque<Long>>> kills = new LinkedHashMap<>();

	// ------------------------------------------------------------------ 参与度

	/**
	 * 记一次随从对目标造成的伤害。
	 *
	 * @param threatenedOwner 这一刻这个目标正在盯着主人（保护主人加成的依据）
	 */
	public synchronized void noteDamage(UUID agentId, UUID targetId, String entityId,
			double maxHealth, double amount, boolean threatenedOwner, long tick) {
		if (agentId == null || targetId == null || amount <= 0) {
			return;
		}
		Map<UUID, Engagement> byTarget = engagements.computeIfAbsent(agentId,
			key -> new LinkedHashMap<>());
		Engagement existing = byTarget.get(targetId);
		byTarget.put(targetId, existing == null
			? new Engagement(targetId, entityId, maxHealth, amount, threatenedOwner, tick)
			: existing.plus(amount, threatenedOwner, tick));
		while (byTarget.size() > MAX_TRACKED_TARGETS) {
			var iterator = byTarget.keySet().iterator();
			iterator.next();
			iterator.remove();
		}
	}

	/** 只更新「正在威胁主人」这件事，不加伤害（远程互射时也要能记上）。 */
	public synchronized void noteThreatensOwner(UUID agentId, UUID targetId,
			String entityId, double maxHealth, long tick) {
		if (agentId == null || targetId == null) {
			return;
		}
		Map<UUID, Engagement> byTarget = engagements.computeIfAbsent(agentId,
			key -> new LinkedHashMap<>());
		Engagement existing = byTarget.get(targetId);
		byTarget.put(targetId, existing == null
			? new Engagement(targetId, entityId, maxHealth, 0.0, true, tick)
			: existing.plus(0.0, true, tick));
	}

	/** 取出并移除一条交战记录；结算时用。没有就返回 null。 */
	public synchronized Engagement take(UUID agentId, UUID targetId) {
		Map<UUID, Engagement> byTarget = engagements.get(agentId);
		return byTarget == null ? null : byTarget.remove(targetId);
	}

	/** 当前还开着的交战记录快照（调用方据此检查目标是不是已经死了）。 */
	public synchronized List<Engagement> open(UUID agentId) {
		Map<UUID, Engagement> byTarget = engagements.get(agentId);
		return byTarget == null ? List.of() : List.copyOf(byTarget.values());
	}

	/** 丢掉太久没动静的交战记录。 */
	public synchronized void pruneEngagements(UUID agentId, long now) {
		Map<UUID, Engagement> byTarget = engagements.get(agentId);
		if (byTarget == null) {
			return;
		}
		byTarget.values().removeIf(entry -> now - entry.lastTick() > ENGAGEMENT_IDLE_TICKS
			|| entry.lastTick() > now);
		if (byTarget.isEmpty()) {
			engagements.remove(agentId);
		}
	}

	// ------------------------------------------------------------------ 击杀窗口

	/**
	 * 记一次击杀，返回<b>窗口内这是第几只同种怪</b>（从 1 开始）。
	 *
	 * <p>返回值直接喂给 {@link GuardXp#repeatMultiplier}。</p>
	 */
	public synchronized int noteKill(UUID agentId, String entityId, long now,
			long windowTicks) {
		if (agentId == null || entityId == null) {
			return 1;
		}
		Deque<Long> stamps = kills.computeIfAbsent(agentId, key -> new LinkedHashMap<>())
			.computeIfAbsent(entityId, key -> new ArrayDeque<>());
		stamps.removeIf(stamp -> now - stamp > windowTicks || stamp > now);
		stamps.addLast(now);
		return stamps.size();
	}

	/** 窗口内已经杀过几只同种怪（不含正要结算的这一只）。 */
	public synchronized int killsInWindow(UUID agentId, String entityId, long now,
			long windowTicks) {
		Map<String, Deque<Long>> byType = kills.get(agentId);
		if (byType == null) {
			return 0;
		}
		Deque<Long> stamps = byType.get(entityId);
		if (stamps == null) {
			return 0;
		}
		stamps.removeIf(stamp -> now - stamp > windowTicks || stamp > now);
		return stamps.size();
	}

	// ------------------------------------------------------------------ 生命周期

	/** 随从被卸载/删除时清干净，别让内存里留着一堆 UUID。 */
	public synchronized void forget(UUID agentId) {
		engagements.remove(agentId);
		kills.remove(agentId);
	}

	public synchronized void clear() {
		engagements.clear();
		kills.clear();
	}

	/** 诊断/测试用。 */
	public synchronized int trackedAgents() {
		return engagements.size();
	}
}
