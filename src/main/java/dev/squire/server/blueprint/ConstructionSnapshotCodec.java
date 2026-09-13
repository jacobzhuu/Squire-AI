package dev.squire.server.blueprint;

import java.util.*;
import com.google.gson.*;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.BlockPos;

/** Versioned placement storage for immutable resolved content and its access program. */
public final class ConstructionSnapshotCodec {
    private ConstructionSnapshotCodec() { }
    /** Static geometry is content-addressed once; frequently written progress has no cells. */
    public static JsonObject writeGeometry(Blueprint.Resolved r) {
        JsonObject o = write(r);
        if (o.has("access")) {
            var a = o.getAsJsonObject("access"); a.addProperty("cursor", 0); a.addProperty("cleanup", false); a.addProperty("cancel", false); a.add("placed", new JsonArray());
            a.addProperty("assistance", false); a.addProperty("recoveries", 0); a.addProperty("recoveryReason", "");
        }
        if (o.has("costPlan")) for (var raw : o.getAsJsonObject("costPlan").getAsJsonArray("operations")) raw.getAsJsonObject().addProperty("settled", false);
        return o;
    }
    public static JsonObject writeProgress(Blueprint.Resolved r) {
        JsonObject o = new JsonObject();
        if (r.access() != null) {
            var a = r.access(); o.addProperty("cursor", a.cursor); o.addProperty("cleanup", a.cleanup); o.addProperty("cancel", a.cancelRequested);
            JsonArray placed = new JsonArray(); a.placed.forEach((p, owner) -> { var x = new JsonObject(); x.add("pos", pos(p)); x.addProperty("owner", owner); placed.add(x); });
            o.add("placed", placed);
            recovery(o, a);
        }
        if (r.costPlan() != null) o.add("settled", r.costPlan().settledIds());
        return o;
    }
    public static void mergeProgress(JsonObject geometry, JsonObject progress) {
        if (progress == null) throw new IllegalArgumentException("missing construction progress");
        if (geometry.has("access")) {
            var access = geometry.getAsJsonObject("access");
            for (String key : new String[]{"cursor", "cleanup", "cancel", "placed"}) {
                if (!progress.has(key)) throw new IllegalArgumentException("missing access progress " + key);
                access.add(key, progress.get(key).deepCopy());
            }
            for (String key : new String[]{"assistance", "recoveries", "recoveryReason"})
                if (progress.has(key)) access.add(key, progress.get(key).deepCopy());
        }
        if (geometry.has("costPlan")) {
            Set<String> paid = new HashSet<>();
            if (!progress.has("settled") || progress.getAsJsonArray("settled").size() > 262144) throw new IllegalArgumentException("missing/excessive settlement progress");
            progress.getAsJsonArray("settled").forEach(v -> paid.add(v.getAsString()));
            for (var raw : geometry.getAsJsonObject("costPlan").getAsJsonArray("operations")) {
                var op = raw.getAsJsonObject(); op.addProperty("settled", paid.remove(op.get("id").getAsString()));
            }
            if (!paid.isEmpty()) throw new IllegalArgumentException("unknown settled operation");
        }
    }
    private static void recovery(JsonObject o, ConstructionAccessPlan a) {
        o.addProperty("assistance", a.assistance); o.addProperty("recoveries", a.recoveries); o.addProperty("recoveryReason", a.recoveryReason);
    }
    public static JsonArray pos(BlockPos p) { JsonArray a = new JsonArray(); a.add(p.getX()); a.add(p.getY()); a.add(p.getZ()); return a; }
    public static BlockPos pos(JsonElement raw) { JsonArray a = raw.getAsJsonArray(); return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()); }
    public static JsonObject cell(Blueprint.Cell c) {
        JsonObject o = new JsonObject(); o.add("pos", pos(c.pos())); o.addProperty("block", c.blockId());
        o.addProperty("order", c.stepOrder()); o.addProperty("optional", c.optional()); o.addProperty("what", c.what());
        JsonObject properties = new JsonObject(); c.properties().forEach(properties::addProperty); o.add("properties", properties); return o;
    }
    public static Blueprint.Cell cell(JsonObject o) {
        Map<String, String> properties = new LinkedHashMap<>(); o.getAsJsonObject("properties").entrySet().forEach(e -> properties.put(e.getKey(), e.getValue().getAsString()));
        return new Blueprint.Cell(pos(o.get("pos")), o.get("block").getAsString(), properties, o.get("order").getAsInt(), o.get("what").getAsString(), o.get("optional").getAsBoolean());
    }
    public static JsonObject write(Blueprint.Resolved r) {
        JsonObject o = new JsonObject(); o.add("min", pos(r.bounds().min())); o.add("max", pos(r.bounds().max()));
        JsonArray cells = new JsonArray(); r.toPlace().forEach(c -> cells.add(cell(c))); o.add("cells", cells);
        JsonArray clear = new JsonArray(); r.toClear().forEach(p -> clear.add(pos(p))); o.add("clear", clear);
        if (r.costPlan() != null) o.add("costPlan", r.costPlan().write());
        JsonArray requirements = new JsonArray();
        for (var rqt : r.siteRequirements()) { JsonObject x = new JsonObject(); x.add("pos", pos(rqt.pos())); x.addProperty("kind", rqt.kind()); requirements.add(x); }
        o.add("siteRequirements", requirements);
        if (r.access() != null) {
            ConstructionAccessPlan a = r.access(); JsonObject access = new JsonObject();
            access.add("min", pos(a.bounds.min())); access.add("max", pos(a.bounds.max()));
            access.add("entrance", pos(a.entrance));
            access.addProperty("failure", a.failure); access.addProperty("cursor", a.cursor);
            access.addProperty("cleanup", a.cleanup); access.addProperty("cancel", a.cancelRequested);
            recovery(access, a); access.addProperty("routeFailure", a.routeFailure.name());
            JsonArray work = new JsonArray(); for (var w : a.work) { JsonObject x = cell(w.cell()); x.add("station", pos(w.station())); x.addProperty("temporary", w.temporary()); x.addProperty("mode", w.mode().name()); work.add(x); } access.add("work", work);
            JsonArray placed = new JsonArray(); a.placed.forEach((p, owner) -> { JsonObject x = new JsonObject(); x.add("pos", pos(p)); x.addProperty("owner", owner); placed.add(x); }); access.add("placed", placed); o.add("access", access);
        }
        return o;
    }
    public static Blueprint.Resolved read(JsonObject o) {
        if (o.getAsJsonArray("cells").size() > 262144 || o.getAsJsonArray("clear").size() > 262144)
            throw new IllegalArgumentException("snapshot exceeds cell limit");
        List<Blueprint.Cell> cells = new ArrayList<>(); for (JsonElement c : o.getAsJsonArray("cells")) cells.add(cell(c.getAsJsonObject()));
        List<BlockPos> clear = new ArrayList<>(); for (JsonElement p : o.getAsJsonArray("clear")) clear.add(pos(p));
        ConstructionAccessPlan a = null;
        if (o.has("access")) {
            JsonObject x = o.getAsJsonObject("access"); List<ConstructionAccessPlan.Work> work = new ArrayList<>();
            if (x.getAsJsonArray("work").size() > 266240 || x.getAsJsonArray("placed").size() > ConstructionAccessPlan.MAX_TEMPORARY)
                throw new IllegalArgumentException("access exceeds cell limit");
            for (JsonElement e : x.getAsJsonArray("work")) { JsonObject w = e.getAsJsonObject(); work.add(new ConstructionAccessPlan.Work(cell(w), pos(w.get("station")), w.get("temporary").getAsBoolean(), w.has("mode") ? ConstructionAccessPlan.Mode.valueOf(w.get("mode").getAsString()) : ConstructionAccessPlan.Mode.WALK)); }
            a = new ConstructionAccessPlan(work, new BoundedRegion(pos(x.get("min")), pos(x.get("max"))), x.get("failure").getAsString(),
                x.has("entrance") ? pos(x.get("entrance")) : work.isEmpty() ? pos(x.get("min")) : work.get(0).station());
            a.cursor = x.get("cursor").getAsInt(); if (a.cursor < 0 || a.cursor > work.size()) throw new IllegalArgumentException("invalid access cursor");
            a.cleanup = x.get("cleanup").getAsBoolean(); a.cancelRequested = x.get("cancel").getAsBoolean();
            a.assistance = x.has("assistance") && x.get("assistance").getAsBoolean();
            a.recoveries = x.has("recoveries") ? x.get("recoveries").getAsInt() : 0;
            if (a.recoveries < 0) throw new IllegalArgumentException("invalid recovery count");
            a.recoveryReason = x.has("recoveryReason") ? x.get("recoveryReason").getAsString() : "";
            a.routeFailure = x.has("routeFailure") ? ConstructionAccessPlan.RouteFailure.valueOf(x.get("routeFailure").getAsString()) : ConstructionAccessPlan.RouteFailure.NONE;
            for (JsonElement e : x.getAsJsonArray("placed")) { JsonObject p = e.getAsJsonObject(); a.placed.put(pos(p.get("pos")), p.get("owner").getAsBoolean()); }
            Set<BlockPos> owned = new HashSet<>();
            for (int i = 0; i < work.size(); i++) {
                var w = work.get(i);
                if (!a.bounds.contains(w.cell().pos()) || !a.bounds.contains(w.station())) throw new IllegalArgumentException("access outside confirmed bounds");
                if (w.temporary()) {
                    if (!Set.of("minecraft:cobblestone", "minecraft:scaffolding").contains(w.cell().blockId()) || !w.cell().properties().isEmpty()) throw new IllegalArgumentException("invalid temporary material");
                    if (i < a.cursor) owned.add(w.cell().pos());
                }
            }
            if (!owned.containsAll(a.placed.keySet())) throw new IllegalArgumentException("unrecorded temporary ownership");
        }
        List<SiteRequirement> requirements = new ArrayList<>();
        if (o.has("siteRequirements")) {
            if (o.getAsJsonArray("siteRequirements").size() > 262144) throw new IllegalArgumentException("site requirement limit");
            for (var raw : o.getAsJsonArray("siteRequirements")) { var r = raw.getAsJsonObject(); requirements.add(new SiteRequirement(pos(r.get("pos")), r.get("kind").getAsString())); }
        }
        return new Blueprint.Resolved(new BoundedRegion(pos(o.get("min")), pos(o.get("max"))), List.copyOf(cells), List.copyOf(clear), a,
            o.has("costPlan") ? ConstructionCostPlan.read(o.getAsJsonObject("costPlan")) : null, requirements);
    }
}
