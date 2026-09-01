package dev.squire.server.task.executors;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.api.body.AgentBody;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.world.BoundedRegion;
import dev.squire.server.world.WorldEditor;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Drives one {@link WorldEditor} operation as a task (spec sections 45/52): the
 * editor validates capability + protection + undo-ability in {@link #start}, writes
 * frame-budgeted cells per tick, and the goal condition re-scans the region so the
 * GoalVerifier — not the executor — completes the task (ADR-013).
 */
public final class WorldEditExecutor implements dev.squire.server.task.TaskExecutor {

	public static final String TYPE = "world.edit";

	public static final String PARAM_KIND = "kind"; // fill | setblock
	public static final String PARAM_BLOCK_ID = "blockId";
	public static final String PARAM_CAPABILITY_ID = "capabilityId";
	// corners arrive as x1,y1,z1,x2,y2,z2 (setblock = all six equal)

	private static final Logger LOG = LoggerFactory.getLogger(WorldEditExecutor.class);

	private final RuntimeServices services;

	public WorldEditExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void start(Task task) {
		WorldEditor editor = services.worldEditor();
		if (editor == null) {
			task.setLastErrorCode("INTERNAL_ERROR");
			throw new IllegalStateException("no WorldEditor configured");
		}
		String kind = task.stringParam(PARAM_KIND);
		String blockId = task.stringParam(PARAM_BLOCK_ID);
		if (!"fill".equals(kind) && !"setblock".equals(kind)) {
			task.setLastErrorCode("INVALID_ARGUMENT");
			throw new IllegalArgumentException("unknown world.edit kind: " + kind);
		}
		BoundedRegion region = regionOf(task);
		UUID capabilityId = parseCapability(task.stringParam(PARAM_CAPABILITY_ID));
		var avatar = services.avatar(task.agentId());
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			throw new IllegalStateException("agent body unavailable");
		}
		WorldEditor.EditPlan plan = new WorldEditor.EditPlan(
			"setblock".equals(kind)
				? WorldEditor.EditPlan.Kind.SETBLOCK : WorldEditor.EditPlan.Kind.FILL,
			world.getRegistryKey().getValue(), region, blockId,
			task.agentId(), task.requesterId(), task.taskId(), capabilityId);
		WorldEditor.OperationResult result =
			editor.begin(world, plan, services.capabilities(), services.currentTick());
		if (!result.accepted()) {
			task.setLastErrorCode(result.errorCode());
			throw new IllegalStateException(result.message());
		}
		task.setExecutionState(result.operationId());
		LOG.info("[worldedit] task {} drives op {}", task.taskId(), result.operationId());
	}

	@Override
	public dev.squire.server.task.TaskExecutor.StepOutcome tick(Task task, long tick) {
		WorldEditor editor = services.worldEditor();
		if (!(task.executionState() instanceof UUID operationId)) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		editor.tick(tick); // advances ALL jobs; ours completes when its cells drain
		if (!editor.isComplete(operationId)) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
		}
		// 方案 F5：capability 在完成/失败时立刻撤销，绝不留着可以再用
		revokeCapability(task);
		String violation = editor.violationOf(operationId);
		if (violation != null) {
			task.setLastErrorCode(violation.startsWith("capability_scope_violation")
				? "CAPABILITY_SCOPE_VIOLATION" : "CAPABILITY_REQUIRED");
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		return dev.squire.server.task.TaskExecutor.StepOutcome.WORK_DONE;
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof UUID operationId && services.worldEditor() != null) {
			services.worldEditor().abort(operationId);
		}
		revokeCapability(task);
	}

	/** Revoke the scoped licence the moment this operation stops needing it (§30/F5). */
	private void revokeCapability(Task task) {
		UUID capabilityId = parseCapability(task.stringParam(PARAM_CAPABILITY_ID));
		if (capabilityId != null && services.capabilities() != null) {
			services.capabilities().revoke(capabilityId);
		}
	}

	private static BoundedRegion regionOf(Task task) {
		Map<String, Object> params = task.parameters();
		return BoundedRegion.ofCorners(
			asInt(params.get("x1")), asInt(params.get("y1")), asInt(params.get("z1")),
			asInt(params.get("x2")), asInt(params.get("y2")), asInt(params.get("z2")));
	}

	private static int asInt(Object o) {
		if (o instanceof Number n) {
			return n.intValue();
		}
		if (o instanceof String s) {
			try {
				return Integer.parseInt(s.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("bad coordinate: " + s);
			}
		}
		throw new IllegalArgumentException("missing coordinate");
	}

	private static UUID parseCapability(String raw) {
		if (raw == null || raw.isBlank()) {
			return null; // only possible for RUNTIME-internal edits, never model calls
		}
		return UUID.fromString(raw);
	}

	/**
	 * Honest completion condition: the region REALLY matches the target block now.
	 * Scans at most {@code MAX_VOLUME} cells — bounded by construction.
	 */
	public static TaskCondition regionMatches(String dimensionKey, BoundedRegion region,
			String blockId) {
		return TaskCondition.of(ctx -> {
			AgentBody body = ctx.body();
			if (!(body instanceof AvatarEntity avatar)
					|| !(avatar.getWorld() instanceof ServerWorld world)) {
				return false;
			}
			if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) {
				return false;
			}
			var id = Identifier.tryParse(blockId);
			if (id == null) {
				return false;
			}
			Block target = Registries.BLOCK.get(id);
			for (BlockPos pos : region.cells()) {
				if (!world.getBlockState(pos).isOf(target)) {
					return false;
				}
			}
			return true;
		}, "region " + region + " is " + blockId);
	}
}
