package dev.squire.server.cbp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import dev.squire.server.security.CapabilityStore;
import dev.squire.server.security.ConfirmationService;
import dev.squire.server.tool.AuditLog;
import dev.squire.server.world.ProtectionAdapter;
import dev.squire.server.world.UndoJournal;

/**
 * Materializes an explicitly requested {@link CbpSpec} into real command blocks
 * through the FULL §50 pipeline — the ONLY path in the mod that ever places a
 * command block (spec §89; ADR-009 keeps automation free of them).
 *
 * <p>Pipeline: spec validation → workspace containment → impact preview → owner
 * confirmation (TTL'd, single-use) → capability issue+consume → undo journal →
 * frame-budgeted placement → verification → registry registration.</p>
 *
 * <ul>
 *   <li>Unconfirmed specs NEVER execute (nothing is written until
 *       {@link ConfirmationService#matchesAndConsume} succeeds).</li>
 *   <li>Entries outside the owner's workspace are refused at plan time.</li>
 *   <li>Every write captures pre-state in the {@link UndoJournal} first, so
 *       removal restores exactly what was there before.</li>
 *   <li>{@code enabled} ships OFF (§94).</li>
 * </ul>
 */
public final class CbpMaterializer {

	private static final Logger LOG = LoggerFactory.getLogger(CbpMaterializer.class);

	/** Confirmation/audit/capability tool identity for this pipeline. */
	public static final String TOOL = "cbp.materialize";

	/** Frame budget: command-block placements per tick (§50 placement step). */
	public static final int BLOCKS_PER_TICK = 16;

	public interface WorldLookup {
		ServerWorld worldFor(String dimensionKey);
	}

	public interface Notifier {
		void send(UUID ownerId, String message);
	}

	/** Result of planning a spec; {@code confirmId} present only when awaiting confirmation. */
	public record PlanResult(boolean ok, String message, UUID confirmId, int blocks,
			dev.squire.server.world.BoundedRegion footprint) {

		static PlanResult ok(String message, UUID confirmId, int blocks,
				dev.squire.server.world.BoundedRegion footprint) {
			return new PlanResult(true, message, confirmId, blocks, footprint);
		}

		static PlanResult refuse(String message) {
			return new PlanResult(false, message, null, 0, null);
		}
	}

	private record Pending(UUID confirmId, CbpSpec spec,
			dev.squire.server.world.BoundedRegion footprint, long issuedAtTick) {
	}

	private static final class Placement {
		final UUID projectId;
		final UUID ownerId;
		final UUID agentId;
		final UUID operationId;
		final UUID capabilityId;
		final CbpSpec spec;
		final String dimensionKey;
		final List<BlockPos> positions;
		int nextIndex = 0;

		Placement(UUID projectId, UUID ownerId, UUID agentId, UUID operationId,
				UUID capabilityId, CbpSpec spec, String dimensionKey,
				List<BlockPos> positions) {
			this.projectId = projectId;
			this.ownerId = ownerId;
			this.agentId = agentId;
			this.operationId = operationId;
			this.capabilityId = capabilityId;
			this.spec = spec;
			this.dimensionKey = dimensionKey;
			this.positions = positions;
		}
	}

	private final CbpWorkspace workspace;
	private final CapabilityStore capabilities;
	private final ConfirmationService confirmations;
	private final UndoJournal undo;
	private final CbpRegistry registry;
	private final AuditLog audit;
	private final WorldLookup worlds;
	private final Notifier notifier;
	/** Protection seam consulted before the first write (same adapter as WorldEditor). */
	private volatile ProtectionAdapter protection;
	private volatile boolean enabled = false;

	private final LinkedHashMap<UUID, Pending> pendings = new LinkedHashMap<>();
	private final LinkedHashMap<UUID, Placement> placements = new LinkedHashMap<>();

