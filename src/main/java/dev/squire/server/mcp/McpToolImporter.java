package dev.squire.server.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.squire.api.tool.ArgSpec;
import dev.squire.api.tool.ExternalTool;
import dev.squire.server.ext.ExtensionManager;

/**
 * Sanitizes remote MCP tool descriptions onto {@link ExternalTool}s (spec section 54).
 * Everything arriving here is HOSTILE INPUT: names are re-namespaced under the
 * sanitized server name, schemas are size-capped and malformed ones REFUSE the whole
 * tool, and provider annotations are kept as fail-closed hints only.
 *
 * <p>Building and registering are separate steps: {@link #build} produces a ready
 * ExternalTool or throws; the {@link McpClientBridge} registers reports-first so one
 * hostile payload can never break startup.</p>
 */
public final class McpToolImporter {

	/** Server names become tool namespaces: strict lowercase ids only. */
	public static String sanitizeServerName(String raw) {
		if (raw == null) {
			return "";
		}
		String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
		return name.matches("[a-z0-9][a-z0-9_.-]*") ? name : "";
	}

	/**
	 * Build the sanitized ExternalTool for one advertised MCP tool.
	 *
	 * @throws IllegalArgumentException on unsanitary names or invalid/oversized schema
	 */
	public ExternalTool build(McpClientBridge bridge, String serverName,
			McpToolInfo info) {
		String safeServer = sanitizeServerName(serverName);
		if (safeServer.isEmpty()) {
			throw new IllegalArgumentException("unsanitary server name: " + serverName);
		}
		if (info == null || !info.name().matches("[a-z0-9_][a-z0-9_-]*")) {
			throw new IllegalArgumentException(
				"unsanitary tool name: " + (info == null ? "null" : info.name()));
		}
		String id = safeServer + ":" + info.name();
		List<ArgSpec> args = parseSchema(info.schemaJson()); // may throw: refuse-all
		boolean claimsReadOnly = info.readOnlyHint();
		ExternalTool.Builder builder = ExternalTool
			.builder(id, call -> bridge.handleRemoteCall(safeServer, info.name(), call))
			.description(truncate(info.description()))
			.tag("mcp").tag(safeServer).alias(info.name().replace('_', ' '));
		for (ArgSpec arg : args) {
			builder.arg(arg);
		}
		// annotations are HINTS ONLY (§55): they can tighten nothing looser
		builder.readOnly(claimsReadOnly);
		if (info.destructiveHint()) {
			builder.destructive(true);
		}
		builder.risk(info.destructiveHint() ? "HIGH"
			: claimsReadOnly ? "LOW" : "MEDIUM");
		return builder.build();
	}

	private List<ArgSpec> parseSchema(String schemaJson) {
		JsonElement parsed = JsonParser.parseString(
			schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson);
		List<ArgSpec> out = new ArrayList<>();
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("schema not an object");
		}
		JsonObject root = parsed.getAsJsonObject();
		JsonElement properties = root.get("properties");
		if (properties == null || properties.isJsonNull()) {
			return out;
		}
		if (!properties.isJsonObject()) {
			throw new IllegalArgumentException("properties not an object");
		}
		JsonObject props = properties.getAsJsonObject();
		if (props.size() > ExtensionManager.MAX_TOOL_ARGS) {
			throw new IllegalArgumentException(
				"oversized schema: " + props.size() + " properties");
		}
		List<String> required = new ArrayList<>();
		JsonElement reqElement = root.get("required");
		if (reqElement != null && reqElement.isJsonArray()) {
			for (JsonElement r : reqElement.getAsJsonArray()) {
				required.add(r.getAsString());
			}
		}
		for (Map.Entry<String, JsonElement> prop : props.entrySet()) {
			String argName = prop.getKey();
			if (!argName.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
				throw new IllegalArgumentException("bad property name: " + argName);
			}
			JsonObject def = prop.getValue().isJsonObject()
				? prop.getValue().getAsJsonObject() : new JsonObject();
			out.add(argSpec(argName, def, required.contains(argName)));
		}
		return out;
	}

	private static ArgSpec argSpec(String name, JsonObject def, boolean required) {
		String description = def.has("description") && def.get("description").isJsonPrimitive()
			? def.get("description").getAsString() : "";
		String jsonType = def.has("type") && def.get("type").isJsonPrimitive()
			? def.get("type").getAsString() : "string";
		Long min = numberMember(def, "minimum");
		Long max = numberMember(def, "maximum");
		return switch (jsonType) {
			case "int", "integer", "number" -> ArgSpec.intRange(name,
				min == null ? Long.MIN_VALUE / 4 : min,
				max == null ? Long.MAX_VALUE / 4 : max, description);
			case "boolean" -> ArgSpec.flag(name, description);
			default -> required
				? ArgSpec.requiredString(name, description)
				: ArgSpec.optionalString(name, description);
		};
	}

	private static Long numberMember(JsonObject def, String member) {
		return def.has(member) && def.get(member).isJsonPrimitive()
			? def.get(member).getAsLong() : null;
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= ExtensionManager.MAX_DESCRIPTION_CHARS ? text
			: text.substring(0, ExtensionManager.MAX_DESCRIPTION_CHARS);
	}
}
