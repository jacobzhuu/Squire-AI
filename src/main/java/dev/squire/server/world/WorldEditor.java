package dev.squire.server.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.common.errors.ErrorCode;
import dev.squire.server.security.CapabilityStore;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The ONLY bulk world-mutation service (spec section 45). Every edit:
 *
 * <pre>
 * typed plan → capability re-validation → ProtectionAdapter → impact estimate
 *   → undo journal open → frame-budgeted writes → verify → audit
 * </pre>
 *
 * Writes are spread over ticks (never one giant tick write); each captured cell
 * records its pre-state for exact undo.
 */
public final class WorldEditor {

	private static final Logger LOG = LoggerFactory.getLogger(WorldEditor.class);

	/** Blocks mutated per tick (§45: 禁止单 Tick 大量写方块). */
	public static final int BLOCKS_PER_TICK = 256;

	/** Typed, fully-validated description of one bulk edit. */
	public record EditPlan(Kind kind, Identifier dimension, BoundedRegion region,
			String blockId, UUID agentId, UUID ownerId, UUID taskId, UUID capabilityId) {

		public enum Kind { FILL, SETBLOCK }
	}

	/** Dry-run result: what WOULD change (preview, §45). */
	public record Preview(int regionVolume, int affectedCells,
			ProtectionAdapter.PermissionDecision protection, String rejection) {

		public boolean executable() {
			return rejection == null && protection.allowed();
		}
	}

	public record OperationResult(UUID operationId, boolean accepted, String errorCode,
			String message, int plannedCells) {
	}

	/**
	 * The player-facing preview a HIGH-risk edit must show BEFORE confirmation
	 * (方案 F3). It answers the questions a player actually has: where, how much,
	 * what does it cost me, what of mine is in the way, and can I take it back.
	 */
	public record RichPreview(String toolName, String dimension, BoundedRegion region,
			long volume, int affectedCells, String blockId, int materialsNeeded,
			int affectedEntities, int affectedContainers, int estimatedUndoEntries,
			ProtectionAdapter.PermissionDecision protection, String rejection) {

		public boolean executable() {
			return rejection == null && protection.allowed();
		}

		/** Multi-line Chinese summary shown in chat before /squire confirm. */
		public String describe() {
			StringBuilder text = new StringBuilder();
			text.append("操作：").append(toolName).append('\n');
			text.append("维度：").append(dimension).append('\n');
			text.append("区域：(").append(region.min().getX()).append(", ")
				.append(region.min().getY()).append(", ").append(region.min().getZ())
				.append(") → (").append(region.max().getX()).append(", ")
				.append(region.max().getY()).append(", ").append(region.max().getZ())
				.append(")，共 ").append(volume).append(" 格\n");
			text.append("将改变：").append(affectedCells).append(" 格 → ")
				.append(blockId).append('\n');
			text.append("需要材料：").append(materialsNeeded).append(" 个 ")
				.append(blockId).append('\n');
			text.append("受影响实体：").append(affectedEntities)
				.append("，容器：").append(affectedContainers).append('\n');
			text.append("可撤销条目：").append(estimatedUndoEntries);
			if (!protection.allowed()) {
				text.append("\n保护拒绝：").append(protection.reason());
			}
			if (rejection != null) {
				text.append("\n无法执行：").append(rejection);
			}
			return text.toString();
		}
	}

	/**
	 * Full pre-confirmation scan. Counts what would really change, what living things
	 * and containers sit inside the region, and how big the undo would be — all
	 * without writing a single block.
	 */
	public RichPreview richPreview(ServerWorld world, EditPlan plan, String toolName) {
		Preview basic = preview(world, plan);
		BoundedRegion region = plan.region();
		int entities = 0;
		int containers = 0;
		if (basic.rejection() == null && basic.protection().allowed()) {
			var box = new net.minecraft.util.math.Box(
				region.min().getX(), region.min().getY(), region.min().getZ(),
				region.max().getX() + 1.0, region.max().getY() + 1.0,
				region.max().getZ() + 1.0);
			entities = world.getOtherEntities(null, box, e -> !e.isSpectator()).size();
			for (BlockPos pos : region.cells()) {
				if (world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory) {
					containers++;
				}
			}
		}
		return new RichPreview(toolName, plan.dimension().toString(), region,
			region.volume(), basic.affectedCells(), plan.blockId(),
			basic.affectedCells(), entities, containers, basic.affectedCells(),
			basic.protection(), basic.rejection());
	}

	/** Mutable per-operation job driven from the task executor's tick. */
	final class Job {
		final UUID operationId;
		final UUID taskId;
		final ServerWorld world;
		final BlockState target;
		final Iterator<BlockPos> cells;
		final EditPlan plan;
		final CapabilityStore capabilities;
		/** Total cells this operation was licensed for; the impact never grows past it. */
		final int plannedCells;
		int applied = 0;
		/** Set when a per-batch re-check fails; the executor surfaces it as the error. */
		String violation;

