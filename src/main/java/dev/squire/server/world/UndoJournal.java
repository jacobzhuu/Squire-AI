package dev.squire.server.world;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Per-operation undo journal (spec section 52, 方案 F4). Every world write captures
 * {@code oldBlockState + oldBlockEntityNbt} BEFORE mutation; undo replays entries in
 * reverse so a multi-block operation restores exactly, including container contents.
 *
 * <p>Journals are PERSISTED to the world save (方案 F4), so an operation stays
 * undoable across a restart. Operations that cannot be journaled within the cap are
 * refused BEFORE starting (spec: 无法生成 Undo → 拒绝执行).</p>
 *
 * <p>Undo is not a privileged back door: it re-checks the same {@link ProtectionAdapter}
 * as the forward edit, and refuses to silently clobber cells that somebody CHANGED
 * after the operation — those come back as a conflict preview the player confirms.</p>
 */
public final class UndoJournal {

	private static final Logger LOG = LoggerFactory.getLogger(UndoJournal.class);

	private static final int SCHEMA_VERSION = 1;

	/** One captured pre-write snapshot (spec §52 field list). */
	public record Entry(UUID operationId, UUID taskId, BlockPos pos,
			BlockState oldBlockState, NbtCompound oldBlockEntityNbt,
			BlockState newBlockState, long tick) {
	}

	/** One undoable operation with everything needed to restore it after a restart. */
	public static final class Journal {
		public final UUID operationId;
		public final UUID taskId;
		public final UUID ownerId;
		public final String dimension;
		public final String description;
		public final long createdTick;
		public final long expiresAtTick;
		public final List<Entry> entries = new ArrayList<>();
		/** True once the operation finished writing; only closed journals are saved. */
		public boolean closed;

		Journal(UUID operationId, UUID taskId, UUID ownerId, String dimension,
				String description, long createdTick, long expiresAtTick) {
			this.operationId = operationId;
			this.taskId = taskId;
			this.ownerId = ownerId;
			this.dimension = dimension;
			this.description = description;
			this.createdTick = createdTick;
			this.expiresAtTick = expiresAtTick;
		}

		public int size() {
			return entries.size();
		}

		public String describe() {
			return operationId.toString().substring(0, 8) + " · "
				+ (description == null ? "world edit" : description)
				+ " · " + entries.size() + " 格 · " + dimension;
		}
	}

	/** What an undo WOULD do right now (方案 F4 冲突预览)。 */
	public record UndoPreview(UUID operationId, int restorableCells, int conflictCells,
			List<BlockPos> conflictSamples, String rejection) {

		public boolean hasConflicts() {
			return conflictCells > 0;
		}

		public boolean executable() {
			return rejection == null;
		}
	}

	public static final int MAX_ENTRIES_PER_OPERATION = 32_768;
	/** Keep the most recent operations per owner; older ones age out. */
	public static final int MAX_OPERATIONS_PER_OWNER = 16;
	/** Roughly three in-game days; an undo older than that is not what anyone means. */
	public static final long DEFAULT_TTL_TICKS = 216_000L;

	private final Map<UUID, Journal> journals = new ConcurrentHashMap<>();
	private volatile dev.squire.server.metrics.SquireMetrics metrics;
	private volatile ProtectionAdapter protection = ProtectionAdapter.ALLOW_ALL;
	private volatile Supplier<java.nio.file.Path> fileSupplier;
	private volatile boolean writable = true;

	/** §75 observability seam (optional; counters stay null-safe). */
	public void setMetrics(dev.squire.server.metrics.SquireMetrics value) {
		this.metrics = value;
	}

	/** The same protection seam the forward edit used (方案 F4). */
	public void setProtection(ProtectionAdapter adapter) {
		this.protection = adapter == null ? ProtectionAdapter.ALLOW_ALL : adapter;
	}

	/** Where the journal file lives; null disables persistence (test runtimes). */
	public void setStorage(Supplier<java.nio.file.Path> supplier) {
		this.fileSupplier = supplier;
	}

