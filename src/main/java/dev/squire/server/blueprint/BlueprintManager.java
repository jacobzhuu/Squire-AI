package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 蓝图子系统的服务端入口：注册表、摆放、材料账、幽灵预览的 tick。
 *
 * <p>刻意只做「数据 + 判定」，不发聊天消息也不提交任务——那些是玩家意图层
 * （{@code SquireBlueprintService}）和执行器的事。这样施工执行器可以在恢复时
 * 只凭一个 {@code placementId} 把形状重新算出来，而不必依赖任何会话状态。</p>
 */
public final class BlueprintManager {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(BlueprintManager.class);

	/** 第 1 期每人同时只允许一个活动摆放：先把一栋盖完，再摆下一栋。 */
	public static final int MAX_ACTIVE_PER_OWNER = 1;

	private final BlueprintRegistry registry = new BlueprintRegistry();
	private final Map<UUID, BlueprintPlacement> placements = new ConcurrentHashMap<>();
	private final Map<UUID, Long> previewRevisions = new ConcurrentHashMap<>();
	private final BlueprintPlacementStore store;
	private final java.util.function.Supplier<MinecraftServer> serverSupplier;

	public BlueprintManager(BlueprintPlacementStore store,
			java.util.function.Supplier<MinecraftServer> serverSupplier) {
		this.store = store;
		this.serverSupplier = serverSupplier;
	}

	public BlueprintRegistry registry() {
		return registry;
	}

	// ------------------------------------------------------------------ 摆放

	public Optional<BlueprintPlacement> placement(UUID placementId) {
		return Optional.ofNullable(placements.get(placementId));
	}

	/** 该玩家当前还在进行中的摆放。 */
	public Optional<BlueprintPlacement> activeOf(UUID ownerId) {
		return placements.values().stream()
			.filter(p -> p.ownerId.equals(ownerId) && p.active())
			.findFirst();
	}

	public List<BlueprintPlacement> all() {
		return List.copyOf(placements.values());
	}

	public void put(BlueprintPlacement placement) {
		placements.put(placement.placementId, placement);
		save();
	}

	public void remove(UUID placementId) {
		previewRevisions.remove(placementId);
		if (placements.remove(placementId) != null) {
			save();
		}
	}

	public boolean save() {
		return store.save(placements.values());
	}

	/** 启动时恢复。已完成/已取消的摆放不再留着，否则档案会无限长胖。 */
	public int load() {
		placements.clear();
		for (BlueprintPlacement placement : store.load()) {
			if (placement.active()) {
				placements.put(placement.placementId, placement);
			}
		}
		LOG.info("[blueprint] catalog: {} families, {} variants ({} buildable); {} active placement(s) restored",
			registry.catalog().families().size(), registry.catalog().variants().size(),
			registry.catalog().variants().stream().filter(BuildingCatalog.BuildingVariantDefinition::buildable).count(), placements.size());
		return placements.size();
	}

	// ------------------------------------------------------------------ 形状与账目

	public Optional<Blueprint.Resolved> resolve(BlueprintPlacement placement) {
		return resolve(placement, true);
	}

	/**
	 * Resolve the moving ghost without running the bounded path planner.
	 *
	 * <p>Ground preparation and the material bill are still refreshed, so the
	 * preview remains useful.  The expensive access review runs only when the
	 * player confirms the project through {@link #resolve(BlueprintPlacement)}.
	 * Committed projects always keep using their pinned full plan.</p>
	 */
	public Optional<Blueprint.Resolved> preview(BlueprintPlacement placement) {
		return resolve(placement, placement.committed());
	}