	public CbpMaterializer(CbpWorkspace workspace, CapabilityStore capabilities,
			ConfirmationService confirmations, UndoJournal undo, CbpRegistry registry,
			AuditLog audit, WorldLookup worlds, Notifier notifier) {
		this.workspace = workspace;
		this.capabilities = capabilities;
		this.confirmations = confirmations;
		this.undo = undo;
		this.registry = registry;
		this.audit = audit;
		this.worlds = worlds;
		this.notifier = notifier;
	}

	public void setProtection(ProtectionAdapter adapter) {
		this.protection = adapter;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean value) {
		this.enabled = value;
		LOG.info("[cbp] materialization {}", value ? "ENABLED" : "disabled");
	}

	public int pendingCount() {
		return pendings.size();
	}

	// ------------------------------------------------------------------ §50 steps 1-6

	/**
	 * Validates a spec against shape rules and the owner's workspace, computes the
	 * impact preview and issues the owner's confirmation request. NOTHING touches
	 * the world here.
	 */
	public synchronized PlanResult plan(CbpSpec spec, long nowTick) {
		if (!enabled) {
			return PlanResult.refuse("materialized command blocks are disabled "
				+ "on this server");
		}
		if (spec == null) {
			return PlanResult.refuse("no spec");
		}
		List<String> errors = spec.validate();
		if (!errors.isEmpty()) {
			return PlanResult.refuse("invalid spec: " + String.join("; ", errors));
		}
		Optional<CbpWorkspace.Area> area = workspace.areaOf(spec.ownerId());
		if (area.isEmpty()) {
			return PlanResult.refuse("no workspace set — use /squire workspace set "
				+ "<from> <to> first; squire never builds in unknown areas");
		}
		for (CbpSpec.Entry e : spec.entries()) {
			if (!area.get().region().contains(e.pos())) {
				return PlanResult.refuse("position " + e.pos().toShortString()
					+ " is outside your workspace — CBP only builds there");
			}
		}
		dev.squire.server.world.BoundedRegion footprint = spec.footprint();
		String fingerprint = fingerprint(spec);
		ConfirmationService.Request request =
			confirmations.issue(spec.ownerId(), spec.agentId(), TOOL, fingerprint,
				nowTick);
		pendings.put(request.confirmId(),
			new Pending(request.confirmId(), spec, footprint, nowTick));
		audit.record(nowTick, "cbp", spec.name() + "/plan", true,
			spec.entries().size() + " blocks in " + footprint);
		return PlanResult.ok("[Squire] CBP '" + spec.name() + "' planned: "
			+ spec.entries().size() + " command block(s) in region " + footprint
			+ ". Confirm within 30s: /squire confirm " + request.confirmId(),
			request.confirmId(), spec.entries().size(), footprint);
	}

	// ------------------------------------------------------------------ §50 steps 7-11

	/** Advances expiries, confirmations and frame-budgeted placement. */
	public synchronized void tick(long nowTick) {
		if (!enabled) {
			return;
		}
		// expiry: dead pendings vanish silently (the confirmation itself also expires)
		pendings.values().removeIf(p -> {
			boolean dead = nowTick - p.issuedAtTick()
				>= ConfirmationService.DEFAULT_TTL_TICKS;
			if (dead) {
				audit.record(nowTick, "cbp", p.spec.name() + "/expire", true,
					"confirmation never given");
			}
			return dead;
		});
		// confirmation poll → begin placement (steps 7-8)
		for (Pending p : List.copyOf(pendings.values())) {
			if (!confirmations.matchesAndConsume(p.spec.ownerId(), TOOL,
					fingerprint(p.spec), nowTick)) {
				continue;
			}
			pendings.remove(p.confirmId());
			beginPlacement(p, nowTick);
		}
		advancePlacements(nowTick);
	}

