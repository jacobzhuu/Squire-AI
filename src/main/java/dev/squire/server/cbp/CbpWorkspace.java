package dev.squire.server.cbp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.math.BlockPos;

/**
 * Per-owner build workspace (spec §50/§89): the ONLY area where a CBP project
 * may ever be materialized. The owner sets it explicitly with
 * {@code /squire workspace set <from> <to>}; without a workspace nothing can be
 * planned — so the server never builds in some unknown underground area.
 *
 * <p>Persisted atomically (tmp + move) so workspaces survive restarts.</p>
 */
public final class CbpWorkspace {

	private static final Logger LOG = LoggerFactory.getLogger(CbpWorkspace.class);

	public record Area(String dimension, dev.squire.server.world.BoundedRegion region) {
	}

	private final Map<UUID, Area> areas = new LinkedHashMap<>();
	private final Supplier<Path> fileSupplier;

	public CbpWorkspace(Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	/**
	 * Sets (or replaces) one owner's workspace.
	 *
	 * @return refusal reason, or empty on success
	 */
	public synchronized Optional<String> set(UUID ownerId, String dimension,
			dev.squire.server.world.BoundedRegion region) {
		if (dimension == null || dimension.isBlank()) {
			return Optional.of("dimension required");
		}
		if (region.volume() > dev.squire.server.world.BoundedRegion.MAX_VOLUME) {
			return Optional.of("workspace too large (max "
				+ dev.squire.server.world.BoundedRegion.MAX_VOLUME + " blocks)");
		}
		areas.put(ownerId, new Area(dimension, region));
		save();
		return Optional.empty();
	}

	public synchronized Optional<String> clear(UUID ownerId) {
		if (areas.remove(ownerId) == null) {
			return Optional.of("no workspace set");
		}
		save();
		return Optional.empty();
	}

	public synchronized Optional<Area> areaOf(UUID ownerId) {
		return Optional.ofNullable(areas.get(ownerId));
	}

	public synchronized int size() {
		return areas.size();
	}

	// ------------------------------------------------------------------ persistence

	/** Writes all workspaces atomically. Never throws. */
	public synchronized void save() {
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			for (Map.Entry<UUID, Area> e : areas.entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("dimension", e.getValue().dimension());
				BlockPos min = e.getValue().region().min();
				BlockPos max = e.getValue().region().max();
				o.addProperty("minX", min.getX());
				o.addProperty("minY", min.getY());
				o.addProperty("minZ", min.getZ());
				o.addProperty("maxX", max.getX());
				o.addProperty("maxY", max.getY());
				o.addProperty("maxZ", max.getZ());
				root.add(e.getKey().toString(), o);
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			LOG.warn("[cbp] workspace save failed (continuing in memory): {}", e.toString());
		}
	}

	/** Restart recovery; tolerant of corruption (starts empty instead). */
	public synchronized int load() {
		areas.clear();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue; // skips "version"
				}
				try { // per-entry tolerance: one corrupt row cannot discard the rest
					JsonObject o = e.getValue().getAsJsonObject();
					UUID owner = UUID.fromString(e.getKey());
					dev.squire.server.world.BoundedRegion region =
						dev.squire.server.world.BoundedRegion.ofCorners(
							o.get("minX").getAsInt(), o.get("minY").getAsInt(),
							o.get("minZ").getAsInt(), o.get("maxX").getAsInt(),
							o.get("maxY").getAsInt(), o.get("maxZ").getAsInt());
					areas.put(owner, new Area(o.get("dimension").getAsString(), region));
				} catch (RuntimeException ex) {
					LOG.warn("[cbp] skipped corrupt workspace entry {}: {}",
						e.getKey(), ex.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[cbp] workspace recovery failed (starting empty): {}", e.toString());
		}
		return areas.size();
	}

	/** Unit-test seam: absolute position inside a workspace region. */
	public static boolean contains(Area area, BlockPos pos) {
		return area.region().contains(pos);
	}
}
