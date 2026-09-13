package dev.squire.server.blueprint;

import java.util.*;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Finite, pre-authorized walk-and-build program. Never edits the world while planning. */
public final class ConstructionAccessPlan {
    public static final int MARGIN = 12;
    public static final int MAX_TEMPORARY = 4096;
    private static final int MAX_ROUTE_NODES = 60_000;
    private static final int MAX_TOTAL_SEARCH_NODES = 6_000_000;
    private static final double TARGET_HEURISTIC_WEIGHT = 3.0;
    private static final double WORK_REACH = 4.70;
    public enum Mode { WALK, SAFE_TELEPORT, SERVER_ASSIST }
    public enum RouteFailure { NONE, NO_ENTRANCE, NO_STATION, ARRIVAL, RETURN, BUDGET, TEMPORARY_LIMIT }
    public record Work(Blueprint.Cell cell, BlockPos station, boolean temporary, Mode mode) {
        public Work(Blueprint.Cell cell, BlockPos station, boolean temporary) { this(cell, station, temporary, Mode.WALK); }
        public boolean excavation() { return !temporary && cell.blockId().equals("minecraft:air"); }
    }
    public final List<Work> work;
    public final BoundedRegion bounds;
    public final String failure;
    public final BlockPos entrance;
    private final Map<BlockPos, Blueprint.Cell> temporaryCells;
    /** Outstanding temporary blocks, with the source of the real item paid for each. */
    public final Map<BlockPos, Boolean> placed = new LinkedHashMap<>();
    public int cursor;
    public boolean cleanup;
    public boolean cancelRequested;
    /** Recovery is progress, not a change to the paid geometry or work identities. */
    public boolean assistance;
    public int recoveries;
    public String recoveryReason = "";
    public RouteFailure routeFailure = RouteFailure.NONE;
    public int assistedCount() { return (int) work.stream().filter(w -> w.mode() != Mode.WALK).count(); }

    public ConstructionAccessPlan(List<Work> work, BoundedRegion bounds, String failure) {
        this(work, bounds, failure, work.isEmpty() ? bounds.min() : work.get(0).station());
    }
    public ConstructionAccessPlan(List<Work> work, BoundedRegion bounds, String failure, BlockPos entrance) {
        this.work = List.copyOf(work);
        this.bounds = bounds;
        this.failure = failure == null ? "" : failure;
        this.entrance = entrance;
        Map<BlockPos, Blueprint.Cell> temporaryIndex = new HashMap<>();
        work.stream().filter(Work::temporary).forEach(w -> temporaryIndex.putIfAbsent(w.cell.pos(), w.cell));
        this.temporaryCells = Map.copyOf(temporaryIndex);
    }