	private Optional<Blueprint.Resolved> resolve(BlueprintPlacement placement,
			boolean planAccess) {
		if (TerrainLeveling.parse(placement.blueprintId).isPresent()) {
			if (placement.snapshot() != null) return Optional.of(placement.snapshot());
			var server = serverSupplier.get();
			var world = server == null ? null : server.getWorld(net.minecraft.registry.RegistryKey.of(
				net.minecraft.registry.RegistryKeys.WORLD, new Identifier(placement.dimensionId)));
			if (world == null || placement.committed()) return Optional.empty();
			var plan = TerrainLeveling.plan(world, placement);
			placement.snapshot(plan.resolved(), false);
			placement.terrainReview = plan.fingerprint();
			return Optional.of(plan.resolved());
		}
		var worker = avatarLookup == null ? null : avatarLookup.apply(placement.agentId);
		int currentLevel = placement.legacyFullPrice() ? 0 : engineerLevel(worker);
		int currentWaste = placement.legacyFullPrice() ? 0 : dev.squire.server.profession.EngineerProgression.current().wasteBasisPoints(currentLevel);
		if (!placement.committed() && placement.snapshot() != null && placement.snapshot().costPlan() != null
				&& worker != null && worker.getWorld() instanceof ServerWorld terrainWorld
				&& placement.snapshot().siteRequirements().stream().anyMatch(q -> q.kind().equals("solid") && !q.satisfied(terrainWorld))) placement.invalidatePreview();
		if (!placement.committed() && placement.snapshot() != null && placement.snapshot().costPlan() != null
				&& worker != null && worker.getWorld() instanceof ServerWorld world
				&& !placement.snapshot().costPlan().sameFoundations(world, placement.snapshot())) placement.invalidatePreview();
		if (!placement.committed() && placement.snapshot() != null && worker != null
				&& worker.getWorld() instanceof ServerWorld world
				&& automaticSiteSupports(world, placement.snapshot()).stream().anyMatch(c -> {
					BlockState state = world.getBlockState(c.pos());
					return !state.isAir() && !state.isReplaceable() && !matches(state, c);
				})) {
			// A ghost may have been open while an older project left a block on a
			// future foundation cell. Rebuild terrain/access from the live world.
			placement.invalidatePreview();
		}
		if (!placement.committed() && placement.snapshot() != null && placement.snapshot().costPlan() != null
				&& (placement.snapshot().costPlan().level() != currentLevel
				|| placement.snapshot().costPlan().wasteBasisPoints() != currentWaste))
			placement.invalidatePreview();
		if (placement.committed() && placement.snapshot() != null && placement.snapshot().costPlan() == null && worker != null
				&& worker.getWorld() instanceof ServerWorld world && world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
			// Existing worlds keep their original full-price bill, never acquire new waste on migration.
			placement.snapshot(placement.snapshot().withCostPlan(ConstructionCostPlan.create(world, placement.snapshot(), 0, 0)), true);
		}
		if (!placement.committed() && previewRevisions.getOrDefault(placement.placementId, -1L) != registry.revision())
			placement.invalidatePreview();
		if (placement.snapshot() != null) {
			if (planAccess && !placement.committed() && placement.snapshot().access() != null && !placement.snapshot().access().valid())
				placement.invalidatePreview();
		}
		if (placement.snapshot() != null) {
			// A preview resolved while the body was unloaded has geometry but no
			// access review. Retry once the body is available; committed jobs stay pinned.
			if (planAccess && !placement.committed() && placement.snapshot().access() == null
					&& avatarLookup != null && avatarLookup.apply(placement.agentId) != null)
				placement.invalidatePreview();
			else return Optional.of(placement.snapshot());
		}
		return registry.byId(placement.blueprintId).map(bp -> {
			var resolved = bp.resolve(placement.origin, placement.facing, placement.materials(), registry.materials());
			if (placement.artificialWater()) resolved = ArtificialWaterSite.prepare(resolved);
			var avatar = avatarLookup == null ? null : avatarLookup.apply(placement.agentId);
			if (avatar != null && avatar.getWorld() instanceof ServerWorld world
					&& world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) {
				ConstructionAccessPlan access = null;
				try {
					resolved = GroundPreparation.prepare(world, resolved);
					if (planAccess) access = ConstructionAccessPlan.plan(world, resolved, avatar);
				} catch (IllegalArgumentException unsafeGround) {
					if (planAccess) access = new ConstructionAccessPlan(List.of(), resolved.bounds(), "自动地基无法安全规划：" + unsafeGround.getMessage());
				}
				resolved = new Blueprint.Resolved(resolved.bounds(), resolved.toPlace(), resolved.toClear(), access, null, resolved.siteRequirements());
				resolved = resolved.withCostPlan(ConstructionCostPlan.create(world, resolved, currentLevel, currentWaste));
				resolved.costPlan().waterSource(placement.waterSource());
			}
			placement.snapshot(resolved, false);
			previewRevisions.put(placement.placementId, registry.revision());
			return resolved;
		});
	}

