package dev.squire.server.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.goal.GoalCoordinator;
import dev.squire.server.goal.GoalRecord;
import dev.squire.server.runtime.PlayerNotifier;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.task.executors.BlueprintBuildExecutor;
import dev.squire.server.task.executors.ContainerExecutors;
import dev.squire.server.task.executors.ExcavateExecutor;
import dev.squire.server.task.executors.LightUpExecutor;
import dev.squire.server.task.executors.RuntimeServices;
import dev.squire.server.world.Torchlight;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * 推进工程。<b>一个阶段一个阶段地推</b>，而且只在伙伴闲着的时候推下一个。
 *
 * <h2>为什么不是第二个调度器</h2>
 * <p>阶段编译出来的是普通任务，交给同一个 {@code TaskScheduler}；这里唯一的调度规则是
 * 「{@code scheduler.current(agentId)} 为空时才编译下一个阶段」。这条规则天然和调度器的
 * one-RUNNING-per-agent 对齐，也意味着玩家随时下的任何指令都会顶掉工程的下一步，
 * 而不是和它抢。跨 agent 才并行——那是第 4 期的事。</p>
 *
 * <h2>为什么材料先给玩家</h2>
 * <p>备料阶段把材料兑现到<b>玩家</b>背包，然后交料阶段等玩家把它交给伙伴。多这一步是
 * 故意的：因果链必须是「玩家是资源来源 → 随从是劳动力」，否则刚解禁的搬运在整条链路里
 * 一次都用不上，工程就退化成一句「开始」加一段等待。只有把自主程度调到积极以上，
 * 才允许跳过这一步直接送到伙伴手里。</p>
 */
