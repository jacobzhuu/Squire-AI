package dev.squire.server.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Backward-compatible multi-provider configuration.
 *
 * <p>A legacy top-level {@link LlmConfig} is treated as one local provider. The
 * expanded shape uses {@code providers[]} and explicit data-boundary metadata so
 * failover can never upload a local conversation to a cloud endpoint by accident.</p>
 */
public record LlmSettings(List<Entry> providers, String primary,
		boolean allowLocalToCloudFailover) {

	public enum DataBoundary { LOCAL, CLOUD }

	public record Entry(String name, String trustDomain, DataBoundary dataBoundary,
			int priority, LlmConfig config) {
		public Entry {
			if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")) {
				throw new IllegalArgumentException("invalid provider name: " + name);
			}
			trustDomain = trustDomain == null || trustDomain.isBlank()
				? name : trustDomain.trim();
			dataBoundary = dataBoundary == null ? DataBoundary.LOCAL : dataBoundary;
			config = java.util.Objects.requireNonNull(config, "config");
		}
	}

	public LlmSettings {
		providers = List.copyOf(providers == null ? List.of() : providers);
		if (providers.isEmpty()) throw new IllegalArgumentException("providers is empty");
		primary = primary == null || primary.isBlank()
			? providers.get(0).name() : primary.trim();
		boolean found = false;
		for (Entry entry : providers) {
			if (entry.name().equals(primary)) found = true;
		}
		if (!found) throw new IllegalArgumentException("primary provider not found: " + primary);
	}

	public List<Entry> ordered() {
		List<Entry> out = new ArrayList<>(providers);
		out.sort(Comparator.comparing((Entry e) -> !e.name().equals(primary))
			.thenComparingInt(Entry::priority).thenComparing(Entry::name));
		return List.copyOf(out);
	}

	public static Optional<LlmSettings> load(Path file) {
		if (file == null || !Files.isRegularFile(file)) return Optional.empty();
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (!root.has("providers")) {
				return LlmConfig.fromJsonObject(root).map(config -> new LlmSettings(
					List.of(new Entry("default", "default", DataBoundary.LOCAL, 0, config)),
					"default", false));
			}
			JsonArray rows = root.getAsJsonArray("providers");
			if (rows == null || rows.size() == 0) return Optional.empty();
			List<Entry> entries = new ArrayList<>();
			for (JsonElement row : rows) {
				if (!row.isJsonObject()) return Optional.empty();
				JsonObject object = row.getAsJsonObject();
				Optional<LlmConfig> config = LlmConfig.fromJsonObject(object);
				if (config.isEmpty()) return Optional.empty();
				String name = string(object, "name", "provider-" + entries.size());
				String trust = string(object, "trustDomain", name);
				String boundary = string(object, "dataBoundary", "local");
				DataBoundary dataBoundary = "cloud".equalsIgnoreCase(boundary)
					? DataBoundary.CLOUD : DataBoundary.LOCAL;
				int priority = object.has("priority") ? object.get("priority").getAsInt()
					: entries.size();
				entries.add(new Entry(name, trust, dataBoundary, priority, config.get()));
			}
			String primary = string(root, "primary", entries.get(0).name());
			boolean allow = root.has("allowLocalToCloudFailover")
				&& root.get("allowLocalToCloudFailover").getAsBoolean();
			return Optional.of(new LlmSettings(entries, primary, allow));
		} catch (RuntimeException | java.io.IOException e) {
			dev.squire.SquireMod.LOGGER.warn("[Squire] llm settings invalid: {}",
				e.toString());
			return Optional.empty();
		}
	}

	private static String string(JsonObject object, String key, String fallback) {
		return object.has(key) && object.get(key).isJsonPrimitive()
			? object.get(key).getAsString() : fallback;
	}
}
