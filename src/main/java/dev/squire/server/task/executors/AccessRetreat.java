package dev.squire.server.task.executors;

import java.util.*;
import dev.squire.server.blueprint.ConstructionAccessPlan;
import dev.squire.server.blueprint.ConstructionScaffolding;
import dev.squire.server.blueprint.BlueprintManager;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;

/** Dry return component after a proposed removal. Never mutates the world. */
final class AccessRetreat {
    private AccessRetreat() { }
    static Set<BlockPos> afterRemoval(ServerWorld world, ConstructionAccessPlan access, BlockPos removed) {
        if (!ConstructionScaffolding.safeRemoval(world, removed)) return Set.of();
        // Reclaim one stable support at a time. Requiring every other branch to stay
        // reachable from the entrance made cleanup all-or-nothing: a harmless closed
        // wall or a scaffold property update could veto the top of an otherwise safe
        // column and deadlock the completed project. Each following removal is checked
        // independently against the then-current world.
        return component(world, access, removed);
    }
    static String diagnose(ServerWorld world, ConstructionAccessPlan access) {
        var reached = component(world, access, null);
        return "entranceComponent=" + reached.size() + " isolated=" + access.placed.keySet().stream()
            .filter(p -> !withinReach(reached, p)).limit(16).map(p -> p.toShortString() + " state=" + world.getBlockState(p)).toList();
    }
    private static boolean withinReach(Set<BlockPos> reached, BlockPos pending) {
        for (int y = -5; y <= 3; y++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            BlockPos s = pending.add(x, y, z);
            if (!s.down().equals(pending) && reached.contains(s) && eyeReach(s, pending)) return true;
        }
        return false;
    }
    private static Set<BlockPos> component(ServerWorld world, ConstructionAccessPlan access, BlockPos removed) {
        BlockPos entrance = access.entrance;
        Set<BlockPos> reached = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        if (!standing(world, access, entrance, removed)) return Set.of();
        reached.add(entrance); queue.add(entrance);
        while (!queue.isEmpty() && reached.size() < 20000) {
            BlockPos p = queue.removeFirst();
            for (BlockPos next : ConstructionScaffolding.neighbours(p)) {
                int dy = next.getY() - p.getY();
                if (!doorPassage(world, p, next) || !ConstructionScaffolding.horizontalDescentClear(world, p, next)) continue;
                if (ConstructionScaffolding.vertical(p, next) && ((dy > 0 ? p : next).equals(removed)
                        || !ConstructionScaffolding.scaffold(world, dy > 0 ? p : next))) continue;
                if (!standing(world, access, next, removed)
                    || dy == 1 && !empty(world, p.up(2), removed)
                    || dy == -1 && !empty(world, next.up(2), removed)) continue;
                if (reached.add(next)) queue.addLast(next);
            }
        }
        return reached;
    }
    static boolean eyeReach(BlockPos station, BlockPos target) {
        return new Vec3d(station.getX() + .5, station.getY() + 1.62, station.getZ() + .5)
            .squaredDistanceTo(Vec3d.ofCenter(target)) <= 4.70 * 4.70;
    }
    static List<BlockPos> path(ServerWorld world, ConstructionAccessPlan access, BlockPos from, BlockPos to) {
		return path(world, access, from, to, Set.of());
	}
	/** Conservative post-placement route: added cells are obstacles, never assumed floors. */
	static List<BlockPos> path(ServerWorld world, ConstructionAccessPlan access, BlockPos from, BlockPos to, Set<BlockPos> added) {
        if (added.contains(from) || added.contains(from.up())) return null;
        if (!standing(world, access, from, null)) return null;
        Map<BlockPos, BlockPos> parent = new HashMap<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        parent.put(from, null); queue.add(from);
        while (!queue.isEmpty() && parent.size() < 20000) {
            BlockPos p = queue.removeFirst();
            if (p.equals(to)) {
                List<BlockPos> route = new ArrayList<>();
                for (BlockPos n = to; !n.equals(from); n = parent.get(n)) route.add(n);
                Collections.reverse(route); return route;
            }
            for (BlockPos next : ConstructionScaffolding.neighbours(p)) {
                int dy = next.getY() - p.getY();
                if (!doorPassage(world, p, next) || !ConstructionScaffolding.horizontalDescentClear(world, p, next)) continue;
                if (ConstructionScaffolding.vertical(p, next) && !ConstructionScaffolding.scaffold(world, dy > 0 ? p : next)) continue;
                if (parent.containsKey(next) || added.contains(next) || added.contains(next.up()) || !standing(world, access, next, null)
                    || dy == 1 && (!empty(world, p.up(2), null) || added.contains(p.up(2)))
                    || dy == -1 && (!empty(world, next.up(2), null) || added.contains(next.up(2)))) continue;
                parent.put(next, p); queue.addLast(next);
            }
        }
        return null;
    }
    private static boolean standing(ServerWorld world, ConstructionAccessPlan a, BlockPos p, BlockPos removed) {
        return a.bounds.contains(p) && !p.down().equals(removed) && empty(world, p, removed) && empty(world, p.up(), removed)
            && ConstructionScaffolding.floor(world, p.down())
            && walkingView(world, p, removed).safeStanding(p, false);
    }
    private static dev.squire.server.blueprint.ConstructionBlockView walkingView(ServerWorld world, BlockPos p, BlockPos removed) {
        Map<BlockPos, net.minecraft.block.BlockState> changes = new HashMap<>();
        if (removed != null) changes.put(removed, Blocks.AIR.getDefaultState());
        // A wooden door is an operable passage, including both halves. The executor opens it
        // within reach before walking through; iron doors remain solid obstacles.
        for (BlockPos q : List.of(p, p.up())) if (woodenDoor(world, q)) changes.put(q, Blocks.AIR.getDefaultState());
        return new dev.squire.server.blueprint.ConstructionBlockView(world, changes);
    }
    private static boolean doorPassage(ServerWorld world, BlockPos from, BlockPos to) {
        // An opened door still has a side panel: cross along its facing axis, never sideways.
        for (BlockPos p : List.of(from, from.up(), to, to.up())) if (woodenDoor(world, p)) {
            var axis = world.getBlockState(p).get(net.minecraft.block.DoorBlock.FACING).getAxis();
            if (axis == Direction.Axis.X ? from.getX() == to.getX() : from.getZ() == to.getZ()) return false;
        }
        return true;
    }
    static boolean woodenDoor(ServerWorld world, BlockPos p) {
        return world.getBlockState(p).isIn(net.minecraft.registry.tag.BlockTags.WOODEN_DOORS);
    }
    private static boolean empty(ServerWorld world, BlockPos p, BlockPos removed) {
        return p.equals(removed) || woodenDoor(world, p) || ConstructionScaffolding.scaffold(world, p) || world.getBlockState(p).getFluidState().isEmpty() && world.getBlockState(p).getCollisionShape(world, p).isEmpty();
    }
}
