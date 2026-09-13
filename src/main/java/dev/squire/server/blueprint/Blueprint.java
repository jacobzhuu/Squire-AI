package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 一份蓝图：形状是<b>数据</b>，不是代码。
 *
 * <p>玩家和模型都只能挑蓝图 id 和落点，永远不能自由构造坐标——这和
 * {@code HouseTemplate} 当初的理由一样（模型逐块摆放会盖出没有门的实心盒子），
 * 但现在这份形状可以从 {@code data/squire/blueprints/*.json} 加载，加一栋新建筑
 * 不用改一行 Java。</p>
 *
 * <h2>为什么要 {@link #resolve} 这一步</h2>
 * <p>作者写的是「先砌实心盒、再掏空内部」这种<b>覆盖式</b>步骤，读起来清楚；
 * 但照字面执行等于先花掉一百多块木板再把它们挖掉，材料账目立刻变成假的。
 * 所以施工前先把所有步骤<b>展平成每格的最终目标</b>：最后一条覆盖该格的步骤说了算。
 * 于是</p>
 * <ul>
 *   <li>最终目标是实体方块 → 进 {@code toPlace}，由建造执行器逐格从背包扣料；</li>
 *   <li>最终目标是空气、且这格被某条挖除步骤覆盖过 → 进 {@code toClear}，
 *       由掘进执行器负责（在空地上它们本来就是空气，一格也不用挖）。</li>
 * </ul>
 * <p>没被任何步骤覆盖的格子一格都不碰。材料清单因此等于「壳」而不是「实心体积」，
 * 玩家看到的缺料数字和他真正要交的木板数一致。</p>
 */
public record Blueprint(String id, String displayName, int tier, Category category,
		int width, int height, int depth, List<BlueprintStep> steps,
		Set<String> requiredAbilities, List<MaterialSlot> materialSlots,
		Metadata metadata) {

	/** Descriptive data travels with the parsed blueprint, independent of its source format. */
	public record Metadata(String author, String source, String license, String style,
			String description, Set<String> tags, String format, int minEngineerLevel, List<SiteRequirement> siteRequirements) {
		public Metadata(String author, String source, String license, String style, String description,
				Set<String> tags, String format, int minEngineerLevel) {
			this(author, source, license, style, description, tags, format, minEngineerLevel, List.of());
		}
		public static final Metadata EMPTY = new Metadata("", "", "", "", "",
			Set.of(), "squire:steps", 0);

		public Metadata {
			siteRequirements = siteRequirements == null ? List.of() : List.copyOf(siteRequirements);
			author = clean(author);
			source = clean(source);
			license = clean(license);
			style = clean(style);
			description = clean(description);
			tags = tags == null ? Set.of() : Set.copyOf(tags);
			format = format == null || format.isBlank() ? "squire:steps" : format.trim();
			if (minEngineerLevel < 0 || minEngineerLevel > 10) {
				throw new IllegalArgumentException("minEngineerLevel must be between 0 and 10");
			}
		}

		private static String clean(String value) {
			return value == null ? "" : value.trim();
		}
	}

	/** A fixed semantic role whose concrete block family is selected per placement. */
	public record MaterialSlot(String id, String displayName, MaterialFamily.SlotType type,
			String defaultFamilyId, Set<String> requiredVariants) {
		public MaterialSlot {
			if (id == null || id.isBlank()) throw new IllegalArgumentException("material slot needs an id");
			displayName = displayName == null || displayName.isBlank() ? id : displayName;
			if (type == null) throw new IllegalArgumentException(id + ": material slot needs a type");
			if (defaultFamilyId == null || defaultFamilyId.isBlank()) {
				throw new IllegalArgumentException(id + ": material slot needs a default family");
			}
			requiredVariants = requiredVariants == null ? Set.of("block")
				: Set.copyOf(requiredVariants);
		}
	}

	/** 蓝图的用途分类，只用于分组展示与后续职业解锁。 */
	public enum Category {
		HOUSING, STORAGE, PRODUCTION, CIVIC, DEFENCE, INFRASTRUCTURE, DECORATION,
		/** @deprecated source compatibility; resource descriptors parse this as HOUSING. */
		@Deprecated SHELTER,
		/** @deprecated source compatibility; resource descriptors parse this as PRODUCTION. */
		@Deprecated MINE;

		public static Category parse(String raw) {
			if (raw == null) {
				return HOUSING;
			}
			String normalized = raw.trim().toUpperCase(Locale.ROOT);
			if ("SHELTER".equals(normalized)) normalized = "HOUSING";
			if ("MINE".equals(normalized)) normalized = "PRODUCTION";
			try {
				return valueOf(normalized);
			} catch (IllegalArgumentException e) {
				return HOUSING;
			}
		}
	}

	/** Level zero means unrestricted; bundled library entries explicitly opt into Engineer gating. */
	public int minEngineerLevel() {
		return metadata.minEngineerLevel();
	}

	/** 单份蓝图的规模上限：要能预览、能撤销、能在一次会话里盖完。 */
	public static final int MAX_CELLS = 65536;

	public Blueprint {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("blueprint needs an id");
		}
		if (width <= 0 || height <= 0 || depth <= 0) {
			throw new IllegalArgumentException(id + " has a degenerate size");
		}
		if (steps == null || steps.isEmpty()) {
			throw new IllegalArgumentException(id + " has no steps");
		}
		steps = List.copyOf(steps.stream()
			.sorted(Comparator.comparingInt(BlueprintStep::order)).toList());
		requiredAbilities = requiredAbilities == null ? Set.of()
			: Set.copyOf(requiredAbilities);
		materialSlots = materialSlots == null ? List.of() : List.copyOf(materialSlots);
		metadata = metadata == null ? Metadata.EMPTY : metadata;
		displayName = displayName == null || displayName.isBlank() ? id : displayName;
		Set<String> slotIds = new java.util.HashSet<>();
		for (MaterialSlot slot : materialSlots) {
			if (!slotIds.add(slot.id())) throw new IllegalArgumentException(id + ": duplicate material slot " + slot.id());
		}
		for (BlueprintStep step : steps) {
			if (step.usesMaterial() && !slotIds.contains(step.materialSlot())) {
				throw new IllegalArgumentException(id + ": unknown material slot " + step.materialSlot());
			}
		}
	}

	/** Source-compatible constructor for blueprints without configurable materials. */
	public Blueprint(String id, String displayName, int tier, Category category,
			int width, int height, int depth, List<BlueprintStep> steps,
			Set<String> requiredAbilities) {
		this(id, displayName, tier, category, width, height, depth, steps,
			requiredAbilities, List.of(), Metadata.EMPTY);
	}

	/** Source-compatible constructor for callers that already provide material slots. */
	public Blueprint(String id, String displayName, int tier, Category category,
			int width, int height, int depth, List<BlueprintStep> steps,
			Set<String> requiredAbilities, List<MaterialSlot> materialSlots) {
		this(id, displayName, tier, category, width, height, depth, steps,
			requiredAbilities, materialSlots, Metadata.EMPTY);
	}

	public Map<String, String> defaultPalette() {
		Map<String, String> out = new LinkedHashMap<>();
		for (MaterialSlot slot : materialSlots) out.put(slot.id(), slot.defaultFamilyId());
		return Map.copyOf(out);
	}

	public MaterialSlot materialSlot(String id) {
		for (MaterialSlot slot : materialSlots) if (slot.id().equals(id)) return slot;
		return null;
	}

	// ------------------------------------------------------------------ 旋转

	/**
	 * 把「按正北写的」局部坐标旋到 {@code facing} 上。
	 *
	 * <p>约定与 {@code HouseTemplate.doorPos} 逐个方向对齐（NORTH 是恒等），
	 * 所以从模板转过来的房子，门仍然开在朝向玩家的那一面。东/西朝向下宽深互换。</p>
	 */
	public static BlockPos rotate(int x, int y, int z, Direction facing,
			int width, int depth) {
		Direction dir = facing == null || facing.getAxis().isVertical()
			? Direction.NORTH : facing;
		return switch (dir) {
			case NORTH -> new BlockPos(x, y, z);
			case SOUTH -> new BlockPos(width - 1 - x, y, depth - 1 - z);
			case EAST -> new BlockPos(depth - 1 - z, y, x);
			case WEST -> new BlockPos(z, y, width - 1 - x);
			default -> new BlockPos(x, y, z);
		};
	}

	/** 旋转之后的东西向跨度。 */
	public int placedWidth(Direction facing) {
		return horizontalSwap(facing) ? depth : width;
	}

	/** 旋转之后的南北向跨度。 */
	public int placedDepth(Direction facing) {
		return horizontalSwap(facing) ? width : depth;
	}

	private static boolean horizontalSwap(Direction facing) {
		return facing == Direction.EAST || facing == Direction.WEST;
	}

	/**
	 * 整栋建筑的包围盒，用于一次性的保护/权限判定和幽灵预览的十二条棱。
	 *
	 * <p>取的是所有步骤的并集，而不是声明的 {@code width×height×depth}：
	 * 像矿井前哨站这种带竖井的蓝图，步骤会伸到原点以下，照声明尺寸算会把地下部分
	 * 漏在保护判定和预览框外面。声明尺寸只用于旋转。</p>
	 */
	public BoundedRegion bounds(BlockPos origin, Direction facing) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlueprintStep step : steps) {
			BoundedRegion region = step.region(origin, facing, width, depth);
			minX = Math.min(minX, region.min().getX());
			minY = Math.min(minY, region.min().getY());
			minZ = Math.min(minZ, region.min().getZ());
			maxX = Math.max(maxX, region.max().getX());
			maxY = Math.max(maxY, region.max().getY());
			maxZ = Math.max(maxZ, region.max().getZ());
		}
		return BoundedRegion.ofCorners(minX, minY, minZ, maxX, maxY, maxZ);
	}

	// ------------------------------------------------------------------ 展平

	/** 一格要放的东西，连同它属于哪一步（报告用）和缺料时能不能跳过。 */
	public record Cell(BlockPos pos, String blockId, Map<String, String> properties,
			int stepOrder, String what, boolean optional) {
		public Cell { pos = pos.toImmutable(); properties = Map.copyOf(properties); }
	}

	/**
	 * 展平结果。{@code toPlace} 自下而上（建造看起来像建造），
	 * {@code toClear} 自上而下（先掏顶再掏底，伙伴不会把自己埋了）。
	 */
	public record Resolved(BoundedRegion bounds, List<Cell> toPlace,
			List<BlockPos> toClear, ConstructionAccessPlan access, ConstructionCostPlan costPlan, List<SiteRequirement> siteRequirements) {
		public Resolved {
			siteRequirements = siteRequirements == null ? List.of() : List.copyOf(siteRequirements);
			toPlace = List.copyOf(toPlace);
			toClear = toClear.stream().map(BlockPos::toImmutable).toList();
		}
		public Resolved(BoundedRegion bounds, List<Cell> toPlace, List<BlockPos> toClear) {
			this(bounds, toPlace, toClear, null, null, List.of());
		}
		public Resolved(BoundedRegion bounds, List<Cell> toPlace, List<BlockPos> toClear, ConstructionAccessPlan access) {
			this(bounds, toPlace, toClear, access, null, List.of());
		}
		public Resolved(BoundedRegion bounds, List<Cell> toPlace, List<BlockPos> toClear, ConstructionAccessPlan access, ConstructionCostPlan plan) {
			this(bounds, toPlace, toClear, access, plan, List.of());
		}
		public Resolved withCostPlan(ConstructionCostPlan plan) { return new Resolved(bounds, toPlace, toClear, access, plan, siteRequirements); }

		public int cellCount() {
			return toPlace.size() + toClear.size();
		}
	}

	/** 把步骤展平成每格最终目标。见类级文档。 */
	public Resolved resolve(BlockPos origin, Direction facing) {
		return resolve(origin, facing, defaultPalette(), MaterialFamilyRegistry.defaults());
	}

	/** Resolve material slots and rotate coordinates and state properties in one pass. */
	public Resolved resolve(BlockPos origin, Direction facing, Map<String, String> palette,
			MaterialFamilyRegistry families) {
		Map<BlockPos, Cell> winner = new LinkedHashMap<>();
		Map<BlockPos, Boolean> dug = new LinkedHashMap<>();
		for (BlueprintStep step : steps) {
			String resolvedBlock = step.blockId();
			if (step.usesMaterial()) {
				MaterialSlot slot = materialSlot(step.materialSlot());
				String familyId = palette == null ? null : palette.get(slot.id());
				MaterialFamily family = families.byId(familyId).filter(f -> f.supports(slot))
					.orElseGet(() -> families.byId(slot.defaultFamilyId())
						.orElseThrow(() -> new IllegalArgumentException(id + ": unknown default family "
							+ slot.defaultFamilyId())));
				resolvedBlock = family.block(step.materialVariant());
			}
			BoundedRegion region = step.region(origin, facing, width, depth);
			for (BlockPos raw : region.cells()) {
				BlockPos pos = raw.toImmutable();
				if (step.negative()) {
					winner.remove(pos);
					dug.put(pos, Boolean.TRUE);
				} else {
					winner.put(pos, new Cell(pos, resolvedBlock,
						step.rotatedProperties(facing, resolvedBlock), step.order(), step.what(),
						step.optional()));
					dug.remove(pos);
				}
			}
		}
		List<Cell> place = new ArrayList<>(winner.values());
		place.sort(Comparator.comparingInt((Cell c) -> c.pos().getY())
			.thenComparingInt(Cell::stepOrder)
			.thenComparingInt(c -> c.pos().getX())
			.thenComparingInt(c -> c.pos().getZ()));
		List<BlockPos> clear = new ArrayList<>(dug.keySet());
		clear.sort(Comparator.comparingInt(BlockPos::getY).reversed()
			.thenComparingInt(BlockPos::getX)
			.thenComparingInt(BlockPos::getZ));
		return new Resolved(bounds(origin, facing), List.copyOf(place), List.copyOf(clear), null, null,
			metadata.siteRequirements().stream().map(r -> new SiteRequirement(origin.add(rotate(r.pos().getX(), r.pos().getY(), r.pos().getZ(), facing, width, depth)), r.kind())).toList());
	}
}
