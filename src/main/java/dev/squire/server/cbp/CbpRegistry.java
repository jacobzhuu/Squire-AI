package dev.squire.server.cbp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.math.BlockPos;

/**
 * Registry of materialized CBP projects (spec §50 step 11). Every placed
 * project is listed here with its exact positions and undo operation id, so
 * the owner can inspect, disable or FULLY remove it later. Nothing stands in
 * the world that this registry does not know about.
 */
public final class CbpRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(CbpRegistry.class);

	public record Project(UUID id, UUID ownerId, String name, String dimensionKey,
			dev.squire.server.world.BoundedRegion footprint, List<BlockPos> positions,
			UUID operationId, boolean disabled, long placedAtTick) {
	}

	private final LinkedHashMap<UUID, Project> projects = new LinkedHashMap<>();
	private final Supplier<Path> fileSupplier;

	public CbpRegistry(Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	public synchronized void register(Project project) {
		projects.put(project.id(), project);
		save();
	}

	public synchronized java.util.Optional<Project> get(UUID id) {
		return java.util.Optional.ofNullable(projects.get(id));
	}

	public synchronized List<Project> ownedBy(UUID ownerId) {
		List<Project> mine = new ArrayList<>();
		for (Project p : projects.values()) {
			if (p.ownerId().equals(ownerId)) {
				mine.add(p);
			}
		}
		return mine;
	}

	public synchronized List<Project> all() {
		return List.copyOf(projects.values());
	}

	public synchronized Project remove(UUID id) {
		Project removed = projects.remove(id);
		if (removed != null) {
			save();
		}
		return removed;
	}

	/** Marks a project disabled/enabled (blocks stay, redstone gate flips off). */
	public synchronized void setDisabled(UUID id, boolean value) {
		Project p = projects.get(id);
		if (p == null || p.disabled() == value) {
			return;
		}
		projects.put(id, new Project(p.id(), p.ownerId(), p.name(), p.dimensionKey(),
			p.footprint(), p.positions(), p.operationId(), value, p.placedAtTick()));
		save();
	}

	public synchronized int size() {
		return projects.size();
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void save() {
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray arr = new JsonArray();
			for (Project p : projects.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", p.id().toString());
				o.addProperty("owner", p.ownerId().toString());
				o.addProperty("name", p.name());
				o.addProperty("dimension", p.dimensionKey());
				o.addProperty("minX", p.footprint().min().getX());
				o.addProperty("minY", p.footprint().min().getY());
				o.addProperty("minZ", p.footprint().min().getZ());
				o.addProperty("maxX", p.footprint().max().getX());
				o.addProperty("maxY", p.footprint().max().getY());
				o.addProperty("maxZ", p.footprint().max().getZ());
				JsonArray posArr = new JsonArray();
				for (BlockPos pos : p.positions()) {
					JsonObject po = new JsonObject();
					po.addProperty("x", pos.getX());
					po.addProperty("y", pos.getY());
					po.addProperty("z", pos.getZ());
					posArr.add(po);
				}
				o.add("positions", posArr);
				o.addProperty("operation", p.operationId().toString());
				o.addProperty("disabled", p.disabled());
				o.addProperty("placedAt", p.placedAtTick());
				arr.add(o);
			}
			root.add("projects", arr);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			LOG.warn("[cbp] registry save failed (continuing in memory): {}", e.toString());
		}
	}

	public synchronized int load() {
		projects.clear();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			for (com.google.gson.JsonElement el : root.getAsJsonArray("projects")) {
				try { // per-entry tolerance: one corrupt row cannot discard the rest
					JsonObject o = el.getAsJsonObject();
					List<BlockPos> positions = new ArrayList<>();
					for (com.google.gson.JsonElement pe : o.getAsJsonArray("positions")) {
						JsonObject po = pe.getAsJsonObject();
						positions.add(new BlockPos(po.get("x").getAsInt(),
							po.get("y").getAsInt(), po.get("z").getAsInt()));
					}
					Project p = new Project(
						UUID.fromString(o.get("id").getAsString()),
						UUID.fromString(o.get("owner").getAsString()),
						o.get("name").getAsString(),
						o.get("dimension").getAsString(),
						dev.squire.server.world.BoundedRegion.ofCorners(
							o.get("minX").getAsInt(), o.get("minY").getAsInt(),
							o.get("minZ").getAsInt(), o.get("maxX").getAsInt(),
							o.get("maxY").getAsInt(), o.get("maxZ").getAsInt()),
						positions,
						UUID.fromString(o.get("operation").getAsString()),
						o.get("disabled").getAsBoolean(),
						o.get("placedAt").getAsLong());
					projects.put(p.id(), p);
				} catch (RuntimeException e) {
					LOG.warn("[cbp] skipped corrupt registry entry: {}", e.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[cbp] registry recovery failed (starting empty): {}", e.toString());
		}
		return projects.size();
	}
}