    public boolean valid() { return failure.isEmpty(); }
    public long excavationCount() { return work.stream().filter(Work::excavation).count(); }
    /** Additional access excavation never destroys containers, fluids or unstable ceilings. */
    public static boolean diggable(net.minecraft.world.BlockView world, BlockPos p) {
        BlockState state = world.getBlockState(p);
        if (state.isAir()) return true;
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.getHardness(world, p) < 0
                || world.getBlockState(p.up()).getBlock() instanceof net.minecraft.block.FallingBlock) return false;
        for (Direction d : Direction.values()) if (!world.getFluidState(p.offset(d)).isEmpty()) return false;
        // Left-over scaffolding from a cancelled/abandoned project is an ordinary,
        // hand-breakable obstruction. Protection is checked separately before mutation.
        return !state.isOf(Blocks.MAGMA_BLOCK)
            && !state.isOf(Blocks.FIRE) && !state.isOf(Blocks.CAMPFIRE) && !state.isOf(Blocks.SOUL_CAMPFIRE);
    }
    public int temporaryCount() { return (int) work.stream().filter(Work::temporary).count(); }
    public int remainingTemporary() {
        if (cleanup || cancelRequested) return 0;
        return (int) work.subList(Math.min(cursor, work.size()), work.size()).stream()
            .filter(Work::temporary).filter(w -> !placed.containsKey(w.cell.pos())).count();
    }
    public Map<net.minecraft.util.Identifier, Integer> remainingTemporaryMaterials() {
        Map<net.minecraft.util.Identifier, Integer> materials = new LinkedHashMap<>();
        if (!cleanup && !cancelRequested) work.subList(Math.min(cursor, work.size()), work.size()).stream()
            .filter(Work::temporary).filter(w -> !placed.containsKey(w.cell.pos()))
            .forEach(w -> materials.merge(BlueprintManager.itemId(w.cell.blockId()), BlueprintManager.itemCost(w.cell), Integer::sum));
        return materials;
    }
    public Blueprint.Cell temporaryCell(BlockPos pos) {
        return temporaryCells.get(pos);
    }

    /** Work above or below the worker also needs access, even for a one-block blueprint. */
    public static ConstructionAccessPlan plan(ServerWorld world, Blueprint.Resolved resolved,
            AvatarEntity avatar) {
        BoundedRegion b = resolved.bounds();
        int workerY = avatar == null ? b.min().getY() : avatar.getBlockY();
        BoundedRegion area = new BoundedRegion(new BlockPos(b.min().getX() - MARGIN,
            Math.max(world.getBottomY(), Math.min(b.min().getY() - 2, workerY - 2)), b.min().getZ() - MARGIN),
            new BlockPos(b.max().getX() + MARGIN,
                Math.min(world.getTopY() - 1, Math.max(b.max().getY() + 2, workerY + 2)), b.max().getZ() + MARGIN));
        try { return new Planner(world, resolved, avatar, area).run(); }
        catch (IllegalArgumentException invalid) {
            return new ConstructionAccessPlan(List.of(), area, "蓝图方块组合无效：" + invalid.getMessage());
        }
    }

    private static final class Planner {
        final ServerWorld world;
        final Blueprint.Resolved resolved;
        final AvatarEntity avatar;
        final BoundedRegion area;
        final Map<BlockPos, BlockState> virtual = new HashMap<>();
        final ConstructionBlockView view;
        final Map<BlockPos, Boolean> safeCells = new HashMap<>();
        final Map<BlockPos, List<BlockPos>> escapePaths = new LinkedHashMap<>();
        int explored;
        final Set<BlockPos> permanent = new HashSet<>();
        final Set<BlockPos> retained = new HashSet<>();
        final Set<BlockPos> optional = new HashSet<>();
        final List<Work> work = new ArrayList<>();
        int temporary;
        BlockPos station;
        BlockPos entrance;
        Blueprint.Cell targetCell;
        BlockPos excavationTarget;
        int lastRouteVisited;
        boolean assisted;
        RouteFailure routeFailure = RouteFailure.NONE;

        Planner(ServerWorld world, Blueprint.Resolved resolved, AvatarEntity avatar, BoundedRegion area) {
            this.world = world; this.resolved = resolved; this.avatar = avatar; this.area = area;
            this.view = new ConstructionBlockView(world, virtual);
            resolved.toPlace().forEach(c -> permanent.add(c.pos()));
            resolved.toPlace().stream().filter(c -> BlueprintManager.matches(world.getBlockState(c.pos()), c))
                .forEach(c -> retained.add(c.pos()));
            resolved.toPlace().stream().filter(Blueprint.Cell::optional).forEach(c -> optional.add(c.pos()));
        }

        BlockState state(BlockPos p) { return view.getBlockState(p); }
        boolean operableDoor(BlockPos p) {
            return state(p).isIn(net.minecraft.registry.tag.BlockTags.WOODEN_DOORS);
        }
        boolean air(BlockPos p) { return ConstructionScaffolding.passable(view, p) || operableDoor(p); }
        boolean floor(BlockPos p) {
            // Optional decor is not escrow-funded and may be skipped at execution.
            // Never make the safety of a later work station depend on its presence.
            return !optional.contains(p) && ConstructionScaffolding.floor(view, p);
        }
        boolean clear(BlockPos p) {
            if (!area.contains(p) || !world.isInBuildLimit(p.up()) || !world.isChunkLoaded(p)
                    || !air(p) || !air(p.up())) return false;
            return safeCells.computeIfAbsent(p.toImmutable(), key -> {
                if (!operableDoor(key) && !operableDoor(key.up())) return view.safeStanding(key, true);
                Map<BlockPos, BlockState> opened = new HashMap<>();
                for (BlockPos door : List.of(key, key.up()))
                    if (operableDoor(door)) opened.put(door, Blocks.AIR.getDefaultState());
                return new ConstructionBlockView(view, opened).safeStanding(key, true);
            });
        }
        boolean doorPassage(BlockPos from, BlockPos to) {
            // An opened door keeps its thin side panel. Cross along the facing axis;
            // this mirrors the live executor's reviewed-door traversal.
            for (BlockPos p : List.of(from, from.up(), to, to.up())) if (operableDoor(p)) {
                var state = state(p);
                var axis = state.get(net.minecraft.block.DoorBlock.FACING).getAxis();
                if (axis == Direction.Axis.X ? from.getX() == to.getX() : from.getZ() == to.getZ()) return false;
            }
            return true;
        }

        List<BlockPos> cuts(BlockPos from, BlockPos to) {
            LinkedHashSet<BlockPos> cells = new LinkedHashSet<>();
            // Clear the swept head space before the feet, including descending stairs.
            if (to.getY() > from.getY()) cells.add(from.up(2));
            if (to.getY() < from.getY()) cells.add(to.up(2));
            cells.add(to.up()); cells.add(to);
            return cells.stream().filter(p -> !air(p)).toList();
        }
        boolean canCut(BlockPos p) {
            return area.contains(p) && !retained.contains(p) && !virtual.containsKey(p) && diggable(view, p);
        }
        boolean clearAfterCuts(BlockPos next, List<BlockPos> cuts) {
            if (cuts.isEmpty()) return clear(next);
            if (cuts.stream().anyMatch(p -> !canCut(p))) return false;
            Map<BlockPos, BlockState> changes = new HashMap<>();
            cuts.forEach(p -> changes.put(p, Blocks.AIR.getDefaultState()));
            return area.contains(next) && world.isInBuildLimit(next.up()) && world.isChunkLoaded(next)
                && new ConstructionBlockView(view, changes).safeStanding(next, true);
        }
        void appendCut(BlockPos p) {
            if (state(p).isAir()) return;
            work.add(new Work(new Blueprint.Cell(p, "minecraft:air", Map.of(), Integer.MIN_VALUE,
                "开挖施工通路", false), station, false));
            virtual.put(p, Blocks.AIR.getDefaultState()); safeCells.clear();
        }

        ConstructionAccessPlan run() {
            if (avatar == null) return fail("负责施工的侍从不在场，无法规划施工通道。");
            station = avatar.getBlockPos();
            if (!clear(station) || !floor(station.down()) || insideFootprint(station)) {
                // Prefer a walking entrance; disconnected sites can use assisted work.
                station = null;
                List<BlockPos> starts = new ArrayList<>();
                Set<Integer> entranceHeights = new TreeSet<>();
                for (int dy = -1; dy <= 1; dy++) {
                    entranceHeights.add(resolved.bounds().min().getY() + dy);
                    entranceHeights.add(avatar.getBlockY() + dy);
                }
                for (int y : entranceHeights)
                    for (int x = area.min().getX(); x <= area.max().getX(); x++)
                        for (int z = area.min().getZ(); z <= area.max().getZ(); z++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (!insideFootprint(p) && clear(p) && floor(p.down()) && avatar.isSafeWorkPosition(p)) starts.add(p);
                        }
                starts.sort(Comparator.comparingDouble(p -> avatar.squaredDistanceTo(Vec3d.ofBottomCenter(p))));
                int lookAhead = avatar.profile() == null ? 48 : dev.squire.server.profession.EngineerProgression.current().lookAhead(avatar.profile().profession.level);
                for (BlockPos p : starts.stream().limit(lookAhead).toList()) {
                    var path = avatar.getNavigation().findPathTo(p, 0);
                    if (path != null && path.reachesTarget()) { station = p; break; }
                }
            }
            if (station == null) {
                // An assisted step has no walking station, but its diagnostic placeholder
                // must still belong to the confirmed bounds (including after a reload).
                station = new BlockPos(
                    net.minecraft.util.math.MathHelper.clamp(avatar.getBlockX(), area.min().getX(), area.max().getX()),
                    net.minecraft.util.math.MathHelper.clamp(avatar.getBlockY(), area.min().getY(), area.max().getY()),
                    net.minecraft.util.math.MathHelper.clamp(avatar.getBlockZ(), area.min().getZ(), area.max().getZ()));
                assisted = true; routeFailure = RouteFailure.NO_ENTRANCE;
            }
            entrance = station;
            // Excavate from reachable work stations instead of assuming the buried site is already air.
            List<BlockPos> clearing = new ArrayList<>(BlueprintManager.pendingClear(world, resolved));
            Set<BlockPos> clearingTargets = new HashSet<>(clearing);
            List<Blueprint.Cell> siteSupports = BlueprintManager.automaticSiteSupports(world, resolved);
            // Site preparation runs before this executor. Account for every future
            // foundation that is not itself a clearing target while choosing access
            // stations, otherwise a valid reviewed station can be filled with stone
            // before the engineer arrives. A support at the exact clearing target is
            // applied only after that cut has been planned so it does not become an
            // artificial "planned support cannot be excavated" conflict.
            siteSupports.stream().filter(c -> !clearingTargets.contains(c.pos()))
                .forEach(c -> virtual.put(c.pos(), BlueprintManager.targetState(c)));
            safeCells.clear();
            while (!clearing.isEmpty()) {
                clearing.removeIf(p -> state(p).isAir());
                if (clearing.isEmpty()) break;
                // Preserve pendingClear's top-down ordering: never try to mine granite below uncleared gravel.
                BlockPos p = clearing.remove(0);
                if (!canCut(p)) return fail("施工开挖暂不可执行：" + p.toShortString() + "（"
                    + state(p).getBlock().getName().getString() + "）；" + cutFailure(p));
                excavationTarget = p;
                List<BlockPos> approach = route(p);
                excavationTarget = null;
                if (approach == null) assisted = true;
                else appendRoute(approach);
                // Do not remove the worker's support, even if it is a blueprint conflict.
                if (p.equals(station.down())) assisted = true;
                if (assisted) appendAssistedCut(p); else appendCut(p);
            }
            siteSupports.forEach(c -> virtual.put(c.pos(), BlueprintManager.targetState(c)));
            safeCells.clear();
            // Establish an exterior ascent before walls/roofs can close the route.
            BlockPos high = new BlockPos(resolved.bounds().min().getX() - 3,
                resolved.bounds().max().getY() - 1, resolved.bounds().min().getZ() - 3);
            List<BlockPos> ascent = high.getY() > entrance.getY() + 3 ? route(high) : List.of();
            if (ascent == null) assisted = true;
            else appendRoute(ascent);
            for (Blueprint.Cell cell : BlueprintManager.constructionPlan(world, resolved, false).cells()) {
                if (BlueprintManager.matches(state(cell.pos()), cell)) continue;
                targetCell = cell;
                List<BlockPos> route = route(cell.pos());
                if (route == null) {
                    assisted = true;
                } else {
                    long added = route.stream().filter(p -> !floor(p.down())).count();
                    if (temporary + added > MAX_TEMPORARY) { assisted = true; routeFailure = RouteFailure.TEMPORARY_LIMIT; }
                    else appendRoute(route);
                }
                work.add(new Work(cell, station, false, assisted ? Mode.SERVER_ASSIST : Mode.WALK));
                for (var part : BlueprintAssembly.cells(cell, resolved)) virtual.put(part.pos(), BlueprintManager.targetState(part));
                safeCells.clear();
            }
            var result = new ConstructionAccessPlan(work, area, "", entrance);
            result.routeFailure = routeFailure;
            return result;
        }
        void appendAssistedCut(BlockPos p) {
            if (state(p).isAir()) return;
            work.add(new Work(new Blueprint.Cell(p, "minecraft:air", Map.of(), Integer.MIN_VALUE,
                "辅助开挖", false), station, false, Mode.SERVER_ASSIST));
            virtual.put(p, Blocks.AIR.getDefaultState()); safeCells.clear();
        }
        void appendRoute(List<BlockPos> route) {
            if (temporary + route.stream().filter(p -> !floor(p.down())).distinct().count() > MAX_TEMPORARY) {
                assisted = true; routeFailure = RouteFailure.TEMPORARY_LIMIT; return;
            }
            for (BlockPos next : route) {
                    for (BlockPos p : cuts(station, next)) appendCut(p);
                    if (!floor(next.down())) {
                        Blueprint.Cell support = new Blueprint.Cell(next.down(), "minecraft:scaffolding", Map.of(),
                            Integer.MIN_VALUE, "临时施工通道", false);
                        work.add(new Work(support, station, true));
                        BlockState scaffold = ConstructionScaffolding.placement(view, support.pos());
                        if (scaffold.get(net.minecraft.block.ScaffoldingBlock.DISTANCE) >= 7) throw new IllegalArgumentException("脚手架缺少地面支撑");
                        virtual.put(support.pos(), scaffold);
                        safeCells.clear();
                        temporary++;
                    }
                    station = next;
                }
        }

        ConstructionAccessPlan fail(String message) { return new ConstructionAccessPlan(List.of(), area, message); }
        String cutFailure(BlockPos p) {
            if (retained.contains(p)) return "该格已符合蓝图，不能作为通路拆除。";
            if (virtual.containsKey(p)) return "该格属于已规划的施工支撑，不能重复开挖。";
            if (!area.contains(p)) return "该格超出施工范围。";
            var s = state(p);
            if (s.getHardness(view, p) < 0) return "该方块不可破坏。";
            if (s.hasBlockEntity()) return "该方块带有容器或方块实体，请先迁移。";
            if (state(p.up()).getBlock() instanceof net.minecraft.block.FallingBlock)
                return "上方有沙或沙砾，需先清理未纳入工程范围的落沙顶棚。";
            if (!s.getFluidState().isEmpty()) return "该格含有流体，需先排水或隔离。";
            for (Direction d : Direction.values()) if (!view.getFluidState(p.offset(d)).isEmpty())
                return "相邻 " + p.offset(d).toShortString() + " 有流体，挖开会涌入通路。";
            return "该格属于危险方块或施工设施。";
        }
        boolean insideFootprint(BlockPos p) {
            return p.getX() >= resolved.bounds().min().getX() && p.getX() <= resolved.bounds().max().getX()
                && p.getZ() >= resolved.bounds().min().getZ() && p.getZ() <= resolved.bounds().max().getZ();
        }
        boolean reach(BlockPos p, BlockPos target) {
            if (target.equals(excavationTarget) && p.down().equals(target)) return false;
            if (targetCell != null && targetCell.pos().equals(target))
                return BlueprintAssembly.cells(targetCell, resolved).stream().allMatch(c -> pointReach(p, c.pos()));
            return pointReach(p, target);
        }
        boolean pointReach(BlockPos p, BlockPos target) {
            return !p.equals(target) && !p.up().equals(target)
                && new Vec3d(p.getX() + .5, p.getY() + 1.62, p.getZ() + .5)
                .squaredDistanceTo(Vec3d.ofCenter(target)) <= WORK_REACH * WORK_REACH;
        }
        record Node(BlockPos pos, double cost, double estimate) { }
        List<String> nearbyStations(BlockPos target) {
            List<String> result = new ArrayList<>();
            for (int y = target.getY() - 5; y <= target.getY() + 3 && result.size() < 12; y++)
                for (int dz = -4; dz <= 4 && result.size() < 12; dz++)
                    for (int dx = -4; dx <= 4 && result.size() < 12; dx++) {
                        BlockPos p = target.add(dx, y - target.getY(), dz);
                        if (pointReach(p, target) && clear(p))
                            result.add(p.toShortString() + "/floor=" + floor(p.down()));
                    }
            return result;
        }
        List<BlockPos> route(BlockPos target) {
            if (assisted) return null;
            if (reach(station, target) && escapable(station, List.of())) return List.of();
            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate));
            Map<BlockPos, Double> costs = new HashMap<>();
            Map<BlockPos, BlockPos> parent = new HashMap<>();
            Map<BlockPos, Integer> distances = new HashMap<>();
            open.add(new Node(station, 0, heuristic(station, target)));
            costs.put(station, 0.0);
            distances.put(station, ConstructionScaffolding.scaffold(view, station.down()) ? state(station.down()).get(net.minecraft.block.ScaffoldingBlock.DISTANCE) : 0);
            int visited = 0;
            while (!open.isEmpty() && visited++ < MAX_ROUTE_NODES) {
                if (!checkBudget()) return null;
                Node current = open.remove();
                if (current.cost > costs.getOrDefault(current.pos, Double.MAX_VALUE)) continue;
                if (reach(current.pos, target)) {
                    LinkedList<BlockPos> route = new LinkedList<>();
                    for (BlockPos p = current.pos; !p.equals(station); p = parent.get(p)) route.addFirst(p);
                    if (escapable(current.pos, route)) return route;
                    if (routeFailure != RouteFailure.BUDGET) routeFailure = RouteFailure.RETURN;
                }
                for (BlockPos next : ConstructionScaffolding.neighbours(current.pos)) {
                    int dy = next.getY() - current.pos.getY();
                    boolean vertical = ConstructionScaffolding.vertical(current.pos, next);
                    if (!doorPassage(current.pos, next)
                            || !ConstructionScaffolding.horizontalDescentClear(view, current.pos, next)) continue;
                    List<BlockPos> cuts = cuts(current.pos, next);
                    if (!clearAfterCuts(next, cuts)) continue;
                    boolean supported = floor(next.down());
                    if (vertical && dy < 0 && !ConstructionScaffolding.scaffold(view, next)) continue;
                    if (vertical && dy > 0 && supported && !ConstructionScaffolding.scaffold(view, current.pos)) continue;
                    if (!supported && (!state(next.down()).isAir() || permanent.contains(next.down()) || dy < 0 || dy > 0 && !vertical)) continue;
                    int distance = supported ? (ConstructionScaffolding.scaffold(view, next.down()) ? state(next.down()).get(net.minecraft.block.ScaffoldingBlock.DISTANCE) : 0)
                        : Math.min(net.minecraft.block.ScaffoldingBlock.calculateDistance(view, next.down()), distances.get(current.pos) + (vertical ? 0 : 1));
                    if (!supported && distance >= 7) continue;
                    boolean obstructsReturn = false;
                    for (BlockPos previous = current.pos; previous != null; previous = parent.get(previous)) {
                        if (cuts.contains(previous.down())) { obstructsReturn = true; break; }
                        if (!supported && !vertical && (next.down().equals(previous) || next.down().equals(previous.up()))
                            || !floor(previous.down()) && (next.equals(previous.down()) || next.up().equals(previous.down()))) {
                            obstructsReturn = true; break;
                        }
                    }
                    if (obstructsReturn) continue;
                    // Steps/bridges grow from a supported neighbour; no floating remote columns.
                    double cost = current.cost + (supported ? 1 : 3) + Math.abs(dy) * .25 + cuts.size() * 4;
                    if (cost >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;
                    costs.put(next, cost); parent.put(next, current.pos); distances.put(next, distance);
                    // Mining is deliberately costlier than walking, but an unweighted
                    // heuristic made buried targets flood-fill the whole surface before
                    // considering a short granite tunnel.  A bounded weighted A* stays
                    // focused on the construction point while every accepted step still
                    // passes the same cut, support and return-path safety checks.
                    open.add(new Node(next, cost,
                        cost + heuristic(next, target) * TARGET_HEURISTIC_WEIGHT));
                }
            }
            lastRouteVisited = visited;
            if (routeFailure == RouteFailure.NONE) routeFailure = visited >= MAX_ROUTE_NODES ? RouteFailure.BUDGET
                : nearbyStations(target).isEmpty() ? RouteFailure.NO_STATION : RouteFailure.ARRIVAL;
            return null;
        }
        double heuristic(BlockPos p, BlockPos target) { return Math.max(0, Math.sqrt(p.getSquaredDistance(target)) - 3); }
        boolean checkBudget() {
            if (++explored <= MAX_TOTAL_SEARCH_NODES) return true;
            routeFailure = RouteFailure.BUDGET; return false;
        }
        double exitHeuristic(BlockPos p) {
            return Math.max(Math.abs(p.getX() - entrance.getX()) + Math.abs(p.getZ() - entrance.getZ()), Math.abs(p.getY() - entrance.getY()));
        }
        boolean safeExitPath(List<BlockPos> path) {
            for (int i = 0; i < path.size(); i++) {
                BlockPos p = path.get(i);
                if (!clear(p) || !floor(p.down())) return false;
                if (i > 0) {
                    BlockPos previous = path.get(i - 1);
                    if (!doorPassage(previous, p)
                            || !ConstructionScaffolding.horizontalDescentClear(view, previous, p)) return false;
                    if (ConstructionScaffolding.vertical(previous, p) && !ConstructionScaffolding.scaffold(view, p.getY() > previous.getY() ? previous : p)) return false;
                    if (p.getY() > previous.getY() && !air(previous.up(2)) || p.getY() < previous.getY() && !air(p.up(2))) return false;
                }
            }
            return true;
        }
        boolean escapable(BlockPos from, List<BlockPos> route) {
            Map<BlockPos, BlockState> saved = new HashMap<>();
            BlockPos previous = station;
            for (BlockPos p : route) {
                for (BlockPos cut : cuts(previous, p)) {
                    if (!saved.containsKey(cut)) saved.put(cut, virtual.get(cut));
                    virtual.put(cut, Blocks.AIR.getDefaultState());
                }
                previous = p;
                if (!floor(p.down())) {
                if (!saved.containsKey(p.down())) saved.put(p.down(), virtual.get(p.down()));
                BlockState support = ConstructionScaffolding.placement(view, p.down());
                if (support.get(net.minecraft.block.ScaffoldingBlock.DISTANCE) >= 7) {
                    saved.forEach((q, s) -> { if (s == null) virtual.remove(q); else virtual.put(q, s); }); safeCells.clear(); return false;
                }
                virtual.put(p.down(), support);
                }
            }
            if (excavationTarget != null) {
                if (!saved.containsKey(excavationTarget)) saved.put(excavationTarget, virtual.get(excavationTarget));
                virtual.put(excavationTarget, Blocks.AIR.getDefaultState());
            }
            if (targetCell != null) for (var c : BlueprintAssembly.cells(targetCell, resolved)) { if (!saved.containsKey(c.pos())) saved.put(c.pos(), virtual.get(c.pos())); virtual.put(c.pos(), BlueprintManager.targetState(c)); }
            safeCells.clear();
            if (!clear(from) || !floor(from.down())) {
                saved.forEach((p, state) -> { if (state == null) virtual.remove(p); else virtual.put(p, state); });
                safeCells.clear();
                return false;
            }
            List<BlockPos> previousExit = escapePaths.get(from);
            boolean found = previousExit != null && safeExitPath(previousExit);
            PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate));
            Map<BlockPos, Double> costs = new HashMap<>(); Map<BlockPos, BlockPos> parents = new HashMap<>();
            queue.add(new Node(from, 0, exitHeuristic(from))); costs.put(from, 0.0);
            while (!found && !queue.isEmpty() && costs.size() <= 16000) {
                if (!checkBudget()) break;
                Node current = queue.remove(); BlockPos p = current.pos();
                if (current.cost() > costs.getOrDefault(p, Double.MAX_VALUE)) continue;
                if (p.equals(entrance)) {
                    found = true; List<BlockPos> path = new ArrayList<>();
                    for (BlockPos n = entrance; n != null; n = parents.get(n)) path.add(n);
                    Collections.reverse(path);
                    if (escapePaths.size() >= 256) escapePaths.remove(escapePaths.keySet().iterator().next());
                    escapePaths.put(from, List.copyOf(path)); break;
                }
                for (BlockPos next : ConstructionScaffolding.neighbours(p)) {
                    int dy = next.getY() - p.getY();
                    if (!doorPassage(p, next)
                            || !ConstructionScaffolding.horizontalDescentClear(view, p, next)) continue;
                    if (ConstructionScaffolding.vertical(p, next) && !ConstructionScaffolding.scaffold(view, dy > 0 ? p : next)) continue;
                    if (!clear(next) || !floor(next.down()) || dy == 1 && !air(p.up(2)) || dy == -1 && !air(next.up(2))) continue;
                    double cost = current.cost() + 1;
                    if (cost >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;
                    costs.put(next, cost); parents.put(next, p);
                    queue.add(new Node(next, cost, cost + exitHeuristic(next)));
                }
            }
            saved.forEach((p, state) -> { if (state == null) virtual.remove(p); else virtual.put(p, state); });
            safeCells.clear();
            return found;
        }
    }
}