		Job(UUID operationId, UUID taskId, ServerWorld world, BlockState target,
				List<BlockPos> cells, EditPlan plan, CapabilityStore capabilities) {
			this.operationId = operationId;
			this.taskId = taskId;
			this.world = world;
			this.target = target;
			this.plannedCells = cells.size();
			this.cells = cells.iterator();
			this.plan = plan;
			this.capabilities = capabilities;
		}

		/**
		 * 方案 F5：每一批写入前重新验证 capability。撤销、过期或范围变化必须
		 * 立刻停下剩余的写入，而不是只在第一批时检查一次。
		 */
		private boolean licenceStillValid(long nowTick) {
			if (capabilities == null || plan.capabilityId() == null) {
				return true; // runtime-internal edit: no capability to re-check
			}
			// impact 是这次操作被授权的总格数，不是这一批的大小——用批大小去比
			// maxImpact 会让每一次批量编辑都在第一批就"越界"。
			var problem = capabilities.validateBoundUse(plan.capabilityId(), taskId,
				plan.agentId(), capabilityToolName(plan), plan.dimension(),
				plan.region(), Math.max(1, plannedCells), nowTick);
			if (problem.isPresent()) {
				violation = problem.get();
				LOG.warn("[worldedit] op {} stopped mid-flight: {}", operationId, violation);
				return false;
			}
			return true;
		}

		/** @return true when every planned cell has been written (or the job stopped). */
		boolean advance(UndoJournal journal, long nowTick) {
			if (!licenceStillValid(nowTick)) {
				return true; // finished (aborted) — already-written cells stay undoable
			}
			for (int i = 0; i < BLOCKS_PER_TICK && cells.hasNext(); i++) {
				BlockPos pos = cells.next();
				BlockState old = world.getBlockState(pos);
				if (old.equals(target)) {
					continue; // nothing to change — not journaled, not written
				}
				NbtCompound oldNbt = null;
				var be = world.getBlockEntity(pos);
				if (be != null) {
					oldNbt = be.createNbtWithId();
				}
				world.setBlockState(pos, target, 3);
				journal.record(new UndoJournal.Entry(operationId, taskId,
					pos.toImmutable(), old, oldNbt, target, nowTick));
				applied++;
				dev.squire.server.metrics.SquireMetrics m = metrics;
				if (m != null) {
					m.inc(dev.squire.server.metrics.SquireMetrics.Key.WORLDEDIT_BLOCKS);
				}
			}
			return !cells.hasNext();
		}
	}

	private final UndoJournal journals;
	private volatile ProtectionAdapter protection;
	private volatile dev.squire.server.metrics.SquireMetrics metrics;
	private final Map<UUID, Job> liveJobs = new ConcurrentHashMap<>();
	private final Map<UUID, Boolean> completedOperations = new ConcurrentHashMap<>();
	/** Violations survive the job so the executor can still report why it stopped. */
	private final Map<UUID, String> abortedViolations = new ConcurrentHashMap<>();

	public WorldEditor(UndoJournal journals, ProtectionAdapter protection) {
		this.journals = journals;
		this.protection = protection;
	}

	public void setProtection(ProtectionAdapter adapter) {
		this.protection = adapter;
	}

	/** §75 observability seam (optional; counters stay null-safe). */
	public void setMetrics(dev.squire.server.metrics.SquireMetrics value) {
		this.metrics = value;
	}

	public UndoJournal journal() {
		return journals;
	}

	// ------------------------------------------------------------------ planning

	/**
	 * Dry scan: counts cells that would actually change and checks protection.
	 * Never mutates anything.
	 */
	public Preview preview(ServerWorld world, EditPlan plan) {
		if (!plan.dimension().equals(world.getRegistryKey().getValue())) {
			return new Preview(0, 0, ProtectionAdapter.PermissionDecision.allow(),
				"dimension mismatch");
		}
		if (plan.region().volume() > BoundedRegion.MAX_VOLUME) {
			return new Preview((int) plan.region().volume(), 0,
				ProtectionAdapter.PermissionDecision.allow(),
				ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire() + ": region too large");
		}
		var protectionVerdict = protection.canEditRegion(world, plan.region(), plan.ownerId());
		if (!protectionVerdict.allowed()) {
			return new Preview((int) plan.region().volume(), 0, protectionVerdict, null);
		}
		BlockState target = parseBlock(plan);
		if (target == null) {
			return new Preview((int) plan.region().volume(), 0,
				ProtectionAdapter.PermissionDecision.allow(), "unknown block " + plan.blockId());
		}
		int affected = 0;
		for (BlockPos pos : plan.region().cells()) {
			if (!world.getBlockState(pos).equals(target)) {
				affected++;
			}
		}
		return new Preview((int) plan.region().volume(), affected, protectionVerdict, null);
	}

