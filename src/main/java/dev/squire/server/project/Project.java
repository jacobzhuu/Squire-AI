package dev.squire.server.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.util.Identifier;

/**
 * 一个大目标：「帮我准备一个矿井前哨站」。
 *
 * <p>层次是 {@code Project → Stage → Goal → Task}。刻意<b>不另起一个平行调度器</b>：
 * 阶段编译出来的仍然是普通任务，交给同一个 {@code TaskScheduler}，因此照样会被
 * 玩家的指令抢占、照样有超时、照样跨重启恢复。Project 只多做一件事——
 * 记住「下一步是什么」，以及「现在卡在谁身上」。</p>
 *
 * <p><b>跨维度砍掉。</b>工地锁定单一维度：{@code controlledTeleport} 是仓库里最脆弱的
 * 一段，跨维度搬运加分派会让阶段状态机的复杂度指数上升，而收益接近零。</p>
 */
public final class Project {

	public enum State {
		RUNNING,
		/** 玩家按了暂停：不推进，但什么都不丢。 */
		PAUSED,
		DONE, FAILED, CANCELLED
	}

	public final UUID projectId;
	public final UUID ownerId;
	public final String name;
	public final String blueprintId;
	/** 这个工程要盖的那份蓝图摆放。工地位置、朝向、形状全从它来。 */
	public final UUID placementId;
	public final String dimensionId;
	public final long createdTick;
	private final List<Stage> stages;
	/** Reserved real items, split by their original owner so leftovers can be returned. */
	private final Map<Identifier, Integer> ownerSupply = new LinkedHashMap<>();
	private final Map<Identifier, Integer> agentSupply = new LinkedHashMap<>();
	/** False only for a migrated v2 project that still needs the player's confirmation. */
	private boolean supplyPrepared = true;

	private State state = State.RUNNING;

	public Project(UUID projectId, UUID ownerId, String name, String blueprintId,
			UUID placementId, String dimensionId, long createdTick, List<Stage> stages) {
		this.projectId = projectId;
		this.ownerId = ownerId;
		this.name = name;
		this.blueprintId = blueprintId;
		this.placementId = placementId;
		this.dimensionId = dimensionId;
		this.createdTick = createdTick;
		this.stages = new ArrayList<>(stages);
	}

	public List<Stage> stages() {
		return List.copyOf(stages);
	}

	public State state() {
		return state;
	}

	public void setState(State next) {
		this.state = next == null ? State.RUNNING : next;
	}

	public boolean active() {
		return state == State.RUNNING || state == State.PAUSED;
	}

	/** 第一个还没结束的阶段——也就是「现在在做什么」。 */
	public Optional<Stage> currentStage() {
		return stages.stream().filter(stage -> !stage.terminal()).findFirst();
	}

	public Optional<Stage> stage(UUID stageId) {
		return stages.stream().filter(s -> s.stageId.equals(stageId)).findFirst();
	}

	public int doneCount() {
		return (int) stages.stream().filter(s -> s.state() == Stage.State.DONE
			|| s.state() == Stage.State.SKIPPED).count();
	}

	/** 「3/6」这样的一行进度，名牌和面板都用它。 */
	public String progress() {
		return doneCount() + "/" + stages.size();
	}

	// ------------------------------------------------------------------ durable material escrow

	public synchronized boolean supplyPrepared() {
		return supplyPrepared;
	}

	public synchronized void setSupplyPrepared(boolean prepared) {
		this.supplyPrepared = prepared;
	}

	/** Add a completed, real-inventory transfer to this project's escrow. */
	public synchronized void reserve(Map<Identifier, Integer> fromOwner,
			Map<Identifier, Integer> fromAgent) {
		mergePositive(ownerSupply, fromOwner);
		mergePositive(agentSupply, fromAgent);
		supplyPrepared = true;
	}

	/** Store loader entry point; replaces rather than merges persisted balances. */
	public synchronized void restoreSupply(Map<Identifier, Integer> fromOwner,
			Map<Identifier, Integer> fromAgent, boolean prepared) {
		ownerSupply.clear();
		agentSupply.clear();
		mergePositive(ownerSupply, fromOwner);
		mergePositive(agentSupply, fromAgent);
		supplyPrepared = prepared;
	}

	public synchronized int reservedCount(Identifier itemId) {
		if (itemId == null) return 0;
		return ownerSupply.getOrDefault(itemId, 0)
			+ agentSupply.getOrDefault(itemId, 0);
	}

	public synchronized Map<Identifier, Integer> reservedMaterials() {
		Map<Identifier, Integer> out = new LinkedHashMap<>(agentSupply);
		for (var entry : ownerSupply.entrySet()) {
			out.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}
		return Map.copyOf(out);
	}

	public synchronized Map<Identifier, Integer> missingFrom(
			Map<Identifier, Integer> required) {
		Map<Identifier, Integer> out = new LinkedHashMap<>();
		if (required == null) return Map.of();
		for (var entry : required.entrySet()) {
			int missing = entry.getValue() - reservedCount(entry.getKey());
			if (missing > 0) out.put(entry.getKey(), missing);
		}
		return Map.copyOf(out);
	}

	/** Consume atomically from the escrow. Agent-origin items are spent first. */
	public synchronized boolean consume(Identifier itemId, int count) {
		if (itemId == null || count <= 0 || reservedCount(itemId) < count) {
			return false;
		}
		int left = take(agentSupply, itemId, count);
		left = take(ownerSupply, itemId, left);
		return left == 0;
	}

	public synchronized Map<Identifier, Integer> ownerSupply() {
		return Map.copyOf(ownerSupply);
	}

	public synchronized Map<Identifier, Integer> agentSupply() {
		return Map.copyOf(agentSupply);
	}

	/** Clears escrow after all balances have been handed back or deliberately reconciled. */
	public synchronized void clearSupply() {
		ownerSupply.clear();
		agentSupply.clear();
	}

	private static void mergePositive(Map<Identifier, Integer> target,
			Map<Identifier, Integer> values) {
		if (values == null) return;
		for (var entry : values.entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
				target.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}
	}

	private static int take(Map<Identifier, Integer> source, Identifier id, int wanted) {
		if (wanted <= 0) return 0;
		int have = source.getOrDefault(id, 0);
		int used = Math.min(have, wanted);
		if (used == have) source.remove(id);
		else source.put(id, have - used);
		return wanted - used;
	}
}
