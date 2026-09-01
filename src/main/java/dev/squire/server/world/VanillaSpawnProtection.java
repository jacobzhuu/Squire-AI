package dev.squire.server.world;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Core protection implementation (spec section 60): vanilla spawn protection.
 * Dedicated servers expose the radius via server.properties; integrated servers
 * default to no spawn protection. Operators (level 2) always pass.
 */
public final class VanillaSpawnProtection implements ProtectionAdapter {

	private final MinecraftServer server;

	public VanillaSpawnProtection(MinecraftServer server) {
		this.server = server;
	}

	private int radius() {
		if (server instanceof MinecraftDedicatedServer dedicated) {
			return dedicated.getProperties().spawnProtection;
		}
		return 0; // integrated: vanilla applies none
	}

	/** @return true when the position is inside protected spawn area for this actor. */
	private boolean inProtectedSpawn(ServerWorld world, BlockPos pos, UUID actorOwnerId) {
		int r = radius();
		if (r <= 0) {
			return false;
		}
		BlockPos spawn = world.getSpawnPos();
		var actor = world.getServer().getPlayerManager().getPlayer(actorOwnerId);
		if (actor != null && actor.hasPermissionLevel(2)) {
			return false;
		}
		int dx = Math.abs(pos.getX() - spawn.getX());
		int dz = Math.abs(pos.getZ() - spawn.getZ());
		return Math.max(dx, dz) <= r;
	}

	@Override
	public PermissionDecision canBreak(ServerWorld world, BlockPos pos, UUID actorOwnerId) {
		return inProtectedSpawn(world, pos, actorOwnerId)
			? PermissionDecision.deny("vanilla spawn protection")
			: PermissionDecision.allow();
	}

	@Override
	public PermissionDecision canPlace(ServerWorld world, BlockPos pos, UUID actorOwnerId) {
		return canBreak(world, pos, actorOwnerId);
	}

	@Override
	public PermissionDecision canInteract(ServerWorld world, BlockPos pos, UUID actorOwnerId) {
		return canBreak(world, pos, actorOwnerId);
	}

	@Override
	public PermissionDecision canEditRegion(ServerWorld world, BoundedRegion region,
			UUID actorOwnerId) {
		return inProtectedSpawn(world, region.min(), actorOwnerId)
			|| inProtectedSpawn(world, region.max(), actorOwnerId)
				? PermissionDecision.deny("region touches vanilla spawn protection")
				: PermissionDecision.allow();
	}
}