	private java.util.function.Function<UUID, AvatarEntity> avatarLookup;
	private static int engineerLevel(AvatarEntity worker) {
		return worker != null && worker.profile() != null
			&& worker.profile().profession.profession() == dev.squire.server.profession.SquireProfession.ENGINEER
			? worker.profile().profession.level : 1;
	}
	public void attachAvatarLookup(java.util.function.Function<UUID, AvatarEntity> lookup) { avatarLookup = lookup; }

	/** 还没变成目标方块、需要真正放下去的格子。 */
	public static List<Blueprint.Cell> pendingPlacements(ServerWorld world,
			Blueprint.Resolved resolved) {
		List<Blueprint.Cell> out = new ArrayList<>();
		for (Blueprint.Cell cell : resolved.toPlace()) {
			if (!matches(world.getBlockState(cell.pos()), cell)) {
				out.add(cell);
			}
		}
		return out;
	}

	/**
	 * Extra one-block foundation generated for a durable project when the blueprint's
	 * lowest structural layer would otherwise sit directly over air or fluid.
	 *
	 * <p>The support uses the most common material on that lowest layer, so it remains
	 * part of the real material bill instead of conjuring a hard-coded block.  Only
	 * columns that the blueprint actually occupies are considered; door openings,
	 * shafts and empty corners of a sparse bounding box are never filled.</p>
	 */
	public static List<Blueprint.Cell> automaticSiteSupports(ServerWorld world,
			Blueprint.Resolved resolved) {
		if (world == null || resolved == null || resolved.toPlace().isEmpty()) {
			return List.of();
		}
		if (TerrainLeveling.isTerrain(resolved)) return resolved.toPlace().stream()
			.filter(c -> !matches(world.getBlockState(c.pos()), c))
			.sorted(Comparator.comparingInt(c -> c.pos().getY())).toList();
		int baseY = resolved.toPlace().stream().filter(cell -> !cell.optional())
			.mapToInt(cell -> cell.pos().getY()).min().orElse(Integer.MAX_VALUE);
		if (baseY == Integer.MAX_VALUE) return List.of();

		List<Blueprint.Cell> baseCells = resolved.toPlace().stream()
			.filter(cell -> !cell.optional() && !ConstructionFluids.liquid(cell) && cell.pos().getY() == baseY).toList();
		Map<String, Integer> frequencies = new LinkedHashMap<>();
		for (Blueprint.Cell cell : baseCells) {
			frequencies.merge(cell.blockId(), 1, Integer::sum);
		}
		String supportBlock = frequencies.entrySet().stream()
			.max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		if (supportBlock == null) return List.of();

		java.util.Set<BlockPos> managed = footprint(resolved);
		resolved.siteRequirements().stream().filter(q -> q.kind().equals("fluid")).forEach(q -> managed.add(q.pos()));
		List<Blueprint.Cell> supports = new ArrayList<>();
		resolved.toPlace().stream().filter(c -> GroundPreparation.FILL.equals(c.what())
			&& !matches(world.getBlockState(c.pos()), c)).forEach(supports::add);
		for (Blueprint.Cell cell : baseCells) {
			BlockPos below = cell.pos().down().toImmutable();
			if (managed.contains(below)) continue;
			BlockState current = world.getBlockState(below);
			if (!current.isAir() && world.getFluidState(below).isEmpty()) continue;
			if (GroundPreparation.elevatedAirGap(world, below)) continue;
			supports.add(new Blueprint.Cell(below, supportBlock, Map.of(),
				Integer.MIN_VALUE, "自动承重基础", false));
		}
		supports.sort(Comparator.comparingInt((Blueprint.Cell cell) -> cell.pos().getY())
			.thenComparingInt(cell -> cell.pos().getX())
			.thenComparingInt(cell -> cell.pos().getZ()));
		return List.copyOf(supports);
	}

