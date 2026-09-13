package dev.squire.server.world;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

	/** Match vanilla's dedicated-server rules before applying the spawn-radius geometry. */
	private boolean appliesTo(ServerWorld world, UUID actorOwnerId) {
		if (!(server instanceof MinecraftDedicatedServer dedicated)
				|| !World.OVERWORLD.equals(world.getRegistryKey()) || radius() <= 0) {
			return false;
		}
		var players = dedicated.getPlayerManager();
		boolean operatorListEmpty = players.getOpList().isEmpty();
		boolean operator = actorOwnerId != null
			&& players.isOperator(new GameProfile(actorOwnerId, "Squire"));
		return shouldApplyProtection(true, World.OVERWORLD.equals(world.getRegistryKey()),
			radius(), operatorListEmpty, operator);
	}

	static boolean shouldApplyProtection(boolean dedicatedServer, boolean overworld,
			int radius, boolean operatorListEmpty, boolean actorIsOperator) {
		return dedicatedServer && overworld && radius > 0 && !operatorListEmpty
			&& !actorIsOperator;
	}

	/** @return true when the position is inside protected spawn area for this actor. */
	private boolean inProtectedSpawn(ServerWorld world, BlockPos pos, UUID actorOwnerId) {
		if (!appliesTo(world, actorOwnerId)) return false;
		return insideSpawnSquare(world.getSpawnPos(), pos, radius());
	}

	static boolean insideSpawnSquare(BlockPos spawn, BlockPos pos, int radius) {
		if (radius <= 0) return false;
		long dx = Math.abs((long) pos.getX() - spawn.getX());
		long dz = Math.abs((long) pos.getZ() - spawn.getZ());
		return Math.max(dx, dz) <= radius;

	}

	static boolean overlapsSpawnSquare(BoundedRegion region, BlockPos spawn, int radius) {
		if (radius <= 0) return false;
		long minX = (long) spawn.getX() - radius, maxX = (long) spawn.getX() + radius;
		long minZ = (long) spawn.getZ() - radius, maxZ = (long) spawn.getZ() + radius;
		return region.max().getX() >= minX && region.min().getX() <= maxX
			&& region.max().getZ() >= minZ && region.min().getZ() <= maxZ;
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
		if (!appliesTo(world, actorOwnerId)) return PermissionDecision.allow();
		boolean overlaps = overlapsSpawnSquare(region, world.getSpawnPos(), radius());
		return overlaps ? PermissionDecision.deny("region touches vanilla spawn protection")
			: PermissionDecision.allow();
	}
}
