package dev.squire.server.task.executors;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.body.proxy.FakePlayerInteractionProxy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.task.TaskStateStore;
import dev.squire.server.world.UndoJournal;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 掘进：把蓝图里的<b>负空间</b>挖出来（掏空内部、门洞、竖井、地下室）。
 *
 * <p>这是「矿工」被改造成「掘进工」的落点。伙伴<b>不去野外挖矿</b>——他只在一份
 * 已经摆好的蓝图自己的足印里做减法。这条边界不是靠提示词维持的：下面每一格在动手
 * 之前都要通过 {@link #allowed} 校验，不属于任何摆放的格子一格都挖不动，
 * 提示词只是第二道防线。</p>
 *
 * <p>内脏全部复用 {@link GatherBlockExecutor} 的采集路径：按方块选真实工具、
 * 耐久写回同一个槽、掉落进背包、背包满了就掉在地上而不是静默销毁。区别只在于
 * 目标集合从「扫描搜来的」变成「蓝图算出来的」——反而更简单、更可验证。</p>
 *
 * <p>顺序自上而下，伙伴不会把自己埋了。挖不动的（基岩）和保护区里的格子都跳过而
 * 不是失败，否则一块基岩能让整条竖井永远卡在 VERIFYING。</p>
 */
public final class ExcavateExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ExcavateExecutor.class);

	public static final String TYPE = "build.excavate";
	public static final String PARAM_PLACEMENT_ID = "placementId";

	/** 每 tick 挖几格。比放置慢一点，挖掘本来就该看得出来在挖。 */
	private static final int BLOCKS_PER_TICK = 4;
	/** 「勤勉」特质的加成。 */
	private static final int DILIGENT_BONUS =
		dev.squire.server.profile.Trait.DILIGENT_EXCAVATE_BONUS;
	/** 「照明」一次最多插几根火把——把坑点亮就够了，不是要把它铺成灯带。 */
	private static final int MAX_TORCHES = 16;
	/** 每隔几格插一根。 */
	private static final int TORCH_SPACING = 5;
	/** 离目标格这么远就先走过去。 */
	private static final int WORK_RADIUS = 5;
	/** 走不到、又远到这个程度，就如实失败而不是隔空掏土。 */
	private static final int MAX_REMOTE_DIG = 24;

	public enum Phase { NAVIGATE, DIG }

	/** 重启后 {@code restoreCheckpoint} 先于 {@code start} 触发，用它把计数带进去。 */
	private record Restored(int dug, int skipped) { }

	public record Progress(UUID placementId, Phase phase, MoveHandle handle,
			List<BlockPos> cells, int cursor, UUID operationId,
			int dug, int skipped, boolean inventoryFull) {

		public String summary() {
			return "挖除 " + dug + " 格，跳过（挖不动或受保护）" + skipped;
		}
	}

	private final RuntimeServices services;
	private final java.util.Map<Task, Long> movingSince = new java.util.WeakHashMap<>();
	private final java.util.Set<Task> assisted = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private dev.squire.server.project.Project project(Task task) {
		Object id = task.parameters().get(BlueprintBuildExecutor.PARAM_PROJECT_ID);
		return id == null ? null : services.project(UUID.fromString(id.toString()));
	}

	public ExcavateExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public boolean requiresBody() {
		return true;
	}

	// ------------------------------------------------------------------ lifecycle

	@Override
	public void start(Task task) {
		int carriedDug = 0;
		int carriedSkipped = 0;
		if (task.executionState() instanceof Restored restored) {
			carriedDug = restored.dug();
			carriedSkipped = restored.skipped();
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("agent body unavailable");
		}
		BlueprintPlacement placement = placementOf(task);
		if (!world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
			task.setLastErrorCode("PRECONDITION_FAILED");
			throw new IllegalStateException("the site is in another dimension");
		}
		Blueprint.Resolved resolved = blueprints().resolve(placement)
			.orElseThrow(() -> new IllegalStateException(
				"blueprint " + placement.blueprintId + " is no longer registered"));
		List<BlockPos> cells = BlueprintManager.pendingClear(world, resolved);
		UndoJournal journal = journal();
		UUID operationId = journal == null ? null
			: journal.begin(task.taskId(), task.requesterId(),
				world.getRegistryKey().getValue().toString(),
				"build.excavate " + placement.blueprintId, services.currentTick());
		placement.setState(BlueprintPlacement.State.BUILDING);
		blueprints().save();
		task.setExecutionState(new Progress(placement.placementId, Phase.NAVIGATE, null,
			cells, 0, operationId, carriedDug, carriedSkipped, false));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		if (!(task.executionState() instanceof Progress progress)) {
			return StepOutcome.FAILED;
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()
				|| !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return finish(task, progress, StepOutcome.FAILED);
		}
		if (progress.cells().isEmpty() || progress.cursor() >= progress.cells().size()) {
			LOG.info("[excavate] {} finished: {}", task.taskId(), progress.summary());
			return finish(task, progress, StepOutcome.WORK_DONE);
		}
		BlockPos next = progress.cells().get(progress.cursor());
		if (world.isAir(next)) { task.setExecutionState(new Progress(progress.placementId(), Phase.NAVIGATE, null,
			progress.cells(), progress.cursor() + 1, progress.operationId(), progress.dug(), progress.skipped(), progress.inventoryFull())); return StepOutcome.CONTINUE; }
		if (project(task) != null && !project(task).pendingMutation().isEmpty()) {
			task.setLastErrorCode("CONSTRUCTION_RECOVERY_REQUIRED"); return finish(task, progress, StepOutcome.FAILED);
		}
		if (progress.phase() == Phase.NAVIGATE) {
			StepOutcome nav = navigate(task, avatar, progress, next);
			if (nav != null) {
				return nav;
			}
			progress = (Progress) task.executionState();
		}
		return dig(task, avatar, world, progress);
	}

	private StepOutcome navigate(Task task, AvatarEntity avatar, Progress progress,
			BlockPos next) {
		double distSq = avatar.squaredDistanceTo(next.getX() + 0.5, next.getY(),
			next.getZ() + 0.5);
		if (distSq <= (double) WORK_RADIUS * WORK_RADIUS) {
			task.setExecutionState(withPhase(progress, Phase.DIG, null));
			return null;
		}
		MoveHandle handle = progress.handle();
		if (project(task) != null && (assisted.contains(task) || handle != null && (handle.state() == MoveHandle.State.FAILED
				|| handle.state() == MoveHandle.State.CANCELLED || services.currentTick() - movingSince.getOrDefault(task, services.currentTick()) >= ConstructionRecovery.STALL_TICKS))) {
			assisted.add(task); avatar.stopMoving(); task.setExecutionState(withPhase(progress, Phase.DIG, null)); return null;
		}
		if (handle == null) {
			movingSince.put(task, services.currentTick());
			// 埋在地里的目标没有可站的邻居；先走到它正上方的井口去。
			BlockPos target = GatherBlockExecutor.approachPoint(avatar, next);
			handle = avatar.moveTo(new TargetPosition(
				avatar.getWorld().getRegistryKey().getValue().toString(),
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				EngineerMovement.options(avatar.profile()));
			task.setExecutionState(withPhase(progress, Phase.NAVIGATE, handle));
			return StepOutcome.CONTINUE;
		}
		if (handle.state() == MoveHandle.State.FAILED
				|| handle.state() == MoveHandle.State.CANCELLED
				|| handle.arrived()) {
			if (distSq > (double) MAX_REMOTE_DIG * MAX_REMOTE_DIG) {
				task.setLastErrorCode("NO_REACHABLE_TARGET");
				return finish(task, progress, StepOutcome.FAILED);
			}
			task.setExecutionState(withPhase(progress, Phase.DIG, null));
			return null;
		}
		return StepOutcome.CONTINUE;
	}

	/** 这一 tick 挖几格。「深掘」翻倍，「勤勉」再加一点；成本不变，只是节奏。 */
	static int blocksPerTick(dev.squire.server.profile.SquireProfile profile) {
		int budget = BLOCKS_PER_TICK;
        if (profile != null && profile.profession.profession() == dev.squire.server.profession.SquireProfession.ENGINEER)
            budget = (int) Math.ceil(budget * dev.squire.server.profession.EngineerProgression.current().efficiency(profile.profession.level));
		if (profile != null
				&& profile.can(dev.squire.server.profile.Ability.EXCAVATE_DEEP)) {
			budget *= 2;
		}
		if (profile != null
				&& profile.hasTrait(dev.squire.server.profile.Trait.DILIGENT)) {
			budget += DILIGENT_BONUS;
		}
		return budget;
	}

	private StepOutcome dig(Task task, AvatarEntity avatar, ServerWorld world,
			Progress progress) {
		AvatarInventory items = avatar.items();
		Set<BlockPos> footprint = footprintOf(progress.placementId());
		int perTick = blocksPerTick(services.profile(task.agentId()));
		int cursor = progress.cursor();
		int dug = progress.dug();
		int skipped = progress.skipped();

		for (int i = 0; i < perTick && cursor < progress.cells().size(); i++) {
			BlockPos pos = progress.cells().get(cursor);
			if (!allowed(footprint, pos)) {
				// 服务端硬拦：不属于这份摆放的负空间，一格都不许动。
				LOG.warn("[excavate] {} refused a cell outside the footprint: {}",
					task.taskId(), pos);
				skipped++;
				cursor++;
				continue;
			}
			BlockState state = world.getBlockState(pos);
			var terrainPlacement = services.blueprints().placement(progress.placementId()).orElse(null);
			if (terrainPlacement != null && dev.squire.server.blueprint.TerrainLeveling.parse(terrainPlacement.blueprintId).isPresent()
					&& (!dev.squire.server.blueprint.TerrainLeveling.obstacle(world, pos).isEmpty()
					|| !services.protection().canBreak(world, pos, avatar.ownerId()).allowed())) {
				task.setLastErrorCode("TERRAIN_SITE_UNSAFE");
				return finish(task, progress, StepOutcome.FAILED);
			}
			if (state.isAir()) {
				cursor++;
				continue;
			}
			var project = project(task);
			if (project != null && assisted.contains(task)) {
				var resolved = blueprints().resolve(placementOf(task)).orElseThrow();
				var changes = java.util.Map.of(pos, Blocks.AIR.getDefaultState());
				if (!dev.squire.server.blueprint.ConstructionAccessPlan.diggable(world, pos)
						|| ConstructionRecovery.occupied(world, avatar, changes)) {
					task.setLastErrorCode("ACCESS_DIG_UNSAFE"); return finish(task, progress, StepOutcome.FAILED);
				}
				if (ConstructionRecovery.prepare(avatar, ConstructionRecovery.bounds(resolved, avatar), pos, changes) == null) {
					task.setLastErrorCode("CONSTRUCTION_NO_SAFE_ANCHOR"); return finish(task, progress, StepOutcome.FAILED);
				}
				avatar.setActivityDetail("辅助清场");
			}
			if (state.getHardness(world, pos) < 0) {
				skipped++;
				cursor++;
				continue; // 基岩之类挖不动的，跳过而不是让整条竖井失败
			}
			var decision = services.protection().canBreak(world, pos, avatar.ownerId());
			if (!decision.allowed()) {
				if (project != null) { task.setLastErrorCode("PROTECTED"); return finish(task, progress, StepOutcome.FAILED); }
				skipped++;
				cursor++;
				continue;
			}
			// World.removeBlock deliberately restores a fluid block from its own
			// FluidState.  Treating water/lava like an ordinary mined block therefore
			// reports success while leaving the source in place, and verification retries
			// forever.  Drain managed fluids explicitly; they have no drops or tool cost.
			if (!state.getFluidState().isEmpty()) {
				if (project != null && !services.beginProjectMutation(project.projectId, "clear-fluid:" + pos.asLong())) {
					task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED);
				}
				recordUndo(progress.operationId(), task.taskId(), world, pos, state);
				world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
				if (project != null && !services.completeProjectMutation(project.projectId)) {
					task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED);
				}
				dug++;
				cursor++;
				continue;
			}
			AvatarInventory.SlotRef toolSlot = items.bestToolSlot(state);
			ItemStack tool = items.stackAt(toolSlot); // 真实 stack 引用，不是副本
			if (!FakePlayerInteractionProxy.canHarvest(state, tool)) {
				// 没有能真正挖动它的工具。以前这里把每一格都当「跳过」，
				// 于是一个没带镐的伙伴会“顺利”跑完整个挖除任务、一格都没动，
				// 然后卡在验证里直到超时——玩家看到的就是「他突然不干活了」。
				task.setLastErrorCode("HARVEST_TOOL_MISSING:" + net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()));
				avatar.setActivityDetail(FakePlayerInteractionProxy.missingHarvestToolMessage(state));
				LOG.info("[excavate] {} has no tool that can break {}", task.taskId(),
					state.getBlock());
				return finish(task, new Progress(progress.placementId(), Phase.DIG, null,
					progress.cells(), cursor, progress.operationId(), dug, skipped,
					progress.inventoryFull()), StepOutcome.FAILED);
			}
			recordUndo(progress.operationId(), task.taskId(), world, pos, state);
			if (project != null && !services.beginProjectMutation(project.projectId, "clear:" + pos.asLong())) {
				task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED);
			}
			FakePlayerInteractionProxy.BreakResult result =
				FakePlayerInteractionProxy.breakAndCollect(world, pos, tool, used -> { });
			items.writeBackTool(toolSlot, tool);
			if (!result.broken()) {
				if (project != null) services.completeProjectMutation(project.projectId);
				skipped++;
				cursor++;
				continue;
			}
			boolean full = false;
			for (ItemStack drop : result.drops()) {
				ItemStack remainder = items.insert(drop);
				if (!remainder.isEmpty()) {
					// 背包满：留在世界里，绝不静默销毁（方案 C2）
					Block.dropStack(world, pos, remainder);
					full = true;
				}
			}
			if (project != null && !services.completeProjectMutation(project.projectId)) {
				task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED);
			}
			dug++;
			cursor++;
			if (full) {
				// 背包满不是失败——挖出来的土石本来大多是废料，掉在地上就行。
				// 但要记下来，完工汇总里如实告诉玩家。
				Progress next = new Progress(progress.placementId(), Phase.DIG, null,
					progress.cells(), cursor, progress.operationId(), dug, skipped, true);
				task.setExecutionState(next);
				return StepOutcome.CONTINUE;
			}
		}

		Progress next = new Progress(progress.placementId(), Phase.DIG, null,
			progress.cells(), cursor, progress.operationId(), dug, skipped,
			progress.inventoryFull());
		if (cursor >= progress.cells().size()) {
			LOG.info("[excavate] {} finished: {}", task.taskId(), next.summary());
			return finish(task, next, StepOutcome.WORK_DONE);
		}
		task.setExecutionState(withPhase(next, Phase.NAVIGATE, null));
		return StepOutcome.CONTINUE;
	}

	private static Progress withPhase(Progress p, Phase phase, MoveHandle handle) {
		return new Progress(p.placementId(), phase, handle, p.cells(), p.cursor(),
			p.operationId(), p.dug(), p.skipped(), p.inventoryFull());
	}

	private StepOutcome finish(Task task, Progress progress, StepOutcome outcome) {
		task.setWorkReport(new Task.WorkReport(
			dev.squire.server.profile.Track.EXCAVATE.id(), progress.dug(),
			progress.operationId()));
		if (outcome == StepOutcome.WORK_DONE) {
			lightUp(task, progress);
		}
		task.setExecutionState(progress);
		UndoJournal journal = journal();
		if (journal != null && progress.operationId() != null) {
			journal.close(progress.operationId());
		}
		BlueprintManager manager = services.blueprints();
		if (manager != null) {
			manager.placement(progress.placementId()).ifPresent(placement -> {
				if (placement.state() == BlueprintPlacement.State.BUILDING) {
					placement.setState(BlueprintPlacement.State.GHOST);
				}
			});
			manager.save();
		}
		return outcome;
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof Progress progress) {
			if (progress.handle() != null) {
				progress.handle().cancel();
			}
			finish(task, progress, StepOutcome.FAILED);
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar != null) {
			avatar.stopMoving();
		}
	}

	// ------------------------------------------------------------------ 检查点

	@Override
	public String checkpoint(Task task) {
		if (!(task.executionState() instanceof Progress progress)) {
			return null;
		}
		return "{\"dug\":" + progress.dug() + ",\"skipped\":" + progress.skipped() + "}";
	}

	@Override
	public void restoreCheckpoint(Task task, String checkpointJson) {
		try {
			var o = com.google.gson.JsonParser.parseString(checkpointJson)
				.getAsJsonObject();
			task.setExecutionState(new Restored(
				o.has("dug") ? o.get("dug").getAsInt() : 0,
				o.has("skipped") ? o.get("skipped").getAsInt() : 0));
		} catch (RuntimeException bad) {
			task.setExecutionState(new Restored(0, 0));
		}
	}

	@Override
	public TaskCondition recoverySuccessCondition(TaskStateStore.Snapshot snapshot) {
		Object raw = snapshot.parameters().get(PARAM_PLACEMENT_ID);
		if (!(raw instanceof String id)) {
			return null;
		}
		try {
			return blueprintCleared(services, UUID.fromString(id));
		} catch (IllegalArgumentException notAUuid) {
			return null;
		}
	}

	// ------------------------------------------------------------------ 成功条件

	/**
	 * 成功条件读的是<b>真实世界方块</b>：负空间以及被障碍占住的施工格都必须
	 * 已经清空；要么根本挖不动（基岩），要么保护区不让动。
	 *
	 * <p>必须复用 {@link BlueprintManager#pendingClear} 的完整语义。只验
	 * {@code toClear} 会漏掉落回地基格的沙/沙砾，让掘进提前完成，随后施工把这些
	 * 必需格静默跳过并卡在 VERIFYING。</p>
	 */
	public static TaskCondition blueprintCleared(RuntimeServices services,
			UUID placementId) {
		return TaskCondition.of(ctx -> {
			BlueprintManager manager = services.blueprints();
			if (manager == null) {
				return false;
			}
			BlueprintPlacement placement = manager.placement(placementId).orElse(null);
			AvatarEntity avatar = services.avatar(ctx.agentId());
			if (placement == null || avatar == null
					|| !(avatar.getWorld() instanceof ServerWorld world)
					|| !world.getRegistryKey().getValue().toString()
						.equals(placement.dimensionId)) {
				return false;
			}
			Blueprint.Resolved resolved = manager.resolve(placement).orElse(null);
			if (resolved == null) {
				return false;
			}
			for (BlockPos pos : BlueprintManager.pendingClear(world, resolved)) {
				BlockState state = world.getBlockState(pos);
				if (state.isAir() || state.getHardness(world, pos) < 0) {
					continue;
				}
				if (!services.protection().canBreak(world, pos, avatar.ownerId())
						.allowed()) {
					continue;
				}
				return false;
			}
			return true;
		}, "blueprint placement " + placementId + " has its negative space cleared");
	}

	/**
	 * 「照明」：挖完之后用<b>他自己背包里的</b>火把把坑点亮。
	 *
	 * <p>规则和工程的点灯阶段完全一样，所以放在 {@link dev.squire.server.world.Torchlight}
	 * 里共用——「悬空的火把会被相邻更新打掉」这种坑只该被修一次。</p>
	 */
	private void lightUp(Task task, Progress progress) {
		var placement = services.blueprints().placement(progress.placementId()).orElse(null);
		if (placement != null && dev.squire.server.blueprint.TerrainLeveling.parse(placement.blueprintId).isPresent()) return;
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			return;
		}
		if (!services.can(task.agentId(),
				dev.squire.server.profile.Ability.EXCAVATE_LIGHTS)) {
			return;
		}
		int placed = dev.squire.server.world.Torchlight.lightUp(world, avatar,
			progress.cells(), TORCH_SPACING, MAX_TORCHES, journal(),
			progress.operationId(), services.currentTick());
		if (placed > 0) {
			LOG.info("[excavate] {} lit the dig with {} torch(es)", task.taskId(), placed);
		}
	}

	// ------------------------------------------------------------------ 足印硬拦

	/**
	 * 这一格属于那份摆放自己的负空间吗。
	 *
	 * <p>足印集合为空只可能是摆放已经没了——那时候一格都不许挖，宁可什么都不做，
	 * 也不能退化成「随便挖」。</p>
	 */
	public static boolean allowed(Set<BlockPos> footprint, BlockPos pos) {
		return footprint != null && footprint.contains(pos);
	}

	/** 这份摆放允许挖除的全部格子。 */
	private Set<BlockPos> footprintOf(UUID placementId) {
		BlueprintManager manager = services.blueprints();
		if (manager == null) {
			return Set.of();
		}
		// 负空间加施工位：两者都在蓝图自己的足印内。平整场地（把埋在土里的
		// 墙体格挖出来）和掏空内部是同一件事，不该因为分在两个列表里就被硬拦。
		return manager.placement(placementId)
			.flatMap(manager::resolve)
			.map(BlueprintManager::footprint)
			.orElse(Set.of());
	}

	// ------------------------------------------------------------------ helpers

	private BlueprintPlacement placementOf(Task task) {
		Object raw = task.parameters().get(PARAM_PLACEMENT_ID);
		if (!(raw instanceof String id)) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException(TYPE + " needs " + PARAM_PLACEMENT_ID);
		}
		return blueprints().placement(UUID.fromString(id))
			.orElseThrow(() -> {
				task.setLastErrorCode("PRECONDITION_FAILED");
				return new IllegalStateException("blueprint placement " + id + " is gone");
			});
	}

	private BlueprintManager blueprints() {
		BlueprintManager manager = services.blueprints();
		if (manager == null) {
			throw new IllegalStateException("blueprint subsystem unavailable");
		}
		return manager;
	}

	private UndoJournal journal() {
		var editor = services.worldEditor();
		return editor == null ? null : editor.journal();
	}

	/** 挖除同样进撤销日志：{@code /squire undo} 能把掏空的土填回去。 */
	private void recordUndo(UUID operationId, UUID taskId, ServerWorld world,
			BlockPos pos, BlockState old) {
		UndoJournal journal = journal();
		if (journal == null || operationId == null) {
			return;
		}
		net.minecraft.nbt.NbtCompound oldNbt = null;
		var be = world.getBlockEntity(pos);
		if (be != null) {
			oldNbt = be.createNbtWithId();
		}
		journal.record(new UndoJournal.Entry(operationId, taskId, pos.toImmutable(),
			old, oldNbt, net.minecraft.block.Blocks.AIR.getDefaultState(),
			services.currentTick()));
	}
}