	/**
	 * Validate a plan against the capability + protection and OPEN a framed job.
	 * The caller (task executor) then drives {@link #tick} until done.
	 */
	public synchronized OperationResult begin(ServerWorld world, EditPlan plan,
			CapabilityStore capabilities, long nowTick) {
		if (!plan.dimension().equals(world.getRegistryKey().getValue())) {
			return new OperationResult(null, false, ErrorCode.WORLD_CHANGED.wire(),
				"agent is in another dimension", 0);
		}
		long volume = plan.region().volume();
		if (volume > BoundedRegion.MAX_VOLUME) {
			return new OperationResult(null, false, ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire(),
				"region volume " + volume + " exceeds ceiling", 0);
		}
		BlockState target = parseBlock(plan);
		if (target == null) {
			return new OperationResult(null, false, ErrorCode.INVALID_ARGUMENT.wire(),
				"unknown block " + plan.blockId(), 0);
		}
		// capability: exact numbers this time (§30 — 不可越界 / maxImpact)
		if (capabilities != null && plan.capabilityId() != null) {
			Optional<String> violation = capabilities.validateForUse(plan.capabilityId(),
				plan.agentId(), capabilityToolName(plan), plan.dimension(), plan.region(),
				(int) Math.max(1L, Math.min(volume, Integer.MAX_VALUE)), nowTick);
			if (violation.isPresent()) {
				return new OperationResult(null, false,
					violation.get().startsWith(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire())
						? ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire()
						: ErrorCode.CAPABILITY_REQUIRED.wire(),
					violation.get(), 0);
			}
			capabilities.consume(plan.capabilityId(), plan.taskId());
		}
		var protectionVerdict = protection.canEditRegion(world, plan.region(), plan.ownerId());
		if (!protectionVerdict.allowed()) {
			return new OperationResult(null, false, ErrorCode.PERMISSION_DENIED.wire(),
				protectionVerdict.reason(), 0);
		}
		if (!journals.canJournal((int) volume)) {
			// spec §52: 高风险世界编辑如果无法生成 Undo → 拒绝执行
			return new OperationResult(null, false, ErrorCode.RESOURCE_EXHAUSTED.wire(),
				"operation too large to journal for undo", 0);
		}
		List<BlockPos> cells = new ArrayList<>();
		for (BlockPos pos : plan.region().cells()) {
			cells.add(pos.toImmutable());
		}
		UUID operationId = journals.begin(plan.taskId(), plan.ownerId(),
			plan.dimension().toString(),
			plan.kind().name().toLowerCase(java.util.Locale.ROOT) + " "
				+ plan.blockId() + " " + plan.region(), nowTick);
		liveJobs.put(operationId,
			new Job(operationId, plan.taskId(), world, target, cells, plan, capabilities));
		LOG.info("[worldedit] op {} {} {} cells={} block={}", operationId, plan.kind(),
			plan.region(), cells.size(), plan.blockId());
		return new OperationResult(operationId, true, null, null, cells.size());
	}

	/** Advance every live job by one frame. @return operations finished this tick. */
	public List<UUID> tick(long nowTick) {
		List<UUID> finishedOps = new ArrayList<>();
		for (Job job : liveJobs.values().toArray(Job[]::new)) {
			if (job.advance(journals, nowTick)) {
				liveJobs.remove(job.operationId);
				if (job.violation != null) {
					abortedViolations.put(job.operationId, job.violation);
				}
				completedOperations.put(job.operationId, Boolean.TRUE);
				// 方案 F4：写完立刻落盘，重启后这次操作仍然可以撤销
				journals.close(job.operationId);
				finishedOps.add(job.operationId);
				LOG.info("[worldedit] op {} finished, {} cells changed",
					job.operationId, job.applied);
			}
		}
		return finishedOps;
	}

	public boolean isComplete(UUID operationId) {
		return completedOperations.containsKey(operationId);
	}

	/** Non-null when a per-batch capability re-check stopped the operation (方案 F5). */
	public String violationOf(UUID operationId) {
		Job job = liveJobs.get(operationId);
		String live = job == null ? null : job.violation;
		return live != null ? live : abortedViolations.get(operationId);
	}

	public int appliedCells(UUID operationId) {
		Job job = liveJobs.get(operationId);
		return job == null ? 0 : job.applied;
	}

	/** Cancel an unfinished job; already-written cells stay undoable via journal. */
	public void abort(UUID operationId) {
		liveJobs.remove(operationId);
		journals.abandon(operationId);
	}

	private static BlockState parseBlock(EditPlan plan) {
		try {
			var id = net.minecraft.util.Identifier.tryParse(plan.blockId());
			if (id == null) {
				return null;
			}
			return net.minecraft.registry.Registries.BLOCK.getOrEmpty(id)
				.map(net.minecraft.block.Block::getDefaultState)
				.orElse(Blocks.AIR.getDefaultState());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Stable capability tool names shared by the issuing side (§30 allowedTools). */
	public static String capabilityToolName(EditPlan plan) {
		return capabilityToolNameForKind(plan.kind());
	}

	/** @return the §30 allowedTools name for one plan kind ("fill" / "setblock"). */
	public static String capabilityToolNameForKind(EditPlan.Kind kind) {
		return "minecraft.command." + kind.name().toLowerCase(java.util.Locale.ROOT);
	}
}
