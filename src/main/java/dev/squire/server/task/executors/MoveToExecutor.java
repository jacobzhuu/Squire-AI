package dev.squire.server.task.executors;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.task.Task;

/**
 * Walks the body to a target position (spec section 33 navigation contract).
 * Progress lives in {@code executionState} as a small record; arrival is judged by
 * the MoveHandle and re-checked by the goal condition against the real position.
 */
public final class MoveToExecutor implements dev.squire.server.task.TaskExecutor {

	/** Per-task progress; also carries the target for cancel(). */
	public record Progress(MoveHandle handle, TargetPosition target) {
	}

	private final RuntimeServices services;

	public static final String TYPE = "navigation.move_to";

	public MoveToExecutor(RuntimeServices services) {
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
		dev.squire.server.body.avatar.AvatarEntity avatar =
			services.avatar(task.agentId());
		if (avatar == null) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("no avatar for task");
		}
		String dimension = avatar.getWorld().getRegistryKey().getValue().toString();
		Object xObj = task.parameters().get("x");
		Object yObj = task.parameters().get("y");
		Object zObj = task.parameters().get("z");
		if (!(xObj instanceof Number xn) || !(yObj instanceof Number yn)
				|| !(zObj instanceof Number zn)) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalStateException("move_to needs numeric x/y/z");
		}
		TargetPosition target = new TargetPosition(dimension,
			xn.doubleValue(), yn.doubleValue(), zn.doubleValue());
		MoveHandle handle = avatar.moveTo(target, MoveOptions.WALK);
		task.setExecutionState(new Progress(handle, target));
		if (handle.state() == MoveHandle.State.FAILED) {
			// 具体原因优先：「超出待命范围」是玩家改个锚点就能解决的事，
			// 告诉他「路被挡住」只会让他去找一堵不存在的墙。
			String reason = handle.errorCode();
			task.setLastErrorCode(reason == null ? "PATH_NOT_FOUND" : reason);
		}
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		Progress progress = (Progress) task.executionState();
		if (progress == null) {
			return StepOutcome.FAILED;
		}
		switch (progress.handle().state()) {
			case ARRIVED -> {
				return StepOutcome.WORK_DONE;
			}
			case FAILED -> {
				String reason = progress.handle().errorCode();
				task.setLastErrorCode(reason == null ? "AGENT_STUCK" : reason);
				progress.handle().cancel();
				return StepOutcome.FAILED;
			}
			case CANCELLED -> {
				task.setLastErrorCode("CANCELLED");
				return StepOutcome.FAILED;
			}
			default -> {
				return StepOutcome.CONTINUE;
			}
		}
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof Progress progress) {
			progress.handle().cancel();
			dev.squire.server.body.avatar.AvatarEntity avatar =
				services.avatar(task.agentId());
			if (avatar != null) {
				avatar.stopMoving();
			}
		}
	}

	@Override
	public dev.squire.server.task.TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object x = snapshot.parameters().get("x");
		Object y = snapshot.parameters().get("y");
		Object z = snapshot.parameters().get("z");
		if (!(x instanceof Number xn) || !(y instanceof Number yn)
				|| !(z instanceof Number zn)) {
			return null;
		}
		Object within = snapshot.parameters().get("arriveWithinSq");
		double withinSq = within instanceof Number n ? n.doubleValue()
			: dev.squire.api.body.MoveOptions.WALK.arriveWithin();
		return nearTarget(xn.doubleValue(), yn.doubleValue(), zn.doubleValue(), withinSq);
	}

	/** Goal condition factory: true when the body stands within arriveWithin of target. */
	public static dev.squire.server.task.TaskCondition nearTarget(double x, double y, double z,
			double withinSq) {
		return dev.squire.server.task.TaskCondition.of(ctx -> {
			var s = ctx.body().snapshotState();
			double dx = s.x() - x;
			double dy = s.y() - y;
			double dz = s.z() - z;
			return dx * dx + dy * dy + dz * dz <= withinSq;
		}, "stand within " + Math.sqrt(withinSq) + " blocks of (" + x + "," + y + "," + z + ")");
	}
}
