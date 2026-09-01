package dev.squire.server.blueprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * {@code blueprints.json}：摆放跨重启存活。
 *
 * <p>形状照 {@code GoalStateStore} 抄：版本字段、tmp + 原子改名、坏行只跳过自己。
 * 未来版本一律 fail-closed 并停止写入——一个旧 jar 把新档案覆盖回去，玩家丢的是
 * 一栋正在施工的建筑，而不是一个可以重来的操作。</p>
 */
public final class BlueprintPlacementStore {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BlueprintPlacementStore.class);
	private static final int VERSION = 2;

	private final java.util.function.Supplier<Path> fileSupplier;
	private boolean writable = true;

	public BlueprintPlacementStore(java.util.function.Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	public boolean isWritable() {
		return writable;
	}

	public synchronized void save(Collection<BlueprintPlacement> placements) {
		if (!writable) {
			return;
		}
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			JsonArray array = new JsonArray();
			for (BlueprintPlacement placement : placements) {
				array.add(write(placement));
			}
			root.add("placements", array);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[blueprint] save failed: {}", e.toString());
		}
	}

	public synchronized List<BlueprintPlacement> load() {
		List<BlueprintPlacement> out = new ArrayList<>();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return out;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			int version = root.has("version") ? root.get("version").getAsInt() : 1;
			if (version < VERSION) {
				LOG.warn("[blueprint] legacy placement schema {} is incompatible with fixed-layout templates; active sites were not restored", version);
				return out;
			}
			if (version > VERSION) {
				writable = false;
				LOG.error("[blueprint] future schema {}; refusing recovery and writes",
					root.get("version").getAsInt());
				return out;
			}
			JsonArray array = root.getAsJsonArray("placements");
			if (array == null) {
				return out;
			}
			for (JsonElement element : array) {
				try {
					out.add(read(element.getAsJsonObject()));
				} catch (RuntimeException bad) {
					LOG.warn("[blueprint] skipped corrupt row: {}", bad.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[blueprint] load failed: {}", e.toString());
		}
		return out;
	}

	// ------------------------------------------------------------------ rows

	static JsonObject write(BlueprintPlacement p) {
		JsonObject o = new JsonObject();
		o.addProperty("placementId", p.placementId.toString());
		o.addProperty("ownerId", p.ownerId.toString());
		if (p.agentId != null) {
			o.addProperty("agentId", p.agentId.toString());
		}
		o.addProperty("blueprintId", p.blueprintId);
		o.addProperty("dimension", p.dimensionId);
		o.addProperty("x", p.origin.getX());
		o.addProperty("y", p.origin.getY());
		o.addProperty("z", p.origin.getZ());
		o.addProperty("facing", p.facing.asString());
		o.addProperty("state", p.state().name());
		o.addProperty("createdTick", p.createdTick);
		JsonObject materials = new JsonObject();
		for (var entry : p.materials().entrySet()) materials.addProperty(entry.getKey(), entry.getValue());
		o.add("materials", materials);
		return o;
	}

	static BlueprintPlacement read(JsonObject o) {
		java.util.Map<String, String> materials = new java.util.LinkedHashMap<>();
		if (o.has("materials")) {
			for (var entry : o.getAsJsonObject("materials").entrySet()) {
				materials.put(entry.getKey(), entry.getValue().getAsString());
			}
		}
		BlueprintPlacement p = new BlueprintPlacement(
			UUID.fromString(o.get("placementId").getAsString()),
			UUID.fromString(o.get("ownerId").getAsString()),
			o.has("agentId") ? UUID.fromString(o.get("agentId").getAsString()) : null,
			o.get("blueprintId").getAsString(),
			o.get("dimension").getAsString(),
			new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(),
				o.get("z").getAsInt()),
			facingOf(o.has("facing") ? o.get("facing").getAsString() : null),
			o.has("createdTick") ? o.get("createdTick").getAsLong() : 0L, materials);
		if (o.has("state")) {
			try {
				p.setState(BlueprintPlacement.State.valueOf(o.get("state").getAsString()));
			} catch (IllegalArgumentException unknown) {
				p.setState(BlueprintPlacement.State.GHOST);
			}
		}
		return p;
	}

	private static Direction facingOf(String raw) {
		if (raw == null) {
			return Direction.NORTH;
		}
		Direction dir = Direction.byName(raw);
		return dir == null || dir.getAxis().isVertical() ? Direction.NORTH : dir;
	}
}
