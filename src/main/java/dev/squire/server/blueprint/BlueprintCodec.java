package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 蓝图 JSON 的解析。刻意是<b>纯函数</b>（字符串进、{@link Blueprint} 出，不碰
 * 世界也不碰注册表），所以它能被普通单测直接盖住——蓝图数据是玩家/整合包作者会
 * 手写的东西，格式错误必须给出说得清的原因，而不是在服务器启动时抛一个栈。
 *
 * <p>格式（{@code data/squire/blueprints/<id>.json}）：</p>
 * <pre>
 * {
	 *   "displayName": "\u77f3\u5c4b", "tier": 1, "category": "HOUSING",
 *   "size": [7, 5, 7],
 *   "requiredAbilities": ["build.blueprint"],
 *   "steps": [
 *     {"block": "minecraft:stone_bricks", "from": [0,0,0], "to": [6,3,6], "what": "\u5899\u4f53"},
 *     {"dig": true, "from": [1,1,1], "to": [5,3,5], "what": "\u638f\u7a7a\u5185\u90e8"},
 *     {"block": "minecraft:glass_pane", "from": [0,2,3], "to": [0,2,3], "optional": true}
 *   ]
 * }
 * </pre>
 * <p>{@code order} 省略时按数组顺序编号——作者写的顺序就是施工顺序，让他再手填一
 * 遍序号只会制造错位。</p>
 */
public final class BlueprintCodec {

	private BlueprintCodec() {
	}

	/** @throws IllegalArgumentException 带上 id 和出错的字段，调用方直接读给玩家 */
	public static Blueprint parse(String id, String json) {
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(id + ": not a JSON object (" + e + ")");
		}
		int[] size = intArray(root, "size", id);
		if (size.length != 3) {
			throw new IllegalArgumentException(id + ": size must be [width, height, depth]");
		}
		JsonArray rawSteps = root.getAsJsonArray("steps");
		if (rawSteps == null || rawSteps.isEmpty()) {
			throw new IllegalArgumentException(id + ": steps must be a non-empty array");
		}
		List<BlueprintStep> steps = new ArrayList<>();
		int order = 0;
		for (JsonElement element : rawSteps) {
			steps.add(step(id, element.getAsJsonObject(), order++));
		}
		Set<String> abilities = new LinkedHashSet<>();
		JsonArray rawAbilities = root.getAsJsonArray("requiredAbilities");
		if (rawAbilities != null) {
			for (JsonElement element : rawAbilities) {
				abilities.add(element.getAsString());
			}
		}
		List<Blueprint.MaterialSlot> slots = materialSlots(id, root);
		String format = root.has("format") ? root.get("format").getAsString()
			: "squire:steps";
		return new Blueprint(id,
			root.has("displayName") ? root.get("displayName").getAsString() : id,
			root.has("tier") ? root.get("tier").getAsInt() : 1,
			Blueprint.Category.parse(root.has("category")
				? root.get("category").getAsString() : null),
			size[0], size[1], size[2], steps, abilities, slots,
			metadata(root, format));
	}

	static Blueprint.Metadata metadata(JsonObject root, String format) {
		JsonObject metadata = root.getAsJsonObject("metadata");
		if (metadata == null) metadata = new JsonObject();
		Set<String> tags = new LinkedHashSet<>();
		JsonArray rawTags = metadata.getAsJsonArray("tags");
		if (rawTags != null) for (JsonElement value : rawTags) tags.add(value.getAsString());
		return new Blueprint.Metadata(string(metadata, "author"),
			string(metadata, "source"), string(metadata, "license"),
			string(metadata, "style"), string(metadata, "description"), tags, format,
			root.has("minEngineerLevel") ? root.get("minEngineerLevel").getAsInt() : 0);
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) ? object.get(key).getAsString() : "";
	}

	static List<Blueprint.MaterialSlot> materialSlots(String id,
			JsonObject root) {
		JsonArray raw = root.getAsJsonArray("materialSlots");
		if (raw == null) return List.of();
		List<Blueprint.MaterialSlot> out = new ArrayList<>();
		for (JsonElement element : raw) {
			JsonObject slot = element.getAsJsonObject();
			Set<String> variants = new LinkedHashSet<>();
			JsonArray required = slot.getAsJsonArray("requiredVariants");
			if (required != null) for (JsonElement value : required) variants.add(value.getAsString());
			out.add(new Blueprint.MaterialSlot(slot.get("id").getAsString(),
				slot.has("displayName") ? slot.get("displayName").getAsString() : null,
				MaterialFamily.SlotType.parse(slot.get("type").getAsString()),
				slot.get("defaultFamily").getAsString(), variants));
		}
		return List.copyOf(out);
	}

	private static BlueprintStep step(String id, JsonObject o, int fallbackOrder) {
		int[] from = intArray(o, "from", id);
		int[] to = o.has("to") ? intArray(o, "to", id) : from;
		if (from.length != 3 || to.length != 3) {
			throw new IllegalArgumentException(id + ": step " + fallbackOrder
				+ " needs from/to as [x, y, z]");
		}
		int order = o.has("order") ? o.get("order").getAsInt() : fallbackOrder;
		String what = o.has("what") ? o.get("what").getAsString() : null;
		boolean dig = o.has("dig") && o.get("dig").getAsBoolean();
		if (dig) {
			return BlueprintStep.dig(order, from[0], from[1], from[2],
				to[0], to[1], to[2], what == null ? "\u6316\u9664" : what);
		}
		if (!o.has("block")) {
			if (!o.has("material")) {
				throw new IllegalArgumentException(id + ": step " + order
					+ " needs \"block\", \"material\", or \"dig\": true");
			}
			JsonObject material = o.getAsJsonObject("material");
			return BlueprintStep.materialState(order, from[0], from[1], from[2],
				to[0], to[1], to[2], material.get("slot").getAsString(),
				material.has("variant") ? material.get("variant").getAsString() : "block",
				what == null ? material.get("slot").getAsString() : what,
				o.has("optional") && o.get("optional").getAsBoolean(), properties(o));
		}
		String block = o.get("block").getAsString();
		if (BlueprintStep.AIR.equals(block)) {
			// 作者用 air 表达的一定是「挖掉」，而不是「放一格空气」。直接当成挖除，
			// 免得白白多出一个消耗 minecraft:air 物品的施工步骤。
			return BlueprintStep.dig(order, from[0], from[1], from[2],
				to[0], to[1], to[2], what == null ? "\u6316\u9664" : what);
		}
		return BlueprintStep.placeState(order, from[0], from[1], from[2],
			to[0], to[1], to[2], block, what == null ? block : what,
			o.has("optional") && o.get("optional").getAsBoolean(), properties(o));
	}

	private static java.util.Map<String, String> properties(JsonObject step) {
		JsonObject raw = step.getAsJsonObject("properties");
		if (raw == null) return java.util.Map.of();
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : raw.entrySet()) out.put(entry.getKey(), entry.getValue().getAsString());
		return Map.copyOf(out);
	}

	private static int[] intArray(JsonObject o, String key, String id) {
		JsonArray array = o.getAsJsonArray(key);
		if (array == null) {
			throw new IllegalArgumentException(id + ": missing \"" + key + "\"");
		}
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsInt();
		}
		return out;
	}
}
