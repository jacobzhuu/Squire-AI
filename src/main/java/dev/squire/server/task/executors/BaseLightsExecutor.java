package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.world.UndoJournal;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;

/**
 * 真实的基地灯光开关（方案 G2）。
 *
 * <p>灯光锚点是 <b>拉杆</b>，不是红石灯。直接把 {@code RedstoneLampBlock.LIT} 设成
 * true 是骗人的：香草的 scheduledTick 发现没有红石信号就会立刻把它关掉。真正能长期
 * 保持的状态是开关本身，所以这里翻的是拉杆，并像玩家点击那样做邻居更新——灯是被
 * 真的红石信号点亮的。</p>
 *
 * <p>扫描半径有界，区域来自可信的 BASE/HOME 位置记忆；每一次写入都过 Protection，
 * 并进 {@link UndoJournal}，所以开灯同样可以撤销。世界里不会多出任何命令方块。</p>
 */
public final class BaseLightsExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BaseLightsExecutor.class);

	public static final String TYPE = "base.lights.set";

	/** true = 开灯，false = 关灯。 */
	public static final String PARAM_ON = "on";
	/** 可选：显式基地坐标；缺省时用 owner 的 BASE/HOME 记忆。 */
	public static final String PARAM_X = "x";
	public static final String PARAM_Y = "y";
	public static final String PARAM_Z = "z";
	public static final String PARAM_RADIUS = "radius";

	/** 扫描半径上限——绝不无界扫描（方案 G2）。 */
	public static final int MAX_RADIUS = 16;
	public static final int DEFAULT_RADIUS = 8;

	private final RuntimeServices services;

	public BaseLightsExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void start(Task task) {
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		boolean on = Boolean.TRUE.equals(task.parameters().get(PARAM_ON))
			|| "true".equalsIgnoreCase(task.stringParam(PARAM_ON));
		GlobalPos base = resolveBase(task, world);
		if (base == null) {
			task.setLastErrorCode("NO_BASE_MEMORY");
			return StepOutcome.FAILED;
		}
		if (!world.getRegistryKey().getValue().toString()
				.equals(base.getDimension().getValue().toString())) {
			task.setLastErrorCode("WORLD_CHANGED");
			return StepOutcome.FAILED;
		}
		Integer radiusParam = task.intParam(PARAM_RADIUS);
		int radius = Math.min(MAX_RADIUS,
			radiusParam == null ? DEFAULT_RADIUS : Math.max(1, radiusParam));

		List<BlockPos> anchors = findLightSwitches(world, base.getPos(), radius);
		if (anchors.isEmpty()) {
			// 没有开关就如实说没有——绝不用一句日志假装灯亮了
			task.setLastErrorCode("NO_LIGHT_ANCHOR");
			return StepOutcome.FAILED;
		}

		UndoJournal journal = journal();
		UUID operationId = journal == null ? null
			: journal.begin(task.taskId(), task.requesterId(),
				world.getRegistryKey().getValue().toString(),
				(on ? "lights on" : "lights off") + " @ " + base.getPos().toShortString(),
				tick);
		int flipped = 0;
		int blocked = 0;
		for (BlockPos pos : anchors) {
			BlockState state = world.getBlockState(pos);
			if (state.get(LeverBlock.POWERED) == on) {
				continue; // already in the wanted position
			}
			var decision = services.protection().canInteract(world, pos, avatar.ownerId());
			if (!decision.allowed()) {
				blocked++;
				continue;
			}
			BlockState next = state.with(LeverBlock.POWERED, on);
			if (journal != null && operationId != null) {
				journal.record(new UndoJournal.Entry(operationId, task.taskId(),
					pos.toImmutable(), state, null, next, tick));
			}
			world.setBlockState(pos, next, Block.NOTIFY_ALL);
			updateRedstoneNeighbors(world, pos, next);
			flipped++;
		}
		if (journal != null && operationId != null) {
			journal.close(operationId);
		}
		LOG.info("[lights] {} flipped {} switch(es) near {} (blocked {})",
			task.taskId(), flipped, base.getPos().toShortString(), blocked);
		task.setExecutionState(flipped);
		if (flipped == 0 && blocked > 0) {
			task.setLastErrorCode("PROTECTED_REGION");
			return StepOutcome.FAILED;
		}
		return StepOutcome.WORK_DONE;
	}

	/**
	 * Vanilla lever behaviour: after changing POWERED the block AND the surface it is
	 * mounted on must be told, or the lamp next door never sees the signal.
	 */
	private static void updateRedstoneNeighbors(ServerWorld world, BlockPos pos,
			BlockState state) {
		world.updateNeighborsAlways(pos, state.getBlock());
		world.updateNeighborsAlways(pos.offset(mountedOn(state)), state.getBlock());
	}

	/**
	 * The block face this lever is attached to. {@code WallMountedBlock.getDirection}
	 * is protected, so the same FACE/FACING mapping is spelled out here rather than
	 * skipping the update that makes the lamp actually see the signal.
	 */
	private static Direction mountedOn(BlockState state) {
		return switch (state.get(net.minecraft.state.property.Properties.WALL_MOUNT_LOCATION)) {
			case CEILING -> Direction.UP;
			case FLOOR -> Direction.DOWN;
			default -> state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING)
				.getOpposite();
		};
	}

	/** Levers within a BOUNDED radius of the base — never a world-wide scan. */
	public static List<BlockPos> findLightSwitches(ServerWorld world, BlockPos center,
			int radius) {
		List<BlockPos> found = new ArrayList<>();
		int bounded = Math.min(MAX_RADIUS, Math.max(1, radius));
		for (BlockPos pos : BlockPos.iterate(
				center.add(-bounded, -bounded, -bounded),
				center.add(bounded, bounded, bounded))) {
			if (world.getBlockState(pos).isOf(Blocks.LEVER)) {
				found.add(pos.toImmutable());
			}
		}
		return found;
	}

	private GlobalPos resolveBase(Task task, ServerWorld world) {
		Integer x = task.intParam(PARAM_X);
		Integer y = task.intParam(PARAM_Y);
		Integer z = task.intParam(PARAM_Z);
		if (x != null && y != null && z != null) {
			return GlobalPos.create(world.getRegistryKey(), new BlockPos(x, y, z));
		}
		var runtime = dev.squire.server.runtime.SquireRuntime.get();
		if (runtime == null) {
			return null;
		}
		// 可信来源：玩家显式记下的 HOME/基地
		var resolved = runtime.locations().resolve(task.requesterId(), "基地");
		if (resolved.empty()) {
			resolved = runtime.locations().resolve(task.requesterId(), "家");
		}
		return resolved.unique() ? resolved.best().pos() : null;
	}

	private UndoJournal journal() {
		var editor = services.worldEditor();
		return editor == null ? null : editor.journal();
	}

	@Override
	public void cancel(Task task) {
	}

	/**
	 * Goal condition over REAL redstone state: every reachable light switch near the
	 * base sits in the requested position. Executor counters are never the evidence.
	 */
	public static TaskCondition lightsAre(RuntimeServices services, boolean on,
			String dimensionId, BlockPos center, int radius) {
		return TaskCondition.of(ctx -> {
			AvatarEntity avatar = services.avatar(ctx.agentId());
			if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)
					|| !world.getRegistryKey().getValue().toString().equals(dimensionId)) {
				return false;
			}
			List<BlockPos> anchors = findLightSwitches(world, center, radius);
			if (anchors.isEmpty()) {
				return false;
			}
			for (BlockPos pos : anchors) {
				BlockState state = world.getBlockState(pos);
				if (state.get(LeverBlock.POWERED) == on) {
					continue;
				}
				if (!services.protection().canInteract(world, pos, avatar.ownerId())
						.allowed()) {
					continue; // legitimately untouched: protection said no
				}
				return false;
			}
			return true;
		}, "light switches near " + center.toShortString() + " are "
			+ (on ? "ON" : "OFF"));
	}
}
