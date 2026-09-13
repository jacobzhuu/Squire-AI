package dev.squire.server.blueprint;

import java.util.*;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Bounded, read-only cut/fill planning. The id is a durable specification, never catalog content. */
public final class TerrainLeveling {
    public static final String PREFIX = "terrain_level/";
    // Resource ceilings protect preview memory and the snapshot codec, not profession progression.
    public static final int MAX_SIZE = 128, MAX_REVIEW_CELLS = 262144;
    private TerrainLeveling() { }

    public record Spec(int width, int depth) {
        public Spec {
            if (width < 1 || depth < 1 || width > MAX_SIZE || depth > MAX_SIZE)
                throw new IllegalArgumentException("单次预览长宽为 1～128 格，更大区域可分片处理");
        }
        public String id() { return PREFIX + width + "/" + depth; }
        public Blueprint blueprint() {
            return new Blueprint(id(), "平整地基 " + width + "×" + depth, 1, Blueprint.Category.INFRASTRUCTURE,
                width, 1, depth, List.of(BlueprintStep.dig(0, 0, 1, 0, width - 1, 1, depth - 1, "平地清理")), Set.of());
        }
    }
    public static Optional<Spec> parse(String id) {
        if (id == null || !id.startsWith(PREFIX)) return Optional.empty();
        try {
            String[] parts = id.substring(PREFIX.length()).split("/", -1);
            return parts.length == 2 ? Optional.of(new Spec(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    public static boolean isTerrain(Blueprint.Resolved r) {
        return r.siteRequirements().stream().anyMatch(q -> q.kind().equals("terrain_surface"));
    }
    public record Plan(Blueprint.Resolved resolved, String material, int dig, int fill, String fingerprint) { }

    public static Plan plan(ServerWorld world, BlueprintPlacement placement) {
        Spec spec = parse(placement.blueprintId).orElseThrow();
        BlockPos origin = placement.origin;
        int minY = Math.max(world.getBottomY(), origin.getY()), maxY = Math.min(world.getTopY() - 1, origin.getY() + 2);
        BoundedRegion frameBounds = new BoundedRegion(origin, origin.add(spec.width() - 1, 0, spec.depth() - 1));
        List<Blueprint.Cell> fill = new ArrayList<>();
        List<BlockPos> clear = new ArrayList<>();
        List<SiteRequirement> ground = new ArrayList<>();
        Set<String> issues = new LinkedHashSet<>();
        StringBuilder signature = new StringBuilder(placement.blueprintId).append(origin);
        Blueprint.Resolved frame = new Blueprint.Resolved(frameBounds, List.of(), List.of());
        String material = GroundPreparation.sample(world, origin.add(-1, 0, -1), frame);
        signature.append(material);
        int dig = 0;
        columns:
        for (int x = 0; x < spec.width(); x++) for (int z = 0; z < spec.depth(); z++) {
            BlockPos surface = origin.add(x, 0, z);
            ground.add(new SiteRequirement(surface, "terrain_surface"));
            if (!readable(world, surface, issues)) continue;
            int top = Math.min(world.getTopY() - 1, Math.max(surface.getY() + 2,
                world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, surface.getX(), surface.getZ()) - 1));
            maxY = Math.max(maxY, top);
            for (int y = top; y > surface.getY(); y--) {
                if (clear.size() + fill.size() >= MAX_REVIEW_CELLS) {
                    issues.add("单次预览数据过大，请将选区分片处理"); break columns;
                }
                BlockPos p = new BlockPos(surface.getX(), y, surface.getZ());
                BlockState state = world.getBlockState(p);
                clear.add(p);
                if (!state.isAir()) dig++;
                // Only the intended operations matter: grass growth or stone replacing
                // dirt at an already-authorized excavation cell is not a new scope.
                signature.append(p.asLong());
                String blocked = obstacle(world, p);
                if (!blocked.isEmpty()) issue(issues, blocked, p);
            }
            for (int y = surface.getY(); y >= world.getBottomY(); y--) {
                BlockPos p = new BlockPos(surface.getX(), y, surface.getZ());
                BlockState state = world.getBlockState(p);
                boolean sameSurface = BlueprintManager.matches(state,
                    new Blueprint.Cell(p, material, Map.of(), Integer.MIN_VALUE, GroundPreparation.FILL, false));
                if (state.isSolidBlock(world, p) && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                        && !state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                        && (y < surface.getY() || sameSurface)) {
                    ground.add(new SiteRequirement(p, "solid"));
                    break;
                }
                String blocked = obstacle(world, p, true);
                if (!blocked.isEmpty()) { issue(issues, blocked, p); break; }
                if (clear.size() + fill.size() >= MAX_REVIEW_CELLS) {
                    issues.add("单次预览数据过大，请将选区分片处理"); break columns;
                }
                minY = Math.min(minY, y);
                fill.add(new Blueprint.Cell(p, material, Map.of(), Integer.MIN_VALUE, GroundPreparation.FILL, false));
                signature.append(p.asLong());
                if (!state.isAir() && !replaceableWater(state)) { clear.add(p); dig++; }
                if (y == world.getBottomY()) issue(issues, "已到世界底部，无法找到承重地面", p);
            }
        }
        BoundedRegion bounds = new BoundedRegion(new BlockPos(origin.getX(), minY, origin.getZ()),
            new BlockPos(origin.getX() + spec.width() - 1, maxY, origin.getZ() + spec.depth() - 1));
        // A tree intersecting the selection is reviewed as a whole, including branches
        // outside the rectangle. Never silently truncate a connected tree.
        Set<BlockPos> tree = new HashSet<>();
        ArrayDeque<BlockPos> logs = new ArrayDeque<>();
        for (BlockPos p : clear) if (world.getBlockState(p).isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                && tree.add(p)) logs.add(p);
        while (!logs.isEmpty() && tree.size() + clear.size() + fill.size() < MAX_REVIEW_CELLS) {
            BlockPos p = logs.removeFirst();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = p.add(dx, dy, dz);
                if (!world.isChunkLoaded(q)) { issue(issues, "树木延伸至未加载区块", q); continue; }
                if (world.getBlockState(q).isIn(net.minecraft.registry.tag.BlockTags.LOGS) && tree.add(q)) logs.add(q);
            }
        }
        if (!logs.isEmpty()) issues.add("整棵树的清理预览过大，请分步处理");
        Set<BlockPos> frontier = new HashSet<>(tree);
        for (int distance = 0; distance < 6 && !frontier.isEmpty(); distance++) {
            Set<BlockPos> next = new HashSet<>();
            for (BlockPos p : frontier) for (var direction : net.minecraft.util.math.Direction.values()) {
                BlockPos q = p.offset(direction);
                if (!world.isChunkLoaded(q)) { issue(issues, "树冠延伸至未加载区块", q); continue; }
                if (world.getBlockState(q).isIn(net.minecraft.registry.tag.BlockTags.LEAVES) && tree.add(q)) next.add(q);
            }
            if (tree.size() + clear.size() + fill.size() >= MAX_REVIEW_CELLS) {
                issues.add("整棵树的清理预览过大，请分步处理"); break;
            }
            frontier = next;
        }
        Set<BlockPos> reviewed = new HashSet<>(clear);
        for (BlockPos p : tree.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList()) if (reviewed.add(p)) {
            if (clear.size() + fill.size() >= MAX_REVIEW_CELLS) {
                issues.add("整棵树的清理预览过大，请分步处理"); break;
            }
            String blocked = obstacle(world, p);
            if (!blocked.isEmpty()) issue(issues, blocked, p);
            clear.add(p); dig++; signature.append(p.asLong());
            bounds = new BoundedRegion(new BlockPos(Math.min(bounds.min().getX(), p.getX()), Math.min(bounds.min().getY(), p.getY()), Math.min(bounds.min().getZ(), p.getZ())),
                new BlockPos(Math.max(bounds.max().getX(), p.getX()), Math.max(bounds.max().getY(), p.getY()), Math.max(bounds.max().getZ(), p.getZ())));
        }
        var access = new ConstructionAccessPlan(List.of(), bounds, String.join("；", issues));
        var resolved = new Blueprint.Resolved(bounds, fill, clear, access, null, ground);
        resolved = resolved.withCostPlan(ConstructionCostPlan.create(world, resolved, 1, 0));
        signature.append(access.failure).append(dig).append(fill.size());
        return new Plan(resolved, material, dig, fill.size(), UUID.nameUUIDFromBytes(
            signature.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    }

    private static boolean readable(ServerWorld world, BlockPos p, Set<String> issues) {
        if (!world.isInBuildLimit(p) || !world.isChunkLoaded(p)) {
            issue(issues, "超出世界高度或区块未加载", p); return false;
        }
        return true;
    }
    private static void issue(Set<String> issues, String message, BlockPos p) {
        if (issues.size() < 8) issues.add(message + "：" + p.toShortString());
    }
    public static boolean replaceableWater(BlockState state) {
        return state.isReplaceable() && state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }
    public static String obstacle(ServerWorld world, BlockPos p) {
        return obstacle(world, p, false);
    }
    public static String obstacle(ServerWorld world, BlockPos p, boolean filling) {
        if (!world.isInBuildLimit(p) || !world.isChunkLoaded(p)) return "区块未加载或高度越界";
        var state = world.getBlockState(p);
        if (state.hasBlockEntity()) return "请先移走箱子等方块实体";
        if (!state.getFluidState().isEmpty()) {
            if (filling && replaceableWater(state)) return "";
            return state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)
                ? "目标面以上有水或含水结构，请提高目标面或移走含水结构" : "请先处理岩浆";
        }
        if (state.getHardness(world, p) < 0) return "不可破坏方块";
        return "";
    }
    /** Check all future work before a tick may change the world. */
    public static String liveBlocker(ServerWorld world, Blueprint.Resolved r) {
        Set<BlockPos> fillPositions = new HashSet<>();
        r.toPlace().forEach(c -> fillPositions.add(c.pos()));
        for (BlockPos p : BlueprintManager.footprint(r)) {
            String reason = obstacle(world, p, fillPositions.contains(p));
            if (!reason.isEmpty()) return reason + "：" + p.toShortString();
        }
        // Legacy terrain_air markers describe the reviewed volume, not an obligation
        // to keep a live worksite pristine; excavation handles new ordinary obstacles.
        for (var q : r.siteRequirements()) if (q.kind().equals("solid") && !q.satisfied(world))
            return "承重地面已被移走：" + q.pos().toShortString();
        return "";
    }

    public static int draw(ServerWorld world, net.minecraft.server.network.ServerPlayerEntity viewer, Blueprint.Resolved r) {
        if (viewer.getWorld() != world) return 0;
        var surfaces = r.siteRequirements().stream().filter(q -> q.kind().equals("terrain_surface")).map(SiteRequirement::pos).toList();
        if (surfaces.isEmpty()) return 0;
        int y = surfaces.get(0).getY();
        var nearest = new BlockPos(Math.max(r.bounds().min().getX(), Math.min(r.bounds().max().getX(), viewer.getBlockX())),
            y, Math.max(r.bounds().min().getZ(), Math.min(r.bounds().max().getZ(), viewer.getBlockZ())));
        if (viewer.getBlockPos().getSquaredDistance(nearest) > 96 * 96) return 0;
        var frame = BlueprintGhost.outline(new BoundedRegion(surfaces.get(0), surfaces.get(surfaces.size() - 1)));
        int sent = drawPoints(world, viewer, frame, new org.joml.Vector3f(.2f, .75f, 1f), 100, 1.01);
        sent += drawPoints(world, viewer, surfaces, new org.joml.Vector3f(.2f, .75f, 1f), 60, 1.01);
        sent += drawPoints(world, viewer, r.toPlace().stream().map(Blueprint.Cell::pos).toList(), new org.joml.Vector3f(.3f, 1f, .3f), 80, .5);
        sent += drawPoints(world, viewer, r.toClear().stream().filter(p -> !world.getBlockState(p).isAir()).toList(),
            new org.joml.Vector3f(1f, .3f, .15f), 80, .5);
        return sent;
    }
    private static int drawPoints(ServerWorld world, net.minecraft.server.network.ServerPlayerEntity viewer,
            List<BlockPos> points, org.joml.Vector3f color, int budget, double dy) {
        int stride = Math.max(1, (points.size() + budget - 1) / budget), count = 0;
        for (int i = 0; i < points.size(); i += stride) {
            var p = points.get(i);
            world.spawnParticles(viewer, new net.minecraft.particle.DustParticleEffect(color, 1f), true,
                p.getX() + .5, p.getY() + dy, p.getZ() + .5, 1, 0, 0, 0, 0); count++;
        }
        return count;
    }
}
