package dev.squire.server.goal;

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

/** JSON persistence for active and recent goal records. */
public final class GoalStateStore {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(GoalStateStore.class);
	private static final int VERSION = 1;
	private final java.util.function.Supplier<Path> fileSupplier;
	private boolean writable = true;

	public GoalStateStore(java.util.function.Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	public synchronized void save(Collection<GoalRecord> records) {
		if (!writable) return;
		try {
			Path file = fileSupplier.get();
			if (file == null) return;
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			JsonArray goals = new JsonArray();
			for (GoalRecord r : records) goals.add(write(r));
			root.add("goals", goals);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[goal] save failed: {}", e.toString());
		}
	}

	public synchronized List<GoalRecord> load() {
		List<GoalRecord> out = new ArrayList<>();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) return out;
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (root.has("version") && root.get("version").getAsInt() > VERSION) {
				writable = false;
				LOG.error("[goal] future schema {}; refusing recovery",
					root.get("version").getAsInt());
				return out;
			}
			JsonArray goals = root.getAsJsonArray("goals");
			if (goals == null) return out;
			for (JsonElement element : goals) {
				try { out.add(read(element.getAsJsonObject())); }
				catch (RuntimeException bad) {
					LOG.warn("[goal] skipped corrupt row: {}", bad.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[goal] load failed: {}", e.toString());
		}
		return out;
	}

	private static JsonObject write(GoalRecord r) {
		JsonObject o = new JsonObject();
		o.addProperty("goalId", r.goalId().toString());
		o.addProperty("ownerId", r.ownerId().toString());
		o.addProperty("agentId", r.agentId().toString());
		o.addProperty("kind", r.kind().name());
		o.addProperty("subject", r.subject());
		o.addProperty("requestedCount", r.requestedCount());
		o.addProperty("targetFinalCount", r.targetFinalCount());
		o.addProperty("createdTick", r.createdTick());
		o.addProperty("updatedTick", r.updatedTick());
		o.addProperty("state", r.state().name());
		o.addProperty("replans", r.replans());
		if (r.lastError() != null) o.addProperty("lastError", r.lastError());
		JsonArray tasks = new JsonArray();
		for (UUID id : r.remainingTaskIds()) tasks.add(id.toString());
		o.add("remainingTaskIds", tasks);
		return o;
	}

	private static GoalRecord read(JsonObject o) {
		List<UUID> tasks = new ArrayList<>();
		JsonArray ids = o.getAsJsonArray("remainingTaskIds");
		if (ids != null) for (JsonElement id : ids) tasks.add(UUID.fromString(id.getAsString()));
		return new GoalRecord(UUID.fromString(o.get("goalId").getAsString()),
			UUID.fromString(o.get("ownerId").getAsString()),
			UUID.fromString(o.get("agentId").getAsString()),
			GoalRecord.Kind.valueOf(o.get("kind").getAsString()),
			o.get("subject").getAsString(), o.get("requestedCount").getAsInt(),
			o.get("targetFinalCount").getAsInt(), o.get("createdTick").getAsLong(),
			o.get("updatedTick").getAsLong(),
			GoalRecord.State.valueOf(o.get("state").getAsString()),
			o.has("replans") ? o.get("replans").getAsInt() : 0,
			o.has("lastError") ? o.get("lastError").getAsString() : null, tasks);
	}
}
