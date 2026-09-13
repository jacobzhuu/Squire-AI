package dev.squire.server.blueprint;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Fluid goals stay in the resolved blueprint; only sources/waterlogging are paid operations. */
public final class ConstructionFluids {
    public static final Identifier WATER_BUCKET = new Identifier("minecraft:water_bucket");
    public static final Identifier LAVA_BUCKET = new Identifier("minecraft:lava_bucket");
    public static final Identifier BUCKET = new Identifier("minecraft:bucket");
    private ConstructionFluids() { }
    public static boolean liquid(Blueprint.Cell c) { return liquid(c.blockId()); }
    public static boolean liquid(String id) { return id.equals("minecraft:water") || id.equals("minecraft:lava"); }
    public static boolean wet(Blueprint.Cell c) { return "true".equals(c.properties().get("waterlogged")); }
    public static boolean source(Blueprint.Cell c) { return liquid(c) && "0".equals(c.properties().getOrDefault("level", "0")); }
    public static boolean operation(Blueprint.Cell c) { return source(c) || wet(c); }
    public static boolean hasFluids(Blueprint.Resolved r) { return r.toPlace().stream().anyMatch(c -> liquid(c) || wet(c)); }
    public static Identifier bucket(Blueprint.Cell c) { return c.blockId().equals("minecraft:lava") ? LAVA_BUCKET : WATER_BUCKET; }
    public static Blueprint.Cell dry(Blueprint.Cell c) {
        if (!wet(c)) return c;
        Map<String, String> props = new HashMap<>(c.properties()); props.remove("waterlogged");
        return new Blueprint.Cell(c.pos(), c.blockId(), props, c.stepOrder(), c.what(), c.optional());
    }
    public static List<Blueprint.Cell> goals(Blueprint.Resolved r) { return r.toPlace().stream().filter(c -> liquid(c) || wet(c)).toList(); }
    public static List<Blueprint.Cell> operations(Blueprint.Resolved r) {
        return goals(r).stream().filter(ConstructionFluids::operation)
            .sorted(Comparator.comparingInt((Blueprint.Cell c) -> c.pos().getY()).thenComparingLong(c -> c.pos().asLong())).toList();
    }
    public static boolean matches(ServerWorld world, Blueprint.Cell c) {
        var s = world.getBlockState(c.pos());
        if (wet(c)) return BlueprintManager.matches(s, c);
        var f = s.getFluidState();
        if (!liquid(c) || !s.isOf(c.blockId().equals("minecraft:lava") ? Blocks.LAVA : Blocks.WATER)) return false;
        return !source(c) || f.isStill(); // flowing level is neighbour-derived, not a FORCE_STATE target
    }
    /** Fail closed before invoking a bucket: no displacement, evaporation or uncontrolled runoff. */
    public static String safety(ServerWorld world, Blueprint.Resolved r, Blueprint.Cell c) {
        if (bucket(c).equals(WATER_BUCKET) && world.getDimension().ultrawarm()) return "FLUID_WATER_EVAPORATES";
        var current = world.getBlockState(c.pos());
        if (wet(c)) {
            if (!BlueprintManager.matches(current, dry(c)) || !(current.getBlock() instanceof FluidFillable fill)
                    || !fill.canFillWithFluid(world, c.pos(), current, Fluids.WATER)) return "FLUID_CONTAINER_CHANGED";
        } else if (!current.isAir() && !(current.isOf(Blocks.WATER) && bucket(c).equals(WATER_BUCKET))
                && !(current.isOf(Blocks.LAVA) && bucket(c).equals(LAVA_BUCKET))) return "FLUID_TARGET_OCCUPIED";
        Set<BlockPos> allowed = new HashSet<>(); goals(r).stream().filter(g -> bucket(g).equals(bucket(c))).forEach(g -> allowed.add(g.pos()));
        Set<BlockPos> environment = new HashSet<>();
        if (bucket(c).equals(WATER_BUCKET)) r.siteRequirements().stream().filter(q -> q.kind().equals("fluid") && q.satisfied(world)).forEach(q -> environment.add(q.pos()));
        // Check every authored wet cell: fluid ticks must not escape through an unreviewed opening.
        for (BlockPos p : allowed) {
            for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                BlockPos n = p.offset(d);
                if (allowed.contains(n) || environment.contains(n)) continue;
                if (!world.isChunkLoaded(n) || !world.getBlockState(n).isSideSolidFullSquare(world, n, d.getOpposite()))
                    return "FLUID_CONTAINMENT_OPEN:" + n.toShortString();
            }
            if (bucket(c).equals(LAVA_BUCKET)) for (BlockPos n : BlockPos.iterate(p.add(-3, -1, -3), p.add(3, 3, 3)))
                if (world.getBlockState(n).isBurnable()) return "FLUID_FIRE_RISK:" + n.toShortString();
        }
        return "";
    }
    /** Must be called only after protection, reach, safety and escrow preflight on this server tick. */
    public static boolean pour(ServerWorld world, Blueprint.Cell cell) {
        if (wet(cell)) {
            var state = world.getBlockState(cell.pos());
            return state.getBlock() instanceof FluidFillable fill && fill.tryFillWithFluid(world, cell.pos(), state, Fluids.WATER.getStill(false))
                && matches(world, cell);
        }
        var item = (BucketItem) (bucket(cell).equals(WATER_BUCKET) ? Items.WATER_BUCKET : Items.LAVA_BUCKET);
        return item.placeFluid(null, world, cell.pos(), null) && matches(world, cell);
    }
}
