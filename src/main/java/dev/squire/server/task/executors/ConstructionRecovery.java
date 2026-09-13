package dev.squire.server.task.executors;

import java.util.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;

/** Shared bounded assistance. Only movement/reach changes; callers still authorize and settle every write. */
public final class ConstructionRecovery {
    public static final int STALL_TICKS = 100;
    private ConstructionRecovery() { }

    public static BoundedRegion bounds(Blueprint.Resolved resolved, AvatarEntity avatar) {
        // Terrain previews store their work footprint as the access bounds; they do
        // not plan routes or reserve standing space. Recovery may need a safe anchor
        // just outside a narrow selection (for example, above a deep fill column).
        if (resolved.access() != null && !dev.squire.server.blueprint.TerrainLeveling.isTerrain(resolved))
            return resolved.access().bounds;
        var b = resolved.bounds(); var world = avatar.getWorld();
        return new BoundedRegion(new BlockPos(b.min().getX() - 12,
            Math.max(world.getBottomY(), Math.min(b.min().getY() - 2, avatar.getBlockY() - 2)), b.min().getZ() - 12),
            new BlockPos(b.max().getX() + 12, Math.min(world.getTopY() - 1,
                Math.max(b.max().getY() + 2, avatar.getBlockY() + 2)), b.max().getZ() + 12));
    }

    public static Map<BlockPos, BlockState> changes(Blueprint.Cell cell, Blueprint.Resolved r) {
        if (cell.blockId().equals("minecraft:air")) return Map.of(cell.pos(), Blocks.AIR.getDefaultState());
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (var part : BlueprintAssembly.cells(cell, r)) result.put(part.pos(), BlueprintManager.targetState(part));
        return result;
    }

    /** Full body, loaded chunks, world border, actual support and post-operation support. */
    public static boolean safe(AvatarEntity avatar, BoundedRegion area, BlockPos p, Map<BlockPos, BlockState> changes) {
        if (!(avatar.getWorld() instanceof ServerWorld world) || !area.contains(p)
                || !world.isInBuildLimit(p.up()) || !world.getWorldBorder().contains(p)
                || !world.isChunkLoaded(p) || !world.isChunkLoaded(p.down())) return false;
        for (BlockPos corner : List.of(p.add(-1, 0, -1), p.add(-1, 0, 1), p.add(1, 0, -1), p.add(1, 0, 1)))
            if (!world.isChunkLoaded(corner)) return false;
        var view = new ConstructionBlockView(world, changes);
        if (!ConstructionScaffolding.floor(world, p.down()) || !ConstructionScaffolding.floor(view, p.down())) return false;
        if (!new ConstructionBlockView(world, Map.of()).safeStanding(p, false) || !view.safeStanding(p, false)) return false;
        var body = avatar.getBoundingBox().offset(Vec3d.ofBottomCenter(p).subtract(avatar.getPos())).contract(.001);
        if (!world.isSpaceEmpty(avatar, body)) return false;
        for (BlockPos n : BlockPos.iterate(p.add(-1, -1, -1), p.add(1, 2, 1))) {
            var state = view.getBlockState(n);
            if (state.isOf(Blocks.SCAFFOLDING)) continue;
            if (state.getCollisionShape(view, n).getBoundingBoxes().stream().anyMatch(b -> b.offset(n).intersects(body))) return false;
        }
        return world.getOtherEntities(avatar, body, e -> e instanceof net.minecraft.entity.LivingEntity && e.isAlive()).isEmpty();
    }

    /** No teleport into a wall or onto a floor that this very operation removes. */
    public static ConstructionAccessPlan.Mode prepare(AvatarEntity avatar, BoundedRegion area,
            BlockPos target, Map<BlockPos, BlockState> changes) {
        var world = (ServerWorld) avatar.getWorld();
        for (BlockPos p : changes.keySet()) if (!area.contains(p) || !world.isChunkLoaded(p)
                || !world.isInBuildLimit(p) || !world.getWorldBorder().contains(p)) return null;
        BlockPos current = new BlockPos(avatar.getBlockX(), (int) Math.round(avatar.getY()), avatar.getBlockZ());
        if (safe(avatar, area, current, changes) && reaches(current, changes.keySet(), target)) {
            teleport(avatar, current); return ConstructionAccessPlan.Mode.SAFE_TELEPORT;
        }
        // Check loaded bounds before consulting vanilla collision/path views. The
        // generic walking candidate finder can read neighbouring, unloaded chunks.
        List<BlockPos> nearby = new ArrayList<>();
        for (int dy = -5; dy <= 3; dy++) for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++)
            nearby.add(target.add(dx, dy, dz));
        nearby.sort(Comparator.comparingDouble(p -> avatar.squaredDistanceTo(Vec3d.ofBottomCenter(p))));
        for (BlockPos candidate : nearby) {
            if (reaches(candidate, changes.keySet(), target) && safe(avatar, area, candidate, changes)) {
                teleport(avatar, candidate); return ConstructionAccessPlan.Mode.SAFE_TELEPORT;
            }
        }
        if (safe(avatar, area, current, changes)) {
            teleport(avatar, current); return ConstructionAccessPlan.Mode.SERVER_ASSIST;
        }
        // Only necessary if the worker was displaced or its floor is the current target.
        // Search nearest heights first, with a finite footprint inherited from the confirmed plan.
        for (int dy = 0; dy <= area.max().getY() - area.min().getY(); dy++) {
            for (int y : dy == 0 ? new int[]{current.getY()} : new int[]{current.getY() - dy, current.getY() + dy}) {
                if (y < area.min().getY() || y > area.max().getY()) continue;
                for (int x = area.min().getX(); x <= area.max().getX(); x++)
                    for (int z = area.min().getZ(); z <= area.max().getZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (safe(avatar, area, p, changes)) {
                            teleport(avatar, p); return ConstructionAccessPlan.Mode.SERVER_ASSIST;
                        }
                    }
            }
        }
        return null;
    }

    private static boolean reaches(BlockPos p, Set<BlockPos> cells, BlockPos target) {
        return AccessRetreat.eyeReach(p, target) && cells.stream().allMatch(c -> AccessRetreat.eyeReach(p, c));
    }

    static void teleport(AvatarEntity avatar, BlockPos p) {
        avatar.stopMoving(); avatar.setSneaking(false); avatar.setJumping(false);
        avatar.repositionForConstruction(p.getX() + .5, p.getY(), p.getZ() + .5);
        avatar.setVelocity(Vec3d.ZERO); avatar.fallDistance = 0; avatar.setOnGround(true);
    }

    static boolean occupied(ServerWorld world, AvatarEntity avatar, Map<BlockPos, BlockState> changes) {
        for (var entry : changes.entrySet()) {
            var pos = entry.getKey(); var state = entry.getValue();
            // Protect living occupants, including a person standing on a block being excavated.
            var box = state.isAir() ? new Box(pos).stretch(0, 1.8, 0) : new Box(pos);
            if (!world.getOtherEntities(avatar, box, e -> e instanceof net.minecraft.entity.LivingEntity && e.isAlive()).isEmpty()) return true;
        }
        return false;
    }
}
