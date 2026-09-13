package dev.squire.server.blueprint;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;

/** Doors and beds are one item and one atomic write, even though templates store two cells. */
public final class BlueprintAssembly {
    private BlueprintAssembly() { }
    public static List<Blueprint.Cell> cells(Blueprint.Cell cell, Blueprint.Resolved resolved) {
        BlockPos other = null;
        BlockState state = BlueprintManager.targetState(ConstructionFluids.dry(cell));
        if (state.getBlock() instanceof DoorBlock) other = "upper".equals(cell.properties().get("half")) ? cell.pos().down() : cell.pos().up();
        if (state.getBlock() instanceof TallPlantBlock) other = "upper".equals(cell.properties().get("half")) ? cell.pos().down() : cell.pos().up();
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = Direction.byName(cell.properties().getOrDefault("facing", "north"));
            if (facing != null) other = cell.pos().offset("head".equals(cell.properties().get("part")) ? facing.getOpposite() : facing);
        }
        if (other == null) return List.of(cell);
        BlockPos partner = other;
        Blueprint.Cell mate = resolved.toPlace().stream().filter(c -> c.pos().equals(partner)
            && c.blockId().equals(cell.blockId()) && c.optional() == cell.optional()).map(ConstructionFluids::dry).findFirst().orElse(null);
        if (mate == null) throw new IllegalArgumentException("Incomplete two-cell assembly at " + cell.pos().toShortString());
        BlockState paired = BlueprintManager.targetState(mate);
        if (state.getBlock() instanceof TallPlantBlock && state.get(TallPlantBlock.HALF) == paired.get(TallPlantBlock.HALF))
            throw new IllegalArgumentException("Mismatched tall plant at " + cell.pos().toShortString());
        if (state.getBlock() instanceof DoorBlock && (state.get(DoorBlock.HALF) == paired.get(DoorBlock.HALF)
                || state.get(DoorBlock.FACING) != paired.get(DoorBlock.FACING)
                || state.get(DoorBlock.HINGE) != paired.get(DoorBlock.HINGE))
            || state.getBlock() instanceof BedBlock && (state.get(BedBlock.PART) == paired.get(BedBlock.PART)
                || state.get(BedBlock.FACING) != paired.get(BedBlock.FACING)))
            throw new IllegalArgumentException("Mismatched door/bed at " + cell.pos().toShortString());
        return List.of(cell, mate);
    }
    public static List<Blueprint.Cell> pending(ServerWorld world, Blueprint.Cell cell, Blueprint.Resolved resolved) {
        return cells(cell, resolved).stream().filter(c -> !BlueprintManager.matches(world.getBlockState(c.pos()), c)).toList();
    }
    public static int cost(List<Blueprint.Cell> cells) { return cells.stream().mapToInt(BlueprintManager::itemCost).sum(); }
    /** Caller has checked all positions, protection and stock on the same server tick. */
    public static boolean place(ServerWorld world, List<Blueprint.Cell> cells) {
        if (cells.stream().anyMatch(c -> ConstructionFluids.liquid(c) || ConstructionFluids.wet(c))) return false;
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (var c : cells) before.put(c.pos(), world.getBlockState(c.pos()));
        for (var c : cells) if (!world.setBlockState(c.pos(), BlueprintManager.targetState(c), Block.NOTIFY_LISTENERS | Block.FORCE_STATE)) {
            restore(world, before); return false;
        }
        for (var c : cells) {
            var state = BlueprintManager.targetState(c);
            if (state.isOf(Blocks.SCAFFOLDING)) {
                state = ConstructionScaffolding.placement(world, c.pos());
                if (state.get(ScaffoldingBlock.DISTANCE) >= 7) { restore(world, before); return false; }
            }
            // Connection geometry belongs to vanilla, not to stale imported neighbours.
            for (Direction direction : Direction.values()) state = state.getStateForNeighborUpdate(direction,
                world.getBlockState(c.pos().offset(direction)), world, c.pos(), c.pos().offset(direction));
            if (!state.isOf(BlueprintManager.targetState(c).getBlock())) { restore(world, before); return false; }
            world.setBlockState(c.pos(), state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            if (!state.canPlaceAt(world, c.pos())) { restore(world, before); return false; }
        }
        for (var c : cells) {
            var state = world.getBlockState(c.pos());
            state.updateNeighbors(world, c.pos(), Block.NOTIFY_ALL);
            world.updateNeighbors(c.pos(), state.getBlock());
        }
        return true;
    }
    public static void restore(ServerWorld world, Map<BlockPos, BlockState> before) {
        before.forEach((p, state) -> world.setBlockState(p, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE));
    }
}