	/**
	 * 方案 H2：{@code cbp.materialize_project} 的入口。与 {@link #tick} 的确认轮询
	 * 走的是同一条放置路径，只是触发方式不同——两者都必须先真正消费掉一条属于
	 * 该玩家的确认，绝不存在第二种"绕过确认直接放块"的方式。
	 */
	public synchronized Access materializeConfirmed(UUID confirmId, UUID requesterId,
			long nowTick) {
		if (!enabled) {
			return Access.deny("materialized command blocks are disabled on this server");
		}
		Pending pending = pendings.get(confirmId);
		if (pending == null) {
			return Access.deny("unknown or already-used plan id");
		}
		if (!pending.spec().ownerId().equals(requesterId)) {
			LOG.warn("[cbp] player {} tried to materialize {} owned by {}", requesterId,
				confirmId, pending.spec().ownerId());
			return Access.deny("that plan belongs to another player");
		}
		if (!confirmations.matchesAndConsume(requesterId, TOOL,
				fingerprint(pending.spec()), nowTick)) {
			return Access.deny("this plan has not been confirmed yet — run /squire confirm "
				+ confirmId);
		}
		pendings.remove(confirmId);
		beginPlacement(pending, nowTick);
		return Access.ok("placing " + pending.spec().entries().size()
			+ " block(s) for '" + pending.spec().name() + "'");
	}

	/** The spec still awaiting confirmation under this id, for previews. */
	public synchronized Optional<CbpSpec> pendingSpec(UUID confirmId) {
		Pending pending = pendings.get(confirmId);
		return pending == null ? Optional.empty() : Optional.of(pending.spec());
	}

	private void beginPlacement(Pending p, long nowTick) {
		CbpSpec spec = p.spec();
		ServerWorld world = worlds.worldFor(workspace.areaOf(spec.ownerId())
			.map(a -> a.dimension()).orElse(""));
		if (world == null) {
			fail(spec, nowTick, "dimension not loaded", "NO_DIMENSION");
			return;
		}
		ProtectionAdapter adapter = protection;
		if (adapter != null) {
			ProtectionAdapter.PermissionDecision decision =
				adapter.canEditRegion(world, p.footprint(), spec.ownerId());
			if (!decision.allowed()) {
				fail(spec, nowTick, "protected area: " + decision.reason(),
					"PROTECTED");
				return;
			}
		}
		if (!undo.canJournal(spec.entries().size())) {
			fail(spec, nowTick, "project too large to journal for undo", "NO_UNDO");
			return;
		}
		// §50 step 7: capability AFTER owner confirmation, bounded to the footprint
		Identifier dimension = Identifier.tryParse(workspace.areaOf(spec.ownerId())
			.map(a -> a.dimension()).orElse(""));
		UUID projectId = UUID.randomUUID();
		var capability = capabilities.issue(spec.ownerId(), spec.agentId(), TOOL,
			Set.of(TOOL), dimension, p.footprint(), spec.entries().size(), nowTick);
		LOG.info("[cbp] '{}' confirmed: {} entries ({} command blocks)", spec.name(),
			spec.entries().size(), spec.commandBlockCount());
		Optional<String> violation = capabilities.validateForUse(
			capability.capabilityId(), spec.agentId(), TOOL, dimension, p.footprint(),
			spec.entries().size(), nowTick);
		if (violation.isPresent()) {
			capabilities.revoke(capability.capabilityId());
			fail(spec, nowTick, "capability self-check failed: " + violation.get(),
				"CAPABILITY");
			return;
		}
		capabilities.consume(capability.capabilityId(), projectId);
		List<BlockPos> positions = new ArrayList<>();
		for (CbpSpec.Entry e : spec.entries()) {
			positions.add(e.pos());
		}
		UUID operationId = undo.begin(projectId, spec.ownerId(),
			world.getRegistryKey().getValue().toString(),
			"cbp " + spec.name() + " (" + spec.entries().size() + " blocks)", nowTick);
		placements.put(projectId, new Placement(projectId, spec.ownerId(),
			spec.agentId(), operationId, capability.capabilityId(), spec,
			world.getRegistryKey().getValue().toString(), positions));
		audit.record(nowTick, "cbp", spec.name() + "/start", true,
			"placing " + positions.size() + " blocks");
	}

