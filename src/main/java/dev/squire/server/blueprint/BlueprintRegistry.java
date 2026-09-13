package dev.squire.server.blueprint;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** Server-authoritative catalog of bundled and data-pack blueprints. */
public final class BlueprintRegistry {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BlueprintRegistry.class);

	/** Descriptor location under {@code data/<namespace>/blueprints/}. */
	public static final String RESOURCE_DIR = "blueprints";
	public static final String ABILITY_BUILD = "build.blueprint";
	public static final String ABILITY_EXCAVATE = "build.excavate";

	private final Map<String, Blueprint> byId = new LinkedHashMap<>();
	private final MaterialFamilyRegistry materials = new MaterialFamilyRegistry();
	private final BlueprintLoader loader = new BlueprintLoader();
	private BuildingCatalog catalog = BuildingCatalog.bundled();
	private final BlueprintRepository repository = new BlueprintRepository(loader);
	public BuildingCatalog catalog() { return catalog; }
	public BlueprintRepository repository() { return repository; }
	private long revision = System.nanoTime();
	public long revision() { return revision; }

	public BlueprintRegistry() { resetBundled(); }

	public synchronized void register(Blueprint blueprint) {
		byId.put(normalize(blueprint.id()), blueprint);
		revision++;
	}

	public Optional<Blueprint> byId(String id) {
		String normalized = normalize(id);
		var terrain = TerrainLeveling.parse(normalized);
		if (terrain.isPresent()) return Optional.of(terrain.get().blueprint());
		var definition = catalog.variant(normalized).orElse(null);
		if (definition != null) return repository.get(definition);
		Blueprint registered = byId.get(normalized);
		return registered != null ? Optional.of(registered)
			: ProjectBlueprintFactory.byId(normalized);
	}

	public MaterialFamilyRegistry materials() { return materials; }
	public List<Blueprint> all() { return List.copyOf(byId.values()); }
	public int size() { return byId.size(); }
	public List<String> ids() { return List.copyOf(byId.keySet()); }
	public List<String> playerIds() {
		var ids = new java.util.LinkedHashSet<String>();
		catalog.variants().stream().filter(BuildingCatalog.BuildingVariantDefinition::buildable).forEach(v -> ids.add(v.id()));
		byId.keySet().stream().filter(id -> !BuildingContentPolicy.current().retired(id)).forEach(ids::add);
		return List.copyOf(ids);
	}
	public java.util.Set<String> supportedFormats() { return loader.formats(); }

	/** Reload data-pack descriptors; same ids override bundled definitions. */
	public synchronized String reload(ResourceManager resources) {
		String materialSummary = materials.reload(resources);
		resetBundled();
		if (resources == null) return "内置 " + byId.size() + " 份（没有可读的数据包）";
		int loaded = 0;
		int failed = 0;
		Map<Identifier, net.minecraft.resource.Resource> found = resources.findResources(
			RESOURCE_DIR, path -> path.getPath().endsWith(".json"));
		BlueprintImporter.ResourceProvider provider = id -> resources.getResource(id)
			.orElseThrow(() -> new java.io.IOException("missing resource " + id))
			.getInputStream();
		repository.reload(provider);
		dev.squire.server.profession.EngineerProgression.reload(provider);
		BuildingContentPolicy.reload(provider);
		try {
			var packs = new java.util.ArrayList<BuildingCatalog>();
			var indexes = resources.findResources("building_catalog", id -> id.getPath().endsWith(".json")
				&& id.getPath().substring("building_catalog/".length()).indexOf('/') < 0);
			for (var id : indexes.keySet().stream().sorted(java.util.Comparator.comparing(Identifier::toString)).toList()) {
				try (var input = indexes.get(id).getInputStream()) { packs.add(BuildingCatalog.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))); }
			}
			if (packs.isEmpty()) throw new IllegalArgumentException("empty building catalog");
			catalog = BuildingCatalog.merge(packs);
		} catch (Exception bad) { LOG.warn("Keeping previous building catalog: {}", bad.toString()); }
		for (var entry : found.entrySet()) {
			try (var input = entry.getValue().getInputStream()) {
				String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
				Blueprint blueprint = loader.load(BundledBlueprintPack.fallbackId(entry.getKey()),
					json, provider);
				register(blueprint);
				loaded++;
			} catch (Exception bad) {
				failed++;
				LOG.warn("[blueprint] skipped {}: {}", entry.getKey(), bad.toString());
			}
		}
		return "建筑目录 " + catalog.families().size() + " 家族 / " + catalog.variants().size() + " 变体，"
			+ catalog.variants().stream().filter(BuildingCatalog.BuildingVariantDefinition::buildable).count() + " 份可建；兼容解析 " + byId.size() + " 份（数据包 " + loaded + " 份，跳过 "
			+ failed + " 份）；格式 " + String.join(" / ", loader.formats())
			+ "；材料家族 " + materialSummary;
	}

	private void resetBundled() {
		byId.clear();
		repository.reload(null);
		for (Blueprint blueprint : BundledBlueprintPack.load(loader)) register(blueprint);
	}

	private static String normalize(String id) {
		return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
	}
}