public final class ProjectCoordinator {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ProjectCoordinator.class);

	/** 每人同时一个工程。第 4 期开小队之后再谈并行。 */
	public static final int MAX_ACTIVE_PER_OWNER = 1;
	/** 推进节奏。工程是分钟级的事，没必要每 tick 看一遍。 */
	private static final int TICK_INTERVAL = 20;
	/** 交料时顺手替伙伴扫一次地上的掉落物的半径。 */
	private static final double PICKUP_RADIUS = 4.0;

	private final RuntimeServices services;
	private final TaskScheduler scheduler;
	private final GoalCoordinator goals;
	private final BlueprintManager blueprints;
	private final PlayerNotifier notifier;
	private final ProjectStore store;
	private final java.util.function.Function<UUID, AvatarEntity> avatarOfOwner;
	private final java.util.function.Function<UUID, dev.squire.server.profile.SquireProfile>
		profileOfAgent;
	private final dev.squire.server.world.ProtectionAdapter protection;

	private final Map<UUID, Project> projects = new ConcurrentHashMap<>();
	private boolean supplyDirty;

	public ProjectCoordinator(RuntimeServices services, TaskScheduler scheduler,
			GoalCoordinator goals, BlueprintManager blueprints, PlayerNotifier notifier,
			ProjectStore store,
			java.util.function.Function<UUID, AvatarEntity> avatarOfOwner,
			java.util.function.Function<UUID, dev.squire.server.profile.SquireProfile>
				profileOfAgent,
			dev.squire.server.world.ProtectionAdapter protection) {
		this.services = services;
		this.scheduler = scheduler;
		this.goals = goals;
		this.blueprints = blueprints;
		this.notifier = notifier;
		this.store = store;
		this.avatarOfOwner = avatarOfOwner;
		this.profileOfAgent = profileOfAgent;
		this.protection = protection;
	}

	// ------------------------------------------------------------------ 注册

	public Optional<Project> project(UUID projectId) {
		return Optional.ofNullable(projects.get(projectId));
	}

	public Optional<Project> activeOf(UUID ownerId) {
		return projects.values().stream()
			.filter(p -> p.ownerId.equals(ownerId) && p.active())
			.findFirst();
	}

	public List<Project> all() {
		return List.copyOf(projects.values());
	}

	public void put(Project project) {
		projects.put(project.projectId, project);
		save();
	}

	public void remove(UUID projectId) {
		if (projects.remove(projectId) != null) {
			save();
		}
	}

	public void save() {
		store.save(projects.values());
		supplyDirty = false;
	}

	/** Executor-facing atomic debit from a project's durable real-item escrow. */
	public synchronized boolean consumeMaterial(UUID projectId, Identifier itemId,
			int count) {
		Project project = projects.get(projectId);
		if (project == null || !project.active() || !project.consume(itemId, count)) {
			return false;
		}
		supplyDirty = true;
		return true;
	}

	public int reservedCount(UUID projectId, Identifier itemId) {
		Project project = projects.get(projectId);
		return project == null ? 0 : project.reservedCount(itemId);
	}

	/** 启动时恢复。结束的工程不留，否则档案只增不减。 */
	public int load() {
		projects.clear();
		for (Project project : store.load()) {
			if (project.active()) {
				projects.put(project.projectId, project);
			}
		}
		LOG.info("[project] {} active project(s) restored", projects.size());
		return projects.size();
	}

	// ------------------------------------------------------------------ 推进

	public void tick(long tick) {
		if (projects.isEmpty() || tick % TICK_INTERVAL != 0) {
			return;
		}
		boolean dirty = false;
		for (Project project : List.copyOf(projects.values())) {
			if (project.state() != Project.State.RUNNING) {
				continue;
			}
			try {
				dirty |= advance(project, tick);
			} catch (RuntimeException e) {
				LOG.warn("[project] {} failed this tick: {}", project.projectId,
					e.toString());
			}
		}
		if (dirty || supplyDirty) {
			save();
		}
	}

	/** @return true 表示状态变了、值得落盘 */
	private boolean advance(Project project, long tick) {
		Stage stage = project.currentStage().orElse(null);
		if (stage == null) {
			int returned = returnSupply(project);
			project.setState(Project.State.DONE);
			blueprints.placement(project.placementId).ifPresent(placement ->
				placement.setState(BlueprintPlacement.State.DONE));
			blueprints.save();
			AvatarEntity finishedAvatar = avatarOfOwner.apply(project.ownerId);
			if (finishedAvatar != null) finishedAvatar.setActivityDetail(null);
			notifier.send(project.ownerId, "[Squire] 「" + project.name + "」全部完成了。"
				+ (returned > 0 ? "剩余 " + returned + " 件工程物资已返还。" : ""));
			return true;
		}
		AvatarEntity avatar = avatarOfOwner.apply(project.ownerId);
		if (avatar == null) {
			return blockOnce(stage, Stage.BlockerCode.AGENT_MISSING,
				"没有可用的侍从。请右键召集铃让他回来；首次召唤方法可按 K 查看。");
		}
		if (!avatar.getWorld().getRegistryKey().getValue().toString()
				.equals(project.dimensionId)) {
			return blockOnce(stage, Stage.BlockerCode.WRONG_DIMENSION,
				"工地在 " + project.dimensionId + "，他不在那个维度。");
		}
		stage.assignTo(avatar.agentId());

		if (stage.state() == Stage.State.RUNNING) {
			return pollRunning(project, stage, tick);
		}
		// PENDING 或 BLOCKED：两者都是「再试一次看能不能往前走」。
		return startStage(project, stage, avatar, tick);
	}

	private boolean blockOnce(Stage stage, String reason) {
		return blockOnce(stage, Stage.BlockerCode.TASK_FAILED, reason);
	}

	private boolean blockOnce(Stage stage, Stage.BlockerCode code, String reason) {
		if (stage.state() == Stage.State.BLOCKED
				&& code == stage.blockerCode() && reason.equals(stage.blockedReason())) {
			return false; // 同一个理由不重复落盘
		}
		stage.block(code, reason);
		return true;
	}

	private boolean startStage(Project project, Stage stage, AvatarEntity avatar,
			long tick) {
		if (!(avatar.getWorld() instanceof ServerWorld world)) {
			return false;
		}
		BlueprintPlacement placement = blueprints.placement(project.placementId)
			.orElse(null);
		Blueprint.Resolved resolved = placement == null ? null
			: blueprints.resolve(placement).orElse(null);
		if (resolved == null) {
			return failStage(project, stage, Stage.BlockerCode.BLUEPRINT_MISSING,
				"工地上的蓝图没了（数据包换过？）");
		}
		return switch (stage.kind) {
			case FULFIL_MATERIALS -> fulfil(project, stage, avatar, world, resolved);
			case HAUL -> haul(project, stage, avatar, world, resolved);
			case EXCAVATE -> excavate(project, stage, avatar, world, resolved, tick);
			case BUILD -> build(project, stage, avatar, world, resolved, tick);
			case LIGHT -> light(project, stage, avatar, world, resolved, tick);
			case VERIFY -> verify(project, stage, avatar, world, resolved);
		};
	}

	// ------------------------------------------------------------------ 各阶段

	/** 备料核对开工时原子转入的工程物资池。 */
	private boolean fulfil(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved) {
		Map<Identifier, Integer> missing = project.missingFrom(
			BlueprintManager.requiredProjectMaterials(world, resolved));
		if (missing.isEmpty()) {
			return completeStage(project, stage, "真实材料已全部预留。");
		}
		StringBuilder reason = new StringBuilder(
			"工程物资池不足；请补齐后点击“补料并重试”：");
		for (Map.Entry<Identifier, Integer> entry : missing.entrySet()) {
			reason.append("\n  · 还差 ").append(entry.getValue()).append(" 个 ")
				.append(services.itemDisplayName(entry.getKey()));
		}
		return blockOnce(stage, Stage.BlockerCode.MATERIALS_MISSING,
			reason.toString());
	}

	private record Supply(net.minecraft.util.math.BlockPos pos, Identifier itemId,
		int count) { }

	/** Pick one missing item from the warehouse memory's explicitly bound container. */
	private Supply boundSupply(UUID ownerId, ServerWorld world,
			Map<Identifier, Integer> missing) {
		var memories = dev.squire.server.memory.LocationMemoryStore.get(services.server())
			.ofOwner(ownerId).stream()
			.filter(memory -> memory.type()
				== dev.squire.server.memory.LocationMemory.Type.WAREHOUSE)
			.filter(memory -> memory.containerPos() != null
				&& memory.containerPos().getDimension().equals(world.getRegistryKey()))
			.sorted(java.util.Comparator.comparingLong(
				dev.squire.server.memory.LocationMemory::lastVisitedAt).reversed())
			.toList();
		for (var memory : memories) {
			var pos = memory.containerPos().getPos();
			if (!(world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory inv)) {
				continue;
			}
			for (var entry : missing.entrySet()) {
				var item = net.minecraft.registry.Registries.ITEM.get(entry.getKey());
				int available = dev.squire.server.world.ContainerAccess.count(inv,
					stack -> stack.getItem() == item);
				if (available > 0) {
					return new Supply(pos.toImmutable(), entry.getKey(),
						Math.min(entry.getValue(), available));
				}
			}
		}
		return null;
	}

	/**
	 * 交料：等玩家把材料交给伙伴。
	 *
	 * <p>只有<b>保守</b>档才有这一步。它是工程里唯一一个等人的阶段，也正是
	 * {@code GoalCoordinator} 表达不了的那种阻塞点：没有任务在跑，也没有失败。
	 * 其余档位下材料已经在他手上了，这一步直接跳过。</p>
	 */
	private boolean haul(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved) {
		return skipStage(project, stage,
			"材料已在开工时转入工程物资池，无需重复交料。");
	}

	private boolean excavate(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved, long tick) {
		List<net.minecraft.util.math.BlockPos> toClear =
			BlueprintManager.pendingClear(world, resolved);
		if (toClear.isEmpty()) {
			return skipStage(project, stage, "不用挖。");
		}
		// 先问清楚他手上能不能挖动。提交一个注定卡到超时的任务，
		// 玩家看到的只会是「他突然不干活了」，而不是「他没镐」。
		var firstBlock = world.getBlockState(toClear.get(0));
		var toolSlot = avatar.items().bestToolSlot(firstBlock);
		if (!dev.squire.server.body.proxy.FakePlayerInteractionProxy.canHarvest(
				firstBlock, avatar.items().stackAt(toolSlot))) {
			return blockOnce(stage, Stage.BlockerCode.TOOL_MISSING,
				"他手上没有能挖动"
				+ firstBlock.getBlock().getName().getString()
				+ "的工具。给他一把镐（丢在他脚边就行）。");
		}
		if (busy(avatar)) {
			return false;
		}
		Map<String, Object> params = Map.of(ExcavateExecutor.PARAM_PLACEMENT_ID,
			project.placementId.toString());
		Task task = new Task(avatar.agentId(), project.ownerId, ExcavateExecutor.TYPE,
			TaskPriority.P3_USER_TASK, "挖出 " + toClear.size() + " 格负空间", null,
			ExcavateExecutor.blueprintCleared(services, project.placementId),
			600L + 40L * toClear.size(), RetryPolicy.DEFAULT, true, "c3", params);
		return runStage(project, stage, avatar, task,
			"开始掘进（" + toClear.size() + " 格）。");
	}

	private boolean build(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved, long tick) {
		List<Blueprint.Cell> toPlace = BlueprintManager.pendingPlacements(world, resolved);
		if (toPlace.isEmpty()) {
			return skipStage(project, stage, "已经盖好了。");
		}
		if (busy(avatar)) {
			return false;
		}
		Map<String, Object> params = Map.of(
			BlueprintBuildExecutor.PARAM_PLACEMENT_ID, project.placementId.toString(),
			BlueprintBuildExecutor.PARAM_PROJECT_ID, project.projectId.toString());
		Task task = new Task(avatar.agentId(), project.ownerId,
			BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
			"按蓝图盖 " + toPlace.size() + " 格", null,
			BlueprintBuildExecutor.blueprintBuilt(services, project.placementId),
			600L + 40L * toPlace.size(), RetryPolicy.DEFAULT, true, "c3", params);
		return runStage(project, stage, avatar, task,
			"开始施工（" + toPlace.size() + " 格）。");
	}

	private boolean light(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved, long tick) {
		if (Torchlight.brightEnough(world, Torchlight.candidates(resolved))) {
			return skipStage(project, stage, "已经够亮了。");
		}
		// 没火把不是失败，是又一个「等玩家」——和交料同一类。
		// 提交一个注定 INSUFFICIENT_ITEM 的任务，只会把整个工程拖停。
		if (project.reservedCount(Torchlight.TORCH) <= 0) {
			return blockOnce(stage, Stage.BlockerCode.MATERIALS_MISSING,
				"工程物资池里没有火把。请补齐后点击“补料并重试”，"
				+ "或者你自己把这儿点亮。");
		}
		if (busy(avatar)) {
			return false;
		}
		Map<String, Object> params = Map.of(
			LightUpExecutor.PARAM_PLACEMENT_ID, project.placementId.toString(),
			LightUpExecutor.PARAM_PROJECT_ID, project.projectId.toString());
		Task task = new Task(avatar.agentId(), project.ownerId, LightUpExecutor.TYPE,
			TaskPriority.P3_USER_TASK, "点灯", null,
			LightUpExecutor.litUp(services, project.placementId),
			400L, RetryPolicy.DEFAULT, true, "c3", params);
		return runStage(project, stage, avatar, task, "开始点灯。");
	}

	/** 验收读真实世界：建筑立着、而且不黑。 */
	private boolean verify(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved) {
		List<Blueprint.Cell> missing = BlueprintManager.pendingPlacements(world, resolved)
			.stream().filter(cell -> !cell.optional()).toList();
		if (!missing.isEmpty()) {
			return blockOnce(stage, Stage.BlockerCode.VERIFICATION_FAILED,
				"还差 " + missing.size() + " 格没盖上。");
		}
		if (!Torchlight.brightEnough(world, Torchlight.candidates(resolved))) {
			return blockOnce(stage, Stage.BlockerCode.VERIFICATION_FAILED,
				"里面还有暗处，会刷怪。");
		}
		return completeStage(project, stage, "验收通过。");
	}

	// ------------------------------------------------------------------ 阶段状态

	/** 伙伴手上还有活时不编译下一个阶段——这条就是全部的调度规则。 */
	private boolean busy(AvatarEntity avatar) {
		return scheduler.current(avatar.agentId()).isPresent();
	}

	private boolean runStage(Project project, Stage stage, AvatarEntity avatar,
			Task task, String announcement) {
		scheduler.submit(task, services.currentTick());
		var started = goals.track(project.ownerId, avatar.agentId(),
			stage.displayName(), List.of(task.taskId()));
		stage.setGoalId(started.goalId());
		stage.setState(Stage.State.RUNNING);
		announce(project, stage, announcement);
		return true;
	}

	private boolean pollRunning(Project project, Stage stage, long tick) {
		if (stage.goalId() == null) {
			stage.setState(Stage.State.PENDING);
			return true;
		}
		GoalRecord goal = goals.goal(stage.goalId()).orElse(null);
		if (goal == null) {
			// 目标记录被裁掉了（或重启后没恢复）：重来一次，阶段条件都是幂等的。
			stage.setState(Stage.State.PENDING);
			return true;
		}
		return switch (goal.state()) {
			case COMPLETED -> stage.kind == Stage.Kind.FULFIL_MATERIALS
				? retryStage(stage) : completeStage(project, stage, null);
			case FAILED, CANCELLED -> {
				Stage.BlockerCode code = blockerFor(goal.lastError());
				yield failStage(project, stage, code,
					playerFacingFailure(stage, code, goal.lastError()));
			}
			default -> false;
		};
	}

	private static String playerFacingFailure(Stage stage, Stage.BlockerCode code,
			String raw) {
		return switch (code) {
			case MATERIALS_MISSING -> stage.kind == Stage.Kind.LIGHT
				? "缺少火把。请把火把放进侍从背包，再点击继续。"
				: "材料不足。请在工程页面查看中文缺料清单并转交材料。";
			case TOOL_MISSING -> "缺少合适的工具，请把工具交给侍从后再继续。";
			case NO_REACHABLE_TARGET -> "找不到能到达的施工位置，请清理道路后再继续。";
			case PROTECTED -> "工地受到保护，侍从没有修改权限。";
			default -> raw == null || raw.isBlank() ? "任务执行失败。"
				: "任务执行失败（错误码：" + raw + "）。";
		};
	}

	private boolean retryStage(Stage stage) {
		stage.setGoalId(null);
		stage.setState(Stage.State.PENDING);
		return true;
	}

	private boolean completeStage(Project project, Stage stage, String detail) {
		stage.setState(Stage.State.DONE);
		announce(project, stage, "完成" + (detail == null ? "。" : "：" + detail));
		return true;
	}

	private boolean skipStage(Project project, Stage stage, String detail) {
		stage.setState(Stage.State.SKIPPED);
		announce(project, stage, "跳过：" + detail);
		return true;
	}

	/**
	 * 一步做不下去了。
	 *
	 * <p>工程本身进的是 <b>PAUSED 而不是 FAILED</b>：玩家把问题处理完（补上材料、
	 * 换个地方）之后应该能接着做，而不是发现整个工程连同进度一起从列表里消失了。
	 * 失败是一个可以回到的状态，不是一个终点。</p>
	 */
	private boolean failStage(Project project, Stage stage, String error) {
		return failStage(project, stage, Stage.BlockerCode.TASK_FAILED, error);
	}

	private boolean failStage(Project project, Stage stage, Stage.BlockerCode code,
			String error) {
		stage.block(code, error);
		project.setState(Project.State.PAUSED);
		notifier.send(project.ownerId, "[Squire] 「" + project.name + "」在"
			+ stage.displayName() + "这一步停了：" + error
			+ "\n处理完之后可在侍从面板的工程页继续。");
		return true;
	}

	private static Stage.BlockerCode blockerFor(String error) {
		String code = error == null ? "" : error.toUpperCase(java.util.Locale.ROOT);
		if (code.contains("INSUFFICIENT_ITEM")) return Stage.BlockerCode.MATERIALS_MISSING;
		if (code.contains("NO_REACHABLE_TARGET")) return Stage.BlockerCode.NO_REACHABLE_TARGET;
		if (code.contains("PROTECT") || code.contains("DENIED")) return Stage.BlockerCode.PROTECTED;
		if (code.contains("TOOL") || code.contains("HARVEST")) return Stage.BlockerCode.TOOL_MISSING;
		return Stage.BlockerCode.TASK_FAILED;
	}

	/**
	 * 只在<b>阶段边界</b>说话。绝不在 tick 里播报——一个每秒汇报一次进度的伙伴，
	 * 玩家两分钟后就会把聊天框关掉。
	 */
	private void announce(Project project, Stage stage, String what) {
		notifier.send(project.ownerId, "[Squire] 「" + project.name + "」"
			+ project.progress() + " " + stage.displayName() + what);
		// 名牌也只在阶段边界改一次——最廉价的进度展示，不需要新的渲染路径。
		AvatarEntity avatar = avatarOfOwner.apply(project.ownerId);
		if (avatar != null) {
			avatar.setActivityDetail(project.state() == Project.State.RUNNING
				? stage.displayName() + " " + project.progress() : null);
		}
	}

	// ------------------------------------------------------------------ 玩家控制

	public void pause(Project project) {
		project.currentStage().ifPresent(stage -> {
			if (stage.goalId() != null) {
				goals.goal(stage.goalId()).ifPresent(goal ->
					scheduler.cancelTasks(goal.remainingTaskIds(), "PROJECT_PAUSED"));
				stage.setGoalId(null);
			}
			if (stage.state() == Stage.State.RUNNING) {
				stage.setState(Stage.State.PENDING);
			}
		});
		project.setState(Project.State.PAUSED);
		AvatarEntity avatar = avatarOfOwner.apply(project.ownerId);
		if (avatar != null) avatar.setActivityDetail(null);
		save();
	}

	public void resume(Project project) {
		project.setState(Project.State.RUNNING);
		project.currentStage().ifPresent(stage -> {
			if (stage.state() == Stage.State.FAILED
					|| stage.state() == Stage.State.BLOCKED) {
				stage.setState(Stage.State.PENDING); // 处理完问题就该能接着走
			}
		});
		save();
	}

	public void cancel(Project project) {
		project.setState(Project.State.CANCELLED);
		AvatarEntity avatar = avatarOfOwner.apply(project.ownerId);
		if (avatar != null) {
			avatar.setActivityDetail(null);
		}
		project.currentStage().ifPresent(stage -> {
			if (stage.goalId() != null) {
				goals.goal(stage.goalId()).ifPresent(goal ->
					scheduler.cancelTasks(goal.remainingTaskIds(), "PROJECT_CANCELLED"));
				stage.setGoalId(null);
			}
		});
		int returned = returnSupply(project);
		if (returned > 0) {
			notifier.send(project.ownerId,
				"[Squire] 已返还 " + returned + " 件剩余工程物资。");
		}
		projects.remove(project.projectId);
		save();
	}

	/** Return every unspent escrow item, preferring the holder it originally came from. */
	private int returnSupply(Project project) {
		Map<Identifier, Integer> ownerItems = project.ownerSupply();
		Map<Identifier, Integer> agentItems = project.agentSupply();
		if (ownerItems.isEmpty() && agentItems.isEmpty()) return 0;
		ServerPlayerEntity owner = services.requester(project.ownerId);
		AvatarEntity avatar = avatarOfOwner.apply(project.ownerId);
		// advance() only completes with a live avatar, and cancel is owner-driven.  Keep
		// the escrow untouched if neither holder exists rather than voiding real items.
		if (owner == null && avatar == null) return 0;
		int returned = 0;
		for (var entry : agentItems.entrySet()) {
			returned += returnStacks(entry.getKey(), entry.getValue(), avatar, owner, false);
		}
		for (var entry : ownerItems.entrySet()) {
			returned += returnStacks(entry.getKey(), entry.getValue(), avatar, owner, true);
		}
		project.clearSupply();
		if (owner != null) {
			owner.getInventory().markDirty();
			owner.currentScreenHandler.sendContentUpdates();
		}
		return returned;
	}

	private static int returnStacks(Identifier id, int count, AvatarEntity avatar,
			ServerPlayerEntity owner, boolean preferOwner) {
		int max = Math.max(1, Registries.ITEM.get(id).getMaxCount());
		for (int left = count; left > 0;) {
			int chunk = Math.min(left, max);
			ItemStack stack = new ItemStack(Registries.ITEM.get(id), chunk);
			if (preferOwner && owner != null) owner.getInventory().insertStack(stack);
			if (!stack.isEmpty() && avatar != null) stack = avatar.items().insert(stack);
			if (!stack.isEmpty() && !preferOwner && owner != null) {
				owner.getInventory().insertStack(stack);
			}
			if (!stack.isEmpty()) {
				if (avatar != null) avatar.dropStack(stack);
				else if (owner != null) owner.dropItem(stack, false);
			}
			left -= chunk;
		}
		return count;
	}

	/** 一份工程模板：目前每份蓝图都编译成同一条六步流水线。 */
	public static List<Stage> compileStages() {
		List<Stage> stages = new ArrayList<>();
		for (Stage.Kind kind : Stage.Kind.values()) {
			stages.add(new Stage(UUID.randomUUID(), kind));
		}
		return List.copyOf(stages);
	}

	/** 诊断：每个阶段一行。 */
	public Map<String, String> describe(Project project) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Stage stage : project.stages()) {
			out.put(stage.displayName(), stage.state()
				+ (stage.blockedReason() == null ? "" : " — " + stage.blockedReason()));
		}
		return out;
	}
}
