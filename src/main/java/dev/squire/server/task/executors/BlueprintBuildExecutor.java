package dev.squire.server.task.executors;

import java.util.List;
import java.util.UUID;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
import dev.squire.server.blueprint.ConstructionPlan;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.task.TaskStateStore;
import dev.squire.server.world.UndoJournal;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 按蓝图施工（第 1 期）：逐格从伙伴的<b>真实背包</b>里扣料放下去。
 *
 * <p>这是对老 {@code executeHouse}「一个 tick 直接 setBlockState 整栋房子、不检查也
 * 不消耗任何材料」的替换。资源必须真实流动，否则「探索 → 资源 → 基地 → 配置随从
 * → 提效 → 更远探索」这个循环在建造这一环直接断掉。</p>
 *
 * <p>刻意不改造 {@link BuildStructureExecutor}：它的 Progress 和 {@code structureBuilt}
 * 都是单方块单区域语义，而那个成功条件是 {@code VERIFYING→COMPLETED} 的唯一凭据，
 * 还被 {@code M8WorldEditGameTests} 依赖。这里复用的是它的三块内脏——保护判定、
 * {@code items.extract}、撤销日志——而不是它的形状假设。</p>
 *
 * <h2>重启恢复</h2>
 * <p>不存格子游标。任务参数里只有一个 {@code placementId}，重启后重新解析蓝图、
 * 重新问世界「哪些格子还不是目标方块」，天然从断点续建；已经盖好的格子不会被再算一次。
 * 这比游标更强：中途别人拆了一面墙，续建会把它补回去，而游标会视而不见。
 * 检查点只用来把「已放/已跳过」的计数带过重启，好让完工汇总不撒谎。</p>
 */
