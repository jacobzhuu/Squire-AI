package dev.squire.server.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.Identifier;

/** Loads the built-in catalog through the same descriptor/importer path as data packs. */
final class BundledBlueprintPack {
	private static final String CATALOG = "data/squire/blueprint_catalog.json";

	private BundledBlueprintPack() { }

	static List<Blueprint> load(BlueprintLoader loader) {
		ClassLoader classes = BundledBlueprintPack.class.getClassLoader();
		BlueprintImporter.ResourceProvider provider = id -> open(classes, resourcePath(id));
		try (InputStream input = open(classes, CATALOG)) {
			JsonObject catalog = JsonParser.parseString(read(input)).getAsJsonObject();
			List<Blueprint> out = new ArrayList<>();
			for (JsonElement element : catalog.getAsJsonArray("blueprints")) {
				Identifier descriptorId = Identifier.tryParse(element.getAsString());
				if (descriptorId == null) throw new IOException("invalid bundled blueprint id " + element);
				try (InputStream descriptor = provider.open(descriptorId)) {
					out.add(loader.load(fallbackId(descriptorId), read(descriptor), provider));
				}
			}
			return List.copyOf(out);
		} catch (IOException | RuntimeException bad) {
			throw new IllegalStateException("cannot load bundled blueprint catalog", bad);
		}
	}

	static String fallbackId(Identifier resourceId) {
		String path = resourceId.getPath();
		if (path.startsWith(BlueprintRegistry.RESOURCE_DIR + "/")) {
			path = path.substring(BlueprintRegistry.RESOURCE_DIR.length() + 1);
		}
		if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
		return "squire".equals(resourceId.getNamespace()) && !path.contains("/")
			? path : resourceId.getNamespace() + ":" + path;
	}

	static String resourcePath(Identifier id) {
		return "data/" + id.getNamespace() + "/" + id.getPath();
	}

	private static InputStream open(ClassLoader classes, String path) throws IOException {
		InputStream input = classes.getResourceAsStream(path);
		if (input == null) throw new IOException("missing classpath resource " + path);
		return input;
	}

	private static String read(InputStream input) throws IOException {
		return new String(input.readAllBytes(), StandardCharsets.UTF_8);
	}
}
