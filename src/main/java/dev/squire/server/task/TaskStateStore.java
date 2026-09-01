package dev.squire.server.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 可恢复任务的声明式持久层（方案 A4）。保存 taskId、agentId、ownerId、type、参数、
 * 依赖、优先级、状态、重试次数与 executor checkpoint；重启后 RUNNING 转 READY
 * 幂等恢复，不可恢复任务明确 FAILED(SERVER_RESTARTED) 并通知 owner。
 */
public final class TaskStateStore {
	private static final Logger LOG = LoggerFactory.getLogger(TaskStateStore.class);

	/** One persisted task's declarative state. */
	public record Snapshot(UUID taskId, UUID agentId, UUID ownerId, String type,
			String priority, String state, int attemptsUsed, String goalDescription,
			Map<String, Object> parameters, List<String> dependencies,
			long createdTick, long timeoutTicks, String lastErrorCode,
			String checkpoint, int maxRetries, int backoffTicks,
			boolean interruptible, String policySnapshot) {
	}

	private final java.util.function.Supplier<Path> fileSupplier;

	public TaskStateStore(java.util.function.Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	// ------------------------------------------------------------------ persistence

	public synchronized void save(List<Snapshot> snapshots) {
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray arr = new JsonArray();
			for (Snapshot s : snapshots) {
				arr.add(writeSnapshot(s));
			}
			root.add("tasks", arr);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			LOG.warn("[task-state] save failed: {}", e.toString());
		}
	}

	/** @return recovered snapshots; never throws. Empty list clears the file. */
	public synchronized List<Snapshot> loadAndClear() {
		List<Snapshot> out = new ArrayList<>();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return out;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			for (JsonElement el : root.getAsJsonArray("tasks")) {
				try {
					out.add(readSnapshot(el.getAsJsonObject()));
				} catch (RuntimeException bad) {
					LOG.warn("[task-state] skipped corrupt snapshot: {}", bad.toString());
				}
			}
			Files.deleteIfExists(file); // consumed exactly once
		} catch (Exception e) {
			LOG.warn("[task-state] recovery failed (starting empty): {}", e.toString());
		}
		LOG.info("[task-state] recovered {} task snapshot(s)", out.size());
		return out;
	}

	private static JsonObject writeSnapshot(Snapshot s) {
		JsonObject o = new JsonObject();
		o.addProperty("taskId", s.taskId().toString());
		o.addProperty("agentId", s.agentId().toString());
		o.addProperty("ownerId", s.ownerId().toString());
		o.addProperty("type", s.type());
		o.addProperty("priority", s.priority());
		o.addProperty("state", s.state());
		o.addProperty("attemptsUsed", s.attemptsUsed());
		if (s.goalDescription() != null) {
			o.addProperty("goal", s.goalDescription());
		}
		JsonObject params = new JsonObject();
		for (Map.Entry<String, Object> e : s.parameters().entrySet()) {
			Object v = e.getValue();
			if (v instanceof Number n) {
				params.addProperty(e.getKey(), n);
			} else if (v != null) {
				params.addProperty(e.getKey(), String.valueOf(v));
			}
		}
		o.add("params", params);
		JsonArray deps = new JsonArray();
		for (String dep : s.dependencies()) {
			deps.add(dep);
		}
		o.add("dependencies", deps);
		o.addProperty("createdTick", s.createdTick());
		o.addProperty("timeoutTicks", s.timeoutTicks());
		if (s.lastErrorCode() != null) {
			o.addProperty("lastError", s.lastErrorCode());
		}
		if (s.checkpoint() != null) {
			o.addProperty("checkpoint", s.checkpoint());
		}
		o.addProperty("maxRetries", s.maxRetries());
		o.addProperty("backoffTicks", s.backoffTicks());
		o.addProperty("interruptible", s.interruptible());
		if (s.policySnapshot() != null) {
			o.addProperty("policySnapshot", s.policySnapshot());
		}
		return o;
	}

	private static Snapshot readSnapshot(JsonObject o) {
		Map<String, Object> params = new LinkedHashMap<>();
		JsonObject params0 = o.getAsJsonObject("params");
		for (Map.Entry<String, JsonElement> e : params0.entrySet()) {
			JsonElement v = e.getValue();
			if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
				params.put(e.getKey(), v.getAsNumber());
			} else if (v.isJsonPrimitive()) {
				params.put(e.getKey(), v.getAsString());
			}
		}
		List<String> deps = new ArrayList<>();
		for (JsonElement d : o.getAsJsonArray("dependencies")) {
			deps.add(d.getAsString());
		}
		return new Snapshot(
			UUID.fromString(o.get("taskId").getAsString()),
			UUID.fromString(o.get("agentId").getAsString()),
			UUID.fromString(o.get("ownerId").getAsString()),
			o.get("type").getAsString(),
			o.has("priority") ? o.get("priority").getAsString()
				: TaskPriority.P3_USER_TASK.name(),
			o.has("state") ? o.get("state").getAsString() : "RUNNING",
			o.has("attemptsUsed") ? o.get("attemptsUsed").getAsInt() : 0,
			o.has("goal") ? o.get("goal").getAsString() : "restored task",
			params, deps,
			o.has("createdTick") ? o.get("createdTick").getAsLong() : 0L,
			o.has("timeoutTicks") ? o.get("timeoutTicks").getAsLong() : 1200L,
			o.has("lastError") ? o.get("lastError").getAsString() : null,
			o.has("checkpoint") ? o.get("checkpoint").getAsString() : null,
			o.has("maxRetries") ? o.get("maxRetries").getAsInt()
				: RetryPolicy.DEFAULT.maxRetries(),
			o.has("backoffTicks") ? o.get("backoffTicks").getAsInt()
				: RetryPolicy.DEFAULT.backoffTicks(),
			o.has("interruptible") ? o.get("interruptible").getAsBoolean() : true,
			o.has("policySnapshot") ? o.get("policySnapshot").getAsString()
				: "restart-recovery");
	}
}
