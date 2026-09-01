package dev.squire.server.world;

import java.util.UUID;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Third-party-protection seam consulted before EVERY world mutation (spec section 60).
 * The core ships a vanilla-spawn-protection implementation; protection mods plug in
 * additional adapters rather than editing Core logic.
 */
public interface ProtectionAdapter {

	/** Neutral adapter for minimal/test runtimes: every call is a single code path. */
	ProtectionAdapter ALLOW_ALL = new ProtectionAdapter() {
		@Override
		public PermissionDecision canBreak(ServerWorld world, BlockPos pos, UUID actor) {
			return PermissionDecision.allow();
		}

		@Override
		public PermissionDecision canPlace(ServerWorld world, BlockPos pos, UUID actor) {
			return PermissionDecision.allow();
		}

		@Override
		public PermissionDecision canInteract(ServerWorld world, BlockPos pos, UUID actor) {
			return PermissionDecision.allow();
		}

		@Override
		public PermissionDecision canEditRegion(ServerWorld world, BoundedRegion region,
				UUID actor) {
			return PermissionDecision.allow();
		}
	};

	PermissionDecision canBreak(ServerWorld world, BlockPos pos, UUID actorOwnerId);

	PermissionDecision canPlace(ServerWorld world, BlockPos pos, UUID actorOwnerId);

	PermissionDecision canInteract(ServerWorld world, BlockPos pos, UUID actorOwnerId);

	/** Whole-region gate for bulk edits — must hold for every cell. */
	PermissionDecision canEditRegion(ServerWorld world, BoundedRegion region, UUID actorOwnerId);

	/** Immutable decision value; reason surfaces to audit and the player. */
	record PermissionDecision(boolean allowed, String reason) {

		public static PermissionDecision allow() {
			return new PermissionDecision(true, null);
		}

		public static PermissionDecision deny(String reason) {
			return new PermissionDecision(false, reason);
		}
	}
}
