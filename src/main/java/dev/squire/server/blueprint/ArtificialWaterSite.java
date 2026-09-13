package dev.squire.server.blueprint;

import java.util.*;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.*;

/** Explicitly selected paid terrain work, limited to the authored fluid mask and its lining. */
public final class ArtificialWaterSite {
    private ArtificialWaterSite() { }
    public static Blueprint.Resolved prepare(Blueprint.Resolved r) {
        Set<BlockPos> water = new HashSet<>();
        r.siteRequirements().stream().filter(q -> q.kind().equals("fluid") || q.kind().equals("water")).forEach(q -> water.add(q.pos()));
        if (water.isEmpty()) return r;
        if (water.size() > 8192) throw new IllegalArgumentException("人工水域超过 8192 格安全上限，请使用现有水域");
        Map<BlockPos, Blueprint.Cell> cells = new LinkedHashMap<>(); r.toPlace().forEach(c -> cells.put(c.pos(), c));
        Set<BlockPos> clear = new HashSet<>(r.toClear());
        for (BlockPos p : water) {
            if (cells.containsKey(p) && !ConstructionFluids.liquid(cells.get(p))) throw new IllegalArgumentException("水域掩码与建筑实体冲突：" + p.toShortString());
            cells.put(p, new Blueprint.Cell(p, "minecraft:water", Map.of("level", "0"), Integer.MAX_VALUE, "人工水域", false)); clear.remove(p);
        }
        // Durable paid stone lining: never pretend these recoverable blocks are free temporary supports.
        for (BlockPos p : water) for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos n = p.offset(d);
            if (water.contains(n) || cells.containsKey(n)) continue;
            if (clear.contains(n)) throw new IllegalArgumentException("人工水域围护将堵住建筑负空间：" + n.toShortString());
            cells.put(n, new Blueprint.Cell(n, "minecraft:stone_bricks", Map.of(), Integer.MIN_VALUE + 1, "人工水域永久围护", false));
        }
        BlockPos min = r.bounds().min(), max = r.bounds().max();
        if (cells.size() > Blueprint.MAX_CELLS) throw new IllegalArgumentException("人工水域与建筑合计超过蓝图方块上限");
        for (BlockPos p : cells.keySet()) {
            min = new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
            max = new BlockPos(Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ()));
        }
        return new Blueprint.Resolved(new BoundedRegion(min, max), List.copyOf(cells.values()), List.copyOf(clear), null, null,
            r.siteRequirements().stream().filter(q -> !water.contains(q.pos())).toList());
    }
}
