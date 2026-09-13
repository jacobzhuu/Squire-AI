package dev.squire.server.blueprint;

import java.io.IOException;
import java.io.InputStream;

import com.google.gson.JsonObject;

import net.minecraft.util.Identifier;

/** Converts one external representation into Squire's immutable blueprint model. */
public interface BlueprintImporter {
	String format();

	Blueprint importBlueprint(String id, JsonObject descriptor,
			ResourceProvider resources) throws IOException;

	@FunctionalInterface
	interface ResourceProvider {
		InputStream open(Identifier id) throws IOException;
	}
}
