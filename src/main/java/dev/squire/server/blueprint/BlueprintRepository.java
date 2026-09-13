package dev.squire.server.blueprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Optional;
import net.minecraft.util.Identifier;

/** Bounded, on-demand immutable geometry cache. Active placements own their snapshots. */
public final class BlueprintRepository {
    private final BlueprintLoader loader;
    private final LinkedHashMap<String, Blueprint> cache = new LinkedHashMap<>(16, .75f, true);
    private BlueprintImporter.ResourceProvider resources;
    public BlueprintRepository(BlueprintLoader loader) { this.loader = loader; }
    public synchronized void reload(BlueprintImporter.ResourceProvider provider) { resources = provider; cache.clear(); }
    public synchronized int cachedCount() { return cache.size(); }
    public synchronized Optional<Blueprint> get(BuildingCatalog.BuildingVariantDefinition definition) {
        if (!definition.buildable()) return Optional.empty();
        Blueprint cached = cache.get(definition.id());
        if (cached != null) return Optional.of(cached);
        try {
            BlueprintImporter.ResourceProvider provider = id -> {
                if (resources != null) return resources.open(id);
                var input = BlueprintRepository.class.getClassLoader().getResourceAsStream(BundledBlueprintPack.resourcePath(id));
                if (input == null) throw new IOException("missing blueprint resource " + id);
                return input;
            };
            Blueprint parsed;
            try (var input = provider.open(new Identifier(definition.descriptor()))) {
                parsed = loader.load(definition.id(), new String(input.readAllBytes(), StandardCharsets.UTF_8), provider);
            }
            var m = parsed.metadata();
            if (!parsed.id().equals(definition.id())) throw new IllegalArgumentException("catalog/descriptor ID mismatch");
            parsed = new Blueprint(parsed.id(), definition.displayName(), Math.max(1, definition.tier()), parsed.category(),
                parsed.width(), parsed.height(), parsed.depth(), parsed.steps(), parsed.requiredAbilities(), parsed.materialSlots(),
                new Blueprint.Metadata(m.author(), m.source(), m.license(), m.style(), m.description(), m.tags(), m.format(), definition.requiredEngineerLevel(), m.siteRequirements()));
            cache.put(definition.id(), parsed);
            while (cache.size() > 8 || cache.values().stream().mapToLong(b -> b.steps().stream().mapToLong(s ->
                    (long)(Math.abs(s.x2()-s.x1())+1)*(Math.abs(s.y2()-s.y1())+1)*(Math.abs(s.z2()-s.z1())+1)).sum()).sum() > 131072) {
                cache.remove(cache.keySet().iterator().next());
            }
            return Optional.of(parsed);
        } catch (IOException | RuntimeException bad) {
            org.slf4j.LoggerFactory.getLogger(BlueprintRepository.class).warn("Cannot load {}: {}", definition.id(), bad.toString());
            return Optional.empty();
        }
    }
}
