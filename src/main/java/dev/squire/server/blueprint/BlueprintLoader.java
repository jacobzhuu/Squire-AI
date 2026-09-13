package dev.squire.server.blueprint;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Format-neutral entry point for blueprint descriptors. */
public final class BlueprintLoader {
	public static final int SCHEMA_VERSION = 1;
	public static final String STEPS_FORMAT = "squire:steps";
	public static final String STRUCTURE_NBT_FORMAT = "minecraft:structure_nbt";
	public static final String STRUCTURIZE_FORMAT = "structurize:blueprint_v1";

	private final Map<String, BlueprintImporter> importers = new LinkedHashMap<>();

	public BlueprintLoader() {
		register(new StepsImporter());
		register(new StructureNbtBlueprintImporter());
		register(new StructurizeBlueprintImporter());
	}

	public void register(BlueprintImporter importer) {
		if (importer == null || importer.format() == null || importer.format().isBlank()) {
			throw new IllegalArgumentException("blueprint importer needs a format id");
		}
		importers.put(normalize(importer.format()), importer);
	}

	public Blueprint load(String fallbackId, String json,
			BlueprintImporter.ResourceProvider resources) throws IOException {
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException bad) {
			throw new IllegalArgumentException(fallbackId + ": not a JSON descriptor (" + bad + ")");
		}
		int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
		if (version != SCHEMA_VERSION) {
			throw new IllegalArgumentException(fallbackId + ": unsupported schemaVersion "
				+ version + " (expected " + SCHEMA_VERSION + ")");
		}
		String id = normalize(root.has("id") ? root.get("id").getAsString() : fallbackId);
		String format = normalize(root.has("format") ? root.get("format").getAsString()
			: STEPS_FORMAT);
		BlueprintImporter importer = importers.get(format);
		if (importer == null) {
			throw new IllegalArgumentException(id + ": unsupported blueprint format " + format);
		}
		Blueprint imported = importer.importBlueprint(id, root, resources);
		if (root.has("neighborComputedProperties")) {
			var dynamic = root.getAsJsonObject("neighborComputedProperties");
			var steps = imported.steps().stream().map(step -> {
				if (!dynamic.has(step.blockId())) return step;
				var properties = new java.util.LinkedHashMap<>(step.properties());
				dynamic.getAsJsonArray(step.blockId()).forEach(p -> properties.remove(p.getAsString()));
				return new BlueprintStep(step.order(), step.x1(), step.y1(), step.z1(), step.x2(), step.y2(), step.z2(),
					step.blockId(), step.what(), step.optional(), step.negative(), step.materialSlot(), step.materialVariant(), properties);
			}).toList();
			imported = new Blueprint(imported.id(), imported.displayName(), imported.tier(), imported.category(), imported.width(), imported.height(), imported.depth(),
				steps, imported.requiredAbilities(), imported.materialSlots(), imported.metadata());
		}
		Blueprint parsed = ImportMaterialBindings.apply(imported, root);
		if (!root.has("siteRequirements")) return parsed;
		var requirements = new java.util.ArrayList<SiteRequirement>();
		if (root.getAsJsonArray("siteRequirements").size() > Blueprint.MAX_CELLS) throw new IllegalArgumentException(id + ": site requirement limit");
		for (var raw : root.getAsJsonArray("siteRequirements")) {
			var r = raw.getAsJsonObject();
			requirements.add(new SiteRequirement(ConstructionSnapshotCodec.pos(r.get("pos")), r.get("kind").getAsString()));
		}
		var m = parsed.metadata();
		return new Blueprint(parsed.id(), parsed.displayName(), parsed.tier(), parsed.category(), parsed.width(), parsed.height(), parsed.depth(),
			parsed.steps(), parsed.requiredAbilities(), parsed.materialSlots(), new Blueprint.Metadata(m.author(), m.source(), m.license(), m.style(),
			m.description(), m.tags(), m.format(), m.minEngineerLevel(), requirements));
	}

	public java.util.Set<String> formats() {
		return java.util.Set.copyOf(importers.keySet());
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static final class StepsImporter implements BlueprintImporter {
		@Override public String format() { return STEPS_FORMAT; }

		@Override
		public Blueprint importBlueprint(String id, JsonObject descriptor,
				ResourceProvider resources) {
			return BlueprintCodec.parse(id, descriptor.toString());
		}
	}
}
