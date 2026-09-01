package dev.squire.server.project;

import java.util.UUID;

/**
 * 工程里的一步。
 *
 * <p>阶段分两类，区别不在于难易，而在于<b>谁在推进它</b>：</p>
 * <ul>
 *   <li><b>任务型</b>（EXCAVATE / BUILD / LIGHT）——编译成真实任务交给调度器，
 *       伙伴在干活；</li>
 *   <li><b>阻塞型</b>（FULFIL_MATERIALS / HAUL / VERIFY）——等一个条件成立，
 *       其中 HAUL 等的是<b>玩家</b>把料交出来。</li>
 * </ul>
 *
 * <p>「等玩家」这件事正是 {@code GoalCoordinator} 表达不了、因而需要 Project 这一层的
 * 原因：它的 {@code tick()} 只认「剩余任务是否全部完成」，一个没有任务在跑、
 * 却也没有失败的阶段在那套语义里不存在。</p>
 */
public final class Stage {

	/** 阶段类型。顺序即是它们在工程里出现的顺序。 */
	public enum Kind {
		/** 用指令把材料兑现到<b>玩家</b>背包。 */
		FULFIL_MATERIALS,
		/** 等玩家把材料交给伙伴（丢在他脚边、或放进工地箱）。 */
		HAUL,
		/** 按蓝图挖出负空间。 */
		EXCAVATE,
		/** 按蓝图砌墙。 */
		BUILD,
		/** 用伙伴自己的火把点亮。 */
		LIGHT,
		/** 读真实世界复查：建筑立着、而且不黑。 */
		VERIFY
	}

	public enum State {
		PENDING, RUNNING,
		/** 卡住了，但不是失败——通常是在等玩家。{@link #blockedReason} 说清等什么。 */
		BLOCKED,
		DONE, FAILED, SKIPPED
	}

	/** Stable blocker codes for GUI actions and save migration. */
	public enum BlockerCode {
		NONE, MATERIALS_MISSING, TOOL_MISSING, OWNER_OFFLINE, AGENT_MISSING,
		WRONG_DIMENSION, SITE_UNSAFE, PROTECTED, NO_REACHABLE_TARGET,
		VERIFICATION_FAILED, TASK_FAILED, BLUEPRINT_MISSING
	}

	public final UUID stageId;
	public final Kind kind;

	private UUID assignedAgentId;
	private UUID goalId;
	private State state = State.PENDING;
	private String blockedReason;
	private BlockerCode blockerCode = BlockerCode.NONE;

	public Stage(UUID stageId, Kind kind) {
		this.stageId = stageId;
		this.kind = kind;
	}

	public State state() {
		return state;
	}

	public void setState(State next) {
		this.state = next == null ? State.PENDING : next;
		if (this.state != State.BLOCKED) {
			this.blockedReason = null;
			this.blockerCode = BlockerCode.NONE;
		}
	}

	/** 卡住并说明原因。原因是给玩家看的一句话，不是错误码。 */
	public void block(String reason) {
		block(BlockerCode.TASK_FAILED, reason);
	}

	public void block(BlockerCode code, String reason) {
		this.state = State.BLOCKED;
		this.blockedReason = reason;
		this.blockerCode = code == null ? BlockerCode.TASK_FAILED : code;
	}

	public String blockedReason() {
		return blockedReason;
	}

	public BlockerCode blockerCode() {
		return blockerCode;
	}

	public UUID assignedAgentId() {
		return assignedAgentId;
	}

	public void assignTo(UUID agentId) {
		this.assignedAgentId = agentId;
	}

	/** 任务型阶段对应的 GoalRecord；阻塞型阶段没有。 */
	public UUID goalId() {
		return goalId;
	}

	public void setGoalId(UUID goalId) {
		this.goalId = goalId;
	}

	public boolean terminal() {
		return state == State.DONE || state == State.FAILED || state == State.SKIPPED;
	}

	/** 面板和聊天用的中文名。 */
	public String displayName() {
		return switch (kind) {
			case FULFIL_MATERIALS -> "备料";
			case HAUL -> "交料";
			case EXCAVATE -> "掘进";
			case BUILD -> "施工";
			case LIGHT -> "点灯";
			case VERIFY -> "验收";
		};
	}

	public String nameKey() {
		return "squire.gui.stage." + kind.name().toLowerCase(java.util.Locale.ROOT);
	}
}
