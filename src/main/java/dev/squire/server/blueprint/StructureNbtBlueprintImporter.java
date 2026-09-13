package dev.squire.server.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtTagSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

/** Imports vanilla structure-block files without adding another mod as a dependency. */
public final class StructureNbtBlueprintImporter implements BlueprintImporter {
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

	private record Replacement(String slot, String variant) { }

	@Override
	public String format() {
		return BlueprintLoader.STRUCTURE_NBT_FORMAT;
	}

	@Override
	public Blueprint importBlueprint(String id, JsonObject descriptor,
			ResourceProvider resources) throws IOException {
		if (resources == null) throw new IllegalArgumentException(id + ": no resource provider");
		Identifier structure = structureId(id, descriptor);
		Identifier file = new Identifier(structure.getNamespace(),
			"structures/" + structure.getPath() + ".nbt");
		NbtCompound nbt;
		try (InputStream input = resources.open(file);
				DataInputStream data = new DataInputStream(new GZIPInputStream(input))) {
			nbt = NbtIo.read(data, new NbtTagSizeTracker(MAX_NBT_BYTES));
		}
		boolean stripEntities = "strip".equals(string(descriptor, "entityPolicy", "reject"));
		if (!stripEntities && !nbt.getList("entities", NbtElement.COMPOUND_TYPE).isEmpty()) {
			throw new IllegalArgumentException(id
				+ ": structure entities require entityPolicy=strip; construction never spawns entities");
		}

		StructureTemplate template = new StructureTemplate();
		// Validate before Minecraft's tolerant reader can turn unknown states into air/defaults.
		if (nbt.contains("palettes")) throw new IllegalArgumentException(id + ": choose one explicit structure palette before importing");
		for (NbtElement element : nbt.getList("palette", NbtElement.COMPOUND_TYPE)) {
			NbtCompound entry = (NbtCompound) element;
			Identifier blockId = Identifier.tryParse(entry.getString("Name"));
			if (blockId == null || !Registries.BLOCK.containsId(blockId)) throw new IllegalArgumentException(id + ": unknown structure block " + entry.getString("Name"));
			BlockState state = Registries.BLOCK.get(blockId).getDefaultState();
			NbtCompound properties = entry.getCompound("Properties");
			for (String key : properties.getKeys()) {
				Property<?> property = state.getBlock().getStateManager().getProperty(key);
				if (property == null || property.parse(properties.getString(key)).isEmpty())
					throw new IllegalArgumentException(id + ": invalid property " + key + " on " + blockId);
			}
		}
		template.readNbt(Registries.BLOCK.getReadOnlyWrapper(), nbt);
		Vec3i size = template.getSize();
		if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
			throw new IllegalArgumentException(id + ": structure has a degenerate size");
		}

		boolean clearAir = "clear".equals(string(descriptor, "airMode", "ignore"));
		boolean stripBlockEntities = "strip".equals(
			string(descriptor, "blockEntityPolicy", "reject"));
		Map<String, Replacement> replacements = replacements(id, descriptor);
		List<StructureTemplate.StructureBlockInfo> infos = allBlocks(template, nbt);
		infos.sort(Comparator.comparingInt((StructureTemplate.StructureBlockInfo info) ->
			info.pos().getY()).thenComparingInt(info -> info.pos().getX())
			.thenComparingInt(info -> info.pos().getZ()));

		List<BlueprintStep> steps = new ArrayList<>();
		int order = 0;
		for (StructureTemplate.StructureBlockInfo info : infos) {
			BlockState state = info.state();
			Block block = state.getBlock();
			if (block == Blocks.STRUCTURE_VOID || block == Blocks.STRUCTURE_BLOCK
					|| block == Blocks.JIGSAW) continue;
			if (info.nbt() != null && !stripBlockEntities) {
				throw new IllegalArgumentException(id + ": block entity NBT at "
					+ info.pos().toShortString()
					+ " requires blockEntityPolicy=strip; construction never imports payload NBT");
			}
			BlockPos p = info.pos().add(0, descriptor.has("offsetY") ? descriptor.get("offsetY").getAsInt() : 0, 0);
			if (block == Blocks.AIR) {
				if (clearAir) {
					steps.add(BlueprintStep.dig(order++, p.getX(), p.getY(), p.getZ(),
						p.getX(), p.getY(), p.getZ(), "结构负空间"));
				}
				continue;
			}
			String blockId = Registries.BLOCK.getId(block).toString();
			Replacement replacement = replacements.get(blockId);
			Map<String, String> properties = properties(state);
			if (replacement == null) {
				steps.add(BlueprintStep.placeState(order++, p.getX(), p.getY(), p.getZ(),
					p.getX(), p.getY(), p.getZ(), blockId, blockId, false, properties));
			} else {
				steps.add(BlueprintStep.materialState(order++, p.getX(), p.getY(), p.getZ(),
					p.getX(), p.getY(), p.getZ(), replacement.slot(), replacement.variant(),
					replacement.slot(), false, properties));
			}
		}
		if (steps.isEmpty()) throw new IllegalArgumentException(id + ": structure has no buildable blocks");
		if (steps.size() > Blueprint.MAX_CELLS) {
			throw new IllegalArgumentException(id + ": structure contains " + steps.size()
				+ " cells; limit is " + Blueprint.MAX_CELLS);
		}

