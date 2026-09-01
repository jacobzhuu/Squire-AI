package dev.squire.server.guide;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** Per-world record of players that have already received the compact. */
public final class SquireCompactState extends PersistentState {

	private static final Logger LOG = LoggerFactory.getLogger(SquireCompactState.class);
	private static final String FILE_ID = "squire_compact_recipients";
	private static final String KEY_SCHEMA_VERSION = "schemaVersion";
	private static final String KEY_RECIPIENTS = "recipients";
	public static final int CURRENT_SCHEMA_VERSION = 1;

	private final Set<UUID> recipients = new HashSet<>();
	private boolean failClosed;

	public static SquireCompactState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(
			SquireCompactState::createFromNbt, SquireCompactState::new, FILE_ID);
	}

	private static SquireCompactState createFromNbt(NbtCompound nbt) {
		SquireCompactState state = new SquireCompactState();
		state.readNbt(nbt);
		return state;
	}

	public static SquireCompactState createFromNbtPublic(NbtCompound nbt) {
		return createFromNbt(nbt);
	}

	public boolean hasReceived(UUID playerId) {
		// Unknown future data must be preserved and must not trigger mass duplicate gifts.
		return failClosed || recipients.contains(playerId);
	}

	public void markReceived(UUID playerId) {
		if (failClosed) {
			throw new IllegalStateException(
				"compact recipient schema is newer than this mod; writes are disabled");
		}
		if (recipients.add(playerId)) markDirty();
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
		NbtList rows = new NbtList();
		for (UUID playerId : recipients) {
			NbtCompound row = new NbtCompound();
			row.putUuid("playerId", playerId);
			rows.add(row);
		}
		nbt.put(KEY_RECIPIENTS, rows);
		return nbt;
	}

	private void readNbt(NbtCompound nbt) {
		int version = nbt.contains(KEY_SCHEMA_VERSION)
			? nbt.getInt(KEY_SCHEMA_VERSION) : 0;
		if (version > CURRENT_SCHEMA_VERSION) {
			failClosed = true;
			LOG.error("[squire-compact] schemaVersion {} is newer than supported {}; "
				+ "recipient state loaded read-only", version, CURRENT_SCHEMA_VERSION);
			return;
		}
		NbtList rows = nbt.getList(KEY_RECIPIENTS, NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < rows.size(); i++) {
			try {
				recipients.add(rows.getCompound(i).getUuid("playerId"));
			} catch (RuntimeException e) {
				LOG.warn("[squire-compact] skipped corrupt recipient row: {}", e.toString());
			}
		}
	}

	@Override
	public boolean isDirty() {
		return !failClosed && super.isDirty();
	}
}
