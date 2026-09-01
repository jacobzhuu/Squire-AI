package dev.squire.server.cognition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.squire.common.protocol.ToolCall;

/**
 * Provider-independent function-calling wire format (spec section 22):
 *
 * <pre>
 * {"say": "optional text", "tool_calls": [{"id":"c1", "name":"tool.name", "arguments":{...}}]}
 * </pre>
 *
 * Plain conversational text without a leading '{' is a pure say-turn. Text that LOOKS
 * like a turn (starts with '{') but fails to parse is MALFORMED_MODEL_OUTPUT — it is
 * never silently treated as chatter, and never executed partially.
 */
public final class FunctionCallingParser {

	/** Parsed model output: what to say plus which tools to invoke. */
	public record ModelTurn(String say, String ask, List<String> plan,
			List<ToolCall> calls, boolean malformed, String parseError) {
		public static ModelTurn say(String text) {
			return new ModelTurn(text == null ? "" : text, "", List.of(),
				List.of(), false, null);
		}

		public boolean hasWork() {
			return !calls.isEmpty();
		}
	}

	private FunctionCallingParser() {
	}

	public static ModelTurn parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return ModelTurn.say("");
		}
		String text = stripFence(raw.trim());
		if (!text.startsWith("{")) {
			return ModelTurn.say(raw.trim());
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			return malformed("", "not valid JSON: " + e.getMessage());
		}
		if (!root.isJsonObject()) {
			return malformed("", "turn must be a JSON object");
		}
		JsonObject obj = root.getAsJsonObject();
		String say = obj.has("say") && obj.get("say").isJsonPrimitive()
				? obj.get("say").getAsString()
				: "";
		String ask = obj.has("ask") && obj.get("ask").isJsonPrimitive()
			? obj.get("ask").getAsString() : "";
		List<String> plan = stringArray(obj, "plan");
		if (!ask.isBlank() && obj.has("tool_calls")
				&& obj.get("tool_calls").isJsonArray()
				&& obj.getAsJsonArray("tool_calls").size() > 0) {
			return malformed(say, "a clarification turn cannot also call tools");
		}

		List<ToolCall> calls = new ArrayList<>();
		if (obj.has("tool_calls")) {
			if (!obj.get("tool_calls").isJsonArray()) {
				return malformed(say, "'tool_calls' must be an array");
			}
			JsonArray array = obj.getAsJsonArray("tool_calls");
			for (int i = 0; i < array.size(); i++) {
				JsonElement element = array.get(i);
				if (!element.isJsonObject()) {
					return malformed(say, "tool_call[" + i + "] must be an object");
				}
				JsonObject callObj = element.getAsJsonObject();
				String name = callObj.has("name") && callObj.get("name").isJsonPrimitive()
						? callObj.get("name").getAsString()
						: null;
				if (name == null || name.isBlank()) {
					return malformed(say, "tool_call[" + i + "] missing 'name'");
				}
				Map<String, Object> arguments = callObj.has("arguments")
						&& callObj.get("arguments").isJsonObject()
							? flatten(callObj.getAsJsonObject("arguments"))
							: Map.of();
				UUID callId = UUID.randomUUID();
				calls.add(new ToolCall(callId, name, arguments));
			}
		}
		return new ModelTurn(say, ask, plan, List.copyOf(calls), false, null);
	}

	private static ModelTurn malformed(String say, String error) {
		return new ModelTurn(say, "", List.of(), List.of(), true, error);
	}

	private static List<String> stringArray(JsonObject object, String name) {
		if (!object.has(name)) return List.of();
		if (!object.get(name).isJsonArray()) return List.of();
		List<String> out = new ArrayList<>();
		for (JsonElement element : object.getAsJsonArray(name)) {
			if (element.isJsonPrimitive()) {
				String value = element.getAsString().trim();
				if (!value.isEmpty() && out.size() < 8) out.add(value);
			}
		}
		return List.copyOf(out);
	}

	/** Decode a flat JSON object into decoded-JSON values (String/Number/Boolean). */
	private static Map<String, Object> flatten(JsonObject obj) {
		var builder = new java.util.LinkedHashMap<String, Object>();
		for (var entry : obj.entrySet()) {
			JsonElement value = entry.getValue();
			Object decoded;
			if (value.isJsonNull() || !value.isJsonPrimitive()) {
				decoded = value.isJsonNull() ? null : value.toString();
			} else {
				var prim = value.getAsJsonPrimitive();
				if (prim.isBoolean()) {
					decoded = prim.getAsBoolean();
				} else if (prim.isNumber()) {
					Number n = prim.getAsNumber();
					decoded = n.doubleValue() == n.longValue() ? (Object) n.longValue() : n;
				} else {
					decoded = prim.getAsString();
				}
			}
			builder.put(entry.getKey(), decoded);
		}
		return java.util.Collections.unmodifiableMap(builder);
	}

	private static String stripFence(String text) {
		if (text.startsWith("```")) {
			int firstNewline = text.indexOf('\n');
			int lastFence = text.lastIndexOf("```");
			if (firstNewline > 0 && lastFence > firstNewline) {
				return text.substring(firstNewline + 1, lastFence).trim();
			}
		}
		return text;
	}
}