	/** Pending foundation preparation followed by the blueprint's ordinary cells. */
	public static List<Blueprint.Cell> pendingProjectPlacements(ServerWorld world,
			Blueprint.Resolved resolved) {
		List<Blueprint.Cell> out = new ArrayList<>(automaticSiteSupports(world, resolved));
		out.addAll(pendingPlacements(world, resolved));
		return List.copyOf(out);
	}

	/** The deterministic worker plan used by placement, accounting and recovery. */
	public static ConstructionPlan constructionPlan(ServerWorld world,
			Blueprint.Resolved resolved, boolean includeSiteSupports) {
		List<Blueprint.Cell> cells = includeSiteSupports
			? pendingProjectPlacements(world, resolved)
			: pendingPlacements(world, resolved);
		return ConstructionPlan.create(resolved, cells);
	}

	/** Site preparation is deliberately a support-only construction pass. */
	public static ConstructionPlan sitePreparationPlan(ServerWorld world,
			Blueprint.Resolved resolved) {
		return ConstructionPlan.create(resolved, automaticSiteSupports(world, resolved));
	}

	/** Region touched by a project, including its automatically generated support layer. */
	public static BoundedRegion projectBounds(ServerWorld world,
			Blueprint.Resolved resolved) {
		return ConstructionPlan.create(resolved, automaticSiteSupports(world, resolved))
			.bounds();
	}

	/**
	 * 开工前还需要清掉的格子。两类：
	 *
	 * <ul>
	 *   <li><b>设计上的负空间</b>——掏空的内部、门洞、竖井（空地上它们本来就是
	 *       空气，一格都不用挖）；</li>
	 *   <li><b>占住了施工位的障碍物</b>——地不平时埋在土里的那些墙体格。</li>
	 * </ul>
	 *
	 * <p>第二类以前不在这里，后果是一个安静的死锁：施工执行器把它们当「已被占」跳过，
	 * 任务报 WORK_DONE，而成功条件读到那几格不是目标方块于是永远不满足——任务卡在
	 * VERIFYING 直到超时，玩家看到的就是「盖了几块就停在那儿了」。平整场地本来就是
	 * 施工的一部分，而且它全部发生在蓝图自己的足印内（ADR-038 允许的范围）。</p>
	 */
	public static List<BlockPos> pendingClear(ServerWorld world,
			Blueprint.Resolved resolved) {
		List<BlockPos> out = new ArrayList<>();
		java.util.Set<BlockPos> preserved = new java.util.HashSet<>();
		resolved.siteRequirements().stream().filter(q -> q.kind().equals("fluid")).forEach(q -> preserved.add(q.pos()));
		resolved.toPlace().stream()
			.filter(c -> matches(world.getBlockState(c.pos()), c)).forEach(c -> preserved.add(c.pos()));
		for (BlockPos pos : resolved.toClear()) {
			if (preserved.contains(pos)) continue;
			if (!world.getBlockState(pos).isAir()) {
				out.add(pos);
			}
		}
		for (Blueprint.Cell cell : resolved.toPlace()) {
			BlockState current = world.getBlockState(cell.pos());
			if (ConstructionFluids.wet(cell) && matches(current, ConstructionFluids.dry(cell))) continue;
			if (!current.isAir() && !current.isReplaceable()
					&& !matches(current, cell)) {
				out.add(cell.pos());
			}
		}
		// Gravity is observable between individual breaks.  Clearing a sand/gravel
		// column from the bottom up lets every block above fall back into a cell that
		// was already processed, leaving construction to retry forever.  The
		// blueprint's negative-space list was already top-down; site obstructions
		// appended above were not.  Sort the combined, de-duplicated work list here so
		// every caller gets the safe excavation order.
		out = new ArrayList<>(new LinkedHashSet<>(out));
		out.sort(Comparator.comparingInt(BlockPos::getY).reversed()
			.thenComparingInt(BlockPos::getX)
			.thenComparingInt(BlockPos::getZ));
		return out;
	}

	/** 这份摆放允许动的全部格子（负空间 + 施工位）。掘进的硬拦用它。 */
	public static java.util.Set<BlockPos> footprint(Blueprint.Resolved resolved) {
		java.util.Set<BlockPos> out = new java.util.HashSet<>(resolved.toClear());
		for (Blueprint.Cell cell : resolved.toPlace()) {
			out.add(cell.pos());
		}
		return out;
	}

