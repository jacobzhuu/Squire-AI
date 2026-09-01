package dev.squire.gametest;

import java.util.List;

import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Shared determinism helpers for GameTests. Batches share ONE world, so
 * environment noise from concurrent batches (leftover zombies from the guard
 * test, permanent-night windows from automation tests) can kill avatars mid-test.
 * Tests that need a live body call {@link #clearHostilesNear} when they summon.
 */
final class TestSupport {

	private TestSupport() {
	}

	/** Discards hostile mobs within {@code radius} blocks of {@code center}. */
	static void clearHostilesNear(ServerWorld world, BlockPos center, double radius) {
		Box area = new Box(
			center.getX() - 0.5, center.getY() - 0.5, center.getZ() - 0.5,
			center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5
		).expand(radius);
		List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class,
			area, m -> m.isAlive() && !m.isRemoved());
		for (HostileEntity monster : hostiles) {
			monster.discard();
		}
	}
}