	public boolean canJournal(int expectedWrites) {
		return expectedWrites <= MAX_ENTRIES_PER_OPERATION;
	}

	// ------------------------------------------------------------------ lifecycle

	/** Begin an operation bound to its task; returns the operation id. */
	public UUID begin(UUID taskId) {
		return begin(taskId, null, "minecraft:overworld", null, 0L);
	}

	/**
	 * Begin an owner-attributed operation. Owner + dimension + description are what
	 * make {@code /squire undo list} readable and what bind the undo to one player.
	 */
	public UUID begin(UUID taskId, UUID ownerId, String dimension, String description,
			long nowTick) {
		UUID operationId = UUID.randomUUID();
		journals.put(operationId, new Journal(operationId, taskId, ownerId, dimension,
			description, nowTick, nowTick + DEFAULT_TTL_TICKS));
		return operationId;
	}

	public void record(Entry entry) {
		Journal journal = journals.get(entry.operationId());
		if (journal == null) {
			throw new IllegalStateException("no open journal for " + entry.operationId());
		}
		if (journal.entries.size() >= MAX_ENTRIES_PER_OPERATION) {
			throw new IllegalStateException("undo journal overflow");
		}
		journal.entries.add(entry);
		dev.squire.server.metrics.SquireMetrics m = metrics;
		if (m != null) {
			m.inc(dev.squire.server.metrics.SquireMetrics.Key.UNDO_ENTRIES);
		}
	}

	/** Mark an operation finished and persist it so a restart keeps it undoable. */
	public void close(UUID operationId) {
		Journal journal = journals.get(operationId);
		if (journal == null) {
			return;
		}
		journal.closed = true;
		if (journal.entries.isEmpty()) {
			journals.remove(operationId); // nothing was written: nothing to undo
			return;
		}
		pruneOwner(journal.ownerId);
		save();
	}

	public int entryCount(UUID operationId) {
		Journal journal = journals.get(operationId);
		return journal == null ? 0 : journal.entries.size();
	}

	public Optional<Journal> journal(UUID operationId) {
		return Optional.ofNullable(journals.get(operationId));
	}

	/** Undoable operations for one player, newest first. */
	public List<Journal> listFor(UUID ownerId) {
		return journals.values().stream()
			.filter(j -> j.closed && ownerId != null && ownerId.equals(j.ownerId))
			.sorted(Comparator.comparingLong((Journal j) -> j.createdTick).reversed())
			.toList();
	}

	/** The most recent undoable operation for one player. */
	public Optional<Journal> mostRecentFor(UUID ownerId) {
		return listFor(ownerId).stream().findFirst();
	}

	public void expireAllBefore(long nowTick) {
		List<UUID> dead = journals.values().stream()
			.filter(j -> j.closed && j.expiresAtTick > 0 && j.expiresAtTick <= nowTick)
			.map(j -> j.operationId).toList();
		if (!dead.isEmpty()) {
			dead.forEach(journals::remove);
			save();
		}
	}

	private void pruneOwner(UUID ownerId) {
		if (ownerId == null) {
			return;
		}
		List<Journal> mine = listFor(ownerId);
		for (int i = MAX_OPERATIONS_PER_OWNER; i < mine.size(); i++) {
			journals.remove(mine.get(i).operationId);
		}
	}

	// ------------------------------------------------------------------ undo

	/**
	 * What an undo would do right now, WITHOUT touching the world: how many cells can
	 * be restored, and how many were changed by somebody else since (conflicts).
	 */
	public UndoPreview preview(ServerWorld world, UUID operationId) {
		Journal journal = journals.get(operationId);
		if (journal == null) {
			return new UndoPreview(operationId, 0, 0, List.of(), "unknown operation");
		}
		if (!world.getRegistryKey().getValue().toString().equals(journal.dimension)) {
			return new UndoPreview(operationId, 0, 0, List.of(),
				"operation belongs to " + journal.dimension);
		}
		int restorable = 0;
		int conflicts = 0;
		List<BlockPos> samples = new ArrayList<>();
		for (Entry entry : journal.entries) {
			BlockState current = world.getBlockState(entry.pos());
			if (entry.newBlockState() != null && !current.equals(entry.newBlockState())) {
				conflicts++;
				if (samples.size() < 8) {
					samples.add(entry.pos());
				}
			} else {
				restorable++;
			}
			var decision = protection.canPlace(world, entry.pos(), journal.ownerId);
			if (!decision.allowed()) {
				return new UndoPreview(operationId, restorable, conflicts, samples,
					"PROTECTED_REGION: " + decision.reason());
			}
		}
		return new UndoPreview(operationId, restorable, conflicts, samples, null);
	}