	/**
	 * 材料账。只统计「还没就位」的格子，所以在半成品上再算一次得到的是剩余量，
	 * 不是每次都吓人的总量。
	 */
	public static BillOfMaterials bill(ServerWorld world, Blueprint.Resolved resolved,
			dev.squire.server.body.avatar.AvatarInventory inventory) {
		Map<Identifier, Integer> required = new LinkedHashMap<>();
		Map<Identifier, Integer> inPlace = new LinkedHashMap<>();
		for (Blueprint.Cell cell : resolved.toPlace()) {
			Identifier id = itemId(cell.blockId());
			if (matches(world.getBlockState(cell.pos()), cell)) {
				inPlace.merge(id, itemCost(cell), Integer::sum);
			} else {
				required.merge(id, itemCost(cell), Integer::sum);
			}
		}
		if (resolved.costPlan() != null) {
			required.clear(); required.putAll(resolved.costPlan().remaining(world));
		} else for (var c : resolved.toPlace()) if (ConstructionFluids.wet(c) && !ConstructionFluids.matches(world, c))
			required.merge(ConstructionFluids.WATER_BUCKET, 1, Integer::sum);
		Map<Identifier, Integer> carried = new LinkedHashMap<>();
		if (inventory != null) {
			for (Identifier id : required.keySet()) {
				carried.put(id, inventory.countOf(id));
			}
		}
		return new BillOfMaterials(required, inPlace, carried);
	}

	/**
	 * 当前工程真正还缺的全部消耗品：施工方块加后续照明使用的真实火把。
	 * 这样玩家在“备料”时就能一次交齐，不会盖完房子才突然卡在点灯。
	 */
	public static Map<Identifier, Integer> missingMaterials(ServerWorld world,
			Blueprint.Resolved resolved, dev.squire.server.body.avatar.AvatarEntity avatar) {
		return missingProjectMaterials(world, resolved, null, avatar);
	}

	/**
	 * Required real items for a durable project escrow.  Unlike the preview bill,
	 * optional decoration never blocks the project; players may add it separately.
	 * Existing matching world blocks are excluded so retries reserve only the delta.
	 */
	public static Map<Identifier, Integer> requiredProjectMaterials(ServerWorld world,
			Blueprint.Resolved resolved) {
		Map<Identifier, Integer> required = new LinkedHashMap<>();
		if (world == null || resolved == null) return Map.of();
		if (resolved.costPlan() != null) required.putAll(resolved.costPlan().remaining(world));
		else for (Blueprint.Cell cell : constructionPlan(world, resolved, true).cells()) {
			if (!cell.optional() && !matches(world.getBlockState(cell.pos()), cell)) {
				int cost = itemCost(cell);
				if (cost > 0) required.merge(itemId(cell.blockId()), cost, Integer::sum);
			}
		}
		if (resolved.costPlan() == null) for (var c : ConstructionFluids.operations(resolved))
			if (!ConstructionFluids.matches(world, c)) required.merge(ConstructionFluids.bucket(c), 1, Integer::sum);
		// Before construction, current light/air cannot predict the finished rooms.
		// Once the structure is complete, only outstanding planned lights cost items.
		boolean structuralWork = !required.isEmpty() || resolved.toClear().stream()
			.anyMatch(pos -> !world.getBlockState(pos).isAir()
				&& !world.getBlockState(pos).isOf(net.minecraft.block.Blocks.TORCH));
		int torches = TerrainLeveling.isTerrain(resolved) ? 0 : structuralWork
			? dev.squire.server.world.Torchlight.projectTorchRequirement(world, resolved)
			: dev.squire.server.world.Torchlight.remainingProjectTorchRequirement(world, resolved);
		if (torches > 0) {
			required.merge(dev.squire.server.world.Torchlight.TORCH, torches, Integer::sum);
		}
		if (resolved.access() != null)
			resolved.access().remainingTemporaryMaterials().forEach((item, count) -> required.merge(item, count, Integer::sum));
		return Map.copyOf(required);
	}

