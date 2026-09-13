package dev.squire.server.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.util.Identifier;

/** Lightweight content index. Reading it never loads geometry or plans a worksite. */
public final class BuildingCatalog {
    public enum Status { READY, ADAPTED, UNSUPPORTED, INTERNAL }
    public record BuildingVariantDefinition(String id, String family, String familyName,
            String displayName, String category, int tier, String variant, boolean deck,
            int requiredEngineerLevel, int complexity, int blockCount, int width, int height,
            int depth, Status status, String reason, String descriptor, String author,
            String license, String style, String source, String upstreamCommit, String sourceSha256) {
        public boolean buildable() { return status == Status.READY || status == Status.ADAPTED; }
        public boolean allowed(int level) { return buildable() && level >= requiredEngineerLevel; }
    }
    public record BuildingDefinition(String id, String name, String category,
            List<BuildingVariantDefinition> variants) {
        public BuildingDefinition { variants = List.copyOf(variants); }
        public int minLevel() { return variants.stream().mapToInt(BuildingVariantDefinition::requiredEngineerLevel).min().orElse(10); }
    }
    private final Map<String, BuildingVariantDefinition> variants;
    private final Map<String, BuildingDefinition> families;
    private final Map<String, String> categories;
    public static BuildingCatalog bundled() { return Bundled.VALUE; }
    private static final class Bundled {
        static final BuildingCatalog VALUE = loadBundled();
    }
    private BuildingCatalog(Map<String, BuildingVariantDefinition> variants, Map<String, String> categories) {
        this.variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants));
        this.categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        Map<String, List<BuildingVariantDefinition>> grouped = new LinkedHashMap<>();
        variants.values().stream().filter(v -> v.status() != Status.INTERNAL).forEach(v ->
            grouped.computeIfAbsent(v.family(), key -> new ArrayList<>()).add(v));
        Map<String, BuildingDefinition> definitions = new LinkedHashMap<>();
        grouped.forEach((id, group) -> {
            group.sort(Comparator.comparingInt(BuildingVariantDefinition::tier)
                .thenComparing(BuildingVariantDefinition::deck).thenComparing(BuildingVariantDefinition::id));
            var first = group.get(0);
            if (group.stream().anyMatch(v -> !v.category().equals(first.category()) || !v.familyName().equals(first.familyName())))
                throw new IllegalArgumentException("inconsistent family " + id);
            definitions.put(id, new BuildingDefinition(id, first.familyName(), first.category(), group));
        });
        families = Collections.unmodifiableMap(definitions);
    }
    public Optional<BuildingVariantDefinition> variant(String id) { return Optional.ofNullable(variants.get(id)); }
    public Optional<BuildingDefinition> family(String id) { return Optional.ofNullable(families.get(id)); }
    public List<BuildingDefinition> families() { return List.copyOf(families.values()); }
    public List<BuildingVariantDefinition> variants() { return List.copyOf(variants.values()); }
    public Map<String, String> categories() { return categories; }
    public String categoryName(String id) { return categories.getOrDefault(id, id); }
    /** Separate resource packs add independent indexes; duplicate stable IDs are rejected. */
    public static BuildingCatalog merge(List<BuildingCatalog> packs) {
        Map<String, BuildingVariantDefinition> entries = new LinkedHashMap<>(); Map<String, String> categories = new LinkedHashMap<>();
        for (var pack : packs) {
            pack.categories.forEach((id, name) -> {
                String previous = categories.putIfAbsent(id, name);
                if (previous != null && !previous.equals(name)) throw new IllegalArgumentException("conflicting category " + id);
            });
            pack.variants.forEach((id, variant) -> { if (entries.putIfAbsent(id, variant) != null) throw new IllegalArgumentException("duplicate catalog ID " + id); });
        }
        if (entries.size() > 8192) throw new IllegalArgumentException("combined catalog limit");
        return new BuildingCatalog(entries, categories);
    }

    public static BuildingCatalog parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException("unsupported building catalog version");
        Map<String, String> categories = new LinkedHashMap<>();
        for (var value : root.getAsJsonArray("categories")) {
            var c = value.getAsJsonObject(); categories.put(s(c, "id"), s(c, "name"));
        }
        if (root.getAsJsonArray("variants").size() > 8192) throw new IllegalArgumentException("catalog entry limit");
        Map<String, BuildingVariantDefinition> variants = new LinkedHashMap<>();
        for (var value : root.getAsJsonArray("variants")) {
            var v = value.getAsJsonObject();
            var entry = new BuildingVariantDefinition(s(v,"id"), s(v,"family"), s(v,"familyName"), s(v,"displayName"),
                s(v,"category"), n(v,"tier"), s(v,"variant"), v.get("deck").getAsBoolean(), n(v,"requiredEngineerLevel"),
                n(v,"complexity"), n(v,"blockCount"), n(v,"width"), n(v,"height"), n(v,"depth"),
                Status.valueOf(s(v,"status")), s(v,"reason"), s(v,"descriptor"), s(v,"author"), s(v,"license"),
                s(v,"style"), s(v,"source"), s(v,"upstreamCommit"), s(v,"sourceSha256"));
            if (entry.requiredEngineerLevel() < 1 || entry.requiredEngineerLevel() > 10 || entry.tier() < 0 || entry.tier() > 5
                    || entry.width() <= 0 || entry.height() <= 0 || entry.depth() <= 0 || Identifier.tryParse(entry.descriptor()) == null
                    || !categories.containsKey(entry.category()) && entry.status() != Status.INTERNAL)
                throw new IllegalArgumentException("invalid catalog entry " + entry.id());
            if (variants.putIfAbsent(entry.id(), entry) != null) throw new IllegalArgumentException("duplicate variant " + entry.id());
        }
        return new BuildingCatalog(variants, categories);
    }
    private static BuildingCatalog loadBundled() {
        try (var input = BuildingCatalog.class.getClassLoader().getResourceAsStream("data/squire/building_catalog/keepitlevel.json")) {
            if (input == null) throw new IOException("missing bundled building catalog");
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    private static String s(JsonObject v, String key) { return v.has(key) ? v.get(key).getAsString() : ""; }
    private static int n(JsonObject v, String key) { return v.get(key).getAsInt(); }
}
