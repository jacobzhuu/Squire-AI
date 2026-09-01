package dev.squire.server.runtime;

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

/** Atomic JSON persistence for conversational turns. */
public final class TurnStateStore {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(TurnStateStore.class);
	private static final int VERSION = 2;
	private final java.util.function.Supplier<Path> fileSupplier;
	private boolean writable = true;

	public TurnStateStore(java.util.function.Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	public synchronized void save(Collection<TurnRecord> records) {
		if (!writable) return; // future schema: never overwrite data we cannot understand
		try {
			Path file = fileSupplier.get();
			if (file == null) return;
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			JsonArray turns = new JsonArray();
			for (TurnRecord record : records) turns.add(write(record));
			root.add("turns", turns);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[turn] save failed: {}", e.toString());
		}
	}

	public synchronized List<TurnRecord> load() {
		List<TurnRecord> out = new ArrayList<>();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) return out;
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			int version = root.has("version") ? root.get("version").getAsInt() : 0;
			if (version > VERSION) {
				writable = false;
				LOG.error("[turn] future schema {}; recovery and writes refused", version);
				return out;
			}
			JsonArray rows = root.getAsJsonArray("turns");
			if (rows == null) return out;
			for (JsonElement element : rows) {
				try { out.add(read(element.getAsJsonObject())); }
				catch (RuntimeException bad) {
					LOG.warn("[turn] skipped corrupt row: {}", bad.toString());
				}
			}
		} catch (Exception e) {
			LOG.warn("[turn] load failed: {}", e.toString());
		}
		return out;
	}

	private static JsonObject write(TurnRecord r) {
		JsonObject o = new JsonObject();
		o.addProperty("turnId", r.turnId().toString());
		o.addProperty("ownerId", r.ownerId().toString());
		if (r.agentId() != null) o.addProperty("agentId", r.agentId().toString());
		// Persist a bounded task summary, never a raw multi-message transcript.
		o.addProperty("goalSummary", structuredSummary(r.originalInput()));
		o.addProperty("providerId", r.providerId());
		o.addProperty("createdTick", r.createdTick());
		o.addProperty("updatedTick", r.updatedTick());
		o.addProperty("deadlineTick", r.deadlineTick());
		o.addProperty("state", r.state().name());
		o.addProperty("replans", r.replans());
		o.addProperty("toolCalls", r.toolCalls());
		o.addProperty("estimatedTokens", r.estimatedTokens());
		o.addProperty("highRiskCalls", r.highRiskCalls());
		if (r.lastError() != null) o.addProperty("lastError", r.lastError());
		o.add("waitingTaskIds", uuidArray(r.waitingTaskIds()));
		o.add("waitingCallIds", uuidArray(r.waitingCallIds()));
		o.addProperty("waitingFailed", r.waitingFailed());
		o.addProperty("providerRetries", r.providerRetries());
		if (r.pendingQuestion() != null) {
			o.addProperty("pendingQuestion", structuredSummary(r.pendingQuestion()));
		}
		JsonArray plan = new JsonArray();
		for (String step : r.planSteps()) plan.add(structuredSummary(step));
		o.add("planSteps", plan);
		JsonArray observations = new JsonArray();
		for (String observation : r.observations()) observations.add(observation);
		o.add("observations", observations);
		return o;
	}

	private static TurnRecord read(JsonObject o) {
		return new TurnRecord(UUID.fromString(o.get("turnId").getAsString()),
			UUID.fromString(o.get("ownerId").getAsString()),
			o.has("agentId") ? UUID.fromString(o.get("agentId").getAsString()) : null,
			o.has("goalSummary") ? o.get("goalSummary").getAsString()
				: o.has("originalInput") ? structuredSummary(
					o.get("originalInput").getAsString()) : "",
			o.has("providerId") ? o.get("providerId").getAsString() : "",
			o.get("createdTick").getAsLong(), o.get("updatedTick").getAsLong(),
			o.has("deadlineTick") ? o.get("deadlineTick").getAsLong()
				: o.get("createdTick").getAsLong() + 1200L,
			TurnRecord.State.valueOf(o.get("state").getAsString()),
			o.has("replans") ? o.get("replans").getAsInt() : 0,
			o.has("toolCalls") ? o.get("toolCalls").getAsInt() : 0,
			o.has("estimatedTokens") ? o.get("estimatedTokens").getAsInt() : 0,
			o.has("highRiskCalls") ? o.get("highRiskCalls").getAsInt() : 0,
			o.has("lastError") ? o.get("lastError").getAsString() : null,
			readUuids(o.getAsJsonArray("waitingTaskIds")),
			readUuids(o.getAsJsonArray("waitingCallIds")),
			readStrings(o.getAsJsonArray("observations")),
			o.has("waitingFailed") && o.get("waitingFailed").getAsBoolean(),
			o.has("providerRetries") ? o.get("providerRetries").getAsInt() : 0,
			o.has("pendingQuestion") ? o.get("pendingQuestion").getAsString() : null,
			readStrings(o.getAsJsonArray("planSteps")));
	}

	static String structuredSummary(String value) {
		if (value == null) return "";
		String oneLine = value.replaceAll("\\s+", " ").trim();
		return oneLine.length() <= 512 ? oneLine : oneLine.substring(0, 511) + "…";
	}

	private static JsonArray uuidArray(List<UUID> values) {
		JsonArray out = new JsonArray();
		for (UUID value : values) out.add(value.toString());
		return out;
	}

	private static List<UUID> readUuids(JsonArray array) {
		List<UUID> out = new ArrayList<>();
		if (array != null) for (JsonElement e : array) out.add(UUID.fromString(e.getAsString()));
		return out;
	}

	private static List<String> readStrings(JsonArray array) {
		List<String> out = new ArrayList<>();
		if (array != null) for (JsonElement e : array) out.add(e.getAsString());
		return out;
	}
}
