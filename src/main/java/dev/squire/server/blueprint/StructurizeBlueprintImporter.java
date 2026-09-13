package dev.squire.server.blueprint;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.Identifier;

/**
 * Small, dependency-free reader for the documented Structurize v1 blueprint layout.
 * It deliberately imports blocks only: entities and block-entity payloads are never
 * executed by an Engineer construction plan.
 */
public final class StructurizeBlueprintImporter implements BlueprintImporter {
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;
	private record Mapping(String blockId, Set<String> preserve, Map<String, String> properties) { }
	private record SourceState(String id, Map<String, String> properties) { }
	private record Cell(int x, int y, int z, SourceState state, boolean ignored) { }

	@Override public String format() { return BlueprintLoader.STRUCTURIZE_FORMAT; }

	@Override
	public Blueprint importBlueprint(String id, JsonObject descriptor,
			ResourceProvider resources) throws IOException {
		if (resources == null) throw new IllegalArgumentException(id + ": no resource provider");
		Identifier source = Identifier.tryParse(requiredString(id, descriptor, "blueprint"));
		if (source == null) throw new IllegalArgumentException(id + ": invalid blueprint resource id");
		String encoding = string(descriptor, "encoding", "gzip");
		if (!"gzip".equals(encoding) && !"base64-gzip".equals(encoding)) {
			throw new IllegalArgumentException(id + ": unsupported blueprint encoding " + encoding);
		}
		String suffix = "base64-gzip".equals(encoding) ? ".blueprint.b64" : ".blueprint";
		Identifier file = new Identifier(source.getNamespace(), "structurize/" + source.getPath() + suffix);
		Map<String, Object> nbt;
		try (InputStream raw = resources.open(file);
				InputStream decoded = "base64-gzip".equals(encoding)
					? new java.io.ByteArrayInputStream(Base64.getMimeDecoder()
						.decode(raw.readAllBytes())) : raw;
				GZIPInputStream gzip = new GZIPInputStream(decoded)) {
			byte[] expanded = gzip.readNBytes((int) MAX_NBT_BYTES + 1);
			if (expanded.length > MAX_NBT_BYTES) throw new IOException("NBT exceeds size limit");
			try (DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(expanded))) {
				nbt = NbtReader.read(data, MAX_NBT_BYTES);
			}
		}
		if (!"strip".equals(string(descriptor, "entityPolicy", "reject"))
				&& !list(nbt, "entities").isEmpty()) {
			throw new IllegalArgumentException(id + ": entities require entityPolicy=strip");
		}
		if (!"strip".equals(string(descriptor, "blockEntityPolicy", "reject"))
				&& !list(nbt, "tile_entities").isEmpty()) {
			throw new IllegalArgumentException(id + ": block entities require blockEntityPolicy=strip");
		}
		int sx = integer(nbt, "size_x"), sy = integer(nbt, "size_y"), sz = integer(nbt, "size_z");
		if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > Blueprint.MAX_CELLS * 16L) {
			throw new IllegalArgumentException(id + ": invalid or excessive source dimensions");
		}
		List<SourceState> palette = palette(id, list(nbt, "palette"));
		if (!(nbt.get("blocks") instanceof int[] packed)) {
			throw new IllegalArgumentException(id + ": missing Structurize packed block array");
		}
		Set<String> ignored = strings(descriptor.getAsJsonArray("ignoredBlocks"));
		Map<String, Mapping> mappings = mappings(descriptor.getAsJsonObject("blockMap"));
		List<Cell> cells = new ArrayList<>();
		int minX = sx, minY = sy, minZ = sz, maxX = -1, maxY = -1, maxZ = -1;
		int total = sx * sy * sz;
		if (packed.length != (total + 1) / 2) throw new IllegalArgumentException(id + ": truncated packed block array");
		for (int index = 0; index < total; index++) {
			int word = packed[index / 2];
			int paletteIndex = (index & 1) == 0 ? word >>> 16 : word & 0xffff;
			if (paletteIndex < 0 || paletteIndex >= palette.size()) {
				throw new IllegalArgumentException(id + ": palette index out of range at cell " + index);
			}
			int x = index % sx, z = (index / sx) % sz, y = index / (sx * sz);
			SourceState state = palette.get(paletteIndex);
			boolean marker = ignored.contains(state.id());
			cells.add(new Cell(x, y, z, state, marker));
			if (!marker && !"minecraft:air".equals(state.id())) {
				minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
				maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
			}
		}
		if (maxX < minX) throw new IllegalArgumentException(id + ": no buildable content");
		boolean crop = "content".equals(string(descriptor, "crop", "none"));
		if (!crop) { minX = minY = minZ = 0; maxX = sx - 1; maxY = sy - 1; maxZ = sz - 1; }
		boolean clearAir = "clear".equals(string(descriptor, "airMode", "ignore"));
		List<BlueprintStep> steps = new ArrayList<>();
		for (Cell cell : cells) {
			if (cell.x < minX || cell.x > maxX || cell.y < minY || cell.y > maxY
					|| cell.z < minZ || cell.z > maxZ || cell.ignored) continue;
			int x = cell.x - minX, y = cell.y - minY, z = cell.z - minZ;
			if ("minecraft:air".equals(cell.state.id())) {
				if (clearAir) steps.add(BlueprintStep.dig(steps.size(), x, y, z, x, y, z, "structure air"));
				continue;
			}
			Mapping mapping = mappings.get(cell.state.id());
			String blockId = mapping == null ? cell.state.id() : mapping.blockId();
			Map<String, String> properties = mapping == null ? cell.state.properties()
				: mappedProperties(cell.state.properties(), mapping);
			steps.add(BlueprintStep.placeState(steps.size(), x, y, z, x, y, z,
				blockId, blockId, false, properties));
		}
		if (steps.size() > Blueprint.MAX_CELLS) throw new IllegalArgumentException(id + ": too many cells");
		Set<String> abilities = strings(descriptor.getAsJsonArray("requiredAbilities"));
		if (abilities.isEmpty()) abilities = clearAir
			? Set.of(BlueprintRegistry.ABILITY_BUILD, BlueprintRegistry.ABILITY_EXCAVATE)
			: Set.of(BlueprintRegistry.ABILITY_BUILD);
		return new Blueprint(id, string(descriptor, "displayName", id),
			descriptor.has("tier") ? descriptor.get("tier").getAsInt() : 1,
			Blueprint.Category.parse(string(descriptor, "category", "HOUSING")),
			maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, steps, abilities,
			BlueprintCodec.materialSlots(id, descriptor), BlueprintCodec.metadata(descriptor, format()));
	}

	private static List<SourceState> palette(String id, List<?> list) {
		List<SourceState> out = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			Map<String, Object> entry = compound(list.get(i));
			String blockId = String.valueOf(entry.get("Name"));
			if (Identifier.tryParse(blockId) == null) throw new IllegalArgumentException(id + ": invalid palette block " + blockId);
			Map<String, String> props = new LinkedHashMap<>();
			if (entry.get("Properties") instanceof Map<?, ?> raw) {
				for (var value : raw.entrySet()) props.put(String.valueOf(value.getKey()), String.valueOf(value.getValue()));
			}
			out.add(new SourceState(blockId, Map.copyOf(props)));
		}
		return List.copyOf(out);
	}

	private static Map<String, Mapping> mappings(JsonObject object) {
		if (object == null) return Map.of();
		Map<String, Mapping> out = new LinkedHashMap<>();
		for (var entry : object.entrySet()) {
			JsonObject value = entry.getValue().getAsJsonObject();
			out.put(entry.getKey(), new Mapping(value.get("block").getAsString(),
				strings(value.getAsJsonArray("preserveProperties")), stringMap(value.getAsJsonObject("properties"))));
		}
		return Map.copyOf(out);
	}

	private static Map<String, String> mappedProperties(Map<String, String> source, Mapping mapping) {
		Map<String, String> out = new LinkedHashMap<>(mapping.properties());
		for (String key : mapping.preserve()) if (source.containsKey(key)) out.put(key, source.get(key));
		return Map.copyOf(out);
	}

	private static Set<String> strings(JsonArray array) {
		if (array == null) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		for (JsonElement value : array) out.add(value.getAsString());
		return Set.copyOf(out);
	}
	private static Map<String, String> stringMap(JsonObject object) {
		if (object == null) return Map.of();
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : object.entrySet()) out.put(entry.getKey(), entry.getValue().getAsString());
		return Map.copyOf(out);
	}
	private static String requiredString(String id, JsonObject object, String key) {
		if (!object.has(key)) throw new IllegalArgumentException(id + ": missing " + key);
		return object.get(key).getAsString();
	}
	private static String string(JsonObject object, String key, String fallback) {
		return object.has(key) ? object.get(key).getAsString() : fallback;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> compound(Object value) {
		return (Map<String, Object>) value;
	}
	private static List<?> list(Map<String, Object> nbt, String key) {
		Object value = nbt.get(key);
		return value instanceof List<?> found ? found : List.of();
	}
	private static int integer(Map<String, Object> nbt, String key) {
		Object value = nbt.get(key);
		if (!(value instanceof Number number)) throw new IllegalArgumentException("missing NBT int " + key);
		return number.intValue();
	}

	/** Minimal standard big-endian NBT reader; keeps this importer usable in plain JVM tests. */
	private static final class NbtReader {
		private int remaining;
		private final DataInputStream in;
		private NbtReader(DataInputStream in, long limit) { this.in = in; this.remaining = (int) Math.min(Integer.MAX_VALUE, limit); }
		static Map<String, Object> read(DataInputStream in, long limit) throws IOException {
			NbtReader reader = new NbtReader(in, limit);
			if (reader.u8() != 10) throw new IOException("NBT root is not a compound");
			reader.utf();
			return reader.compoundPayload();
		}
		private Map<String, Object> compoundPayload() throws IOException {
			Map<String, Object> out = new LinkedHashMap<>();
			while (true) {
				int type = u8();
				if (type == 0) return out;
				out.put(utf(), payload(type));
			}
		}
		private Object payload(int type) throws IOException {
			return switch (type) {
				case 1 -> (byte) u8();
				case 2 -> { take(2); yield in.readShort(); }
				case 3 -> { take(4); yield in.readInt(); }
				case 4 -> { take(8); yield in.readLong(); }
				case 5 -> { take(4); yield in.readFloat(); }
				case 6 -> { take(8); yield in.readDouble(); }
				case 7 -> { int n = length(); take(n); yield in.readNBytes(n); }
				case 8 -> utf();
				case 9 -> {
					int child = u8(), n = length();
					List<Object> values = new ArrayList<>(n);
					for (int i = 0; i < n; i++) values.add(payload(child));
					yield values;
				}
				case 10 -> compoundPayload();
				case 11 -> { int n = length(); take(Math.multiplyExact(n, 4)); int[] a = new int[n]; for (int i=0;i<n;i++) a[i]=in.readInt(); yield a; }
				case 12 -> { int n = length(); take(Math.multiplyExact(n, 8)); long[] a = new long[n]; for (int i=0;i<n;i++) a[i]=in.readLong(); yield a; }
				default -> throw new IOException("unsupported NBT tag " + type);
			};
		}
		private int length() throws IOException { take(4); int n=in.readInt(); if(n<0) throw new IOException("negative NBT length"); return n; }
		private int u8() throws IOException { take(1); return in.readUnsignedByte(); }
		private String utf() throws IOException {
			take(2); int n=in.readUnsignedShort(); take(n);
			byte[] bytes=in.readNBytes(n); if(bytes.length!=n) throw new IOException("truncated NBT string");
			return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		}
		private void take(int count) throws IOException { remaining -= count; if (remaining < 0) throw new IOException("NBT exceeds size limit"); }
	}
}