public final class BlueprintBuildExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BlueprintBuildExecutor.class);

	public static final String TYPE = "build.blueprint";
	public static final String PARAM_PLACEMENT_ID = "placementId";
	public static final String PARAM_PROJECT_ID = "projectId";
	/** When true, this task only seals the generated foundation/support cells. */
	public static final String PARAM_SITE_PREPARATION = "sitePreparation";

	/** 每 tick 放几格——建造应该看起来像建造。 */
	private static final int BLOCKS_PER_TICK = 8;
	/** 「勤勉」特质的加成。特质只改观感节奏，不改战力。 */
	private static final int DILIGENT_BONUS =
		dev.squire.server.profile.Trait.DILIGENT_BUILD_BONUS;
	public enum Phase { SELECT_STATION, NAVIGATE, BUILD }

	/** 重启后 {@code restoreCheckpoint} 先于 {@code start} 触发，用它把计数带进去。 */
	private record Restored(int placed, int skipped, int skippedProtected) { }

	public record Progress(UUID placementId, Phase phase, MoveHandle handle,
			ConstructionPlan plan, int cursor, List<BlockPos> stations, int stationIndex,
			UUID operationId,
			int placed, int skipped, int skippedProtected) {

		public String summary() {
			return "放置 " + placed + " 格，跳过（可选或已被占）" + skipped
				+ "，跳过（保护区）" + skippedProtected;
		}
	}

	private final RuntimeServices services;
	private final java.util.Map<Task, Double> nextPlacement = new java.util.WeakHashMap<>();
	private final java.util.Map<Task, Recovery> recoveries = new java.util.WeakHashMap<>();
	private static final class Recovery {
		BlockPos target; double bestDistance; long movedAt; int attempts; boolean assisted;
	}

	public BlueprintBuildExecutor(RuntimeServices services) {
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
		int carriedPlaced = 0;
		int carriedSkipped = 0;
		int carriedProtected = 0;
		if (task.executionState() instanceof Restored restored) {
			carriedPlaced = restored.placed();
			carriedSkipped = restored.skipped();
			carriedProtected = restored.skippedProtected();
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("agent body unavailable");
		}
		Context context = contextOf(task, world);
		UUID projectId = projectIdOf(task);
		if (projectId != null && !sitePreparationOnly(task) && context.resolved().access() != null
				&& !context.resolved().access().work.isEmpty()) {
			if (!context.placement().committed()) {
				task.setLastErrorCode("ACCESS_CONFIRMATION_REQUIRED");
				throw new IllegalStateException("old project access footprint requires confirmation");
			}
			var j = journal();
			UUID op = j == null ? null : j.begin(task.taskId(), task.requesterId(), context.placement().dimensionId,
				"build.blueprint " + context.placement().blueprintId, services.currentTick());
			task.setExecutionState(new AccessBuildExecutor.Progress(context.placement().placementId, projectId, op));
			return;
		}
		ConstructionPlan plan = sitePreparationOnly(task)
			? BlueprintManager.sitePreparationPlan(world, context.resolved())
			: BlueprintManager.constructionPlan(world, context.resolved(), projectId != null);
		UndoJournal journal = journal();
		UUID operationId = journal == null ? null
			: journal.begin(task.taskId(), task.requesterId(),
				world.getRegistryKey().getValue().toString(),
				"build.blueprint " + context.placement().blueprintId,
				services.currentTick());
		context.placement().setState(BlueprintPlacement.State.BUILDING);
		blueprints().save();
		task.setExecutionState(new Progress(context.placement().placementId,
			Phase.SELECT_STATION, null, plan, 0, List.of(), -1, operationId,
			carriedPlaced, carriedSkipped, carriedProtected));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		if (task.executionState() instanceof AccessBuildExecutor.Progress progress) {
			StepOutcome outcome = StepOutcome.CONTINUE;
			// Preserve existing build-speed abilities, but only spend extra block
			// budget while standing at the reviewed station. Walking still takes ticks.
			for (int i = 0, budget = blocksPerTick(services.profile(task.agentId())); i < budget; i++) {
				int cursor = progress.program == null ? -1 : progress.program.cursor;
				int owned = progress.program == null ? -1 : progress.program.placed.size();
				outcome = AccessBuildExecutor.tick(services, task, progress, tick);
				if (outcome != StepOutcome.CONTINUE || cursor < 0 || progress.program == null
						|| cursor == progress.program.cursor && owned == progress.program.placed.size()) break;
			}
			if (outcome != StepOutcome.CONTINUE && progress.operationId != null && journal() != null) journal().close(progress.operationId);
			return outcome;
		}
		if (!(task.executionState() instanceof Progress progress)) {
			return StepOutcome.FAILED;
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()
				|| !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return finish(task, progress, StepOutcome.FAILED);
		}
		if (progress.plan().size() == 0 || progress.cursor() >= progress.plan().size()) {
			if (!sitePreparationOnly(task)) {
				UUID fluidProjectId = projectIdOf(task);
				var liquid = FluidBuildExecutor.tick(services, task, fluidProjectId == null ? null : services.project(fluidProjectId), contextOf(task, world).resolved(), null, tick);
				if (liquid == StepOutcome.FAILED) return finish(task, progress, liquid);
				if (liquid != StepOutcome.WORK_DONE) return liquid;
			}
			LOG.info("[blueprint] {} finished: {}", task.taskId(), progress.summary());
			return finish(task, progress, StepOutcome.WORK_DONE);
		}
		Blueprint.Cell next = progress.plan().entries().get(progress.cursor()).cell();
		var recovery = recoveries.computeIfAbsent(task, k -> new Recovery());
		if (!next.pos().equals(recovery.target)) {
			recovery.target = next.pos(); recovery.attempts = 0; recovery.assisted = false; recovery.bestDistance = Double.MAX_VALUE; recovery.movedAt = tick;
		}
		double distance = avatar.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(next.pos()));
		if (distance < recovery.bestDistance - .04) {
			recovery.bestDistance = distance; recovery.movedAt = tick;
		}
		var projectId = projectIdOf(task);
		if (projectId != null && services.project(projectId) != null && !services.project(projectId).pendingMutation().isEmpty()) {
			task.setLastErrorCode("CONSTRUCTION_RECOVERY_REQUIRED"); return finish(task, progress, StepOutcome.FAILED);
		}
		boolean substitutes = projectIdOf(task) == null && services.can(task.agentId(),
			dev.squire.server.profile.Ability.BUILD_SUBSTITUTE);
		if (BlueprintManager.matches(world.getBlockState(next.pos()), next, substitutes)) {
			task.setExecutionState(advance(progress));
			return StepOutcome.CONTINUE;
		}
		if (projectId != null && progress.phase() == Phase.NAVIGATE && tick - recovery.movedAt >= ConstructionRecovery.STALL_TICKS)
			return assist(task, avatar, progress, next);
		if (progress.phase() == Phase.SELECT_STATION) {
			return selectStation(task, avatar, progress, next);
		}
		if (progress.phase() == Phase.NAVIGATE) {
			return navigate(task, avatar, progress, next);
		}
		return build(task, avatar, world, progress, tick);
	}

	/** Choose and retry real, pathable work stations until this cell is in reach. */
	private StepOutcome selectStation(Task task, AvatarEntity avatar, Progress progress,
			Blueprint.Cell cell) {
		if (!materialAvailable(task, avatar.items(), cell)) {
			if (cell.optional()) {
				task.setExecutionState(skip(progress));
				return StepOutcome.CONTINUE;
			}
			task.setLastErrorCode("INSUFFICIENT_ITEM");
			return finish(task, progress, StepOutcome.FAILED);
		}
		BlockState target;
		try {
			target = BlueprintManager.targetState(cell);
		} catch (IllegalArgumentException invalid) {
			LOG.warn("[blueprint] {} has invalid state for {}: {}", task.taskId(),
				cell.blockId(), invalid.getMessage());
			task.setExecutionState(skip(progress));
			return StepOutcome.CONTINUE;
		}
		if (ConstructionWorksite.inReach(avatar, cell.pos())
				&& ConstructionWorksite.clearOfWorker(avatar, target, cell.pos())) {
			avatar.stopMoving();
			task.setExecutionState(withPhase(progress, Phase.BUILD, null));
			return StepOutcome.CONTINUE;
		}
		List<BlockPos> stations = ConstructionWorksite.stations(avatar, cell.pos(),
			progress.plan().bounds());
		if (stations.isEmpty()) return assist(task, avatar, progress, cell);
		return moveToStation(task, avatar, progress, stations, 0);
	}

	private StepOutcome navigate(Task task, AvatarEntity avatar, Progress progress,
			Blueprint.Cell cell) {
		BlockState target;
		try {
			target = BlueprintManager.targetState(cell);
		} catch (IllegalArgumentException invalid) {
			task.setExecutionState(skip(progress));
			return StepOutcome.CONTINUE;
		}
		if (ConstructionWorksite.inReach(avatar, cell.pos())
				&& ConstructionWorksite.clearOfWorker(avatar, target, cell.pos())) {
			avatar.stopMoving();
			task.setExecutionState(withPhase(progress, Phase.BUILD, null));
			return StepOutcome.CONTINUE;
		}
		MoveHandle handle = progress.handle();
		if (handle == null || handle.state() == MoveHandle.State.FAILED
				|| handle.state() == MoveHandle.State.CANCELLED || handle.arrived()) {
			if (projectIdOf(task) != null && ++recoveries.computeIfAbsent(task, k -> new Recovery()).attempts > 1)
				return assist(task, avatar, progress, cell);
			int nextStation = progress.stationIndex() + 1;
			if (nextStation >= progress.stations().size()) {
				return assist(task, avatar, progress, cell);
			}
			return moveToStation(task, avatar, progress, progress.stations(), nextStation);
		}
		return StepOutcome.CONTINUE;
	}

	private StepOutcome moveToStation(Task task, AvatarEntity avatar, Progress progress,
			List<BlockPos> stations, int index) {
		BlockPos station = stations.get(index);
		MoveHandle handle = avatar.moveTo(new TargetPosition(
			avatar.getWorld().getRegistryKey().getValue().toString(),
			station.getX() + 0.5, station.getY(), station.getZ() + 0.5),
			EngineerMovement.options(avatar.profile()));
		task.setExecutionState(new Progress(progress.placementId(), Phase.NAVIGATE,
			handle, progress.plan(), progress.cursor(), stations, index,
			progress.operationId(), progress.placed(), progress.skipped(),
			progress.skippedProtected()));
		return StepOutcome.CONTINUE;
	}

	private StepOutcome unreachable(Task task, Progress progress, BlockPos target) {
		task.setLastErrorCode("NO_REACHABLE_TARGET");
		LOG.info("[blueprint] {} has no reachable work station for {}", task.taskId(), target);
		return finish(task, progress, StepOutcome.FAILED);
	}
	private StepOutcome assist(Task task, AvatarEntity avatar, Progress progress, Blueprint.Cell cell) {
		if (projectIdOf(task) == null) return unreachable(task, progress, cell.pos());
		var resolved = contextOf(task, (ServerWorld) avatar.getWorld()).resolved();
		var changes = ConstructionRecovery.changes(cell, resolved);
		if (ConstructionRecovery.occupied((ServerWorld) avatar.getWorld(), avatar, changes)) {
			task.setLastErrorCode("CONSTRUCTION_OCCUPIED"); return finish(task, progress, StepOutcome.FAILED);
		}
		var mode = ConstructionRecovery.prepare(avatar, ConstructionRecovery.bounds(resolved, avatar), cell.pos(), changes);
		if (mode == null) { task.setLastErrorCode("CONSTRUCTION_NO_SAFE_ANCHOR"); return finish(task, progress, StepOutcome.FAILED); }
		recoveries.computeIfAbsent(task, k -> new Recovery()).assisted = true;
		if (resolved.access() != null && !resolved.access().assistance) {
			resolved.access().assistance = true; resolved.access().recoveries++; resolved.access().recoveryReason = "NO_REACHABLE_TARGET";
			if (!blueprints().save()) { task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED); }
		}
		avatar.setActivityDetail("辅助施工");
		task.setExecutionState(withPhase(progress, Phase.BUILD, null));
		return StepOutcome.CONTINUE;
	}

	/**
	 * 这一 tick 放几格。「双倍工速」能力翻倍，「勤勉」特质再加一点。
	 *
	 * <p>能力和特质都只改<b>节奏</b>，不改材料账：翻倍的是每 tick 的格数，
	 * 不是每格的成本。</p>
	 */
	static int blocksPerTick(dev.squire.server.profile.SquireProfile profile) {
		int budget = BLOCKS_PER_TICK;
		if (profile != null && profile.profession.profession() == dev.squire.server.profession.SquireProfession.ENGINEER) {
			budget = (int) Math.ceil(BLOCKS_PER_TICK * dev.squire.server.profession.EngineerProgression.current().efficiency(profile.profession.level));
		} else if (profile != null && profile.can(dev.squire.server.profile.Ability.BUILD_FAST)) {
			budget *= 2;
		}
		if (profile != null
				&& profile.hasTrait(dev.squire.server.profile.Trait.DILIGENT)) {
			budget += DILIGENT_BONUS;
		}
		return Math.min(32, budget);
	}
	static double placementInterval(dev.squire.server.profile.SquireProfile profile) {
		return profile != null && profile.profession.profession() == dev.squire.server.profession.SquireProfession.ENGINEER
			? dev.squire.server.profession.EngineerProgression.current().placementInterval(profile.profession.level) : 1;
	}

	private StepOutcome build(Task task, AvatarEntity avatar, ServerWorld world,
			Progress progress, long tick) {
		var context = contextOf(task, world);
		AvatarInventory items = avatar.items();
		dev.squire.server.profile.SquireProfile profile =
			services.profile(task.agentId());
		boolean canSubstitute = projectIdOf(task) == null && profile != null
			&& profile.can(dev.squire.server.profile.Ability.BUILD_SUBSTITUTE);
		boolean canDemolish = profile != null
			&& profile.can(dev.squire.server.profile.Ability.BUILD_DEMOLISH);
		int perTick = blocksPerTick(profile);
		int cursor = progress.cursor();
		int placed = progress.placed();
		int skipped = progress.skipped();
		int skippedProtected = progress.skippedProtected();
		if (tick < nextPlacement.getOrDefault(task, 0.0)) return StepOutcome.CONTINUE;

		for (int i = 0; i < perTick && cursor < progress.plan().size(); i++) {
			Blueprint.Cell cell = progress.plan().entries().get(cursor).cell();
			BlockPos pos = cell.pos();
			if (!(Registries.ITEM.get(BlueprintManager.itemId(cell.blockId()))
					instanceof BlockItem blockItem)) {
				// 数据包写了一个放不下去的方块：跳过并留日志，绝不静默盖成别的东西
				LOG.warn("[blueprint] {} has an unplaceable block {}", task.taskId(),
					cell.blockId());
				skipped++;
				cursor++;
				continue;
			}
			BlockState target;
			try {
				target = BlueprintManager.targetState(cell);
			} catch (IllegalArgumentException invalid) {
				LOG.warn("[blueprint] {} has invalid state for {}: {}", task.taskId(),
					cell.blockId(), invalid.getMessage());
				skipped++;
				cursor++;
				continue;
			}
			BlockState current = world.getBlockState(pos);
			if (dev.squire.server.blueprint.TerrainLeveling.isTerrain(context.resolved())
					&& !world.getOtherEntities(avatar, new net.minecraft.util.math.Box(pos),
						entity -> entity instanceof net.minecraft.entity.LivingEntity && entity.isAlive()).isEmpty()) {
				avatar.setActivityDetail("等待施工位置的生物让开：" + pos.toShortString());
				return StepOutcome.CONTINUE;
			}
			if (dev.squire.server.blueprint.TerrainLeveling.isTerrain(context.resolved())
					&& (!dev.squire.server.blueprint.TerrainLeveling.obstacle(world, pos, true).isEmpty()
					|| !services.protection().canPlace(world, pos, avatar.ownerId()).allowed()
					|| !current.isAir() && !current.isReplaceable() && !BlueprintManager.matches(current, cell))) {
				task.setLastErrorCode("TERRAIN_SITE_UNSAFE");
				return finish(task, progress, StepOutcome.FAILED);
			}
			if (BlueprintManager.matches(current, cell, canSubstitute)) {
				cursor++;
				continue; // 已经就位，不重复计数也不重复扣料
			}
			boolean assisted = recoveries.containsKey(task) && recoveries.get(task).assisted && pos.equals(recoveries.get(task).target);
			if (assisted) {
				var changes = ConstructionRecovery.changes(cell, context.resolved());
				if (ConstructionRecovery.occupied(world, avatar, changes)) {
					task.setLastErrorCode("CONSTRUCTION_OCCUPIED"); return finish(task, progress, StepOutcome.FAILED);
				}
				if (!current.isAir() && !current.isReplaceable()) {
					task.setLastErrorCode("ACCESS_SITE_CHANGED"); return finish(task, progress, StepOutcome.FAILED);
				}
				if (ConstructionRecovery.prepare(avatar, ConstructionRecovery.bounds(context.resolved(), avatar), pos, changes) == null) {
					task.setLastErrorCode("CONSTRUCTION_NO_SAFE_ANCHOR"); return finish(task, progress, StepOutcome.FAILED);
				}
			}
			if (!assisted && !ConstructionWorksite.inReach(avatar, pos)
					|| !ConstructionWorksite.clearOfWorker(avatar, target, pos)) {
				Progress next = new Progress(progress.placementId(), Phase.SELECT_STATION,
					null, progress.plan(), cursor, List.of(), -1, progress.operationId(),
					placed, skipped, skippedProtected);
				task.setExecutionState(next);
				return StepOutcome.CONTINUE;
			}
			if (!current.isAir() && !current.isReplaceable()) {
				// 「拆改」：挡路的错方块拆掉再盖，掉落归他。没有这项能力就跳过——
				// 默认绝不动玩家已经放在那里的东西。
				// 但沙、沙砾等会在掘进阶段结束后重新落回已清理的施工格；它们是
				// 场地清理的延迟结果，不应要求“拆改”能力。范围仍被固定蓝图足印和
				// 领地保护双重约束，普通实体方块仍保持原来的 fail-closed 行为。
				boolean fallingRefill = current.getBlock() instanceof FallingBlock;
				boolean cleared = (canDemolish || fallingRefill)
					&& current.getHardness(world, pos) >= 0
					&& services.protection().canBreak(world, pos, avatar.ownerId())
						.allowed()
					&& world.breakBlock(pos, true, avatar);
				if (!cleared) {
					skipped++;
					cursor++;
					continue;
				}
				current = world.getBlockState(pos);
			}
			var decision = services.protection().canPlace(world, pos, avatar.ownerId());
			if (!decision.allowed()) {
				// 工地压到别人的地：跳过这一格继续，最后一次性汇总。整栋停下反而
				// 让玩家不知道到底卡在哪一格。
				skippedProtected++;
				cursor++;
				continue;
			}
			Identifier itemId = BlueprintManager.itemId(cell.blockId());
			Identifier materialId = itemId;
			// Optional details were not charged by the project bill. They may only use
			// spare carried stock, never the escrow reserved for later required work.
			UUID projectId = cell.optional() ? null : projectIdOf(task);
			List<Blueprint.Cell> assembly;
			try { assembly = dev.squire.server.blueprint.BlueprintAssembly.pending(world, cell, context.resolved()); }
			catch (IllegalArgumentException invalid) {
				task.setLastErrorCode("BLUEPRINT_ASSEMBLY_INVALID");
				return finish(task, progress, StepOutcome.FAILED);
			}
			int cost = dev.squire.server.blueprint.BlueprintAssembly.cost(assembly);
			var costPlan = projectId == null ? null : context.resolved().costPlan();
			if (costPlan != null) cost = costPlan.quote(assembly);
			if (cost < 0) {
				task.setLastErrorCode("SETTLED_BUILDING_CHANGED");
				return finish(task, progress, StepOutcome.FAILED);
			}
			if (assembly.size() > 1) for (var part : assembly) {
				var existing = world.getBlockState(part.pos());
				if ((!existing.isAir() && !existing.isReplaceable())
						|| !assisted && !ConstructionWorksite.inReach(avatar, part.pos())
						|| !ConstructionWorksite.clearOfWorker(avatar, BlueprintManager.targetState(part), part.pos())
						|| !services.protection().canPlace(world, part.pos(), avatar.ownerId()).allowed()) {
					task.setLastErrorCode("BLUEPRINT_ASSEMBLY_BLOCKED");
					return finish(task, progress, StepOutcome.FAILED);
				}
			}
			boolean materialReady = cost == 0 || (projectId != null
				? services.projectMaterialCount(projectId, itemId) >= cost
				: items.countOf(itemId) >= cost);
			if (!materialReady && projectId == null && canSubstitute) {
				// 「通用建材」：同一个等价组里的东西顶得上（各种木板互通）。
				// 组很窄，换来的仍然是同一种建筑，只是花色不同。
				List<Identifier> alternatives = cell.properties().isEmpty()
					? dev.squire.server.profile.MaterialSubstitutes.substitutesFor(itemId)
					: List.of();
				for (Identifier alternative : alternatives) {
					if (items.countOf(alternative) > 0) {
						materialReady = true;
						materialId = alternative;
						if (Registries.ITEM.get(alternative) instanceof BlockItem swap) {
							target = swap.getBlock().getDefaultState();
						}
						break;
					}
				}
			}
			if (!materialReady) {
				if (cell.optional()) {
					skipped++;
					cursor++;
					continue; // 窗户可以先不装
				}
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				LOG.info("[blueprint] {} out of {} after {} blocks", task.taskId(),
					itemId, placed);
				return finish(task, new Progress(progress.placementId(), Phase.BUILD,
					null, progress.plan(), cursor, List.of(), -1,
					progress.operationId(), placed, skipped, skippedProtected),
					StepOutcome.FAILED);
			}
			for (var part : assembly) recordUndo(progress.operationId(), task.taskId(), world,
				part.pos(), world.getBlockState(part.pos()), part.pos().equals(pos) ? target
					: BlueprintManager.targetState(part), tick);
			var beforeAssembly = new java.util.LinkedHashMap<BlockPos, BlockState>();
			for (var part : assembly) beforeAssembly.put(part.pos(), world.getBlockState(part.pos()));
			if (projectId != null && !services.beginProjectMutation(projectId, "place:" + pos.asLong())) {
				task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED);
			}
			if (!(assembly.size() > 1 ? dev.squire.server.blueprint.BlueprintAssembly.place(world, assembly)
					: world.setBlockState(pos, target, net.minecraft.block.Block.NOTIFY_ALL))) {
				if (projectId != null) services.completeProjectMutation(projectId);
				task.setLastErrorCode("PLACE_FAILED");
				return finish(task, new Progress(progress.placementId(), Phase.BUILD,
					null, progress.plan(), cursor, List.of(), -1,
					progress.operationId(), placed, skipped, skippedProtected),
					StepOutcome.FAILED);
			}
			// Server work runs on one tick thread, so this preflighted debit cannot
			// normally race. If an inventory integration still invalidates it, restore
			// the old world state instead of granting a free block.
			boolean materialTaken = cost == 0 || (projectId != null
				? services.consumeProjectMaterial(projectId, materialId, cost)
				: items.extract(materialId, cost).stream().mapToInt(net.minecraft.item.ItemStack::getCount).sum() == cost);
			if (!materialTaken) {
				dev.squire.server.blueprint.BlueprintAssembly.restore(world, beforeAssembly);
				if (projectId != null) services.completeProjectMutation(projectId);
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				return finish(task, new Progress(progress.placementId(), Phase.BUILD,
					null, progress.plan(), cursor, List.of(), -1,
					progress.operationId(), placed, skipped, skippedProtected),
					StepOutcome.FAILED);
			}
			avatar.getLookControl().lookAt(pos.getX() + 0.5, pos.getY() + 0.5,
				pos.getZ() + 0.5);
			if (costPlan != null) costPlan.settle(assembly);
			if (projectId != null && !services.completeProjectMutation(projectId)) {
				task.setLastErrorCode("CONSTRUCTION_CHECKPOINT_FAILED"); return finish(task, progress, StepOutcome.FAILED);
			}
			avatar.swingHand(Hand.MAIN_HAND);
			placed++;
			cursor++;
			nextPlacement.put(task, dev.squire.server.profession.EngineerProgression.nextWorkTick(tick, nextPlacement.getOrDefault(task, (double) tick), placementInterval(profile)));
			break; // Placement cooldown is independent of traversal/skip budget.
		}

		Progress next = new Progress(progress.placementId(), Phase.SELECT_STATION, null,
			progress.plan(), cursor, List.of(), -1, progress.operationId(), placed,
			skipped, skippedProtected);
		if (cursor >= progress.plan().size()) {
			task.setExecutionState(next);
			if (!sitePreparationOnly(task) && dev.squire.server.blueprint.ConstructionFluids.hasFluids(context.resolved())) return StepOutcome.CONTINUE;
			LOG.info("[blueprint] {} finished: {}", task.taskId(), next.summary());
			return finish(task, next, StepOutcome.WORK_DONE);
		}
		// The next cell is independently reach-checked before another material is spent.
		task.setExecutionState(next);
		return StepOutcome.CONTINUE;
	}

	private boolean materialAvailable(Task task, AvatarInventory items,
			Blueprint.Cell cell) {
		Identifier wanted = BlueprintManager.itemId(cell.blockId());
		if (BlueprintManager.itemCost(cell) == 0) return true;
		UUID projectId = projectIdOf(task);
		if (projectId != null && !cell.optional()) {
			var avatar = services.avatar(task.agentId());
			if (avatar != null && avatar.getWorld() instanceof ServerWorld world) {
				var resolved = contextOf(task, world).resolved();
				if (resolved.costPlan() != null) {
					int quoted = resolved.costPlan().quote(dev.squire.server.blueprint.BlueprintAssembly.pending(world, cell, resolved));
					// A zero-cost discounted operation is executable with an empty escrow.
					// Invalid/settled operations reach the precise error in build(), not a false missing-material error.
					return quoted <= 0 || services.projectMaterialCount(projectId, wanted) >= quoted;
				}
			}
			return services.projectMaterialCount(projectId, wanted) >= BlueprintManager.itemCost(cell);
		}
		if (projectId != null) return items.countOf(wanted) >= BlueprintManager.itemCost(cell);
		if (items.countOf(wanted) > 0) return true;
		if (!cell.properties().isEmpty()
				|| !services.can(task.agentId(),
					dev.squire.server.profile.Ability.BUILD_SUBSTITUTE)) return false;
		for (Identifier alternative : dev.squire.server.profile.MaterialSubstitutes
				.substitutesFor(wanted)) {
			if (items.countOf(alternative) > 0) return true;
		}
		return false;
	}

	private static UUID projectIdOf(Task task) {
		Object raw = task.parameters().get(PARAM_PROJECT_ID);
		if (!(raw instanceof String id)) return null;
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException bad) {
			return null;
		}
	}

	private static boolean sitePreparationOnly(Task task) {
		return Boolean.TRUE.equals(task.parameters().get(PARAM_SITE_PREPARATION))
			|| "true".equals(task.parameters().get(PARAM_SITE_PREPARATION));
	}

	private static Progress withPhase(Progress p, Phase phase, MoveHandle handle) {
		return new Progress(p.placementId(), phase, handle, p.plan(), p.cursor(),
			p.stations(), p.stationIndex(), p.operationId(), p.placed(), p.skipped(),
			p.skippedProtected());
	}

	private static Progress advance(Progress p) {
		return new Progress(p.placementId(), Phase.SELECT_STATION, null, p.plan(),
			p.cursor() + 1, List.of(), -1, p.operationId(), p.placed(), p.skipped(),
			p.skippedProtected());
	}

	private static Progress skip(Progress p) {
		return new Progress(p.placementId(), Phase.SELECT_STATION, null, p.plan(),
			p.cursor() + 1, List.of(), -1, p.operationId(), p.placed(), p.skipped() + 1,
			p.skippedProtected());
	}

	/** 撤销日志无论任务怎么结束都恰好关一次。 */
	private StepOutcome finish(Task task, Progress progress, StepOutcome outcome) {
		// 工作量单独公布：任务进入 VERIFYING 后 executionState 会被调度器拿去存
		// 验证开始的 tick，而熟练度记账恰好发生在那之后。
		task.setWorkReport(new Task.WorkReport(
			dev.squire.server.profile.Track.BUILD.id(), progress.placed(),
			progress.operationId()));
		task.setExecutionState(progress);
		UndoJournal journal = journal();
		if (journal != null && progress.operationId() != null) {
			journal.close(progress.operationId());
		}
		BlueprintManager manager = services.blueprints();
		if (manager != null) {
			manager.placement(progress.placementId()).ifPresent(placement -> {
				// 成不成由 GoalVerifier 说了算，这里只把「不再施工」这件事记回去。
				if (projectIdOf(task) == null
						&& placement.state() == BlueprintPlacement.State.BUILDING) {
					placement.setState(outcome == StepOutcome.WORK_DONE
						? BlueprintPlacement.State.READY
						: BlueprintPlacement.State.GHOST);
				}
			});
			manager.save();
		}
		return outcome;
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof AccessBuildExecutor.Progress p && p.operationId != null && journal() != null)
			journal().close(p.operationId);
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
		return "{\"placed\":" + progress.placed() + ",\"skipped\":"
			+ progress.skipped() + ",\"skippedProtected\":"
			+ progress.skippedProtected() + ",\"remaining\":"
			+ Math.max(0, progress.plan().size() - progress.cursor()) + "}";
	}

	@Override
	public void restoreCheckpoint(Task task, String checkpointJson) {
		try {
			var o = com.google.gson.JsonParser.parseString(checkpointJson)
				.getAsJsonObject();
			task.setExecutionState(new Restored(
				o.has("placed") ? o.get("placed").getAsInt() : 0,
				o.has("skipped") ? o.get("skipped").getAsInt() : 0,
				o.has("skippedProtected")
					? o.get("skippedProtected").getAsInt() : 0));
		} catch (RuntimeException bad) {
			task.setExecutionState(new Restored(0, 0, 0));
		}
	}

	@Override
	public TaskCondition recoverySuccessCondition(TaskStateStore.Snapshot snapshot) {
		Object raw = snapshot.parameters().get(PARAM_PLACEMENT_ID);
		if (!(raw instanceof String id)) {
			return null;
		}
		try {
			UUID placementId = UUID.fromString(id);
			if (Boolean.TRUE.equals(snapshot.parameters().get(PARAM_SITE_PREPARATION))
					|| "true".equals(snapshot.parameters().get(PARAM_SITE_PREPARATION))) {
				return sitePrepared(services, placementId);
			}
			return blueprintBuilt(services, placementId,
				snapshot.parameters().containsKey(PARAM_PROJECT_ID));
		} catch (IllegalArgumentException notAUuid) {
			return null;
		}
	}

	// ------------------------------------------------------------------ 成功条件

	/**
	 * 成功条件读的是<b>真实世界方块</b>，不是执行器自己的计数。
	 *
	 * <p>可选步骤和被保护挡住的格子不算数——否则一栋差两块玻璃的房子会永远
	 * 卡在 VERIFYING，玩家除了取消无路可走。</p>
	 */
	public static TaskCondition blueprintBuilt(RuntimeServices services, UUID placementId) {
		return blueprintBuilt(services, placementId, false);
	}

	/** The preparation pass succeeds once no generated support cell remains missing. */
	public static TaskCondition sitePrepared(RuntimeServices services, UUID placementId) {
		return TaskCondition.of(ctx -> {
			BlueprintManager manager = services.blueprints();
			if (manager == null) return false;
			BlueprintPlacement placement = manager.placement(placementId).orElse(null);
			AvatarEntity avatar = services.avatar(ctx.agentId());
			if (placement == null || avatar == null
					|| !(avatar.getWorld() instanceof ServerWorld world)
					|| !world.getRegistryKey().getValue().toString()
						.equals(placement.dimensionId)) {
				return false;
			}
			Blueprint.Resolved resolved = manager.resolve(placement).orElse(null);
			if (resolved == null) return false;
			for (Blueprint.Cell cell : BlueprintManager.automaticSiteSupports(world,
					resolved)) {
				BlockPos pos = cell.pos();
				if (!services.protection().canPlace(world, pos, avatar.ownerId()).allowed()
						|| world.getBlockState(pos).getHardness(world, pos) < 0) {
					continue;
				}
				return false;
			}
			return true;
		}, "automatic support layer for " + placementId + " stands in the world");
	}

	/** Project builds also verify their automatically generated support layer. */
	public static TaskCondition blueprintBuilt(RuntimeServices services, UUID placementId,
			boolean prepareSite) {
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
			if (resolved.access() != null && !resolved.access().work.isEmpty()) {
				if (!resolved.access().placed.isEmpty() || !resolved.access().cleanup) return false;
				if (resolved.access().cancelRequested) return true;
			}
			boolean substitutes = services.can(ctx.agentId(),
				dev.squire.server.profile.Ability.BUILD_SUBSTITUTE);
			List<Blueprint.Cell> cells = prepareSite
				? BlueprintManager.pendingProjectPlacements(world, resolved)
				: resolved.toPlace();
			for (Blueprint.Cell cell : cells) {
				if (BlueprintManager.matches(world.getBlockState(cell.pos()),
						cell, substitutes)) {
					continue;
				}
				if (cell.optional()) {
					continue;
				}
				if (!services.protection().canPlace(world, cell.pos(), avatar.ownerId())
						.allowed()) {
					continue; // 合理地跳过了：保护区说不行
				}
				if (world.getBlockState(cell.pos()).getHardness(world, cell.pos()) < 0) {
					continue; // 基岩之类拿不走的东西。不认它就意味着整栋建筑永远
					// 卡在 VERIFYING，而玩家除了取消无路可走。
				}
				return false;
			}
			return true;
		}, "blueprint placement " + placementId + " stands in the world");
	}

	// ------------------------------------------------------------------ helpers

	/** 任务参数只带一个 placementId，形状每次现算，见类级文档。 */
	record Context(BlueprintPlacement placement, Blueprint.Resolved resolved) { }

	private Context contextOf(Task task, ServerWorld world) {
		BlueprintManager manager = blueprints();
		Object raw = task.parameters().get(PARAM_PLACEMENT_ID);
		if (!(raw instanceof String id)) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException(TYPE + " needs " + PARAM_PLACEMENT_ID);
		}
		BlueprintPlacement placement = manager.placement(UUID.fromString(id))
			.orElse(null);
		if (placement == null) {
			task.setLastErrorCode("PRECONDITION_FAILED");
			throw new IllegalStateException("blueprint placement " + id + " is gone");
		}
		if (!world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
			task.setLastErrorCode("PRECONDITION_FAILED");
			throw new IllegalStateException("the site is in another dimension");
		}
		Blueprint.Resolved resolved = manager.resolve(placement).orElse(null);
		if (resolved == null) {
			task.setLastErrorCode("PRECONDITION_FAILED");
			throw new IllegalStateException("blueprint " + placement.blueprintId
				+ " is no longer registered");
		}
		return new Context(placement, resolved);
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

	private void recordUndo(UUID operationId, UUID taskId, ServerWorld world, BlockPos pos,
			BlockState old, BlockState target, long tick) {
		UndoJournal journal = journal();
		if (journal == null || operationId == null) {
			return;
		}
		NbtCompound oldNbt = null;
		BlockEntity be = world.getBlockEntity(pos);
		if (be != null) {
			oldNbt = be.createNbtWithId();
		}
		journal.record(new UndoJournal.Entry(operationId, taskId, pos.toImmutable(),
			old, oldNbt, target, tick));
	}
}
