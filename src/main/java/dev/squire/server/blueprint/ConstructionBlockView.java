package dev.squire.server.blueprint;

import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/** Read-only hypothetical construction world; vanilla remains the hazard authority. */
public final class ConstructionBlockView implements BlockView {
    private final BlockView world;
    private final Map<BlockPos, BlockState> changes;
    // A view lives for one synchronous planning/review call, never across world ticks.
    private final Map<BlockPos, BlockState> original = new java.util.HashMap<>();
    private BlockPos proposedSupport;

    public ConstructionBlockView(BlockView world, Map<BlockPos, BlockState> changes) {
        this.world = world; this.changes = changes;
    }
    @Override public BlockState getBlockState(BlockPos p) {
        if (p.equals(proposedSupport)) return Blocks.COBBLESTONE.getDefaultState();
        BlockState changed = changes.get(p);
        return changed != null ? changed : original.computeIfAbsent(p.toImmutable(), world::getBlockState);
    }
    @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos p) { return changes.containsKey(p) ? null : world.getBlockEntity(p); }
    @Override public int getHeight() { return world.getHeight(); }
    @Override public int getBottomY() { return world.getBottomY(); }

    public boolean safeStanding(BlockPos p, boolean allowProposedSupport) {
        if (allowProposedSupport && getBlockState(p.down()).isAir()) proposedSupport = p.down();
        try {
            if ((ConstructionScaffolding.scaffold(this, p.down()) || ConstructionScaffolding.scaffold(this, p))
                    && ConstructionScaffolding.floor(this, p.down()) && ConstructionScaffolding.passable(this, p)
                    && ConstructionScaffolding.passable(this, p.up())) {
                // Normalize only the climbable body/floor; vanilla still sees surrounding fire/lava/cacti.
                var walking = new ConstructionBlockView(this, Map.of(p.down(), ConstructionScaffolding.scaffold(this, p.down()) ? Blocks.STONE.getDefaultState() : getBlockState(p.down()),
                    p, Blocks.AIR.getDefaultState(), p.up(), Blocks.AIR.getDefaultState()));
                if (LandPathNodeMaker.getLandNodeType(walking, p.mutableCopy()) != PathNodeType.WALKABLE) return false;
                // Air cells beside fences/walls can still intersect their neighbour's collision shape.
                var body = new net.minecraft.util.math.Box(p.getX() + .2, p.getY() + .001, p.getZ() + .2,
                    p.getX() + .8, p.getY() + 1.8, p.getZ() + .8);
                for (BlockPos n : BlockPos.iterate(p.add(-1, -1, -1), p.add(1, 2, 1))) {
                    var state = getBlockState(n);
                    if (state.isOf(Blocks.SCAFFOLDING)) continue;
                    if (state.getCollisionShape(this, n).getBoundingBoxes().stream().anyMatch(b -> b.offset(n).intersects(body))) return false;
                }
                return true;
            }
            return LandPathNodeMaker.getLandNodeType(this, p.mutableCopy()) == PathNodeType.WALKABLE
                && !(getBlockState(p.down()).getBlock() instanceof net.minecraft.block.LeavesBlock);
        } finally { proposedSupport = null; }
    }
}
