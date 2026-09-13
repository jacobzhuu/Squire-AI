package dev.squire.server.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.junit.jupiter.api.Test;

class ProtectionAdapterRegistryTest {
	private static final UUID ACTOR = UUID.randomUUID();
	private static final BlockPos POS = BlockPos.ORIGIN;
	private static final BoundedRegion REGION = BoundedRegion.ofCorners(0, 0, 0, 1, 1, 1);

	@Test
	void everyAdapterMustAllowEachMutationKind() {
		ProtectionAdapter denyOtherHalf = new ProtectionAdapter() {
			private PermissionDecision check(BlockPos pos) {
				return pos.equals(POS) ? PermissionDecision.allow()
					: PermissionDecision.deny("other owner");
			}
			@Override public PermissionDecision canBreak(net.minecraft.server.world.ServerWorld w, BlockPos p, UUID a) { return check(p); }
			@Override public PermissionDecision canPlace(net.minecraft.server.world.ServerWorld w, BlockPos p, UUID a) { return check(p); }
			@Override public PermissionDecision canInteract(net.minecraft.server.world.ServerWorld w, BlockPos p, UUID a) { return check(p); }
			@Override public PermissionDecision canEditRegion(net.minecraft.server.world.ServerWorld w, BoundedRegion r, UUID a) {
				return r.equals(REGION) ? PermissionDecision.deny("region intersects claim") : PermissionDecision.allow();
			}
		};
		ProtectionAdapter combined = ProtectionAdapterRegistry.combine(
			List.of(ProtectionAdapter.ALLOW_ALL, denyOtherHalf));
		assertTrue(combined.canInteract(null, POS, ACTOR).allowed());
		assertFalse(combined.canInteract(null, POS.east(), ACTOR).allowed());
		assertFalse(combined.canBreak(null, POS.east(), ACTOR).allowed());
		assertFalse(combined.canPlace(null, POS.east(), ACTOR).allowed());
		assertFalse(combined.canEditRegion(null, REGION, ACTOR).allowed());
	}

	@Test
	void brokenAdapterFailsClosed() {
		ProtectionAdapter broken = new ProtectionAdapter() {
			@Override public PermissionDecision canBreak(net.minecraft.server.world.ServerWorld w, BlockPos p, UUID a) {
				throw new IllegalStateException("integration unavailable");
			}
			@Override public PermissionDecision canPlace(net.minecraft.server.world.ServerWorld w, BlockPos p, UUID a) { return PermissionDecision.allow(); }
			@Override public PermissionDecision canInteract(net.minecraft.server.world.ServerWorld w, BlockPos p, UUID a) { return PermissionDecision.allow(); }
			@Override public PermissionDecision canEditRegion(net.minecraft.server.world.ServerWorld w, BoundedRegion r, UUID a) { return PermissionDecision.allow(); }
		};
		assertFalse(ProtectionAdapterRegistry.combine(List.of(broken))
			.canBreak(null, POS, ACTOR).allowed());
	}
}
