package dev.squire.server.task.executors;

import java.util.UUID;

import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

/**
 * Protects one player from nearby hostiles for a bounded period (spec sections
 * 38/39): the RUNTIME owns threat selection and every swing, entirely on the server
 * thread. The LLM is never consulted during combat ticks — it can only start/stop a
 * guard task through the Tool Gateway like any other action.
 */
public final class GuardTaskExecutor implements dev.squire.server.task.TaskExecutor {

	public static final String TYPE = "guard.owner";

	public static final String PARAM_OWNER_ID = "ownerId";
	public static final String PARAM_RADIUS = "radius";
	public static final String PARAM_DURATION = "durationTicks";

	private static final int DEFAULT_RADIUS = 12;
	private static final int MAX_RADIUS = 32;
	private static final long DEFAULT_DURATION_TICKS = 1200;
	private static final long MAX_DURATION_TICKS = 24000;
	/** Threat rescans are throttled; combat swings happen every tick regardless. */
	private static final int SCAN_INTERVAL_TICKS = 10;
	/** While no threat exists, keep within this distance of the protected player. */
	private static final double ESCORT_REPATH_DISTANCE_SQ = 100.0;

	record Progress(UUID ownerUuid, int radius, long deadlineTick, UUID targetId,
			int lastScanTick) {
	}

	private final RuntimeServices services;

	public GuardTaskExecutor(RuntimeServices services) {
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

	@Override
	public void start(Task task) {
		UUID ownerId = task.stringParam(PARAM_OWNER_ID) == null
			? task.requesterId()
			: UUID.fromString(task.stringParam(PARAM_OWNER_ID));
		int radius = clamp(task.intParam(PARAM_RADIUS) == null ? DEFAULT_RADIUS
			: task.intParam(PARAM_RADIUS), 4, MAX_RADIUS);
		long duration = clampLong(task.stringParam(PARAM_DURATION) == null
			? DEFAULT_DURATION_TICKS
			: Long.parseLong(task.stringParam(PARAM_DURATION)), 100, MAX_DURATION_TICKS);
		task.setExecutionState(new Progress(ownerId, radius,
			services.currentTick() + duration, null, Integer.MIN_VALUE));
	}

	@Override
	public dev.squire.server.task.TaskExecutor.StepOutcome tick(Task task, long tick) {
		if (!(task.executionState() instanceof Progress progress)) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		if (tick >= progress.deadlineTick()) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.WORK_DONE;
		}
		ServerPlayerEntity owner = services.server().getPlayerManager()
			.getPlayer(progress.ownerUuid());
		if (owner == null || !owner.isAlive()
				|| !(owner.getWorld() instanceof ServerWorld ownerWorld)) {
			// nobody left to protect: the duty ends successfully, not as a failure
			return dev.squire.server.task.TaskExecutor.StepOutcome.WORK_DONE;
		}

		LivingEntity target = currentTarget(ownerWorld, progress.targetId());
		boolean rescan = target == null
			|| tick - progress.lastScanTick() >= SCAN_INTERVAL_TICKS;
		if (rescan) {
			target = nearestHostileTo(ownerWorld, owner.getBlockPos(), progress.radius());
			task.setExecutionState(new Progress(progress.ownerUuid(), progress.radius(),
				progress.deadlineTick(),
				target == null ? null : target.getUuid(), (int) tick));
		}

		if (target != null) {
			// the BODY closes distance and swings; result is intentionally ignored —
			// the next tick re-issues until the threat dies or the deadline hits
			avatar.attack(target.getUuid());
			return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
		}

		// no threats: escort formation near the protected player
		if (avatar.squaredDistanceTo(owner) > ESCORT_REPATH_DISTANCE_SQ
				&& tick % 20 == 0) {
			avatar.moveTo(new TargetPosition(
				avatar.getWorld().getRegistryKey().getValue().toString(),
				owner.getX(), owner.getY(), owner.getZ()), MoveOptions.WALK);
		}
		return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
	}

	private static LivingEntity currentTarget(ServerWorld world, UUID targetId) {
		if (targetId == null) {
			return null;
		}
		return world.getEntity(targetId) instanceof LivingEntity living && living.isAlive()
			? living
			: null;
	}

	/** Nearest hostile within {@code radius} of the given position, or null. */
	private static HostileEntity nearestHostileTo(ServerWorld world,
			net.minecraft.util.math.BlockPos center, int radius) {
		Box area = Box.of(net.minecraft.util.math.Vec3d.ofCenter(center),
			radius * 2.0, radius * 2.0, radius * 2.0);
		HostileEntity best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (HostileEntity hostile : world.getEntitiesByClass(HostileEntity.class,
				area, LivingEntity::isAlive)) {
			double distSq = hostile.squaredDistanceTo(center.getX() + 0.5,
				center.getY() + 0.5, center.getZ() + 0.5);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = hostile;
			}
		}
		return best;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static long clampLong(long v, long min, long max) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	public void cancel(Task task) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar != null) {
			avatar.stopMoving();
		}
	}

	@Override
	public dev.squire.server.task.TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		return guardSurvived();
	}

	/** Verification: the avatar must still stand after the guard period. */
	public static dev.squire.server.task.TaskCondition guardSurvived() {
		return dev.squire.server.task.TaskCondition.of(
			ctx -> ctx.body() != null && ctx.body().alive(),
			"avatar still alive after guarding");
	}
}
