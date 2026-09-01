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
		if (placements.remove(placementId) != null) {
			save();
		}
	}

	public void save() {
		store.save(placements.values());
	}

	/** 启动时恢复。已完成/已取消的摆放不再留着，否则档案会无限长胖。 */
	public int load() {
		placements.clear();
		for (BlueprintPlacement placement : store.load()) {
			if (placement.active()) {
				placements.put(placement.placementId, placement);
			}
		}
		LOG.info("[blueprint] {} blueprint(s), {} active placement(s) restored",
			registry.size(), placements.size());
		return placements.size();
	}

	// ------------------------------------------------------------------ 形状与账目

	public Optional<Blueprint.Resolved> resolve(BlueprintPlacement placement) {
		return registry.byId(placement.blueprintId)
			.map(bp -> bp.resolve(placement.origin, placement.facing,
				placement.materials(), registry.materials()));
	}

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
		for (BlockPos pos : resolved.toClear()) {
			if (!world.getBlockState(pos).isAir()) {
				out.add(pos);
			}
		}
		for (Blueprint.Cell cell : resolved.toPlace()) {
			BlockState current = world.getBlockState(cell.pos());
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
				inPlace.merge(id, 1, Integer::sum);
			} else {
				required.merge(id, 1, Integer::sum);
			}
		}
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
		Map<Identifier, Integer> missing = new LinkedHashMap<>(
			bill(world, resolved, avatar == null ? null : avatar.items()).missing());
		int torches = dev.squire.server.world.Torchlight.lightingDeficit(
			world, resolved, avatar);
		if (torches > 0) {
			missing.merge(dev.squire.server.world.Torchlight.TORCH, torches, Integer::sum);
		}
		return Map.copyOf(missing);
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
		for (Blueprint.Cell cell : resolved.toPlace()) {
			if (!cell.optional() && !matches(world.getBlockState(cell.pos()), cell)) {
				required.merge(itemId(cell.blockId()), 1, Integer::sum);
			}
		}
		if (!dev.squire.server.world.Torchlight.brightEnough(world,
				dev.squire.server.world.Torchlight.candidates(resolved))) {
			required.merge(dev.squire.server.world.Torchlight.TORCH,
				dev.squire.server.world.Torchlight.projectTorchRequirement(world, resolved),
				Integer::sum);
		}
		return Map.copyOf(required);
	}

	/** Material delta after a partially completed lighting stage. */
	public static Map<Identifier, Integer> remainingProjectMaterials(ServerWorld world,
			Blueprint.Resolved resolved) {
		Map<Identifier, Integer> required = new LinkedHashMap<>();
		if (world == null || resolved == null) return Map.of();
		for (Blueprint.Cell cell : resolved.toPlace()) {
			if (!cell.optional() && !matches(world.getBlockState(cell.pos()), cell)) {
				required.merge(itemId(cell.blockId()), 1, Integer::sum);
			}
		}
		int torches = dev.squire.server.world.Torchlight
			.remainingProjectTorchRequirement(world, resolved);
		if (torches > 0) {
			required.merge(dev.squire.server.world.Torchlight.TORCH, torches,
				Integer::sum);
		}
		return Map.copyOf(required);
	}

	/** 蓝图里写的 blockId 对应的<b>物品</b> id；两者在原版里同名，这里只做一次解析。 */
	public static Identifier itemId(String blockId) {
		int colon = blockId.indexOf(':');
		return colon < 0 ? new Identifier(blockId)
			: new Identifier(blockId.substring(0, colon), blockId.substring(colon + 1));
	}

	/** 这个 blockId 真的能被伙伴放下去吗（存在、且有对应的方块物品）。 */
	public static boolean placeable(String blockId) {
		return Registries.ITEM.get(itemId(blockId)) instanceof BlockItem;
	}

	/** 世界里这一格是不是已经是目标方块了。 */
	public static boolean matches(BlockState state, String blockId) {
		return Registries.ITEM.get(itemId(blockId)) instanceof BlockItem item
			&& state.isOf(item.getBlock());
	}

	/** Exact target match, including authored facing/axis/half/type properties. */
	public static boolean matches(BlockState state, Blueprint.Cell cell) {
		if (!matches(state, cell.blockId())) return false;
		BlockState target = targetState(cell);
		for (Property<?> property : target.getProperties()) {
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
		if (!(Registries.ITEM.get(itemId(cell.blockId())) instanceof BlockItem item)) {
			throw new IllegalArgumentException("unplaceable block " + cell.blockId());
		}
		BlockState state = item.getBlock().getDefaultState();
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
			Blueprint.Resolved resolved = resolve(placement).orElse(null);
			if (resolved == null) {
				continue;
			}
			var inventory = inventoryOf(placement);
			BillOfMaterials bill = bill(world, resolved, inventory);
			// 白 = 还不知道伙伴手里有什么（没有绑定随从），红 = 缺料，绿 = 可以开工。
			BlueprintGhost.Tint tint = inventory == null ? BlueprintGhost.Tint.PENDING
				: bill.satisfied() ? BlueprintGhost.Tint.READY
				: BlueprintGhost.Tint.MISSING;
			BoundedRegion bounds = resolved.bounds();
			BlueprintGhost.draw(world, owner, bounds, pendingClear(world, resolved), tint);
		}
	}

	/** 幽灵着色要看「伙伴身上有多少料」，取不到就当空手，颜色偏保守而不是撒谎。 */
	private dev.squire.server.body.avatar.AvatarInventory inventoryOf(
			BlueprintPlacement placement) {
		if (placement.agentId == null || inventoryLookup == null) {
			return null;
		}
		return inventoryLookup.apply(placement.agentId);
	}

	private java.util.function.Function<UUID,
		dev.squire.server.body.avatar.AvatarInventory> inventoryLookup;

	/** 由运行时注入：agentId → 该伙伴的真实背包。 */
	public void attachInventoryLookup(java.util.function.Function<UUID,
			dev.squire.server.body.avatar.AvatarInventory> lookup) {
		this.inventoryLookup = lookup;
	}
}
