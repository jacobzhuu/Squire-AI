package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.BlockPos;

/**
 * Immutable, deterministic construction order derived from a resolved blueprint.
 *
 * <p>{@link Blueprint.Resolved} answers what the final world must look like.  It is
 * deliberately shared by preview, material accounting and verification.  This class
 * answers the different question of how a worker should get there: generated supports
 * first, then the foundation and ordinary structure bottom-up, with optional details
 * last.  Rows are snaked from the back of the building towards its lowest boundary
 * opening so the worker is less likely to seal its own exit.</p>
 *
 * <p>Small jobs derive this order from their pinned placement on recovery. Tall jobs
 * store a reviewed access program derived from the same cells, including traversal,
 * temporary blocks and their cleanup ledger.</p>
 */
public final class ConstructionPlan {

	public enum Phase { SUPPORT, FOUNDATION, STRUCTURE, FIXTURE, DETAIL }

	/** A single final-state write plus its simple vertical dependency, when present. */
	public record Entry(int index, Blueprint.Cell cell, Phase phase,
			BlockPos supportBelow) { }

	private enum ExitSide { NORTH, SOUTH, WEST, EAST }

	private final BoundedRegion bounds;
	private final List<Entry> entries;

	private ConstructionPlan(BoundedRegion bounds, List<Entry> entries) {
		this.bounds = bounds;
		this.entries = List.copyOf(entries);
	}

	public BoundedRegion bounds() {
		return bounds;
	}

	public List<Entry> entries() {
		return entries;
	}

	public List<Blueprint.Cell> cells() {
		return entries.stream().map(Entry::cell).toList();
	}

	public int size() {
		return entries.size();
	}

	/** Build an execution plan for the supplied still-pending cells. */
	public static ConstructionPlan create(Blueprint.Resolved resolved,
			List<Blueprint.Cell> pendingCells) {
		if (resolved == null) throw new IllegalArgumentException("resolved blueprint is required");
		List<Blueprint.Cell> pending = pendingCells == null ? List.of() : pendingCells;

		Map<BlockPos, Blueprint.Cell> unique = new LinkedHashMap<>();
		for (Blueprint.Cell cell : pending) if (!ConstructionFluids.liquid(cell)) unique.put(cell.pos().toImmutable(), ConstructionFluids.dry(cell));
		List<Blueprint.Cell> ordered = new ArrayList<>(unique.values());

		int foundationY = resolved.toPlace().stream().filter(cell -> !cell.optional())
			.mapToInt(cell -> cell.pos().getY()).min().orElse(resolved.bounds().min().getY());
		ExitSide exit = exitSide(resolved, foundationY);
		ordered.sort(order(exit, foundationY));

		Map<BlockPos, Blueprint.Cell> allTargets = new LinkedHashMap<>();
		for (Blueprint.Cell cell : resolved.toPlace()) allTargets.put(cell.pos(), cell);
		for (Blueprint.Cell cell : ordered) allTargets.put(cell.pos(), cell);

		List<Entry> entries = new ArrayList<>(ordered.size());
		for (int i = 0; i < ordered.size(); i++) {
			Blueprint.Cell cell = ordered.get(i);
			Blueprint.Cell below = allTargets.get(cell.pos().down());
			BlockPos dependency = below != null && !below.optional()
				? below.pos().toImmutable() : null;
			entries.add(new Entry(i, cell, phase(cell, foundationY), dependency));
		}
		return new ConstructionPlan(expandBounds(resolved.bounds(), ordered), entries);
	}

	private static Comparator<Blueprint.Cell> order(ExitSide exit, int foundationY) {
		return Comparator.comparingInt((Blueprint.Cell cell) -> phase(cell, foundationY).ordinal())
			.thenComparingInt(cell -> cell.pos().getY())
			.thenComparingInt(cell -> primary(exit, cell.pos()))
			.thenComparingInt(cell -> secondary(exit, cell.pos()))
			.thenComparingInt(Blueprint.Cell::stepOrder);
	}

	private static Phase phase(Blueprint.Cell cell, int foundationY) {
		if (cell.pos().getY() < foundationY || cell.stepOrder() == Integer.MIN_VALUE) {
			return Phase.SUPPORT;
		}
		if (cell.optional()) return Phase.DETAIL;
		String id = cell.blockId();
		if (id.endsWith("_torch") || id.endsWith(":torch") || id.endsWith("_door")
				|| id.endsWith("_bed") || id.endsWith(":ladder") || id.endsWith("_carpet")
				|| id.endsWith("_lantern") || id.endsWith(":lantern") || id.endsWith("_pressure_plate")) return Phase.FIXTURE;
		if (cell.pos().getY() == foundationY) return Phase.FOUNDATION;
		return Phase.STRUCTURE;
	}

	/* Primary rows run from the side opposite the exit back towards the exit. */
	private static int primary(ExitSide exit, BlockPos pos) {
		return switch (exit) {
			case NORTH -> -pos.getZ();
			case SOUTH -> pos.getZ();
			case WEST -> -pos.getX();
			case EAST -> pos.getX();
		};
	}

	/* Alternate the cross-row direction to avoid teleport-like zig-zag traversal. */
	private static int secondary(ExitSide exit, BlockPos pos) {
		int row = switch (exit) {
			case NORTH, SOUTH -> pos.getZ();
			case WEST, EAST -> pos.getX();
		};
		int cross = switch (exit) {
			case NORTH, SOUTH -> pos.getX();
			case WEST, EAST -> pos.getZ();
		};
		return (row & 1) == 0 ? cross : -cross;
	}

	private static ExitSide exitSide(Blueprint.Resolved resolved, int foundationY) {
		BlockPos min = resolved.bounds().min();
		BlockPos max = resolved.bounds().max();
		return resolved.toClear().stream()
			.filter(pos -> pos.getY() >= foundationY && pos.getY() <= foundationY + 2)
			.filter(pos -> pos.getX() == min.getX() || pos.getX() == max.getX()
				|| pos.getZ() == min.getZ() || pos.getZ() == max.getZ())
			.sorted(Comparator.comparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ))
			.map(pos -> sideOf(pos, min, max)).findFirst().orElse(ExitSide.NORTH);
	}

	private static ExitSide sideOf(BlockPos pos, BlockPos min, BlockPos max) {
		if (pos.getZ() == min.getZ()) return ExitSide.NORTH;
		if (pos.getZ() == max.getZ()) return ExitSide.SOUTH;
		if (pos.getX() == min.getX()) return ExitSide.WEST;
		return ExitSide.EAST;
	}

	private static BoundedRegion expandBounds(BoundedRegion original,
			List<Blueprint.Cell> cells) {
		int minX = original.min().getX();
		int minY = original.min().getY();
		int minZ = original.min().getZ();
		int maxX = original.max().getX();
		int maxY = original.max().getY();
		int maxZ = original.max().getZ();
		for (Blueprint.Cell cell : cells) {
			BlockPos pos = cell.pos();
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return BoundedRegion.ofCorners(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