	/**
	 * Shortfalls shown before project confirmation.  Confirmation can reserve from both
	 * the owner's ordinary inventory and the companion's inventory/backpack, so the UI
	 * must count that exact same stock.  The old preview only counted the companion and
	 * could say "missing" while the start operation was already fully funded.
	 */
	public static Map<Identifier, Integer> missingProjectMaterials(ServerWorld world,
			Blueprint.Resolved resolved,
			ServerPlayerEntity owner, AvatarEntity avatar) {
		return missingProjectMaterials(world, resolved, owner,
			avatar == null ? null : avatar.items(), Map.of());
	}

	/** Shared chat/UI/ghost ledger: remaining work minus escrow and both real inventories. */
	public static Map<Identifier, Integer> missingProjectMaterials(ServerWorld world,
			Blueprint.Resolved resolved, ServerPlayerEntity owner, AvatarEntity avatar,
			Map<Identifier, Integer> reserved) {
		return missingProjectMaterials(world, resolved, owner,
			avatar == null ? null : avatar.items(), reserved);
	}

	private static Map<Identifier, Integer> missingProjectMaterials(ServerWorld world,
			Blueprint.Resolved resolved, ServerPlayerEntity owner,
			AvatarInventory companionInventory, Map<Identifier, Integer> reserved) {
		Map<Identifier, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<Identifier, Integer> entry
				: requiredProjectMaterials(world, resolved).entrySet()) {
			Identifier id = entry.getKey();
			int available = companionInventory == null ? 0
				: companionInventory.countOf(id);
			available += reserved == null ? 0 : reserved.getOrDefault(id, 0);
			if (resolved.costPlan() != null && resolved.costPlan().waterSource() != null && id.equals(ConstructionFluids.BUCKET))
				available += reserved == null ? 0 : reserved.getOrDefault(ConstructionFluids.WATER_BUCKET, 0);
			if (owner != null) {
				var item = Registries.ITEM.get(id);
				for (int slot = 0; slot < 36; slot++) {
					var stack = owner.getInventory().getStack(slot);
					if (stack.isOf(item)) available += stack.getCount();
				}
			}
			int deficit = entry.getValue() - available;
			if (deficit > 0) missing.put(id, deficit);
		}
		return Map.copyOf(missing);
	}

	/** Material delta after a partially completed lighting stage. */
	public static Map<Identifier, Integer> remainingProjectMaterials(ServerWorld world,
			Blueprint.Resolved resolved) {
		return requiredProjectMaterials(world, resolved);
	}

	/** 蓝图里写的 blockId 对应的<b>物品</b> id；两者在原版里同名，这里只做一次解析。 */
	public static Identifier itemId(String blockId) {
		if (blockId.equals("minecraft:water")) return ConstructionFluids.WATER_BUCKET;
		if (blockId.equals("minecraft:lava")) return ConstructionFluids.LAVA_BUCKET;
		Identifier id = new Identifier(blockId);
		var block = Registries.BLOCK.get(id);
		return block.asItem() == net.minecraft.item.Items.AIR ? id : Registries.ITEM.getId(block.asItem());
	}

	public static int itemCost(Blueprint.Cell cell) {
		if (ConstructionFluids.liquid(cell)) return ConstructionFluids.source(cell) ? 1 : 0;
		var block = Registries.BLOCK.get(new Identifier(cell.blockId()));
		String quantity = block instanceof net.minecraft.block.CandleBlock ? "candles"
			: block instanceof net.minecraft.block.SeaPickleBlock ? "pickles"
			: block instanceof net.minecraft.block.TurtleEggBlock ? "eggs"
			: block instanceof net.minecraft.block.SnowBlock ? "layers" : null;
		if (quantity != null) return Math.max(1, Integer.parseInt(cell.properties().getOrDefault(quantity, "1")));
		if ("upper".equals(cell.properties().get("half"))
				&& (block instanceof net.minecraft.block.DoorBlock || block instanceof net.minecraft.block.TallPlantBlock)) return 0;
		if ("head".equals(cell.properties().get("part"))
				&& Registries.BLOCK.get(new Identifier(cell.blockId())) instanceof net.minecraft.block.BedBlock) return 0;
		return "double".equals(cell.properties().get("type"))
			&& Registries.BLOCK.get(new Identifier(cell.blockId())) instanceof net.minecraft.block.SlabBlock ? 2 : 1;
	}

	/** 这个 blockId 真的能被伙伴放下去吗（存在、且有对应的方块物品）。 */
	public static boolean placeable(String blockId) {
		return ConstructionFluids.liquid(blockId) || Registries.ITEM.get(itemId(blockId)) instanceof BlockItem;
	}

	/** 世界里这一格是不是已经是目标方块了。 */
	public static boolean matches(BlockState state, String blockId) {
		return Registries.BLOCK.containsId(new Identifier(blockId))
			&& state.isOf(Registries.BLOCK.get(new Identifier(blockId)));
	}

	/** Exact target match, including authored facing/axis/half/type properties. */
	public static boolean matches(BlockState state, Blueprint.Cell cell) {
		if (GroundPreparation.FILL.equals(cell.what()) && cell.blockId().equals("minecraft:dirt")
				&& (state.isOf(net.minecraft.block.Blocks.GRASS_BLOCK) || state.isOf(net.minecraft.block.Blocks.MYCELIUM))) return true;
		if (!matches(state, cell.blockId())) return false;
		if (ConstructionFluids.liquid(cell)) return !ConstructionFluids.source(cell) || state.getFluidState().isStill();
		BlockState target = targetState(cell);
		for (Property<?> property : target.getProperties()) {
            // Walking through a built door changes its operational state, not its structure.
            // Keep material, facing, hinge and half exact; do not reopen settled material bills.
            if (target.getBlock() instanceof net.minecraft.block.DoorBlock
                    && (property == net.minecraft.state.property.Properties.OPEN
                        || property == net.minecraft.state.property.Properties.POWERED)) continue;
			if (cell.properties().containsKey(property.getName())
					&& !sameProperty(state, target, property)) return false;
		}
		return true;
	}

	private static <T extends Comparable<T>> boolean sameProperty(BlockState actual,
			BlockState target, Property<T> property) {
		return actual.contains(property) && actual.get(property).equals(target.get(property));
	}

	public static BlockState targetState(Blueprint.Cell cell) {
		if (!placeable(cell.blockId())) {
			throw new IllegalArgumentException("unplaceable block " + cell.blockId());
		}
		BlockState state = Registries.BLOCK.get(new Identifier(cell.blockId())).getDefaultState();
		for (var entry : cell.properties().entrySet()) {
			Property<?> found = state.getProperties().stream()
				.filter(p -> p.getName().equals(entry.getKey())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException(cell.blockId()
					+ " has no property " + entry.getKey()));
			state = withProperty(state, found, entry.getValue());
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState withProperty(BlockState state,
			Property<T> property, String value) {
		T parsed = property.parse(value).orElseThrow(() -> new IllegalArgumentException(
			"invalid " + property.getName() + "=" + value));
		return state.with(property, parsed);
	}

	/**
	 * 同上，但「通用建材」能力在场时也认等价组里的方块。
	 *
	 * <p>没有这个重载，一个用云杉木板顶替橡木板盖起来的墙会被成功条件判成「没盖」，
	 * 任务永远卡在 VERIFYING——能力给了玩家一条路，验收却不认，比不给还糟。</p>
	 */
	public static boolean matches(BlockState state, String blockId,
			boolean allowSubstitutes) {
		if (matches(state, blockId)) {
			return true;
		}
		if (!allowSubstitutes) {
			return false;
		}
		Identifier actual = Registries.BLOCK.getId(state.getBlock());
		return dev.squire.server.profile.MaterialSubstitutes.equivalent(
			itemId(blockId), actual);
	}

	public static boolean matches(BlockState state, Blueprint.Cell cell,
			boolean allowSubstitutes) {
		if (matches(state, cell)) return true;
		if (!allowSubstitutes || !cell.properties().isEmpty()) return false;
		Identifier actual = Registries.BLOCK.getId(state.getBlock());
		return dev.squire.server.profile.MaterialSubstitutes.equivalent(
			itemId(cell.blockId()), actual);
	}

	/** 蓝图里有没有 {@link #placeable} 说不出来的方块——数据包写错时要说得清。 */
	public static List<String> unknownBlocks(Blueprint blueprint) {
		return unknownBlocks(blueprint, MaterialFamilyRegistry.defaults());
	}

	public static List<String> unknownBlocks(Blueprint blueprint,
			MaterialFamilyRegistry families) {
		List<String> bad = new ArrayList<>();
		try {
			for (Blueprint.Cell cell : blueprint.resolve(BlockPos.ORIGIN,
					net.minecraft.util.math.Direction.NORTH,
					blueprint.defaultPalette(), families).toPlace()) {
				if (!placeable(cell.blockId()) && !bad.contains(cell.blockId())) {
					bad.add(cell.blockId());
				} else if (placeable(cell.blockId())) {
					targetState(cell); // validates state-property names and values too
				}
			}
		} catch (IllegalArgumentException invalid) {
			bad.add(invalid.getMessage());
		}
		return bad;
	}

	// ------------------------------------------------------------------ 幽灵预览

	/**
	 * 每 tick 调一次；内部按 {@link BlueprintGhost#INTERVAL_TICKS} 抽稀。
	 * 只给<b>摆放的主人本人</b>发粒子，而且他必须在线、同维度、够近。
	 */
	public void tick(long tick) {
		if (placements.isEmpty() || tick % BlueprintGhost.INTERVAL_TICKS != 0) {
			return;
		}
		MinecraftServer server = serverSupplier == null ? null : serverSupplier.get();
		if (server == null) {
			return;
		}
		for (BlueprintPlacement placement : placements.values()) {
			if (!placement.active()) {
				continue;
			}
			ServerPlayerEntity owner = server.getPlayerManager()
				.getPlayer(placement.ownerId);
			if (owner == null || !(owner.getWorld() instanceof ServerWorld world)
					|| !world.getRegistryKey().getValue().toString()
						.equals(placement.dimensionId)) {
				continue;
			}
			Blueprint.Resolved resolved = preview(placement).orElse(null);
			if (resolved == null) {
				continue;
			}
			AvatarInventory inventory = inventoryOf(placement);
			Map<Identifier, Integer> reserved = projectSupplyLookup == null ? Map.of()
				: projectSupplyLookup.apply(placement.placementId);
			Map<Identifier, Integer> missing = missingProjectMaterials(world, resolved,
				owner, inventory, reserved == null ? Map.of() : reserved);
			// 白 = 还不知道伙伴手里有什么（没有绑定随从），红 = 缺料，绿 = 可以开工。
			// 这里和工程页/确认开工一样合计玩家与伙伴库存，避免页面显示已齐、
			// 幽灵轮廓却仍然是红色。
			BlueprintGhost.Tint tint = inventory == null ? BlueprintGhost.Tint.PENDING
				: missing.isEmpty() ? BlueprintGhost.Tint.READY
				: BlueprintGhost.Tint.MISSING;
			BlueprintGhost.draw(world, owner, resolved, tint);
			if (resolved.access() != null) BlueprintGhost.drawAccess(world, owner, resolved.access());
		}
	}

	/** 幽灵着色要看「伙伴身上有多少料」，取不到就当空手，颜色偏保守而不是撒谎。 */
	private AvatarInventory inventoryOf(BlueprintPlacement placement) {
		if (placement.agentId == null || inventoryLookup == null) {
			return null;
		}
		return inventoryLookup.apply(placement.agentId);
	}

	private java.util.function.Function<UUID, AvatarInventory> inventoryLookup;
	private java.util.function.Function<UUID, Map<Identifier, Integer>> projectSupplyLookup;

	/** 由运行时注入：agentId → 该伙伴的真实背包。 */
	public void attachInventoryLookup(
			java.util.function.Function<UUID, AvatarInventory> lookup) {
		this.inventoryLookup = lookup;
	}

	/** Injects placement-id to durable project escrow for truthful preview tinting. */
	public void attachProjectSupplyLookup(
			java.util.function.Function<UUID, Map<Identifier, Integer>> lookup) {
		this.projectSupplyLookup = lookup;
	}
}
