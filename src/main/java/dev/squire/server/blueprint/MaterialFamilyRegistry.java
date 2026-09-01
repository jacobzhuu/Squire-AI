package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** Server-authoritative material-family catalog, reloadable from data packs. */
public final class MaterialFamilyRegistry {
	public static final String RESOURCE_DIR = "material_families";
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(MaterialFamilyRegistry.class);
	private static final MaterialFamilyRegistry DEFAULTS = new MaterialFamilyRegistry();

	private final Map<String, MaterialFamily> byId = new LinkedHashMap<>();

	public MaterialFamilyRegistry() {
		resetBuiltins();
	}

	/** Built-in-only catalog used by direct Blueprint.resolve calls in unit tests. */
	public static MaterialFamilyRegistry defaults() {
		return DEFAULTS;
	}

	public Optional<MaterialFamily> byId(String id) {
		return Optional.ofNullable(byId.get(normalize(id)));
	}

	public List<MaterialFamily> candidates(Blueprint.MaterialSlot slot) {
		return byId.values().stream().filter(f -> f.supports(slot)).toList();
	}

	public List<MaterialFamily> all() {
		return List.copyOf(byId.values());
	}

	public synchronized String reload(ResourceManager resources) {
		resetBuiltins();
		if (resources == null) return "内置 " + byId.size() + " 组";
		int loaded = 0;
		int failed = 0;
		for (var entry : resources.findResources(RESOURCE_DIR,
				path -> path.getPath().endsWith(".json")).entrySet()) {
			Identifier resourceId = entry.getKey();
			String path = resourceId.getPath();
			String name = path.substring(path.lastIndexOf('/') + 1,
				path.length() - ".json".length());
			String id = resourceId.getNamespace() + ":" + name;
			try (var reader = entry.getValue().getReader()) {
				StringBuilder json = new StringBuilder();
				char[] buffer = new char[4096];
				int read;
				while ((read = reader.read(buffer)) > 0) json.append(buffer, 0, read);
				MaterialFamily family = parse(id, json.toString());
				byId.put(family.id(), family);
				loaded++;
			} catch (Exception bad) {
				failed++;
				LOG.warn("[blueprint] skipped material family {}: {}", resourceId, bad.toString());
			}
		}
		return "共 " + byId.size() + " 组（数据包 " + loaded + "，跳过 " + failed + "）";
	}

	static MaterialFamily parse(String fallbackId, String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		String id = normalize(root.has("id") ? root.get("id").getAsString() : fallbackId);
		Set<MaterialFamily.SlotType> types = EnumSet.noneOf(MaterialFamily.SlotType.class);
		for (JsonElement element : root.getAsJsonArray("types")) {
			types.add(MaterialFamily.SlotType.parse(element.getAsString()));
		}
		Map<String, String> variants = new LinkedHashMap<>();
		for (var entry : root.getAsJsonObject("variants").entrySet()) {
			variants.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsString());
		}
		return new MaterialFamily(id,
			root.has("displayName") ? root.get("displayName").getAsString() : id,
			types, variants);
	}

	private synchronized void resetBuiltins() {
		byId.clear();
		for (MaterialFamily family : builtins()) byId.put(family.id(), family);
	}

	private static String normalize(String id) {
		return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
	}

	private static List<MaterialFamily> builtins() {
		List<MaterialFamily> out = new ArrayList<>();
		wood(out, "oak", "橡木");
		wood(out, "spruce", "云杉木");
		wood(out, "birch", "白桦木");
		wood(out, "jungle", "丛林木");
		wood(out, "acacia", "金合欢木");
		wood(out, "dark_oak", "深色橡木");
		wood(out, "mangrove", "红树木");
		wood(out, "cherry", "樱花木");
		masonry(out, "cobblestone", "圆石", "cobblestone", "cobblestone");
		masonry(out, "stone_bricks", "石砖", "stone_bricks", "stone_brick");
		masonry(out, "bricks", "红砖", "bricks", "brick");
		masonry(out, "deepslate_bricks", "深板岩砖", "deepslate_bricks", "deepslate_brick");
		masonry(out, "deepslate_tiles", "深板岩瓦", "deepslate_tiles", "deepslate_tile");
		masonry(out, "sandstone", "砂岩", "sandstone", "sandstone");
		masonry(out, "red_sandstone", "红砂岩", "red_sandstone", "red_sandstone");
		masonry(out, "polished_blackstone_bricks", "磨制黑石砖",
			"polished_blackstone_bricks", "polished_blackstone_brick");
		glass(out, "glass", "透明玻璃", "glass", "glass_pane");
		glass(out, "white_glass", "白色玻璃", "white_stained_glass",
			"white_stained_glass_pane");
		glass(out, "gray_glass", "灰色玻璃", "gray_stained_glass",
			"gray_stained_glass_pane");
		glass(out, "brown_glass", "棕色玻璃", "brown_stained_glass",
			"brown_stained_glass_pane");
		return List.copyOf(out);
	}

	private static void wood(List<MaterialFamily> out, String name, String display) {
		Map<String, String> v = new LinkedHashMap<>();
		v.put("block", "minecraft:" + name + "_planks");
		v.put("planks", "minecraft:" + name + "_planks");
		v.put("log", "minecraft:" + name + "_log");
		v.put("stairs", "minecraft:" + name + "_stairs");
		v.put("slab", "minecraft:" + name + "_slab");
		v.put("fence", "minecraft:" + name + "_fence");
		v.put("trapdoor", "minecraft:" + name + "_trapdoor");
		out.add(new MaterialFamily("squire:" + name, display,
			EnumSet.of(MaterialFamily.SlotType.FRAME, MaterialFamily.SlotType.WALL,
				MaterialFamily.SlotType.FLOOR, MaterialFamily.SlotType.ROOF), v));
	}

	private static void masonry(List<MaterialFamily> out, String id, String display,
			String base, String shaped) {
		Map<String, String> v = new LinkedHashMap<>();
		v.put("block", "minecraft:" + base);
		v.put("stairs", "minecraft:" + shaped + "_stairs");
		v.put("slab", "minecraft:" + shaped + "_slab");
		v.put("wall", "minecraft:" + shaped + "_wall");
		// FLOOR 也在内：石砖地板是再正常不过的选择，少了它「地板换石头」这条
		// 材料分区就永远只能选木头。
		out.add(new MaterialFamily("squire:" + id, display,
			EnumSet.of(MaterialFamily.SlotType.FOUNDATION, MaterialFamily.SlotType.WALL,
				MaterialFamily.SlotType.FLOOR, MaterialFamily.SlotType.ROOF), v));
	}

	private static void glass(List<MaterialFamily> out, String id, String display,
			String block, String pane) {
		out.add(new MaterialFamily("squire:" + id, display,
			Set.of(MaterialFamily.SlotType.WINDOW), Map.of(
				"block", "minecraft:" + block, "pane", "minecraft:" + pane)));
	}
}