	/** Places up to {@link #BLOCKS_PER_TICK} blocks per tick, then verifies. */
	private void advancePlacements(long nowTick) {
		for (Placement job : List.copyOf(placements.values())) {
			ServerWorld world = worlds.worldFor(job.dimensionKey);
			if (world == null) {
				abortJob(job, nowTick, "world unloaded mid-placement");
				continue;
			}
			int budget = BLOCKS_PER_TICK;
			while (budget-- > 0 && job.nextIndex < job.spec.entries().size()) {
				if (!placeOne(job, world, nowTick)) {
					return; // abortJob already ran inside
				}
			}
			if (job.nextIndex >= job.spec.entries().size()
					&& verify(job, world)) {
				finish(job, world, nowTick);
			}
		}
	}

	/** @return false when the job was aborted (capture/write/injection failure). */
	private boolean placeOne(Placement job, ServerWorld world, long nowTick) {
		CbpSpec.Entry e = job.spec.entries().get(job.nextIndex);
		Block targetBlock = blockFor(e.blockType());
		BlockState newState = orient(targetBlock.getDefaultState(), e);
		// §50 step 8: capture BEFORE the write so removal restores exactly
		BlockState oldState = world.getBlockState(e.pos());
		NbtCompound oldNbt = null;
		BlockEntity oldBe = world.getBlockEntity(e.pos());
		if (oldBe != null) {
			oldNbt = oldBe.createNbtWithId();
		}
		try {
			undo.record(new UndoJournal.Entry(job.operationId, job.projectId,
				e.pos(), oldState, oldNbt, newState, nowTick));
		} catch (RuntimeException overflow) {
			abortJob(job, nowTick, "undo journal refused entry: " + overflow);
			return false;
		}
		world.setBlockState(e.pos(), newState, 3);
		if (!e.isCommandBlock()) {
			// 方案 H2：红石件与说明牌只是装置的一部分，永远不带命令
			if (e.signText() != null
					&& world.getBlockEntity(e.pos())
						instanceof net.minecraft.block.entity.SignBlockEntity sign) {
				sign.setText(sign.getFrontText().withMessage(0,
					net.minecraft.text.Text.literal(e.signText())), true);
				sign.markDirty();
			}
			job.nextIndex++;
			return true;
		}
		BlockEntity be = world.getBlockEntity(e.pos());
		if (!(be instanceof CommandBlockBlockEntity cb)) {
			abortJob(job, nowTick, "block entity missing after placement");
			return false;
		}
		cb.getCommandExecutor().setCommand(e.command());
		cb.setPowered(false); // inert until redstone/auto says otherwise
		cb.setAuto(e.auto());
		cb.updateCommandBlock();
		cb.markDirty();
		job.nextIndex++;
		return true;
	}

	/** Orientation + conditional flag, applied only where the block supports them. */
	private static BlockState orient(BlockState state, CbpSpec.Entry entry) {
		BlockState result = state;
		if (entry.facing() != null
				&& result.contains(net.minecraft.state.property.Properties.FACING)) {
			result = result.with(net.minecraft.state.property.Properties.FACING,
				entry.facing());
		} else if (entry.facing() != null && result.contains(
				net.minecraft.state.property.Properties.HORIZONTAL_FACING)
				&& entry.facing().getAxis().isHorizontal()) {
			result = result.with(
				net.minecraft.state.property.Properties.HORIZONTAL_FACING,
				entry.facing());
		}
		if (result.contains(net.minecraft.state.property.Properties.CONDITIONAL)) {
			result = result.with(net.minecraft.state.property.Properties.CONDITIONAL,
				entry.conditional());
		}
		return result;
	}

