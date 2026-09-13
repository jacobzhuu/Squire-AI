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
	private final java.util.function.Function<UUID, AvatarEntity> avatarOfAgent;
	private final java.util.function.Function<UUID, dev.squire.server.profile.SquireProfile>
		profileOfAgent;
	private final dev.squire.server.world.ProtectionAdapter protection;

	private final Map<UUID, Project> projects = new ConcurrentHashMap<>();
	private boolean supplyDirty;

	public ProjectCoordinator(RuntimeServices services, TaskScheduler scheduler,
			GoalCoordinator goals, BlueprintManager blueprints, PlayerNotifier notifier,
			ProjectStore store,
			java.util.function.Function<UUID, AvatarEntity> avatarOfAgent,
			java.util.function.Function<UUID, dev.squire.server.profile.SquireProfile>
				profileOfAgent,
			dev.squire.server.world.ProtectionAdapter protection) {
		this.services = services;
		this.scheduler = scheduler;
		this.goals = goals;
		this.blueprints = blueprints;
		this.notifier = notifier;
		this.store = store;
		this.avatarOfAgent = avatarOfAgent;
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
		Project retained = projects.get(projectId);
		if (retained != null && retained.state() == Project.State.FORCE_CANCELLED) return;
		if (retained != null && (!retained.reservedMaterials().isEmpty() || !retained.pendingMutation().isEmpty())) return;
		if (projects.remove(projectId) != null) {
			save();
		}
	}

	public void save() {
		projects.values().forEach(this::captureProgress);
		store.save(projects.values());
		supplyDirty = false;
	}
	private void captureProgress(Project project) {
		if (project.state() == Project.State.FORCE_CANCELLED) return;
		blueprints.placement(project.placementId).ifPresent(p -> {
			if (p.committed() && p.snapshot() != null) project.constructionProgress(dev.squire.server.blueprint.ConstructionSnapshotCodec.writeProgress(p.snapshot()));
		});
	}
	/** Persist intent before a world mutation; interrupted intents are quarantined on load. */
	public synchronized boolean beginMutation(UUID id, String operation) {
		Project project = projects.get(id);
		if (project == null || project.state() == Project.State.FORCE_CANCELLED || !project.pendingMutation().isEmpty()) return false;
		project.pendingMutation(operation); captureProgress(project);
		return store.save(projects.values());
	}
	public synchronized boolean completeMutation(UUID id) {
		Project project = projects.get(id); if (project == null || project.state() == Project.State.FORCE_CANCELLED) return false;
		String pending = project.pendingMutation(); project.pendingMutation(""); captureProgress(project);
		if (store.save(projects.values())) return true;
		project.pendingMutation(pending); project.setState(Project.State.PAUSED); return false;
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

	/** Read-only escrow snapshot used by the blueprint preview for this placement. */
	public Map<Identifier, Integer> reservedForPlacement(UUID placementId) {
		if (placementId == null) return Map.of();
		return projects.values().stream()
			.filter(Project::active)
			.filter(project -> placementId.equals(project.placementId))
			.findFirst().map(Project::reservedMaterials).orElse(Map.of());
	}

	/** 启动时恢复。结束的工程不留，否则档案只增不减。 */
	public int load() {
		projects.clear();
		for (Project project : store.load()) {
			if (project.state() == Project.State.FORCE_CANCELLED) {
				projects.put(project.projectId, project);
				blueprints.remove(project.placementId);
				continue; // Missing geometry must never turn an abandoned project back into PAUSED.
			}
			var placement = blueprints.placement(project.placementId).orElse(null);
			if (placement != null && project.constructionProgress() == null
					&& (placement.snapshot() == null || placement.snapshot().costPlan() == null)) placement.markLegacyFullPrice();
			if (project.constructionProgress() != null && (placement == null || placement.snapshot() == null)) {
				project.pendingMutation("RECOVERY_GEOMETRY_MISSING"); project.setState(Project.State.PAUSED);
			}
			if (project.constructionProgress() != null && placement != null && placement.snapshot() != null) {
				try {
					var geometry = dev.squire.server.blueprint.ConstructionSnapshotCodec.writeGeometry(placement.snapshot());
					dev.squire.server.blueprint.ConstructionSnapshotCodec.mergeProgress(geometry, project.constructionProgress());
					placement.snapshot(dev.squire.server.blueprint.ConstructionSnapshotCodec.read(geometry), true);
				} catch (RuntimeException bad) {
					project.pendingMutation("RECOVERY_CHECKPOINT_MISMATCH"); project.setState(Project.State.PAUSED);
				}
			}
			if (project.active() || !project.reservedMaterials().isEmpty()) {
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
			if (project.state() == Project.State.FORCE_CANCELLED) {
				stopAbandonedWork(project);
				continue;
			}
			if (project.active() && project.pendingMutation().isEmpty()
					&& dev.squire.server.blueprint.BuildingContentPolicy.current().retired(project.blueprintId)) {
				var retiring = blueprints.placement(project.placementId).map(BlueprintPlacement::snapshot).map(Blueprint.Resolved::access).orElse(null);
				if (retiring == null || !retiring.cancelRequested) {
					cancel(project); dirty = true; continue;
				}
			}
			if ((project.state() == Project.State.CANCELLED || project.state() == Project.State.DONE)
					&& !project.reservedMaterials().isEmpty()) {
				dirty |= returnSupply(project) > 0;
				if (project.reservedMaterials().isEmpty()) { projects.remove(project.projectId); dirty = true; }
				continue;
			}
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
		if (!project.pendingMutation().isEmpty()) { project.setState(Project.State.PAUSED); return true; }
		var oldPlacement = blueprints.placement(project.placementId).orElse(null);
		var access = blueprints.placement(project.placementId).flatMap(blueprints::resolve).map(Blueprint.Resolved::access).orElse(null);
		if (oldPlacement != null && !oldPlacement.committed() && access != null
				&& (!access.valid() || access.temporaryCount() > 0 || access.excavationCount() > 0)) {
			pause(project);
			project.currentStage().ifPresent(s -> s.block("旧工程新增临时通道需要确认，请点击继续查看范围。"));
			return true;
		}
		if (access != null && access.cancelRequested && access.placed.isEmpty()) {
			returnSupply(project);
			if (!project.pendingMutation().isEmpty()) return true;
			project.setState(Project.State.CANCELLED);
			blueprints.remove(project.placementId);
			if (project.reservedMaterials().isEmpty()) projects.remove(project.projectId);
			return true;
		}
		Stage stage = project.currentStage().orElse(null);
		if (stage == null) {
			int returned = returnSupply(project);
			if (!project.pendingMutation().isEmpty()) return true;
			project.setState(Project.State.DONE);
			blueprints.placement(project.placementId).ifPresent(placement ->
				placement.setState(BlueprintPlacement.State.DONE));
			blueprints.save();
			AvatarEntity finishedAvatar = avatarFor(project);
			if (finishedAvatar != null) finishedAvatar.setActivityDetail(null);
			notifier.send(project.ownerId, "[Squire] 「" + project.name + "」全部完成了。"
				+ (returned > 0 ? "剩余 " + returned + " 件工程物资已返还。" : ""));
			return true;
		}
		AvatarEntity avatar = avatarFor(project);
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
		if (dev.squire.server.blueprint.TerrainLeveling.parse(project.blueprintId).isPresent()) {
			var terrain = blueprints.placement(project.placementId).flatMap(blueprints::resolve).orElse(null);
			String blocked = avatar.profile() == null || avatar.profile().profession.profession()
				!= dev.squire.server.profession.SquireProfession.ENGINEER ? "负责平地的侍从已不是工程师" : "";
			if (terrain == null) blocked = "平地快照不可用";
			else if (blocked.isEmpty() && (stage.state() != Stage.State.RUNNING || tick % 20 == 0))
				blocked = dev.squire.server.blueprint.TerrainLeveling.liveBlocker((ServerWorld) avatar.getWorld(), terrain);
			if (blocked.isEmpty() && terrain != null && (stage.state() != Stage.State.RUNNING || tick % 20 == 0)) {
				var owner = services.requester(project.ownerId);
				if (owner != null && owner.getWorld() == avatar.getWorld())
					blocked = dev.squire.server.runtime.TerrainLevelingService.permissionIssue(owner, terrain);
			}
			if (!blocked.isEmpty()) {
				pause(project);
				return blockOnce(stage, Stage.BlockerCode.SITE_UNSAFE, blocked);
			}
		}

		if (stage.state() == Stage.State.RUNNING) {
			return pollRunning(project, stage, tick);
		}
		// PENDING 或 BLOCKED：两者都是「再试一次看能不能往前走」。
		return startStage(project, stage, avatar, tick);
	}

	/** 旧工程优先从蓝图摆放补齐绑定，之后始终按永久 agentId 找身体。 */
	private AvatarEntity avatarFor(Project project) {
		if (project.agentId() == null) {
			blueprints.placement(project.placementId)
				.ifPresent(site -> project.bindAgentIfMissing(site.agentId));
			if (project.agentId() == null) {
				project.currentStage().map(Stage::assignedAgentId)
					.ifPresent(project::bindAgentIfMissing);
			}
		}
		return project.agentId() == null ? null : avatarOfAgent.apply(project.agentId());
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
			case HAUL -> prepareSite(project, stage, avatar, world, resolved);
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
			"工程物资池不足；请把材料放进你或负责施工的侍从背包，再点击“存入本批材料”：");
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
	 * 场地准备。HAUL 是存档里的旧稳定枚举名；材料现在会在确认开工时直接预留，
	 * 因而这个槽位用于先封堵地下水、补齐一层承重基础，再让掘进清理主体空间。
	 */
	private boolean prepareSite(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved) {
		List<Blueprint.Cell> supports = BlueprintManager.automaticSiteSupports(world,
			resolved);
		if (supports.isEmpty()) {
			return skipStage(project, stage, "场地平整，无需补地基。");
		}
		if (busy(avatar)) {
			return false;
		}
		Map<String, Object> params = Map.of(
			BlueprintBuildExecutor.PARAM_PLACEMENT_ID, project.placementId.toString(),
			BlueprintBuildExecutor.PARAM_PROJECT_ID, project.projectId.toString(),
			BlueprintBuildExecutor.PARAM_SITE_PREPARATION, true);
		Task task = new Task(avatar.agentId(), project.ownerId,
			BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
			"自动处理场地（" + supports.size() + " 格）", null,
			BlueprintBuildExecutor.sitePrepared(services, project.placementId),
			400L + 40L * supports.size(), RetryPolicy.DEFAULT, true, "c3", params);
		return runStage(project, stage, avatar, task,
			"开始按地形采样材料自动补齐承重基础（" + supports.size() + " 格）。");
	}

	private boolean excavate(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved, long tick) {
		if (resolved.access() != null && resolved.access().excavationCount() > 0)
			return skipStage(project, stage, "开挖已纳入施工通路，将边挖边推进。");
		List<net.minecraft.util.math.BlockPos> toClear =
			BlueprintManager.pendingClear(world, resolved);
		if (toClear.isEmpty()) {
			return skipStage(project, stage, "不用挖。");
		}
		// 先问清楚他手上能不能挖动。提交一个注定卡到超时的任务，
		// 玩家看到的只会是「他突然不干活了」，而不是「他没镐」。
		for (var pos : toClear) {
			var state = world.getBlockState(pos);
			var slot = avatar.items().bestToolSlot(state);
			if (!dev.squire.server.body.proxy.FakePlayerInteractionProxy.canHarvest(state, avatar.items().stackAt(slot)))
				return blockOnce(stage, Stage.BlockerCode.TOOL_MISSING,
					dev.squire.server.body.proxy.FakePlayerInteractionProxy.missingHarvestToolMessage(state));
		}
		if (busy(avatar)) {
			return false;
		}
		Map<String, Object> params = Map.of(ExcavateExecutor.PARAM_PLACEMENT_ID,
			project.placementId.toString(), BlueprintBuildExecutor.PARAM_PROJECT_ID, project.projectId.toString());
		Task task = new Task(avatar.agentId(), project.ownerId, ExcavateExecutor.TYPE,
			TaskPriority.P3_USER_TASK, "挖出 " + toClear.size() + " 格负空间", null,
			ExcavateExecutor.blueprintCleared(services, project.placementId),
			600L + 40L * toClear.size(), RetryPolicy.DEFAULT, true, "c3", params);
		return runStage(project, stage, avatar, task,
			"开始掘进（" + toClear.size() + " 格）。");
	}

	private boolean build(Project project, Stage stage, AvatarEntity avatar,
			ServerWorld world, Blueprint.Resolved resolved, long tick) {
		List<Blueprint.Cell> toPlace = BlueprintManager.pendingProjectPlacements(world,
			resolved);
		if (toPlace.isEmpty() && (resolved.access() == null || resolved.access().work.isEmpty())) {
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
			BlueprintBuildExecutor.blueprintBuilt(services, project.placementId, true),
			600L + (resolved.access() == null || resolved.access().work.isEmpty() ? 40L : 80L) * toPlace.size()
				+ (resolved.access() == null ? 0L : 200L * resolved.access().temporaryCount() + 80L * resolved.access().excavationCount()),
			RetryPolicy.DEFAULT, true, "c3", params);
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
				"工程物资池里没有火把。请补齐后点击“存入本批材料”，"
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
		if (dev.squire.server.blueprint.TerrainLeveling.isTerrain(resolved)) {
			var fillPositions = resolved.toPlace().stream().map(Blueprint.Cell::pos).collect(java.util.stream.Collectors.toSet());
			if (!BlueprintManager.pendingPlacements(world, resolved).isEmpty()
					|| resolved.toClear().stream().filter(p -> !fillPositions.contains(p))
						.anyMatch(p -> !world.getBlockState(p).isAir())
					|| resolved.siteRequirements().stream().filter(q -> !q.kind().equals("terrain_air")).anyMatch(q -> !q.satisfied(world)))
				return blockOnce(stage, Stage.BlockerCode.VERIFICATION_FAILED, "地面或清理范围已变化，请检查后重试");
			return completeStage(project, stage, "平地验收通过");
		}
		List<Blueprint.Cell> missing = BlueprintManager
			.pendingProjectPlacements(world, resolved)
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
		if (raw != null && raw.startsWith("HARVEST_TOOL_MISSING:")) {
			var id = net.minecraft.util.Identifier.tryParse(raw.substring("HARVEST_TOOL_MISSING:".length()));
			if (id != null && net.minecraft.registry.Registries.BLOCK.containsId(id))
				return dev.squire.server.body.proxy.FakePlayerInteractionProxy.missingHarvestToolMessage(
					net.minecraft.registry.Registries.BLOCK.get(id).getDefaultState());
		}
		if ("SETTLED_BUILDING_CHANGED".equals(raw)) return "已结算方块或固定账单之外的地基发生变化。请恢复原状后继续，或取消工程；不会免费补造可拆除材料。";
		if ("CONSTRUCTION_NO_SAFE_ANCHOR".equals(raw)) return "施工区内暂时没有可安全安置工程师的位置（支撑、碰撞、区块或边界限制）。恢复安全站位后可继续。";
		if ("CONSTRUCTION_OCCUPIED".equals(raw)) return "施工位置有生物占用，请让开后继续；材料与进度保留。";
		if ("CONSTRUCTION_CLEANUP_DEPENDENCY".equals(raw)) return "临时设施仍支撑其他方块，无法安全回收；归属与待退款记录保留。";
		if (raw != null && raw.startsWith("CONSTRUCTION_")) return "施工结算存档异常，已暂停并保留材料、几何及恢复记录；请核对记录后处理，不能自动退款或重建。";
		if (raw != null && raw.startsWith("FLUID_")) {
			if (raw.startsWith("FLUID_SOURCE_EXHAUSTED")) return "指定水源已耗尽或不再是可装桶的水源。请恢复已授权水源或交付满水桶后继续；不会创造水源。";
			if (raw.startsWith("FLUID_SOURCE_UNLOADED")) return "指定水源区块未加载，请靠近水源后继续；不会为远程取水强制加载区块。";
			if (raw.startsWith("FLUID_CONTAINMENT_OPEN")) return "液体围护存在缺口，不能安全注水或注入岩浆。请修复围护后继续：" + raw.substring("FLUID_CONTAINMENT_OPEN".length());
			if (raw.startsWith("FLUID_FIRE_RISK")) return "岩浆附近存在可燃材料，已停止注液以避免火灾。请检查材料主题与周边环境。";
			if (raw.equals("FLUID_WATER_EVAPORATES")) return "当前维度的水会蒸发，不能建造这份含水蓝图；未扣除水桶。";
			if (raw.equals("FLUID_NO_SAFE_ROUTE")) return "找不到安全的取水／注液站位与道路。请清理通道后继续。";
			if (raw.equals("FLUID_DID_NOT_SETTLE")) return "原版液体流动未形成蓝图要求的布局，已暂停验收；不会强行写入流动方块。";
			return "液体施工条件发生变化，已暂停并保留桶与结算记录：" + raw;
		}
		if ("ACCESS_DIG_UNSAFE".equals(raw)) return "开挖通路的方块或立足点已变化，存在容器、流体、落沙或支撑风险；已暂停并保留进度。";
		return switch (code) {
			case MATERIALS_MISSING -> stage.kind == Stage.Kind.LIGHT
				? "缺少火把。请把火把放进侍从背包，再点击继续。"
				: "材料不足。请在工程页面查看中文缺料清单并转交材料。";
			case TOOL_MISSING -> "缺少合适的工具，请把工具交给侍从后再继续。";
			case NO_REACHABLE_TARGET -> raw != null && raw.startsWith("ACCESS_")
				? "施工通道或回收路线无法安全通行，请检查蓝色范围内的脚手架（旧工程可能为圆石）和障碍后继续；进度与材料保留。"
				: "找不到能到达的施工位置，请清理道路后再继续。";
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
		if (code.contains("NO_REACHABLE_TARGET") || code.contains("ACCESS_ROUTE") || code.contains("ACCESS_OUT_OF_REACH")
				|| code.contains("ACCESS_CLEANUP_NO_ROUTE") || code.contains("ACCESS_CLEANUP_UNSAFE")
				|| code.contains("ACCESS_UNSAFE_FALL")) return Stage.BlockerCode.NO_REACHABLE_TARGET;
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
		AvatarEntity avatar = avatarFor(project);
		if (avatar != null) {
			avatar.setActivityDetail(project.state() == Project.State.RUNNING
				? stage.displayName() + " " + project.progress() : null);
		}
	}

	// ------------------------------------------------------------------ 玩家控制

	public void pause(Project project) {
		if (project.state() == Project.State.FORCE_CANCELLED) return;
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
		AvatarEntity avatar = avatarFor(project);
		if (avatar != null) avatar.setActivityDetail(null);
		save();
	}

	public void resume(Project project) {
		if (project.state() == Project.State.FORCE_CANCELLED) return;
		if (!project.pendingMutation().isEmpty()) return;
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
		if (project.state() == Project.State.FORCE_CANCELLED) return;
		if (!project.pendingMutation().isEmpty()) {
			project.setState(Project.State.PAUSED);
			notifier.send(project.ownerId, "工程存在未确定的施工结算，已保留材料与进度；需核对恢复记录，不能自动退款或重建。");
			return;
		}
		var access = blueprints.placement(project.placementId).flatMap(blueprints::resolve).map(Blueprint.Resolved::access).orElse(null);
		if (access != null && !access.placed.isEmpty()) {
			pause(project); access.cancelRequested = true; access.cleanup = true;
			for (Stage stage : project.stages()) {
				stage.setState(stage.kind == Stage.Kind.BUILD ? Stage.State.PENDING : Stage.State.SKIPPED);
				stage.setGoalId(null);
			}
			project.setState(Project.State.RUNNING); blueprints.save(); save(); return;
		}
		project.setState(Project.State.CANCELLED);
		AvatarEntity avatar = avatarFor(project);
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
		if (!project.pendingMutation().isEmpty()) { save(); return; }
		if (returned > 0) {
			notifier.send(project.ownerId,
				"[Squire] 已返还 " + returned + " 件剩余工程物资。");
		}
		if (project.reservedMaterials().isEmpty()) projects.remove(project.projectId);
		save();
	}

	/** Persist the terminal decision before stopping tasks. Do not guess at disputed item balances. */
	public synchronized boolean forceCancel(Project project) {
		if (projects.get(project.projectId) != project) return false;
		if (project.state() == Project.State.FORCE_CANCELLED) { stopAbandonedWork(project); return true; }
		if (!project.active()) return false;
		var tasks = new java.util.LinkedHashSet<UUID>();
		for (Stage stage : project.stages()) {
			if (stage.goalId() != null) goals.goal(stage.goalId()).ifPresent(goal -> tasks.addAll(goal.remainingTaskIds()));
		}
		for (Task task : scheduler.liveTasks()) if (belongsTo(project, task)) tasks.add(task.taskId());
		project.rememberCancelledTasks(tasks);
		captureProgress(project);
		Project.State previous = project.state();
		project.setState(Project.State.FORCE_CANCELLED);
		if (!store.save(projects.values())) {
			project.setState(previous);
			return false;
		}
		stopAbandonedWork(project);
		AvatarEntity avatar = avatarFor(project);
		if (avatar != null) avatar.setActivityDetail(null);
		LOG.warn("[project] force-cancelled owner={} agent={} project={} pending={} ownerSupply={} agentSupply={}",
			project.ownerId, project.agentId(), project.projectId, project.pendingMutation(), project.ownerSupply(), project.agentSupply());
		return true;
	}

	private boolean belongsTo(Project project, Task task) {
		return project.ownerId.equals(task.requesterId()) && (project.forceCancelledTaskIds().contains(task.taskId())
			|| project.projectId.toString().equals(task.stringParam(BlueprintBuildExecutor.PARAM_PROJECT_ID))
			|| project.placementId.toString().equals(task.stringParam(BlueprintBuildExecutor.PARAM_PLACEMENT_ID)));
	}

	private void stopAbandonedWork(Project project) {
		var tasks = new java.util.LinkedHashSet<>(project.forceCancelledTaskIds());
		for (Task task : scheduler.liveTasks()) if (belongsTo(project, task)) tasks.add(task.taskId());
		scheduler.cancelTasks(tasks, "PROJECT_FORCE_CANCELLED");
		goals.cancelForTasks(tasks, "PROJECT_FORCE_CANCELLED");
		blueprints.remove(project.placementId);
	}

	/** Called after task/goal recovery as well, before recovered work can tick. */
	public void reconcileForceCancelled() {
		projects.values().stream().filter(p -> p.state() == Project.State.FORCE_CANCELLED).forEach(this::stopAbandonedWork);
	}

	/** Return every unspent escrow item, preferring the holder it originally came from. */
	private int returnSupply(Project project) {
		if (project.state() == Project.State.FORCE_CANCELLED) return 0;
		if (!project.pendingMutation().isEmpty()) return 0;
		Map<Identifier, Integer> ownerItems = project.ownerSupply();
		Map<Identifier, Integer> agentItems = project.agentSupply();
		if (ownerItems.isEmpty() && agentItems.isEmpty()) return 0;
		ServerPlayerEntity owner = services.requester(project.ownerId);
		AvatarEntity avatar = avatarFor(project);
		// advance() only completes with a live avatar, and cancel is owner-driven.  Keep
		// the escrow untouched if neither holder exists rather than voiding real items.
		if (owner == null && avatar == null) return 0;
		if (!beginMutation(project.projectId, "refund:" + ownerItems + ":" + agentItems)) { project.setState(Project.State.PAUSED); return 0; }
		int returned = 0;
		for (var entry : agentItems.entrySet()) {
			returned += returnStacks(entry.getKey(), entry.getValue(), avatar, owner, false);
		}
		for (var entry : ownerItems.entrySet()) {
			returned += returnStacks(entry.getKey(), entry.getValue(), avatar, owner, true);
		}
		project.clearSupply();
		if (!completeMutation(project.projectId)) return 0;
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

	public static List<Stage> compileTerrainStages() {
		return java.util.stream.Stream.of(Stage.Kind.FULFIL_MATERIALS, Stage.Kind.EXCAVATE,
			Stage.Kind.HAUL, Stage.Kind.VERIFY).map(k -> new Stage(UUID.randomUUID(), k)).toList();
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
