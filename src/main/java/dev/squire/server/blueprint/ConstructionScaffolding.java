package dev.squire.server.blueprint;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.util.math.*;
import net.minecraft.world.BlockView;

/** Vanilla scaffolding physics shared by the hypothetical route and live executor. */
public final class ConstructionScaffolding {
    private ConstructionScaffolding() { }
    public static boolean scaffold(BlockView world, BlockPos p) {
        var s = world.getBlockState(p);
        return s.isOf(Blocks.SCAFFOLDING) && s.getFluidState().isEmpty() && s.get(ScaffoldingBlock.DISTANCE) < 7;
    }
    public static boolean passable(BlockView world, BlockPos p) {
        return world.getBlockState(p).isAir() || scaffold(world, p);
    }
    public static boolean floor(BlockView world, BlockPos p) {
        return scaffold(world, p) || world.getBlockState(p).isSideSolidFullSquare(world, p, Direction.UP);
    }
    public static BlockState placement(BlockView world, BlockPos p) {
        int distance = ScaffoldingBlock.calculateDistance(world, p);
        return Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, distance)
            .with(ScaffoldingBlock.BOTTOM, distance > 0 && !world.getBlockState(p.down()).isOf(Blocks.SCAFFOLDING));
    }
    /** Recompute dependencies rather than trusting stale distance properties after removal.
     * Includes unowned neighbours: reclaiming our tower must not collapse a player's bridge. */
    public static boolean safeRemoval(BlockView world, BlockPos removed) {
        if (!world.getBlockState(removed).isOf(Blocks.SCAFFOLDING)) return true;
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(removed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst();
            for (Direction d : Direction.values()) {
                BlockPos n = p.offset(d);
                if (!n.equals(removed) && world.getBlockState(n).isOf(Blocks.SCAFFOLDING) && connected.add(n)) queue.add(n);
            }
            if (connected.size() > ConstructionAccessPlan.MAX_TEMPORARY * 2) return false;
        }
        Map<BlockPos, BlockState> changes = new HashMap<>();
        changes.put(removed, Blocks.AIR.getDefaultState());
        connected.forEach(p -> changes.put(p, world.getBlockState(p).with(ScaffoldingBlock.DISTANCE, 7)));
        var view = new ConstructionBlockView(world, changes);
        queue.addAll(connected);
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst();
            int distance = ScaffoldingBlock.calculateDistance(view, p);
            if (distance >= changes.get(p).get(ScaffoldingBlock.DISTANCE)) continue;
            changes.put(p, changes.get(p).with(ScaffoldingBlock.DISTANCE, distance));
            for (Direction d : Direction.values()) if (connected.contains(p.offset(d))) queue.add(p.offset(d));
        }
        return connected.stream().allMatch(p -> changes.get(p).get(ScaffoldingBlock.DISTANCE) < 7);
    }
    public static List<BlockPos> neighbours(BlockPos p) {
        List<BlockPos> result = new ArrayList<>(14);
        for (Direction d : Direction.Type.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) result.add(p.offset(d).up(dy));
        result.add(p.up()); result.add(p.down());
        return result;
    }
    public static boolean vertical(BlockPos a, BlockPos b) { return a.getX() == b.getX() && a.getZ() == b.getZ(); }
    public static boolean horizontalDescentClear(BlockView world, BlockPos from, BlockPos to) {
        // The bottom lip of a cantilevered scaffold cannot be crouched through.
        // Enter towers from the side at equal height, then descend vertically inside them.
        return to.getY() >= from.getY() || vertical(from, to) || !scaffold(world, to) && !scaffold(world, to.up());
    }
}