	/** §50 step 10: honest verification — block type AND baked command must match. */
	private boolean verify(Placement job, ServerWorld world) {
		for (int i = 0; i < job.spec.entries().size(); i++) {
			CbpSpec.Entry e = job.spec.entries().get(i);
			Block expected = blockFor(e.blockType());
			if (!world.getBlockState(e.pos()).isOf(expected)) {
				abortJob(job, nowTickOf(world), "verification failed at "
					+ e.pos().toShortString());
				return false;
			}
			if (!e.isCommandBlock()) {
				continue; // support parts only need to BE there
			}
			BlockEntity be = world.getBlockEntity(e.pos());
			if (!(be instanceof CommandBlockBlockEntity cb)
					|| !e.command().equals(cb.getCommandExecutor().getCommand())) {
				abortJob(job, nowTickOf(world), "command mismatch at "
					+ e.pos().toShortString());
				return false;
			}
		}
		return true;
	}

	private long nowTickOf(ServerWorld world) {
		return world.getTime();
	}

	private void finish(Placement job, ServerWorld world, long nowTick) {
		placements.remove(job.projectId);
		// 方案 H3：日志落盘，重启之后 remove 仍然能精确恢复原方块
		undo.close(job.operationId);
		capabilities.revoke(job.capabilityId); // §30: licence dies with the operation
		CbpRegistry.Project project = new CbpRegistry.Project(job.projectId,
			job.ownerId, job.spec.name(), job.dimensionKey, job.spec.footprint(),
			List.copyOf(job.positions), job.operationId, false, nowTick);
		registry.register(project);
		notifier.send(job.ownerId, "[Squire] CBP '" + job.spec.name()
			+ "' placed " + job.positions.size()
			+ " command block(s). They are INERT until powered; manage via /squire cbp.");
		audit.record(nowTick, "cbp", job.spec.name() + "/materialized", true,
			job.positions.size() + " blocks verified");
	}

	private void abortJob(Placement job, long nowTick, String why) {
		placements.remove(job.projectId);
		ServerWorld world = worlds.worldFor(job.dimensionKey);
		if (world != null) {
			undo.undo(world, job.operationId); // restore every captured cell
		} else {
			undo.abandon(job.operationId);
		}
		capabilities.revoke(job.capabilityId);
		fail(job.spec, nowTick, why, "PLACEMENT_FAILED");
	}

	/** Shared refusal path: notify + audit; nothing partial stays in the world. */
	private void fail(CbpSpec spec, long nowTick, String why, String code) {
		LOG.info("[cbp] '{}' refused: {} ({})", spec.name(), why, code);
		notifier.send(spec.ownerId(), "[Squire] CBP '" + spec.name()
			+ "' NOT materialized: " + why);
		audit.record(nowTick, "cbp", spec.name() + "/refused", false, code + ": " + why);
	}

	// ------------------------------------------------------------------ control

	public record Access(boolean ok, String message) {
		static Access ok(String m) {
			return new Access(true, m);
		}

		static Access deny(String m) {
			return new Access(false, m);
		}
	}

	/** Powers every block of the project down (blocks stay, commands cannot run). */
	public synchronized Access disable(UUID projectId, UUID requesterId,
			boolean requesterAdmin) {
		CbpRegistry.Project project = controlled(projectId, requesterId,
			requesterAdmin);
		if (project == null) {
			return Access.deny("no such CBP project or not yours");
		}
		ServerWorld world = worlds.worldFor(project.dimensionKey());
		if (world == null) {
			return Access.deny("dimension not loaded");
		}
		int off = 0;
		for (BlockPos pos : project.positions()) {
			if (world.getBlockEntity(pos) instanceof CommandBlockBlockEntity cb) {
				cb.setAuto(false);
				cb.setPowered(false);
				cb.updateCommandBlock();
				cb.markDirty();
				off++;
			}
		}
		registry.setDisabled(projectId, true);
		audit.record(nowTickOf(world), "cbp", project.name() + "/disable", true,
			off + " blocks inert");
		return Access.ok(off + " command block(s) disabled (inert until re-enabled)");
	}

