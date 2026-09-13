package dev.squire.server.blueprint;

import java.util.*;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Read-only, bounded terrain preparation baked into the same paid Blueprint snapshot. */
public final class GroundPreparation {
    public static final String FILL = "自动地基填补";
    public static final int MAX_DEPTH = 8, MAX_FILL = 8192;
    private GroundPreparation() { }

    /** A deep, dry air gap is an elevated build, not a request for an earth pillar. */
    public static boolean elevatedAirGap(ServerWorld world, BlockPos root) {
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            BlockPos p = root.down(depth);
            if (!world.isInBuildLimit(p) || !world.isChunkLoaded(p) || !world.getBlockState(p).isAir()) return false;
        }
        return true;
    }

    public static Blueprint.Resolved prepare(ServerWorld world, Blueprint.Resolved r) {
        Map<BlockPos, Blueprint.Cell> cells = new LinkedHashMap<>();
        r.toPlace().forEach(c -> cells.put(c.pos(), c));
        Set<BlockPos> clear = new HashSet<>(r.toClear()), water = new HashSet<>();
        r.siteRequirements().stream().filter(q -> q.kind().equals("fluid")).forEach(q -> water.add(q.pos()));
        Set<BlockPos> roots = new TreeSet<>(Comparator.comparingLong(BlockPos::asLong));
        List<SiteRequirement> remaining = new ArrayList<>();
        for (var q : r.siteRequirements()) {
            if (!q.kind().equals("solid")) { remaining.add(q); continue; }
            clear.remove(q.pos()); // Terrain markers are not excavation targets.
            if (cells.containsKey(q.pos())) continue; // Explicit building geometry owns this position.
            if (q.satisfied(world)) remaining.add(q);
            else if (r.toPlace().isEmpty() || !elevatedAirGap(world, q.pos())) roots.add(q.pos());
        }
        BlueprintManager.automaticSiteSupports(world, r).forEach(c -> roots.add(c.pos()));
        int added = 0;
        String material = roots.isEmpty() ? "minecraft:dirt" : sample(world,
            new BlockPos(r.bounds().min().getX() - 1, roots.iterator().next().getY(), r.bounds().min().getZ() - 1), r);
        for (BlockPos root : roots) {
            if (cells.containsKey(root) || water.contains(root)) continue;
            List<BlockPos> column = new ArrayList<>();
            BlockPos p = root;
            for (int depth = 0; ; depth++, p = p.down()) {
                if (!world.isInBuildLimit(p) || !world.isChunkLoaded(p))
                    throw new IllegalArgumentException("地基超出世界高度或区块未加载：" + p.toShortString());
                if (cells.containsKey(p)) break;
                if (water.contains(p) || clear.contains(p) && !p.equals(root))
                    throw new IllegalArgumentException("自动地基与水域/地下负空间冲突：" + p.toShortString());
                if (world.getBlockState(p).getFluidState().isEmpty() && world.getBlockState(p).isSolidBlock(world, p)) break;
                if (depth >= MAX_DEPTH) {
                    // This is an elevated column, not a reason to reject the whole
                    // project.  A permanent eight-block dirt pillar is neither a
                    // useful foundation nor what the player selected; the access
                    // planner will provide paid, reclaimable scaffolding instead.
                    column.clear();
                    break;
                }
                column.add(p.toImmutable());
            }
            for (BlockPos fill : column) {
                if (++added > MAX_FILL || cells.size() >= Blueprint.MAX_CELLS)
                    throw new IllegalArgumentException("自动填土超过安全上限，请缩小工程或调整落点。");
                cells.put(fill, new Blueprint.Cell(fill, material, Map.of(), Integer.MIN_VALUE, FILL, false));
                clear.remove(fill);
            }
        }
        BlockPos min = r.bounds().min(), max = r.bounds().max();
        for (BlockPos p : cells.keySet()) {
            min = new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
            max = new BlockPos(Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ()));
        }
        return new Blueprint.Resolved(new BoundedRegion(min, max), List.copyOf(cells.values()), List.copyOf(clear), null, null, remaining);
    }

    /** Sample nearby surface columns; do not copy workstations, inventories or authored structure. */
    static String sample(ServerWorld world, BlockPos root, Blueprint.Resolved r) {
        Set<BlockPos> authored = BlueprintManager.footprint(r);
        Map<String, Integer> counts = new TreeMap<>();
        Set<String> natural = Set.of("dirt", "coarse_dirt", "rooted_dirt", "mud", "clay", "stone", "andesite", "diorite", "granite",
            "deepslate", "tuff", "calcite", "sand", "red_sand", "gravel", "sandstone", "red_sandstone", "netherrack", "end_stone", "basalt", "blackstone", "snow_block");
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (x == 0 && z == 0) continue;
            for (int y = 2; y >= -3; y--) {
                BlockPos p = root.add(x, y, z);
                if (!world.isChunkLoaded(p) || authored.contains(p)) continue;
                var state = world.getBlockState(p);
                String id = Registries.BLOCK.getId(state.getBlock()).toString();
                if (Set.of("minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium", "minecraft:dirt_path").contains(id)) id = "minecraft:dirt";
                if (!id.startsWith("minecraft:") || !natural.contains(id.substring(10)) || !state.getFluidState().isEmpty()) continue;
                counts.merge(id, 1, Integer::sum); break;
            }
        }
        return counts.entrySet().stream().max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
            .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).orElse("minecraft:dirt");
    }
}
