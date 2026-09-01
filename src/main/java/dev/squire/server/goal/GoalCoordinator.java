package dev.squire.server.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.runtime.PlayerNotifier;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCompiler;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.task.TaskState;
import dev.squire.server.task.executors.DeliverToOwnerExecutor;
import dev.squire.server.task.executors.GuardTaskExecutor;
import dev.squire.server.task.executors.RuntimeServices;

/**
 * Owns player-visible goals above individual executor tasks. It compiles a verified
 * DAG, correlates terminal task outcomes, persists progress, and sends completion or
 * structured failure notifications (including for offline owners).
 */
public final class GoalCoordinator {
	private static final int MAX_REPLANS = 2;
	private static final int MAX_REQUEST_COUNT = 4096;
	/** 最多保留这么多目标记录（含已结束的），够诊断用又不会无限堆积。 */
	private static final int MAX_RETAINED_GOALS = 8;

	public record StartResult(boolean success, UUID goalId, String message) { }

	private final TaskCompiler compiler;
	private final TaskScheduler scheduler;
	private final RuntimeServices services;
	private final PlayerNotifier notifier;
	private final GoalStateStore store;
	private final Map<UUID, GoalRecord> goals = new LinkedHashMap<>();

	public GoalCoordinator(TaskCompiler compiler, TaskScheduler scheduler,
			RuntimeServices services, PlayerNotifier notifier, GoalStateStore store) {
		this.compiler = compiler;
		this.scheduler = scheduler;
		this.services = services;
		this.notifier = notifier;
		this.store = store;
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	public StartResult acquireAndGive(UUID ownerId, UUID agentId, String itemId,
//			int count, GoalRecord.Kind kind) {
//		if (count <= 0 || count > MAX_REQUEST_COUNT) {
//			return new StartResult(false, null, "数量必须在 1–4096 之间。");
//		}
//		AvatarEntity avatar = services.avatar(agentId);
//		var player = services.requester(ownerId);
//		if (avatar == null || player == null) {
//			return new StartResult(false, null, "伙伴或玩家当前不在世界中。");
//		}
//		int playerTarget = DeliverToOwnerExecutor.playerCount(player,
//			new net.minecraft.util.Identifier(itemId)) + count;
//		GoalRecord goal = new GoalRecord(UUID.randomUUID(), ownerId, agentId, kind,
//			itemId, count, playerTarget, services.currentTick(), services.currentTick(),
//			GoalRecord.State.RUNNING, 0, null, List.of());
//		try {
//			goal.replaceTasks(compileAcquireAndDelivery(goal), services.currentTick());
//		} catch (RuntimeException e) {
//			return new StartResult(false, null, honestPlannerError(e));
//		}
//		goals.put(goal.goalId(), goal);
//		store.save(goals.values());
//		return new StartResult(true, goal.goalId(), "目标已创建：准备并交付 " + count
//			+ " 个 " + itemId + "（goal " + shortId(goal.goalId()) + "）。");
//	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	public StartResult gather(UUID ownerId, UUID agentId, String itemId, int count) {
//		if (count <= 0 || count > MAX_REQUEST_COUNT) {
//			return new StartResult(false, null, "数量必须在 1–4096 之间。");
//		}
//		AvatarEntity avatar = services.avatar(agentId);
//		if (avatar == null) {
//			return new StartResult(false, null, "没有已召唤的伙伴。");
//		}
//		int target = avatar.inventory().countOf(itemId) + count;
//		GoalRecord goal = new GoalRecord(UUID.randomUUID(), ownerId, agentId,
//			GoalRecord.Kind.GATHER, itemId, count, target, services.currentTick(),
//			services.currentTick(), GoalRecord.State.RUNNING, 0, null, List.of());
//		try {
//			List<Task> tasks = compiler.compileAcquire(agentId, ownerId, itemId, target);
//			goal.replaceTasks(tasks.stream().map(Task::taskId).toList(), services.currentTick());
//		} catch (RuntimeException e) {
//			return new StartResult(false, null, honestPlannerError(e));
//		}
//		goals.put(goal.goalId(), goal);
//		if (goal.remainingTaskIds().isEmpty()) {
//			complete(goal);
//		} else {
//			store.save(goals.values());
//		}
//		return new StartResult(true, goal.goalId(), "采集目标已创建：" + count + " 个 "
//			+ itemId + "（goal " + shortId(goal.goalId()) + "）。");
//	}

	public StartResult guard(UUID ownerId, UUID agentId, int radius, boolean persistent) {
		int boundedRadius = Math.max(4, Math.min(32, radius));
		Map<String, Object> params = Map.of(
			GuardTaskExecutor.PARAM_OWNER_ID, ownerId.toString(),
			GuardTaskExecutor.PARAM_RADIUS, String.valueOf(boundedRadius),
			GuardTaskExecutor.PARAM_DURATION, String.valueOf(24000));
		Task guard = new Task(agentId, ownerId, GuardTaskExecutor.TYPE,
			TaskPriority.P2_OWNER_URGENT, "persistent guard", null,
			GuardTaskExecutor.guardSurvived(), 24200, RetryPolicy.DEFAULT, true,
			persistent ? "persistent-guard" : "guard", params);
		scheduler.submit(guard, services.currentTick());
		GoalRecord goal = new GoalRecord(UUID.randomUUID(), ownerId, agentId,
			GoalRecord.Kind.GUARD, "guard", 1, 1, services.currentTick(),
			services.currentTick(), GoalRecord.State.RUNNING, 0, null,
			List.of(guard.taskId()));
		goals.put(goal.goalId(), goal);
		store.save(goals.values());
		return new StartResult(true, goal.goalId(), "已进入护卫状态（半径 "
			+ boundedRadius + "，本地战斗运行时接管）。");
	}

	// —— 已停用（方向性收缩：真身只保留跟随/护卫/救援，取物改走指令兑现）——
	// 保留代码而非删除，方便日后回退。当前无任何调用方。
//	private List<UUID> compileAcquireAndDelivery(GoalRecord goal) {
//		var player = services.requester(goal.ownerId());
//		int already = player == null ? 0 : DeliverToOwnerExecutor.playerCount(player,
//			new net.minecraft.util.Identifier(goal.subject()));
//		int remaining = Math.max(0, goal.targetFinalCount() - already);
//		if (remaining == 0) return List.of();
//		List<Task> acquire = compiler.compileAcquire(goal.agentId(), goal.ownerId(),
//			goal.subject(), remaining);
//		Map<String, Object> params = Map.of(
//			DeliverToOwnerExecutor.PARAM_ITEM_ID, goal.subject(),
//			DeliverToOwnerExecutor.PARAM_TARGET_PLAYER_COUNT, goal.targetFinalCount());
//		Task delivery = new Task(goal.agentId(), goal.ownerId(), DeliverToOwnerExecutor.TYPE,
//			TaskPriority.P3_USER_TASK, "deliver " + remaining + " " + goal.subject(),
//			null, DeliverToOwnerExecutor.playerHas(services, goal.ownerId(), goal.subject(),
//				goal.targetFinalCount()), 2400L + remaining * 20L, RetryPolicy.DEFAULT,
//			true, "goal-delivery", params);
//		if (!acquire.isEmpty()) delivery.dependsOn(acquire.get(acquire.size() - 1));
//		scheduler.submit(delivery, services.currentTick());
//		List<UUID> ids = new ArrayList<>(acquire.stream().map(Task::taskId).toList());
//		ids.add(delivery.taskId());
//		return ids;
//	}

	/**
	 * 把一组已经提交的任务当成一个目标盯着（第 3 期：工程阶段）。
	 *
	 * <p>复用的是这个类已经在做的事：任务→目标的关联、进度落盘、重启后
	 * “任务不见了”的处理。不复用的是它的播报——工程自己会在阶段边界说话，
	 * 两边都说就变成刷屏。</p>
	 */
	public StartResult track(UUID ownerId, UUID agentId, String subject,
			List<UUID> taskIds) {
		GoalRecord goal = new GoalRecord(UUID.randomUUID(), ownerId, agentId,
			GoalRecord.Kind.PROJECT_STAGE, subject, 1, 1, services.currentTick(),
			services.currentTick(), GoalRecord.State.RUNNING, 0, null, taskIds);
		goals.put(goal.goalId(), goal);
		store.save(goals.values());
		return new StartResult(true, goal.goalId(), subject);
	}

	public void tick(long tick) {
		boolean dirty = false;
		for (GoalRecord goal : List.copyOf(goals.values())) {
			if (goal.state() != GoalRecord.State.RUNNING) continue;
			Task failed = null;
			for (UUID taskId : goal.remainingTaskIds()) {
				Optional<Task> found = scheduler.findTask(taskId);
				if (found.isEmpty()) {
					fail(goal, "SERVER_RESTARTED: missing task " + taskId, tick);
					dirty = true;
					failed = null;
					break;
				}
				Task task = found.get();
				if (task.state() == TaskState.COMPLETED) {
					goal.taskCompleted(taskId, tick);
					dirty = true;
				} else if (task.state() == TaskState.FAILED
						|| task.state() == TaskState.CANCELLED) {
					failed = task;
					break;
				}
			}
			if (goal.state() != GoalRecord.State.RUNNING) continue;
			if (failed != null) {
				String error = failed.lastErrorCode().orElse(failed.state().name());
				// —— 已停用：ACQUIRE_AND_GIVE / CRAFT_AND_GIVE 这两类目标不再被创建
				// （取物改走指令兑现），本地重规划分支随之失去入口。只有升级前存盘的
				// 旧 goals.json 还可能带着这类记录，那种情况直接如实失败即可。
				// if (goal.replans() < MAX_REPLANS && locallyRetryable(error)
				// 		&& (goal.kind() == GoalRecord.Kind.ACQUIRE_AND_GIVE
				// 			|| goal.kind() == GoalRecord.Kind.CRAFT_AND_GIVE)) {
				// 	goal.beginReplan(error, tick);
				// 	try {
				// 		goal.replaceTasks(compileAcquireAndDelivery(goal), tick);
				// 		notifier.send(goal.ownerId(), "[Squire] 目标 " + shortId(goal.goalId())
				// 			+ " 遇到 " + error + "，正在进行第 " + goal.replans()
				// 			+ "/" + MAX_REPLANS + " 次本地重规划。");
				// 	} catch (RuntimeException e) {
				// 		fail(goal, error + "; REPLAN_FAILED: " + e.getMessage(), tick);
				// 	}
				// } else {
				fail(goal, error, tick);
				// }
				dirty = true;
			} else if (goal.remainingTaskIds().isEmpty()) {
				complete(goal);
				dirty = true;
			}
		}
		if (dirty) store.save(goals.values());
		pruneTerminal();
	}

	/** 结束的目标保留一小段供诊断查看，之后清掉——否则这个列表只增不减。 */
	private void pruneTerminal() {
		if (goals.size() <= MAX_RETAINED_GOALS) {
			return;
		}
		List<GoalRecord> terminal = goals.values().stream()
			.filter(g -> g.state() != GoalRecord.State.RUNNING)
			.sorted(java.util.Comparator.comparingLong(GoalRecord::updatedTick))
			.toList();
		int remove = Math.min(terminal.size(), goals.size() - MAX_RETAINED_GOALS);
		for (int i = 0; i < remove; i++) {
			goals.remove(terminal.get(i).goalId());
		}
		if (remove > 0) {
			store.save(goals.values());
		}
	}

	public int load() {
		goals.clear();
		for (GoalRecord goal : store.load()) goals.put(goal.goalId(), goal);
		return goals.size();
	}

	public void save() { store.save(goals.values()); }
	public Optional<GoalRecord> goal(UUID id) { return Optional.ofNullable(goals.get(id)); }
	public List<GoalRecord> all() { return List.copyOf(goals.values()); }

	private void complete(GoalRecord goal) {
		goal.finish(GoalRecord.State.COMPLETED, null, services.currentTick());
		if (quiet(goal)) {
			return;
		}
		notifier.send(goal.ownerId(), "[Squire] 目标 " + shortId(goal.goalId())
			+ " 已完成：" + goal.subject() + "。");
	}

	private void fail(GoalRecord goal, String error, long tick) {
		goal.finish(GoalRecord.State.FAILED, error, tick);
		if (quiet(goal)) {
			return;
		}
		notifier.send(goal.ownerId(), "[Squire] 目标 " + shortId(goal.goalId())
			+ " 失败：" + goal.subject() + " —— " + explain(error, goal.subject()));
	}

	/**
	 * 工程阶段由工程自己在阶段边界播报，这里不再说一遍。
	 * 两边都说的结果是玩家把聊天框关掉，连真正要紧的那一句一起错过。
	 */
	private static boolean quiet(GoalRecord goal) {
		return goal.kind() == GoalRecord.Kind.PROJECT_STAGE;
	}

	/**
	 * 把错误码翻译成玩家能据此行动的一句话。只报一个 {@code RESOURCE_EXHAUSTED}
	 * 等于什么都没说——玩家看到的是"它什么都没干"，而不是"附近没有这种资源"。
	 */
	private static String explain(String error, String subject) {
		String code = error == null ? "" : error;
		String hint = switch (code) {
			case "RESOURCE_EXHAUSTED" -> "附近找不到 " + subject
				+ "（已经换了几个方向找过）。带它去有这种资源的地方再说一次。";
			case "NO_REACHABLE_TARGET" -> "看得到 " + subject
				+ "，但走不过去（埋在石头里或隔着障碍）。它不会为了取矿而挖隧道。";
			case "NO_WORKSTATION" -> "缺少工作台或熔炉。合成 3x3 配方要工作台，"
				+ "熔炼要一座真熔炉，就近放一个再试。";
			case "NO_RECIPE" -> "不知道怎么得到 " + subject + "。";
			case "INSUFFICIENT_ITEM" -> "材料不够，中途用完了。";
			case "INSUFFICIENT_FUEL" -> "没有燃料可以烧。";
			case "INVENTORY_FULL" -> "背包满了。";
			case "PROTECTED_REGION" -> "这块地方受保护，不能动。";
			case "PRECONDITION_FAILED" -> "缺少能真正挖下来的工具。";
			case "TIMEOUT" -> "花的时间太长，已经安全停下。";
			case "PATH_NOT_FOUND", "AGENT_STUCK" -> "走不过去，路被挡住了。";
			case "OUT_OF_STAY_AREA" -> "那地方超出了他的待命范围。让他跟你走，或者到那儿再叫一次待命。";
			default -> "";
		};
		return hint.isEmpty() ? code : hint + "（" + code + "）";
	}

	private static boolean locallyRetryable(String error) {
		return "PATH_NOT_FOUND".equals(error) || "AGENT_STUCK".equals(error)
			|| "NO_REACHABLE_TARGET".equals(error);
	}

	private static String honestPlannerError(RuntimeException e) {
		return "无法创建目标：" + (e.getMessage() == null
			? e.getClass().getSimpleName() : e.getMessage());
	}

	private static String shortId(UUID id) { return id.toString().substring(0, 8); }
}