	/** Re-enables auto/manual arming disabled by {@link #disable}. */
	public synchronized Access enable(UUID projectId, UUID requesterId,
			boolean requesterAdmin) {
		CbpRegistry.Project project = controlled(projectId, requesterId,
			requesterAdmin);
		if (project == null) {
			return Access.deny("no such CBP project or not yours");
		}
		ServerWorld world = worlds.worldFor(project.dimensionKey());
		if (world == null) {
			return Access.deny("dimension not loaded");
		}
		registry.setDisabled(projectId, false);
		audit.record(nowTickOf(world), "cbp", project.name() + "/enable", true, "OK");
		return Access.ok("project re-enabled (auto flags restored per spec)");
	}

	/**
	 * Fully removes a project: the undo journal replays every captured pre-state
	 * newest-first, restoring the exact prior world content.
	 */
	public synchronized Access remove(UUID projectId, UUID requesterId,
			boolean requesterAdmin) {
		CbpRegistry.Project project = controlled(projectId, requesterId,
			requesterAdmin);
		if (project == null) {
			return Access.deny("no such CBP project or not yours");
		}
		ServerWorld world = worlds.worldFor(project.dimensionKey());
		if (world == null) {
			return Access.deny("dimension not loaded");
		}
		if (undo.entryCount(project.operationId()) == 0) {
			return Access.deny("no undo data for this project (server restarted "
				+ "since placement?) — break the blocks manually");
		}
		// 玩家明确要求移除整个工程：即使有人事后改过这些格子也照原样恢复
		int restored = Math.max(0, undo.undo(world, project.operationId(), true));
		registry.remove(projectId);
		audit.record(nowTickOf(world), "cbp", project.name() + "/remove", true,
			restored + " cells restored");
		notifier.send(project.ownerId(), "[Squire] CBP '" + project.name()
			+ "' removed; " + restored + " block(s) restored.");
		return Access.ok(restored + " cell(s) restored");
	}

	private CbpRegistry.Project controlled(UUID id, UUID requesterId,
			boolean requesterAdmin) {
		CbpRegistry.Project p = registry.get(id).orElse(null);
		if (p == null) {
			return null;
		}
		return p.ownerId().equals(requesterId) || requesterAdmin ? p : null;
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Resolve the block to place. The spec validator has already restricted
	 * {@code blockType} to the command-block family or the support whitelist, so a
	 * registry lookup is safe here — and necessary: falling back to COMMAND_BLOCK for
	 * anything unrecognised turned every daylight detector, lamp and sign in a
	 * template into a command block.
	 */
	private static Block blockFor(String blockType) {
		return switch (blockType) {
			case "minecraft:chain_command_block" -> Blocks.CHAIN_COMMAND_BLOCK;
			case "minecraft:repeating_command_block" -> Blocks.REPEATING_COMMAND_BLOCK;
			case "minecraft:command_block" -> Blocks.COMMAND_BLOCK;
			default -> {
				Identifier id = Identifier.tryParse(blockType);
				Block resolved = id == null ? null
					: net.minecraft.registry.Registries.BLOCK.getOrEmpty(id).orElse(null);
				if (resolved == null) {
					throw new IllegalStateException("unresolvable block type " + blockType);
				}
				yield resolved;
			}
		};
	}

	/** Stable arguments fingerprint binding the confirmation to THIS exact spec. */
	static String fingerprint(CbpSpec spec) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(spec.ownerId().toString().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '|');
			digest.update(spec.name().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '|');
			List<CbpSpec.Entry> sorted = new ArrayList<>(spec.entries());
			sorted.sort(Comparator.comparing(e -> e.pos().toShortString()));
			for (CbpSpec.Entry e : sorted) {
				String line = e.pos().toShortString() + "|" + e.blockType() + "|"
					+ e.auto() + "|" + e.command() + "\n";
				digest.update(line.getBytes(StandardCharsets.UTF_8));
			}
			StringBuilder hex = new StringBuilder();
			for (byte b : digest.digest()) {
				hex.append(String.format(Locale.ROOT, "%02x", b));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
