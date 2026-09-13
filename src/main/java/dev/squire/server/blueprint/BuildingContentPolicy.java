package dev.squire.server.blueprint;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Product visibility is separate from legacy geometry import and recovery. */
public record BuildingContentPolicy(Set<String> retiredIds, List<String> retiredPrefixes,
        int catalogMaterialLevel, int catalogFullMaterialLevel, Set<String> retiredTools, Set<String> retiredAbilities) {
    private static volatile BuildingContentPolicy current = load(id -> {
        var in = BuildingContentPolicy.class.getClassLoader().getResourceAsStream("data/" + id.getNamespace() + "/" + id.getPath());
        if (in == null) throw new java.io.IOException("missing content policy"); return in;
    });
    public static BuildingContentPolicy current() { return current; }
    public boolean retired(String id) {
        if (id == null) return false;
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        return retiredIds.contains(normalized) || retiredPrefixes.stream().anyMatch(normalized::startsWith);
    }
    public static void reload(BlueprintImporter.ResourceProvider resources) {
        try { current = load(resources); } catch (RuntimeException bad) {
            org.slf4j.LoggerFactory.getLogger(BuildingContentPolicy.class).warn("Keeping previous content policy: {}", bad.toString());
        }
    }
    private static BuildingContentPolicy load(BlueprintImporter.ResourceProvider resources) {
        try (var in = resources.open(new net.minecraft.util.Identifier("squire:building_content_policy.json"))) {
            var o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException("content policy version");
            Set<String> ids = new HashSet<>(); List<String> prefixes = new ArrayList<>();
            o.getAsJsonArray("retiredIds").forEach(v -> ids.add(v.getAsString()));
            o.getAsJsonArray("retiredPrefixes").forEach(v -> { if (v.getAsString().isBlank()) throw new IllegalArgumentException("empty retired prefix"); prefixes.add(v.getAsString()); });
            int basic = o.get("catalogMaterialLevel").getAsInt(), full = o.get("catalogFullMaterialLevel").getAsInt();
            if (basic < 1 || full < basic || full > 10) throw new IllegalArgumentException("material policy levels");
            Set<String> tools = new HashSet<>();
            if (o.has("retiredTools")) o.getAsJsonArray("retiredTools").forEach(v -> tools.add(v.getAsString()));
            Set<String> abilities = new HashSet<>();
            if (o.has("retiredAbilities")) o.getAsJsonArray("retiredAbilities").forEach(v -> abilities.add(v.getAsString()));
            return new BuildingContentPolicy(Set.copyOf(ids), List.copyOf(prefixes), basic, full, Set.copyOf(tools), Set.copyOf(abilities));
        } catch (java.io.IOException bad) { throw new IllegalStateException(bad); }
    }
}