	/**
	 * Restore every cell of {@code world} touched by {@code operationId}, newest first.
	 * Block entity NBT is re-applied after setBlockState so container contents return.
	 *
	 * @param acceptConflicts when false, an operation whose cells somebody changed
	 *                        afterwards is refused instead of clobbering their work
	 * @return number of cells restored, or -1 when refused
	 */
	public int undo(ServerWorld world, UUID operationId, boolean acceptConflicts) {
		UndoPreview check = preview(world, operationId);
		if (!check.executable()) {
			LOG.info("[undo] refused {}: {}", operationId, check.rejection());
			return -1;
		}
		if (check.hasConflicts() && !acceptConflicts) {
			LOG.info("[undo] {} has {} conflicting cells; waiting for confirmation",
				operationId, check.conflictCells());
			return -1;
		}
		Journal journal = journals.remove(operationId);
		if (journal == null) {
			return 0;
		}
		int restored = 0;
		for (int i = journal.entries.size() - 1; i >= 0; i--) {
			Entry entry = journal.entries.get(i);
			world.setBlockState(entry.pos(), entry.oldBlockState(), 3);
			if (entry.oldBlockEntityNbt() != null) {
				BlockEntity be = world.getBlockEntity(entry.pos());
				if (be != null) {
					be.readNbt(entry.oldBlockEntityNbt());
					be.markDirty();
				} else {
					LOG.warn("[undo] expected block entity at {} but found none",
						entry.pos());
				}
			}
			world.updateListeners(entry.pos(), entry.oldBlockState(),
				entry.oldBlockState(), 3); // re-sync NBT-restored BE to clients
			restored++;
		}
		LOG.info("[undo] restored {} cells for operation {}", restored, operationId);
		save();
		return restored;
	}

	/** Legacy two-arg form: refuses on conflicts (safe default). */
	public int undo(ServerWorld world, UUID operationId) {
		return Math.max(0, undo(world, operationId, false));
	}

	/** Discard without applying (e.g. operation failed before any write). */
	public void abandon(UUID operationId) {
		if (journals.remove(operationId) != null) {
			save();
		}
	}

	// ------------------------------------------------------------------ persistence

