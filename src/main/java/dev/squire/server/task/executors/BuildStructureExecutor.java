package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.world.BoundedRegion;
import dev.squire.server.world.UndoJournal;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 普通建造（方案 F1）：结构化计划 + 逐层放置。
 *
 * <p>与 WorldEdit 的关键区别是"从哪来"：每一格都从 Avatar 的真实背包里扣掉一个
 * 方块物品，背包空了就如实停下。普通建造永远不会凭空生成方块，也不走命令。</p>
 *
 * <p>每一格写入前都过同一个 {@code ProtectionAdapter}；被拒绝时按策略 STOP 或
 * SKIP，并在结束时给出汇总。所有写入进 {@link UndoJournal}，所以建造同样可以
 * {@code /squire undo}。</p>
 */
public final class BuildStructureExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BuildStructureExecutor.class);

	public static final String TYPE = "build.structure";

	public static final String PARAM_BLOCK_ID = "blockId";
	public static final String PARAM_PATTERN = "pattern";
	public static final String PARAM_ON_PROTECTED = "onProtected";

	/** 普通建造的规模上限：这是"盖房子"，不是"批量编辑地形"。 */
	public static final int MAX_CELLS = 4096;
	/** 每 tick 放置几格——建造应该看起来像建造。 */
	private static final int BLOCKS_PER_TICK = 16;
	/** 结构形状。 */
	public enum Pattern {
		/** 实心填满。 */
		SOLID,
		/** 只留外壳（六面）。 */
		HOLLOW,
		/** 只有四面墙，没有顶和底。 */
		WALLS,
		/** 只有最底层。 */
		FLOOR;

		public static Pattern parse(String raw) {
			if (raw == null) {
				return SOLID;
			}
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return SOLID;
			}
		}
	}

	/** 遇到保护区域时的策略。 */
	public enum OnProtected {
		/** 停下并如实失败（默认：玩家应该知道自己撞到了保护区）。 */
		STOP,
		/** 跳过这一格继续建，最后汇总跳过了多少。 */
		SKIP;

		public static OnProtected parse(String raw) {
			if (raw == null) {
				return STOP;
			}
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return STOP;
			}
		}
	}

	/** 逐层进度 + 汇总。 */
	public record Progress(List<BlockPos> cells, int cursor, BlockState target,
			Identifier itemId, Pattern pattern, OnProtected onProtected,
			UUID operationId, int placed, int skippedProtected, int skippedOccupied) {

		public String summary() {
			return "放置 " + placed + " 格，跳过（已有方块）" + skippedOccupied
				+ "，跳过（保护区）" + skippedProtected;
		}
	}

	private final RuntimeServices services;

	public BuildStructureExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void start(Task task) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("agent body unavailable");
		}
		String blockId = task.stringParam(PARAM_BLOCK_ID);
		if (blockId == null) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("build.structure needs blockId");
		}
		Identifier itemId = parseId(blockId);
		var item = Registries.ITEM.get(itemId);
		if (!(item instanceof BlockItem blockItem)) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException(blockId + " is not a placeable block item");
		}
		BoundedRegion region = regionOf(task);
		Pattern pattern = Pattern.parse(task.stringParam(PARAM_PATTERN));
		List<BlockPos> cells = cellsOf(region, pattern);
		if (cells.isEmpty()) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("structure has no cells");
		}
		if (cells.size() > MAX_CELLS) {
			task.setLastErrorCode("CAPABILITY_SCOPE_VIOLATION");
			throw new IllegalStateException("structure of " + cells.size()
				+ " cells exceeds the " + MAX_CELLS + " ceiling for ordinary building");
		}
		UndoJournal journal = journal();
		UUID operationId = journal == null ? null
			: journal.begin(task.taskId(), task.requesterId(),
				world.getRegistryKey().getValue().toString(),
				"build " + pattern + " " + blockId + " " + region,
				services.currentTick());
		task.setExecutionState(new Progress(cells, 0,
			blockItem.getBlock().getDefaultState(), itemId, pattern,
			OnProtected.parse(task.stringParam(PARAM_ON_PROTECTED)), operationId,
			0, 0, 0));
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
		AvatarInventory items = avatar.items();
		int cursor = progress.cursor();
		int placed = progress.placed();
		int skippedProtected = progress.skippedProtected();
		int skippedOccupied = progress.skippedOccupied();

		for (int i = 0; i < BLOCKS_PER_TICK && cursor < progress.cells().size(); i++) {
			BlockPos pos = progress.cells().get(cursor);
			BlockState current = world.getBlockState(pos);
			if (current.equals(progress.target())) {
				skippedOccupied++;
				cursor++;
				continue;
			}
			if (!current.isAir() && !current.isReplaceable()) {
				skippedOccupied++;
				cursor++;
				continue;
			}
			// 方案 F1/C5：普通建造与批量编辑共用同一个保护判定
			var decision = services.protection().canPlace(world, pos, avatar.ownerId());
			if (!decision.allowed()) {
				if (progress.onProtected() == OnProtected.STOP) {
					task.setLastErrorCode("PROTECTED_REGION");
					LOG.info("[build] {} stopped at {}: {}", task.taskId(), pos,
						decision.reason());
					return finish(task, new Progress(progress.cells(), cursor,
						progress.target(), progress.itemId(), progress.pattern(),
						progress.onProtected(), progress.operationId(), placed,
						skippedProtected + 1, skippedOccupied), StepOutcome.FAILED);
				}
				skippedProtected++;
				cursor++;
				continue;
			}
			// 材料必须来自真实背包（方案 F1：普通建造不能凭空生成方块）
			List<ItemStack> taken = items.extract(progress.itemId(), 1);
			if (taken.isEmpty()) {
				task.setLastErrorCode("INSUFFICIENT_ITEM");
				LOG.info("[build] {} out of material after {} blocks", task.taskId(), placed);
				return finish(task, new Progress(progress.cells(), cursor,
					progress.target(), progress.itemId(), progress.pattern(),
					progress.onProtected(), progress.operationId(), placed,
					skippedProtected, skippedOccupied), StepOutcome.FAILED);
			}
			recordUndo(progress.operationId(), task.taskId(), world, pos, current,
				progress.target(), tick);
			world.setBlockState(pos, progress.target(), 3);
			placed++;
			cursor++;
		}

		Progress next = new Progress(progress.cells(), cursor, progress.target(),
			progress.itemId(), progress.pattern(), progress.onProtected(),
			progress.operationId(), placed, skippedProtected, skippedOccupied);
		task.setExecutionState(next);
		if (cursor >= progress.cells().size()) {
			LOG.info("[build] {} finished: {}", task.taskId(), next.summary());
			return finish(task, next, StepOutcome.WORK_DONE);
		}
		return StepOutcome.CONTINUE;
	}

	/** Close the undo journal exactly once, whichever way the task ends. */
	private StepOutcome finish(Task task, Progress progress, StepOutcome outcome) {
		task.setWorkReport(new Task.WorkReport(
			dev.squire.server.profile.Track.BUILD.id(), progress.placed(),
			progress.operationId()));
		task.setExecutionState(progress);
		UndoJournal journal = journal();
		if (journal != null && progress.operationId() != null) {
			journal.close(progress.operationId());
		}
		return outcome;
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof Progress progress
				&& progress.operationId() != null && journal() != null) {
			// 已经放下去的方块仍然可以撤销，所以是 close 而不是 abandon
			journal().close(progress.operationId());
		}
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

	// ------------------------------------------------------------------ 计划

	/** Cells of the requested shape, bottom layer first so a build rises like a build. */
	public static List<BlockPos> cellsOf(BoundedRegion region, Pattern pattern) {
		List<BlockPos> cells = new ArrayList<>();
		BlockPos min = region.min();
		BlockPos max = region.max();
		for (int y = min.getY(); y <= max.getY(); y++) {
			for (int x = min.getX(); x <= max.getX(); x++) {
				for (int z = min.getZ(); z <= max.getZ(); z++) {
					if (includes(pattern, min, max, x, y, z)) {
						cells.add(new BlockPos(x, y, z));
					}
				}
			}
		}
		return cells;
	}

	private static boolean includes(Pattern pattern, BlockPos min, BlockPos max,
			int x, int y, int z) {
		boolean edgeX = x == min.getX() || x == max.getX();
		boolean edgeZ = z == min.getZ() || z == max.getZ();
		boolean edgeY = y == min.getY() || y == max.getY();
		return switch (pattern) {
			case SOLID -> true;
			case HOLLOW -> edgeX || edgeZ || edgeY;
			case WALLS -> edgeX || edgeZ;
			case FLOOR -> y == min.getY();
		};
	}

	private static BoundedRegion regionOf(Task task) {
		var params = task.parameters();
		return BoundedRegion.ofCorners(asInt(params.get("x1")), asInt(params.get("y1")),
			asInt(params.get("z1")), asInt(params.get("x2")), asInt(params.get("y2")),
			asInt(params.get("z2")));
	}

	private static int asInt(Object o) {
		if (o instanceof Number n) {
			return n.intValue();
		}
		throw new IllegalArgumentException("missing coordinate");
	}

	static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
	}

	/**
	 * 方案 A4：重启后把成功条件从参数重建。
	 *
	 * <p>以前这里返回默认的 null，于是施工中途关服再开，调度器只能把任务
	 * 直接判成 {@code SERVER_RESTARTED}——房子盖了一半，任务却没了。参数里已经有
	 * 两个角点、pattern 和 blockId，重建条件所需的东西一样不缺；缺字段的旧任务仍然
	 * 返回 null，宁可如实失败也不重新武装一个永远验不了的任务。</p>
	 */
	@Override
	public TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		var params = snapshot.parameters();
		if (!(params.get(PARAM_BLOCK_ID) instanceof String blockId)) {
			return null;
		}
		if (!(params.get("x1") instanceof Number x1) || !(params.get("y1") instanceof Number y1)
				|| !(params.get("z1") instanceof Number z1)
				|| !(params.get("x2") instanceof Number x2)
				|| !(params.get("y2") instanceof Number y2)
				|| !(params.get("z2") instanceof Number z2)) {
			return null;
		}
		AvatarEntity avatar = services.avatar(snapshot.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			return null; // 身体还没加载出来，维度无从确认
		}
		BoundedRegion region = BoundedRegion.ofCorners(x1.intValue(), y1.intValue(),
			z1.intValue(), x2.intValue(), y2.intValue(), z2.intValue());
		return structureBuilt(services, world.getRegistryKey().getValue().toString(),
			region, Pattern.parse(params.get(PARAM_PATTERN) instanceof String p ? p : null),
			blockId);
	}

	/**
	 * Goal condition over REAL world state: every planned cell either holds the target
	 * block, was already occupied by something else, or is currently protected. The
	 * executor's own counters are never the evidence.
	 */
	public static TaskCondition structureBuilt(RuntimeServices services, String dimensionId,
			BoundedRegion region, Pattern pattern, String blockId) {
		return TaskCondition.of(ctx -> {
			AvatarEntity avatar = services.avatar(ctx.agentId());
			if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)
					|| !world.getRegistryKey().getValue().toString().equals(dimensionId)) {
				return false;
			}
			var item = Registries.ITEM.get(parseId(blockId));
			if (!(item instanceof BlockItem blockItem)) {
				return false;
			}
			var target = blockItem.getBlock();
			for (BlockPos pos : cellsOf(region, pattern)) {
				if (world.getBlockState(pos).isOf(target)) {
					continue;
				}
				if (!services.protection().canPlace(world, pos, avatar.ownerId()).allowed()) {
					continue; // legitimately skipped: protection said no
				}
				return false;
			}
			return true;
		}, "structure " + pattern + " of " + blockId + " stands at " + region);
	}
}
