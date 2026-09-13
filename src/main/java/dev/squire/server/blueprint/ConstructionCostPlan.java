package dev.squire.server.blueprint;

import java.util.*;
import com.google.gson.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Frozen base cost plus signed adjustment (negative means savings), apportioned per item to stable atomic operations. */
public final class ConstructionCostPlan {
    public record Operation(String id, Identifier item, int base, int waste, List<Blueprint.Cell> cells) {
        public Operation { cells = List.copyOf(cells); if (base < 1 || waste < -base) throw new IllegalArgumentException("invalid operation cost"); }
        public int total() { return Math.addExact(base, waste); }
        public boolean fluid() { return id.startsWith("fluid:"); }
        public Map<Identifier, Integer> outputs() { return fluid() ? Map.of(ConstructionFluids.BUCKET, 1) : Map.of(); }
        boolean matches(ServerWorld world) { return cells.stream().allMatch(c -> fluid() ? ConstructionFluids.matches(world, c) : BlueprintManager.matches(world.getBlockState(c.pos()), c)); }
    }
    private final int level, wasteBasisPoints;
    private final List<Operation> operations;
    private final Map<BlockPos, Operation> byPosition = new HashMap<>();
    private final Set<String> settled = new HashSet<>();
    private final Map<BlockPos, Operation> fluidByPosition = new HashMap<>();
    private BlockPos waterSource;
    public BlockPos waterSource() { return waterSource; }
    public void waterSource(BlockPos p) { waterSource = p == null ? null : p.toImmutable(); }
    public Operation fluidOperation(Blueprint.Cell c) { return fluidByPosition.get(c.pos()); }
    public synchronized boolean settled(Operation op) { return op != null && settled.contains(op.id()); }
    public synchronized void settleOperation(Operation op) {
        if (op == null || !operations.contains(op) || !settled.add(op.id())) throw new IllegalStateException("unknown/already settled operation");
    }
    private ConstructionCostPlan(int level, int rate, List<Operation> operations) {
        if (level < 0 || level > 10 || rate < -5000 || rate > 10000) throw new IllegalArgumentException("invalid cost level/rate");
        this.level = level; wasteBasisPoints = rate; this.operations = List.copyOf(operations);
        Set<String> ids = new HashSet<>();
        for (var op : operations) {
            if (!ids.add(op.id())) throw new IllegalArgumentException("duplicate cost operation");
            for (var c : op.cells()) if ((op.fluid() ? fluidByPosition : byPosition).putIfAbsent(c.pos(), op) != null) throw new IllegalArgumentException("overlapping cost operations");
        }
    }
    public int level() { return level; }
    public int wasteBasisPoints() { return wasteBasisPoints; }
    public synchronized JsonArray settledIds() {
        JsonArray a = new JsonArray(); settled.stream().sorted().forEach(a::add); return a;
    }
    public List<Operation> operations() { return operations; }
    /** Terrain can change while a ghost is open; its foundation quote must follow it. */
    public boolean sameFoundations(ServerWorld world, Blueprint.Resolved resolved) {
        Set<BlockPos> geometry = new HashSet<>(); resolved.toPlace().forEach(c -> geometry.add(c.pos()));
        Set<BlockPos> quoted = new HashSet<>();
        operations.forEach(op -> op.cells().stream().filter(c -> !geometry.contains(c.pos())).forEach(c -> quoted.add(c.pos())));
        Set<BlockPos> current = new HashSet<>(); BlueprintManager.automaticSiteSupports(world, resolved)
            .stream().filter(c -> !geometry.contains(c.pos())).forEach(c -> current.add(c.pos()));
        if (!quoted.equals(current)) return false;
        // A block present when preview opened may be removed before confirmation.
        // Uncommitted previews must quote it; confirmed snapshots never reprice.
        return resolved.toPlace().stream().filter(c -> !c.optional()).allMatch(c -> {
            boolean solidCovered = ConstructionFluids.liquid(c) || byPosition.containsKey(c.pos())
                || BlueprintManager.matches(world.getBlockState(c.pos()), ConstructionFluids.dry(c));
            boolean fluidCovered = !ConstructionFluids.operation(c) || fluidByPosition.containsKey(c.pos())
                || ConstructionFluids.matches(world, c);
            return solidCovered && fluidCovered;
        });
    }
    public static int waste(int base, int basisPoints) {
        if (base < 0 || basisPoints < -5000 || basisPoints > 10000) throw new IllegalArgumentException("negative cost/invalid waste");
        // Round savings down per material type: never discount more than the configured rate.
        return Math.toIntExact(-Math.floorDiv(-(long) base * basisPoints, 10000));
    }
    public static ConstructionCostPlan create(ServerWorld world, Blueprint.Resolved resolved, int level, int rate) {
        List<Operation> out = new ArrayList<>(); Set<BlockPos> seen = new HashSet<>(); Map<Identifier, Integer> cumulative = new HashMap<>();
        for (var cell : BlueprintManager.constructionPlan(world, resolved, true).cells()) {
            if (cell.optional() || seen.contains(cell.pos())) continue;
            var assembly = BlueprintAssembly.cells(cell, resolved);
            assembly.forEach(c -> seen.add(c.pos()));
            if (assembly.stream().allMatch(c -> BlueprintManager.matches(world.getBlockState(c.pos()), c))) continue;
            int base = BlueprintAssembly.cost(assembly);
            if (base < 1) throw new IllegalArgumentException("unpriced operation at " + cell.pos());
            var item = BlueprintManager.itemId(cell.blockId());
            int previous = cumulative.getOrDefault(item, 0), next = Math.addExact(previous, base);
            int extra = waste(next, rate) - waste(previous, rate); cumulative.put(item, next);
            BlockPos anchor = assembly.stream().map(Blueprint.Cell::pos).min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow();
            out.add(new Operation(Long.toString(anchor.asLong()), item, base, extra, assembly));
        }
        for (var cell : ConstructionFluids.operations(resolved)) if (!ConstructionFluids.matches(world, cell))
            out.add(new Operation("fluid:" + cell.pos().asLong(), ConstructionFluids.bucket(cell), 1, 0, List.of(cell)));
        return new ConstructionCostPlan(level, rate, out);
    }
    /** -1 means a paid/damaged or unquoted operation: never regenerate it for free. */
    public synchronized int quote(List<Blueprint.Cell> cells) {
        if (cells.isEmpty()) return 0;
        Operation op = byPosition.get(cells.get(0).pos());
        if (op == null || settled.contains(op.id()) || cells.stream().anyMatch(c -> byPosition.get(c.pos()) != op)) return -1;
        return op.total();
    }
    public synchronized void settle(List<Blueprint.Cell> cells) {
        if (cells.isEmpty()) return;
        Operation op = byPosition.get(cells.get(0).pos());
        if (op == null || !settled.add(op.id())) throw new IllegalStateException("operation already settled/unquoted");
    }
    public synchronized Map<Identifier, Integer> remaining(ServerWorld world) {
        Map<Identifier, Integer> out = new LinkedHashMap<>();
        boolean canFetch = waterSource != null && world.isChunkLoaded(waterSource)
            && world.getBlockState(waterSource).isOf(net.minecraft.block.Blocks.WATER)
            && world.getFluidState(waterSource).isStill();
        for (var op : operations) if (!settled.contains(op.id()) && !op.matches(world)) {
            if (canFetch && op.fluid() && op.item().equals(ConstructionFluids.WATER_BUCKET)) out.put(ConstructionFluids.BUCKET, 1);
            else if (op.total() > 0) out.merge(op.item(), op.total(), Math::addExact);
        }
        return Map.copyOf(out);
    }
    public Map<Identifier, Integer> totals(boolean includeWaste) {
        Map<Identifier, Integer> out = new LinkedHashMap<>();
        for (var op : operations) {
            int count = includeWaste ? op.total() : op.base();
            if (count > 0) out.merge(op.item(), count, Math::addExact);
        }
        return Map.copyOf(out);
    }
    public synchronized JsonObject write() {
        JsonObject o = new JsonObject(); o.addProperty("version", 2); o.addProperty("level", level); o.addProperty("wasteBasisPoints", wasteBasisPoints);
        if (waterSource != null) o.add("waterSource", ConstructionSnapshotCodec.pos(waterSource));
        JsonArray ops = new JsonArray();
        for (var op : operations) {
            JsonObject x = new JsonObject(); x.addProperty("id", op.id()); x.addProperty("item", op.item().toString());
            x.addProperty("base", op.base()); x.addProperty("waste", op.waste()); x.addProperty("settled", settled.contains(op.id()));
            JsonArray cells = new JsonArray(); op.cells().forEach(c -> cells.add(ConstructionSnapshotCodec.cell(c))); x.add("cells", cells); ops.add(x);
        }
        o.add("operations", ops); return o;
    }
    public static ConstructionCostPlan read(JsonObject o) {
        if (o.get("version").getAsInt() < 1 || o.get("version").getAsInt() > 2 || o.getAsJsonArray("operations").size() > 262144) throw new IllegalArgumentException("cost snapshot limit/version");
        List<Operation> ops = new ArrayList<>(); Set<String> settled = new HashSet<>();
        for (var raw : o.getAsJsonArray("operations")) {
            var x = raw.getAsJsonObject(); List<Blueprint.Cell> cells = new ArrayList<>();
            if (x.getAsJsonArray("cells").size() < 1 || x.getAsJsonArray("cells").size() > 2) throw new IllegalArgumentException("invalid cost assembly");
            x.getAsJsonArray("cells").forEach(c -> cells.add(ConstructionSnapshotCodec.cell(c.getAsJsonObject())));
            var op = new Operation(x.get("id").getAsString(), new Identifier(x.get("item").getAsString()), x.get("base").getAsInt(), x.get("waste").getAsInt(), cells);
            if (op.fluid() && (cells.size() != 1 || !ConstructionFluids.operation(cells.get(0)) || !ConstructionFluids.bucket(cells.get(0)).equals(op.item()) || op.base() != 1 || op.waste() != 0)) throw new IllegalArgumentException("invalid fluid operation");
            if (x.get("settled").getAsBoolean()) settled.add(op.id()); ops.add(op);
        }
        var plan = new ConstructionCostPlan(o.get("level").getAsInt(), o.get("wasteBasisPoints").getAsInt(), ops);
        plan.settled.addAll(settled);
        if (o.has("waterSource")) plan.waterSource(ConstructionSnapshotCodec.pos(o.get("waterSource")));
        return plan;
    }
}
