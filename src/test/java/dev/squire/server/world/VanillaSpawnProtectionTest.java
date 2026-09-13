package dev.squire.server.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;

import org.junit.jupiter.api.Test;

class VanillaSpawnProtectionTest {

	@Test
	void regionIntersectionDetectsCrossingAndEnclosingEdits() {
		BlockPos spawn = BlockPos.ORIGIN;
		assertTrue(VanillaSpawnProtection.overlapsSpawnSquare(
			BoundedRegion.ofCorners(-17, 64, 0, 17, 64, 0), spawn, 16));
		assertTrue(VanillaSpawnProtection.overlapsSpawnSquare(
			BoundedRegion.ofCorners(-100, -64, -100, 100, 320, 100), spawn, 16));
	}

	@Test
	void intersectionUsesInclusiveVanillaSquareAndIgnoresHeight() {
		BlockPos spawn = BlockPos.ORIGIN;
		assertTrue(VanillaSpawnProtection.overlapsSpawnSquare(
			BoundedRegion.ofCorners(16, -64, 16, 16, -64, 16), spawn, 16));
		assertFalse(VanillaSpawnProtection.overlapsSpawnSquare(
			BoundedRegion.ofCorners(17, -64, 17, 20, 320, 20), spawn, 16));
		assertFalse(VanillaSpawnProtection.overlapsSpawnSquare(
			BoundedRegion.ofCorners(-10, 0, -10, 10, 10, 10), spawn, 0));
	}

	@Test
	void individualPositionUsesLongArithmeticAtCoordinateExtremes() {
		assertFalse(VanillaSpawnProtection.insideSpawnSquare(
			new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0),
			16));
		assertTrue(VanillaSpawnProtection.insideSpawnSquare(
			BlockPos.ORIGIN, new BlockPos(16, 0, 0), 16));
		assertFalse(VanillaSpawnProtection.insideSpawnSquare(
			BlockPos.ORIGIN, new BlockPos(17, 0, 0), 16));
	}

	@Test
	void vanillaRulesLimitSpawnProtectionToDedicatedOverworldWithListedOperators() {
		assertTrue(VanillaSpawnProtection.shouldApplyProtection(
			true, true, 16, false, false));
		assertFalse(VanillaSpawnProtection.shouldApplyProtection(
			false, true, 16, false, false), "integrated servers have no vanilla spawn protection");
		assertFalse(VanillaSpawnProtection.shouldApplyProtection(
			true, false, 16, false, false), "Nether and End are not spawn-protected");
		assertFalse(VanillaSpawnProtection.shouldApplyProtection(
			true, true, 16, true, false), "an empty operator list disables vanilla protection");
		assertFalse(VanillaSpawnProtection.shouldApplyProtection(
			true, true, 16, false, true), "operators bypass vanilla protection");
		assertFalse(VanillaSpawnProtection.shouldApplyProtection(
			true, true, 0, false, false), "zero radius disables protection");
	}
}
