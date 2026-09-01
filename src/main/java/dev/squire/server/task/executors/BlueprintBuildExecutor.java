package dev.squire.server.task.executors;

import java.util.List;
import java.util.UUID;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.blueprint.Blueprint;
import dev.squire.server.blueprint.BlueprintManager;
import dev.squire.server.blueprint.BlueprintPlacement;
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
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
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

	/** 每 tick 放几格——建造应该看起来像建造。 */
	private static final int BLOCKS_PER_TICK = 8;
	/** 「勤勉」特质的加成。特质只改观感节奏，不改战力。 */
	private static final int DILIGENT_BONUS =
		dev.squire.server.profile.Trait.DILIGENT_BUILD_BONUS;
	/** 离目标格这么远就先走过去。 */
	private static final int WORK_RADIUS = 6;
	/** 走不到、又远到这个程度，就如实失败而不是隔空盖房。 */
	private static final int MAX_REMOTE_BUILD = 24;

	public enum Phase { NAVIGATE, BUILD }

	/** 重启后 {@code restoreCheckpoint} 先于 {@code start} 触发，用它把计数带进去。 */
	private record Restored(int placed, int skipped) { }

	public record Progress(UUID placementId, Phase phase, MoveHandle handle,
			List<Blueprint.Cell> cells, int cursor, UUID operationId,
			int placed, int skipped, int skippedProtected) {

		public String summary() {
			return "放置 " + placed + " 格，跳过（可选或已被占）" + skipped
				+ "，跳过（保护区）" + skippedProtected;
		}
	}

	private final RuntimeServices services;

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
		if (task.executionState() instanceof Restored restored) {
			carriedPlaced = restored.placed();
			carriedSkipped = restored.skipped();
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("agent body unavailable");
		}
		Context context = contextOf(task, world);
		List<Blueprint.Cell> cells = BlueprintManager.pendingPlacements(world,
			context.resolved());
		UndoJournal journal = journal();
		UUID operationId = journal == null ? null
			: journal.begin(task.taskId(), task.requesterId(),
				world.getRegistryKey().getValue().toString(),
				"build.blueprint " + context.placement().blueprintId,
				services.currentTick());
		context.placement().setState(BlueprintPlacement.State.BUILDING);
		blueprints().save();
		task.setExecutionState(new Progress(context.placement().placementId,
			Phase.NAVIGATE, null, cells, 0, operationId, carriedPlaced,
			carriedSkipped, 0));
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
			LOG.info("[blueprint] {} finished: {}", task.taskId(), progress.summary());
			return finish(task, progress, StepOutcome.WORK_DONE);
		}
		BlockPos next = progress.cells().get(progress.cursor()).pos();

		if (progress.phase() == Phase.NAVIGATE) {
			StepOutcome nav = navigate(task, avatar, progress, next);
			if (nav != null) {
				return nav;
			}
			progress = (Progress) task.executionState();
		}
		return build(task, avatar, world, progress, tick);
	}

	/**
	 * 走到工地。走不通不等于失败——只要还够得着就照常施工；远到隔空盖房才如实停下。
	 *
	 * @return null 表示已经可以开工，非 null 是本 tick 的结论
	 */
	private StepOutcome navigate(Task task, AvatarEntity avatar, Progress progress,
			BlockPos next) {
		double distSq = avatar.squaredDistanceTo(next.getX() + 0.5, next.getY(),
			next.getZ() + 0.5);
		if (distSq <= (double) WORK_RADIUS * WORK_RADIUS) {
			task.setExecutionState(withPhase(progress, Phase.BUILD, null));
			return null;
		}
		MoveHandle handle = progress.handle();
		if (handle == null) {
			// 埋在地里的目标没有可站的邻居；先走到它正上方的井口去。
			BlockPos target = GatherBlockExecutor.approachPoint(avatar, next);
			handle = avatar.moveTo(new TargetPosition(
				avatar.getWorld().getRegistryKey().getValue().toString(),
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				MoveOptions.WALK);
			task.setExecutionState(withPhase(progress, Phase.NAVIGATE, handle));
			return StepOutcome.CONTINUE;
		}
		if (handle.state() == MoveHandle.State.FAILED
				|| handle.state() == MoveHandle.State.CANCELLED
				|| handle.arrived()) {
			if (distSq > (double) MAX_REMOTE_BUILD * MAX_REMOTE_BUILD) {
				task.setLastErrorCode("NO_REACHABLE_TARGET");
				LOG.info("[blueprint] {} cannot reach the site", task.taskId());
				return finish(task, progress, StepOutcome.FAILED);
			}
			task.setExecutionState(withPhase(progress, Phase.BUILD, null));
			return null;
		}
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
		if (profile != null && profile.can(dev.squire.server.profile.Ability.BUILD_FAST)) {
			budget *= 2;
		}
		if (profile != null
				&& profile.hasTrait(dev.squire.server.profile.Trait.DILIGENT)) {
			budget += DILIGENT_BONUS;
		}
		return budget;
	}

	private StepOutcome build(Task task, AvatarEntity avatar, ServerWorld world,
			Progress progress, long tick) {
		AvatarInventory items = avatar.items();
		dev.squire.server.profile.SquireProfile profile =
			services.profile(task.agentId());
		boolean canSubstitute = profile != null
			&& profile.can(dev.squire.server.profile.Ability.BUILD_SUBSTITUTE);
		boolean canDemolish = profile != null
			&& profile.can(dev.squire.server.profile.Ability.BUILD_DEMOLISH);
		int perTick = blocksPerTick(profile);
		int cursor = progress.cursor();
		int placed = progress.placed();
		int skipped = progress.skipped();
		int skippedProtected = progress.skippedProtected();

		for (int i = 0; i < perTick && cursor < progress.cells().size(); i++) {
			Blueprint.Cell cell = progress.cells().get(cursor);
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
			if (BlueprintManager.matches(current, cell, canSubstitute)) {
				cursor++;
				continue; // 已经就位，不重复计数也不重复扣料
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
			UUID projectId = projectIdOf(task);
			boolean materialTaken = projectId != null
				? services.consumeProjectMaterial(projectId, itemId, 1)
				: !items.extract(itemId, 1).isEmpty();
			if (!materialTaken && projectId == null && canSubstitute) {
				// 「通用建材」：同一个等价组里的东西顶得上（各种木板互通）。
				// 组很窄，换来的仍然是同一种建筑，只是花色不同。
				for (Identifier alternative : dev.squire.server.profile
						.MaterialSubstitutes.substitutesFor(itemId)) {
					if (!items.extract(alternative, 1).isEmpty()) {
						materialTaken = true;
						if (Registries.ITEM.get(alternative) instanceof BlockItem swap) {
							target = swap.getBlock().getDefaultState();
						}
						break;
					}
				}
			}
			if (!materialTaken) {
				if (cell.optional()) {
					skipped++;
					cursor++;
					continue; // 窗户可以先不装
				}
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				LOG.info("[blueprint] {} out of {} after {} blocks", task.taskId(),
					itemId, placed);
				return finish(task, new Progress(progress.placementId(), Phase.BUILD,
					null, progress.cells(), cursor, progress.operationId(), placed,
					skipped, skippedProtected), StepOutcome.FAILED);
			}
			recordUndo(progress.operationId(), task.taskId(), world, pos, current,
				target, tick);
			world.setBlockState(pos, target, net.minecraft.block.Block.NOTIFY_ALL);
			placed++;
			cursor++;
		}

		Progress next = new Progress(progress.placementId(), Phase.BUILD, null,
			progress.cells(), cursor, progress.operationId(), placed, skipped,
			skippedProtected);
		if (cursor >= progress.cells().size()) {
			LOG.info("[blueprint] {} finished: {}", task.taskId(), next.summary());
			return finish(task, next, StepOutcome.WORK_DONE);
		}
		// 下一格可能在另一头：回到导航相，让他走过去而不是隔空放。
		task.setExecutionState(withPhase(next, Phase.NAVIGATE, null));
		return StepOutcome.CONTINUE;
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

	private static Progress withPhase(Progress p, Phase phase, MoveHandle handle) {
		return new Progress(p.placementId(), phase, handle, p.cells(), p.cursor(),
			p.operationId(), p.placed(), p.skipped(), p.skippedProtected());
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
				if (placement.state() == BlueprintPlacement.State.BUILDING) {
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
			+ progress.skipped() + "}";
	}

	@Override
	public void restoreCheckpoint(Task task, String checkpointJson) {
		try {
			var o = com.google.gson.JsonParser.parseString(checkpointJson)
				.getAsJsonObject();
			task.setExecutionState(new Restored(
				o.has("placed") ? o.get("placed").getAsInt() : 0,
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
			return blueprintBuilt(services, UUID.fromString(id));
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
			boolean substitutes = services.can(ctx.agentId(),
				dev.squire.server.profile.Ability.BUILD_SUBSTITUTE);
			for (Blueprint.Cell cell : resolved.toPlace()) {
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
