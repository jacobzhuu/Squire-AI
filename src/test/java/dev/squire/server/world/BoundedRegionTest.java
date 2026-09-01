package dev.squire.server.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;

import org.junit.jupiter.api.Test;

/** Typed region safety (spec §47 BoundedPosition / §83 coordinate overflow). */
class BoundedRegionTest {

	@Test
	void cornersAreNormalized() {
		BoundedRegion region = BoundedRegion.ofCorners(10, 10, 10, -5, 0, -3);
		assertEquals(new BlockPos(-5, 0, -3), region.min());
		assertEquals(new BlockPos(10, 10, 10), region.max());
	}

	@Test
	void volumeAndContainment() {
		BoundedRegion region = BoundedRegion.ofCorners(0, 0, 0, 7, 7, 7); // 8^3 = 512
		assertEquals(512L, region.volume());
		assertTrue(region.contains(new BlockPos(0, 0, 0)));
		assertTrue(region.contains(new BlockPos(7, 7, 7)));
		assertFalse(region.contains(new BlockPos(8, 3, 3)));
		assertFalse(region.contains(new BlockPos(-1, 0, 0)));
	}

	@Test
	void extremeCoordinatesClampToWorldBounds() {
		BoundedRegion region = BoundedRegion.ofCorners(
			Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
			Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
		assertEquals(30_000_000, region.max().getX(), "x clamped to world border");
		assertEquals(-2048, region.min().getY(), "y clamped to build limits");
		assertEquals(-30_000_000, region.min().getZ());
		// volume is finite and bounded — no overflow explosion
		assertTrue(region.volume() > 0 && region.volume() <= Long.MAX_VALUE);
	}

	@Test
	void nestedRegionContainment() {
		BoundedRegion outer = BoundedRegion.ofCorners(0, 0, 0, 100, 100, 100);
		assertTrue(outer.contains(BoundedRegion.ofCorners(10, 10, 10, 20, 20, 20)));
		assertFalse(outer.contains(BoundedRegion.ofCorners(10, 10, 10, 120, 20, 20)));
	}
}
