package dev.squire.server.blueprint;

import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;

/** Authored environmental mask: a precondition, not a free block to materialize. */
public record SiteRequirement(BlockPos pos, String kind) {
    public SiteRequirement {
        pos = pos.toImmutable();
        if (!kind.equals("solid") && !kind.equals("fluid") && !kind.equals("terrain_surface") && !kind.equals("terrain_air")) throw new IllegalArgumentException("unknown site requirement " + kind);
    }
    public boolean satisfied(ServerWorld world) {
        var state = world.getBlockState(pos);
        if (kind.equals("terrain_air")) return state.isAir();
        return !kind.equals("fluid") ? state.getFluidState().isEmpty() && state.isSolidBlock(world, pos)
            : state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }
}
