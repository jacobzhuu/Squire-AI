package dev.squire.server.task.executors;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.body.proxy.FakePlayerInteractionProxy;
import dev.squire.server.task.Task;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Gathers N of an item by mining matching source blocks (spec section 37 bounds).
 *
 * <p>Phases per target block: FIND (scan cube) → NAVIGATE → BREAK(collect).
 * Drops are inserted straight into the avatar inventory (ADR-004); the goal
 * condition re-checks the real inventory count, never the executor's bookkeeping.</p>
 *
 * <p>Targets that prove UNREACHABLE or unbreakable are added to a per-task avoid-set
 * and never retried — without it the executor spins on one bad block until timeout
 * (observed when world-gen ore under a floating structure passed the scan but had no
 * path). Exhaustion is declared only once no non-avoided candidate remains.</p>
 */
public final class GatherBlockExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(GatherBlockExecutor.class);

	public static final String TYPE = "gather.block";

	/**
	 * 水平/垂直扫描半径。用扁盒子而不是立方体：矿脉和树都在水平方向铺开，
	 * 往上下多扫十几格几乎只是白花 getBlockState。
	 */
	private static final int SCAN_RADIUS_H = 12;
	private static final int SCAN_RADIUS_V = 6;
	/** 一个落脚点上连续扫这么多次还没有目标，就换个地方继续找。 */
	private static final int MAX_EMPTY_SCANS = 3;
	/**
	 * 换地方找的次数上限。以前是"扫不到就直接失败"——伙伴一步都不迈，玩家看到的
	 * 就是"说了目标已创建，然后它只会跟着我"。现在它会真的走出去看。
	 */
	private static final int MAX_RELOCATIONS = 6;
	/** 每次换地方走多远。 */
	private static final int RELOCATE_DISTANCE = 18;
	/** 换地方途中重新扫描的间隔，避免每 tick 都扫一遍。 */
	private static final int RELOCATE_SCAN_INTERVAL = 20;
	/** Safety valve: never track more than this many avoided positions per task. */
	private static final int MAX_AVOIDED = 64;

	/** 依次尝试的八个罗盘方向，从任务开始的位置向外铺开。 */
	private static final int[][] SEARCH_DIRECTIONS = {
		{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

	public static final String PARAM_BLOCK_ID = "blockId";
	public static final String PARAM_ITEM_ID = "itemId";
	public static final String PARAM_COUNT = "count";

	public enum Phase { FIND, NAVIGATE, BREAK, RELOCATE }

	public record Progress(Phase phase, BlockPos target, MoveHandle handle,
			int wanted, int collectedThisTask, Set<BlockPos> avoided, int emptyScans,
			int relocations, BlockPos searchOrigin) {

		public Progress avoid(BlockPos pos) {
			Set<BlockPos> next = new HashSet<>(avoided);
			next.add(pos);
			return new Progress(Phase.FIND, null, null, wanted, collectedThisTask,
				java.util.Collections.unmodifiableSet(next), emptyScans, relocations,
				searchOrigin);
		}

		Progress at(Phase next, BlockPos nextTarget, MoveHandle nextHandle) {
			return new Progress(next, nextTarget, nextHandle, wanted, collectedThisTask,
				avoided, emptyScans, relocations, searchOrigin);
		}

		Progress withScans(int scans) {
			return new Progress(phase, target, handle, wanted, collectedThisTask,
				avoided, scans, relocations, searchOrigin);
		}

		Progress relocating(MoveHandle nextHandle, int nextRelocations) {
			return new Progress(Phase.RELOCATE, null, nextHandle, wanted,
				collectedThisTask, avoided, 0, nextRelocations, searchOrigin);
		}

		/**
		 * 挖掉一个方块之后世界变了：之前"够不着"的判断可能已经不成立——典型情况是
		 * 后排矿被前排挡着，前排挖掉就能走到了。所以每成功挖一次就清空 avoided
		 * 重新评估，否则那些方块被永久排除，任务明明还差几个却宣告找不到。
		 */
		Progress collected(int total) {
			return new Progress(Phase.FIND, null, null, wanted, total, Set.of(), 0,
				relocations, searchOrigin);
		}
	}

	private final RuntimeServices services;

	public GatherBlockExecutor(RuntimeServices services) {
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

	private static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
	}

	@Override
	public void start(Task task) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("no avatar");
		}
		String blockId = task.stringParam(PARAM_BLOCK_ID);
		Integer absoluteTarget = task.intParam("targetCount");
		int count = absoluteTarget != null ? absoluteTarget
			: task.intParam(PARAM_COUNT) == null ? 1 : task.intParam(PARAM_COUNT);
		if (blockId == null || count <= 0) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("gather needs blockId and positive count");
		}
		task.setExecutionState(new Progress(Phase.FIND, null, null, count, 0,
			Set.of(), 0, 0, avatar.getBlockPos().toImmutable()));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		if (!(task.executionState() instanceof Progress progress)) {
			return StepOutcome.FAILED;
		}
		logProgress(task, progress, tick);
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		ServerWorld world = (ServerWorld) avatar.getWorld();
		Block block = Registries.BLOCK.get(parseId(task.stringParam(PARAM_BLOCK_ID)));
		int wanted = progress.wanted();

		switch (progress.phase()) {
			case FIND -> {
				BlockPos found = scanNearest(world, avatar.getBlockPos(), block,
					progress.avoided());
				if (found == null) {
					if (avatar.countItem(dropIdOf(task)) >= wanted) {
						return StepOutcome.WORK_DONE; // someone else finished the job
					}
					int scans = progress.emptyScans() + 1;
					if (scans < MAX_EMPTY_SCANS) {
						task.setExecutionState(progress.withScans(scans));
						return StepOutcome.CONTINUE;
					}
					if (!progress.avoided().isEmpty()) {
						// 看得到但走不过去：换个地方也没用，而且会把伙伴带离原地，
						// 让后续任务连本来够得着的资源都找不到。如实报出来重规划。
						task.setLastErrorCode("NO_REACHABLE_TARGET");
						return StepOutcome.FAILED;
					}
					// 这一带真的没有：走出去换个地方继续找，而不是站在原地宣告失败
					return beginRelocation(task, avatar, progress);
				}
				if (progress.avoided().size() >= MAX_AVOIDED) {
					task.setLastErrorCode("RESOURCE_EXHAUSTED");
					return StepOutcome.FAILED;
				}
				task.setExecutionState(progress.at(Phase.NAVIGATE, found, null));
				startNavigation(task, avatar, found);
				return StepOutcome.CONTINUE;
			}
			case RELOCATE -> {
				// 走的路上也顺便看看，别走到头才发现目标就在旁边
				if (tick % RELOCATE_SCAN_INTERVAL == 0) {
					BlockPos spotted = scanNearest(world, avatar.getBlockPos(), block,
						progress.avoided());
					if (spotted != null) {
						avatar.stopMoving();
						task.setExecutionState(progress.at(Phase.NAVIGATE, spotted, null));
						startNavigation(task, avatar, spotted);
						return StepOutcome.CONTINUE;
					}
				}
				MoveHandle handle = progress.handle();
				boolean done = handle == null
					|| handle.state() == MoveHandle.State.ARRIVED
					|| handle.state() == MoveHandle.State.FAILED
					|| handle.state() == MoveHandle.State.CANCELLED;
				if (!done) {
					return StepOutcome.CONTINUE;
				}
				// 到了（或这个方向走不通）：在新位置重新开始扫描
				task.setExecutionState(new Progress(Phase.FIND, null, null, wanted,
					progress.collectedThisTask(), progress.avoided(), 0,
					progress.relocations(), progress.searchOrigin()));
				return StepOutcome.CONTINUE;
			}
			case NAVIGATE -> {
				MoveHandle handle = progress.handle();
				double distSq = avatar.squaredDistanceTo(progress.target().getX() + 0.5,
					progress.target().getY(), progress.target().getZ() + 0.5);
				if ((handle != null && handle.arrived()) || distSq <= 4.0) {
					task.setExecutionState(
						progress.at(Phase.BREAK, progress.target(), handle));
					return StepOutcome.CONTINUE; // break on the following tick
				}
				if (handle != null && handle.state() == MoveHandle.State.FAILED) {
					// unreachable right now: never retry this block, scan for another
					LOG.info("[gather] {} avoiding unreachable {}", task.taskId(),
						progress.target());
					task.setExecutionState(progress.avoid(progress.target()));
					return StepOutcome.CONTINUE;
				}
				if (handle == null || handle.state() == MoveHandle.State.CANCELLED) {
					startNavigation(task, avatar, progress.target());
				}
				return StepOutcome.CONTINUE;
			}
			case BREAK -> {
				BlockPos target = progress.target();
				BlockState state = world.getBlockState(target);
				if (!state.isOf(block)) {
					// already gone (or replaced): rescan for another
					task.setExecutionState(progress.at(Phase.FIND, null, null));
					return StepOutcome.CONTINUE;
				}
				// 方案 C5：普通采集与 WorldEdit 共用同一个 Protection 判定
				var decision = services.protection().canBreak(world, target,
					avatar.ownerId());
				if (!decision.allowed()) {
					LOG.info("[gather] {} protection denied {} ({})", task.taskId(),
						target, decision.reason());
					task.setLastErrorCode("PROTECTED_REGION");
					task.setExecutionState(progress.avoid(target));
					return StepOutcome.CONTINUE;
				}

				AvatarInventory items = avatar.items();
				AvatarInventory.SlotRef toolSlot = items.bestToolSlot(state);
				ItemStack tool = items.stackAt(toolSlot); // 真实 stack 引用，不是新建副本
				if (!FakePlayerInteractionProxy.canHarvest(state, tool)) {
					// 没有能真正掉落产物的工具：绝不空手砸掉方块换来 0 掉落
					LOG.info("[gather] {} needs a proper tool for {}", task.taskId(), state);
					task.setLastErrorCode("PRECONDITION_FAILED");
					avatar.setActivityDetail(FakePlayerInteractionProxy.missingHarvestToolMessage(state));
					return StepOutcome.FAILED;
				}

				FakePlayerInteractionProxy.BreakResult result =
					FakePlayerInteractionProxy.breakAndCollect(world, target, tool,
						used -> {
						});
				// 耐久/附魔消耗写回同一个装备或背包槽（方案 C2）
				items.writeBackTool(toolSlot, tool);
				if (!result.broken()) {
					LOG.info("[gather] {} avoiding unbreakable {} tool={}",
						task.taskId(), target, tool);
					task.setExecutionState(progress.avoid(target));
					return StepOutcome.CONTINUE;
				}
				int gained = 0;
				boolean full = false;
				for (ItemStack drop : result.drops()) {
					boolean wantedDrop = drop.getItem() == Registries.ITEM.get(dropIdOf(task))
						|| matchesDrop(task, drop);
					ItemStack remainder = items.insert(drop);
					if (wantedDrop) {
						gained += drop.getCount() - remainder.getCount();
					}
					if (!remainder.isEmpty()) {
						// 背包满：保留世界掉落，绝不静默销毁（方案 C2）
						net.minecraft.block.Block.dropStack(world, target, remainder);
						full = true;
					}
				}
				int collected = progress.collectedThisTask() + gained;
				if (avatar.countItem(dropIdOf(task)) >= wanted) {
					return StepOutcome.WORK_DONE;
				}
				if (full) {
					task.setLastErrorCode("INVENTORY_FULL");
					return StepOutcome.FAILED;
				}
				task.setExecutionState(progress.collected(collected));
				return StepOutcome.CONTINUE;
			}
		}
		return StepOutcome.CONTINUE;
	}

	private static boolean matchesDrop(Task task, ItemStack drop) {
		// default: the drop id IS the block's item form when no explicit itemId param
		String itemId = task.stringParam(PARAM_ITEM_ID);
		if (itemId == null) {
			return true;
		}
		return drop.getItem() == Registries.ITEM.get(parseId(itemId));
	}

	private static Identifier dropIdOf(Task task) {
		String itemId = task.stringParam(PARAM_ITEM_ID);
		return parseId(itemId != null ? itemId : task.stringParam(PARAM_BLOCK_ID));
	}

	/**
	 * Stall diagnosis: log on every progress-signature change plus a 200-tick
	 * heartbeat while unchanged.
	 */
	private static final java.util.Map<java.util.UUID, String> LAST_SIG =
		new java.util.HashMap<>();

	private static void logProgress(Task task, Progress p, long tick) {
		String sig = p.phase() + "|" + p.target() + "|h=" + (p.handle() == null
			? "-" : p.handle().state()) + "|got=" + p.collectedThisTask()
			+ "|avoid=" + p.avoided().size() + "|scans=" + p.emptyScans()
			+ "|moved=" + p.relocations();
		String prev = LAST_SIG.put(task.taskId(), sig);
		if (!sig.equals(prev) || tick % 200 == 0) {
			LOG.info("[gather] {} t={} {}", task.taskId(), tick, sig);
		}
	}

	/**
	 * 换个地方继续找。方向按罗盘顺序依次尝试，从任务开始的位置向外铺开，所以
	 * 伙伴是有章法地搜一圈，而不是随机乱走。全部方向都试过还没有才如实失败。
	 */
	private StepOutcome beginRelocation(Task task, AvatarEntity avatar,
			Progress progress) {
		int attempt = progress.relocations();
		if (attempt >= MAX_RELOCATIONS) {
			task.setLastErrorCode(progress.avoided().isEmpty()
				? "RESOURCE_EXHAUSTED" : "NO_REACHABLE_TARGET");
			LOG.info("[gather] {} gave up after searching {} location(s)",
				task.taskId(), attempt + 1);
			return StepOutcome.FAILED;
		}
		int[] dir = SEARCH_DIRECTIONS[attempt % SEARCH_DIRECTIONS.length];
		BlockPos origin = progress.searchOrigin() == null
			? avatar.getBlockPos() : progress.searchOrigin();
		BlockPos destination = origin.add(dir[0] * RELOCATE_DISTANCE, 0,
			dir[1] * RELOCATE_DISTANCE);
		MoveHandle handle = avatar.moveTo(new TargetPosition(
			avatar.getWorld().getRegistryKey().getValue().toString(),
			destination.getX() + 0.5, avatar.getY(), destination.getZ() + 0.5),
			MoveOptions.WALK);
		LOG.info("[gather] {} nothing within {} blocks; searching {} (attempt {}/{})",
			task.taskId(), SCAN_RADIUS_H, destination.toShortString(), attempt + 1,
			MAX_RELOCATIONS);
		task.setExecutionState(progress.relocating(handle, attempt + 1));
		return StepOutcome.CONTINUE;
	}

	private void startNavigation(Task task, AvatarEntity avatar, BlockPos target) {
		String dimension = avatar.getWorld().getRegistryKey().getValue().toString();
		BlockPos stand = standableAdjacent(avatar, target);
		if (stand == null) {
			stand = target; // last resort: the cell itself (arrival check still gates)
		}
		TargetPosition t = new TargetPosition(dimension, stand.getX() + 0.5,
			stand.getY(), stand.getZ() + 0.5);
		MoveHandle handle = avatar.moveTo(t, MoveOptions.WALK);
		Progress p = (Progress) task.executionState();
		task.setExecutionState(p.at(p.phase(), p.target(), handle));
	}

	/**
	 * A walkable cell next to the target block (the block's own cell is solid, so
	 * navigating INTO it would fail). Nearest to the avatar wins.
	 */
	static BlockPos standableAdjacent(AvatarEntity avatar, BlockPos target) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction
				.Type.HORIZONTAL) {
			BlockPos candidate = target.offset(dir);
			var state = ((ServerWorld) avatar.getWorld()).getBlockState(candidate);
			var below = ((ServerWorld) avatar.getWorld())
				.getBlockState(candidate.down());
			boolean walkable = state.getCollisionShape(
				avatar.getWorld(), candidate).isEmpty()
				&& !below.getCollisionShape(avatar.getWorld(),
					candidate.down()).isEmpty();
			if (walkable) {
				double distSq = candidate.getSquaredDistance(avatar.getBlockPos());
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = candidate;
				}
			}
		}
		return best;
	}

	/**
	 * 一个真的走得过去、又离 {@code target} 足够近的落脚点。
	 *
	 * <p>{@link #standableAdjacent} 对<b>埋在地里</b>的方块永远返回 null——它的六个邻居
	 * 全是实心。以前调用方在那种情况下退回「朝这个实心方块本身寻路」，寻路当场失败，
	 * 于是伙伴一步都不迈、站在原地隔空挖；离得稍远一点就直接报 NO_REACHABLE_TARGET。
	 * 竖井、地下室这类目标<b>全都</b>是埋着的，所以那条路径等于永远走不通。</p>
	 *
	 * <p>这里补上人会做的那一步：沿着目标正上方往上找第一处站得住人的地面，先走到
	 * 井口去。找不到才退回目标本身。</p>
	 */
	public static BlockPos approachPoint(AvatarEntity avatar, BlockPos target) {
		BlockPos adjacent = standableAdjacent(avatar, target);
		if (adjacent != null) {
			return adjacent;
		}
		ServerWorld world = (ServerWorld) avatar.getWorld();
		BlockPos probe = target.up();
		for (int i = 0; i < SURFACE_PROBE_HEIGHT; i++) {
			boolean footing = !world.getBlockState(probe.down())
				.getCollisionShape(world, probe.down()).isEmpty();
			boolean room = world.getBlockState(probe).getCollisionShape(world, probe)
					.isEmpty()
				&& world.getBlockState(probe.up()).getCollisionShape(world, probe.up())
					.isEmpty();
			if (footing && room) {
				return probe;
			}
			probe = probe.up();
		}
		return target;
	}

	/** 往上找井口最多找这么高。比任何内置蓝图的竖井都深。 */
	private static final int SURFACE_PROBE_HEIGHT = 16;

	/** Nearest matching block within the scan box that is NOT in {@code skip}, or null. */
	static BlockPos scanNearest(ServerWorld world, BlockPos center, Block block,
			Set<BlockPos> skip) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(
				center.add(-SCAN_RADIUS_H, -SCAN_RADIUS_V, -SCAN_RADIUS_H),
				center.add(SCAN_RADIUS_H, SCAN_RADIUS_V, SCAN_RADIUS_H))) {
			if (skip.contains(pos)) {
				continue;
			}
			if (world.getBlockState(pos).isOf(block)) {
				double distSq = pos.getSquaredDistance(center);
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = pos.toImmutable();
				}
			}
		}
		return best;
	}

	/**
	 * The REAL slot holding the best tool for this state (方案 C2). Callers break with
	 * {@code items.stackAt(ref)} and write the used stack back to the same ref so
	 * durability and enchantment wear land on the avatar's actual equipment.
	 */
	static AvatarInventory.SlotRef bestToolSlot(AvatarEntity avatar, BlockState state) {
		return avatar.items().bestToolSlot(state);
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof Progress progress && progress.handle() != null) {
			progress.handle().cancel();
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar != null) {
			avatar.stopMoving();
		}
	}

	@Override
	public dev.squire.server.task.TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object item = snapshot.parameters().get(PARAM_ITEM_ID);
		Object target = snapshot.parameters().get("targetCount");
		if (!(item instanceof String itemId) || !(target instanceof Number count)) {
			return null; // old ambiguous deficit-only tasks fail honestly on restart
		}
		return hasItems(itemId, count.intValue());
	}

	/** Goal condition factory over the REAL inventory (spec section 19). */
	public static dev.squire.server.task.TaskCondition hasItems(String itemId, int count) {
		return dev.squire.server.task.TaskCondition.of(
			ctx -> ctx.body().inventory().countOf(itemId) >= count,
			"inventory holds at least " + count + " " + itemId);
	}
}