		Set<String> abilities = strings(descriptor.getAsJsonArray("requiredAbilities"));
		if (abilities.isEmpty()) {
			abilities = clearAir
				? Set.of(BlueprintRegistry.ABILITY_BUILD, BlueprintRegistry.ABILITY_EXCAVATE)
				: Set.of(BlueprintRegistry.ABILITY_BUILD);
		}
		Blueprint.Metadata parsed = BlueprintCodec.metadata(descriptor, format());
		String structureAuthor = template.getAuthor();
		Blueprint.Metadata metadata = parsed.author().isEmpty()
				&& structureAuthor != null && !structureAuthor.isBlank()
			? new Blueprint.Metadata(structureAuthor, parsed.source(), parsed.license(),
				parsed.style(), parsed.description(), parsed.tags(), parsed.format(),
				parsed.minEngineerLevel()) : parsed;
		return new Blueprint(id, string(descriptor, "displayName", id),
			descriptor.has("tier") ? descriptor.get("tier").getAsInt() : 1,
			Blueprint.Category.parse(string(descriptor, "category", "HOUSING")),
			size.getX(), size.getY(), size.getZ(), steps, abilities,
			BlueprintCodec.materialSlots(id, descriptor), metadata);
	}

	private static Identifier structureId(String id, JsonObject descriptor) {
		if (!descriptor.has("structure")) {
			throw new IllegalArgumentException(id + ": structure_nbt format needs \"structure\"");
		}
		Identifier parsed = Identifier.tryParse(descriptor.get("structure").getAsString());
		if (parsed == null) throw new IllegalArgumentException(id + ": invalid structure id");
		return parsed;
	}

	private static List<StructureTemplate.StructureBlockInfo> allBlocks(
			StructureTemplate template, NbtCompound nbt) {
		List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>();
		StructurePlacementData placement = new StructurePlacementData();
		Set<Block> used = new LinkedHashSet<>();
		for (NbtElement raw : nbt.getList("palette", NbtElement.COMPOUND_TYPE))
			used.add(Registries.BLOCK.get(new Identifier(((NbtCompound) raw).getString("Name"))));
		for (Block block : used) {
			out.addAll(template.getInfosForBlock(BlockPos.ORIGIN, placement, block, true));
		}
		return out;
	}

	private static Map<String, Replacement> replacements(String id, JsonObject descriptor) {
		JsonObject palette = descriptor.getAsJsonObject("palette");
		if (palette == null) return Map.of();
		Map<String, Replacement> out = new LinkedHashMap<>();
		for (var entry : palette.entrySet()) {
			JsonObject replacement = entry.getValue().getAsJsonObject();
			if (!replacement.has("slot")) {
				throw new IllegalArgumentException(id + ": palette " + entry.getKey()
					+ " needs a material slot");
			}
			out.put(entry.getKey(), new Replacement(replacement.get("slot").getAsString(),
				string(replacement, "variant", "block")));
		}
		return Map.copyOf(out);
	}

	private static Set<String> strings(JsonArray array) {
		if (array == null) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		for (JsonElement element : array) out.add(element.getAsString());
		return Set.copyOf(out);
	}

	private static Map<String, String> properties(BlockState state) {
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : state.getEntries().entrySet()) {
			out.put(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
		}
		return Map.copyOf(out);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String propertyName(Property property, Comparable value) {
		return property.name(value);
	}

	private static String string(JsonObject object, String key, String fallback) {
		return object.has(key) ? object.get(key).getAsString() : fallback;
	}
}
