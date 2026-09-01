package dev.squire.server.world;

import net.minecraft.util.math.BlockPos;

/**
 * Typed bounded region (spec section 47 {@code BoundedRegion}). Corner coordinates
 * are clamped into sane world bounds at construction; the model never passes raw
 * coordinate strings through to commands.
 */
public record BoundedRegion(BlockPos min, BlockPos max) {

	public static final int MAX_VOLUME = 32_768; // 32^3 — hard safety ceiling

	public BoundedRegion {
		min = clamp(min);
		max = clamp(max);
		// normalize corners so min <= max on every axis
		if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
			BlockPos lo = new BlockPos(
				Math.min(min.getX(), max.getX()),
				Math.min(min.getY(), max.getY()),
				Math.min(min.getZ(), max.getZ()));
			BlockPos hi = new BlockPos(
				Math.max(min.getX(), max.getX()),
				Math.max(min.getY(), max.getY()),
				Math.max(min.getZ(), max.getZ()));
			min = lo;
			max = hi;
		}
	}

	public static BoundedRegion ofCorners(int x1, int y1, int z1, int x2, int y2, int z2) {
		return new BoundedRegion(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
	}

	private static BlockPos clamp(BlockPos pos) {
		return new BlockPos(
			Math.max(-30_000_000, Math.min(30_000_000, pos.getX())),
			Math.max(-2048, Math.min(2048, pos.getY())),
			Math.max(-30_000_000, Math.min(30_000_000, pos.getZ())));
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
			&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
			&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	/** @return true when every cell of {@code inner} lies inside this region. */
	public boolean contains(BoundedRegion inner) {
		return contains(inner.min) && contains(inner.max);
	}

	/** Saturating cell count — extreme regions clamp to {@link Long#MAX_VALUE}, never overflow negative. */
	public long volume() {
		long dx = max.getX() - min.getX() + 1L;
		long dy = max.getY() - min.getY() + 1L;
		long dz = max.getZ() - min.getZ() + 1L;
		long v = dx * dy;
		if (v > Long.MAX_VALUE / dz) {
			return Long.MAX_VALUE;
		}
		return v * dz;
	}

	/** Every position of the region, vanilla iteration order. */
	public Iterable<BlockPos> cells() {
		return BlockPos.iterate(min, max);
	}
}