	/**
	 * Write every CLOSED journal to the world save. Block states go through
	 * {@link NbtHelper} so a state with properties (stairs facing, slab half) comes
	 * back exactly, not as a default state.
	 */
	public synchronized void save() {
		Supplier<java.nio.file.Path> supplier = fileSupplier;
		if (supplier == null || !writable) {
			return;
		}
		try {
			java.nio.file.Path file = supplier.get();
			if (file == null) {
				return;
			}
			java.nio.file.Files.createDirectories(file.getParent());
			NbtCompound root = new NbtCompound();
			root.putInt("schemaVersion", SCHEMA_VERSION);
			NbtList list = new NbtList();
			for (Journal journal : journals.values()) {
				if (journal.closed && !journal.entries.isEmpty()) {
					list.add(writeJournal(journal));
				}
			}
			root.put("operations", list);
			java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tmp.toFile());
			try {
				java.nio.file.Files.move(tmp, file,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				java.nio.file.Files.move(tmp, file,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[undo] save failed: {}", e.toString());
		}
	}

	/**
	 * Reload persisted journals. A journal whose block states no longer resolve is
	 * dropped rather than half-restored — claiming an undo we cannot perform would be
	 * worse than admitting it is gone.
	 *
	 * @return number of operations recovered
	 */
	public synchronized int load(MinecraftServer server) {
		Supplier<java.nio.file.Path> supplier = fileSupplier;
		if (supplier == null) {
			return 0;
		}
		try {
			java.nio.file.Path path = supplier.get();
			if (path == null || !java.nio.file.Files.exists(path)) {
				return 0;
			}
			File file = path.toFile();
			NbtCompound root = NbtIo.readCompressed(file);
			int version = root.getInt("schemaVersion");
			if (version > SCHEMA_VERSION) {
				writable = false; // fail-closed: never overwrite a newer format
				LOG.error("[undo] schemaVersion {} is newer than supported {}; "
					+ "undo history loaded READ-ONLY", version, SCHEMA_VERSION);
				return 0;
			}
			var blockLookup = server.getRegistryManager().get(RegistryKeys.BLOCK)
				.getReadOnlyWrapper();
			NbtList list = root.getList("operations", NbtElement.COMPOUND_TYPE);
			int recovered = 0;
			for (int i = 0; i < list.size(); i++) {
				try {
					Journal journal = readJournal(list.getCompound(i), blockLookup);
					journals.put(journal.operationId, journal);
					recovered++;
				} catch (RuntimeException e) {
					LOG.warn("[undo] skipped corrupt operation: {}", e.toString());
				}
			}
			return recovered;
		} catch (Exception e) {
			LOG.warn("[undo] load failed: {}", e.toString());
			return 0;
		}
	}

	private static NbtCompound writeJournal(Journal journal) {
		NbtCompound c = new NbtCompound();
		c.putUuid("operationId", journal.operationId);
		if (journal.taskId != null) {
			c.putUuid("taskId", journal.taskId);
		}
		if (journal.ownerId != null) {
			c.putUuid("ownerId", journal.ownerId);
		}
		c.putString("dimension", journal.dimension);
		if (journal.description != null) {
			c.putString("description", journal.description);
		}
		c.putLong("createdTick", journal.createdTick);
		c.putLong("expiresAtTick", journal.expiresAtTick);
		NbtList entries = new NbtList();
		for (Entry entry : journal.entries) {
			NbtCompound e = new NbtCompound();
			e.putLong("pos", entry.pos().asLong());
			e.put("old", NbtHelper.fromBlockState(entry.oldBlockState()));
			if (entry.newBlockState() != null) {
				e.put("new", NbtHelper.fromBlockState(entry.newBlockState()));
			}
			if (entry.oldBlockEntityNbt() != null) {
				e.put("oldBe", entry.oldBlockEntityNbt());
			}
			e.putLong("tick", entry.tick());
			entries.add(e);
		}
		c.put("entries", entries);
		return c;
	}

	private static Journal readJournal(NbtCompound c,
			net.minecraft.registry.RegistryEntryLookup<net.minecraft.block.Block> lookup) {
		Journal journal = new Journal(c.getUuid("operationId"),
			c.containsUuid("taskId") ? c.getUuid("taskId") : null,
			c.containsUuid("ownerId") ? c.getUuid("ownerId") : null,
			c.getString("dimension"),
			c.contains("description") ? c.getString("description") : null,
			c.getLong("createdTick"), c.getLong("expiresAtTick"));
		journal.closed = true;
		NbtList entries = c.getList("entries", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < entries.size(); i++) {
			NbtCompound e = entries.getCompound(i);
			BlockState old = NbtHelper.toBlockState(lookup, e.getCompound("old"));
			BlockState now = e.contains("new")
				? NbtHelper.toBlockState(lookup, e.getCompound("new")) : null;
			journal.entries.add(new Entry(journal.operationId, journal.taskId,
				BlockPos.fromLong(e.getLong("pos")), old,
				e.contains("oldBe") ? e.getCompound("oldBe") : null, now, e.getLong("tick")));
		}
		if (journal.entries.isEmpty()) {
			throw new IllegalStateException("operation has no entries");
		}
		return journal;
	}
}
