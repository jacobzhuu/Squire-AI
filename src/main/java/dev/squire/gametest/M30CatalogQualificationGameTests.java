package dev.squire.gametest;

import java.util.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.registry.SquireEntities;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Offline qualification, not a substitute for the real-NPC end-to-end tests.
 * Writes evidence to build/gametest; the importer binds it to each exact NBT hash.
 */
public final class M30CatalogQualificationGameTests implements FabricGameTest {
    private static final BlueprintImporter.ResourceProvider RESOURCES = id -> {
        var in = M30CatalogQualificationGameTests.class.getClassLoader().getResourceAsStream("data/" + id.getNamespace() + "/" + id.getPath());
        if (in == null) throw new java.io.IOException("missing " + id); return in;
    };
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 152000, batchId = "squire-full-catalog-qualification")
    public void convertedLibraryPassesAssemblyPhysicsAndAccessReview(TestContext context) throws Exception {
        JsonArray variants;
        try (var in = RESOURCES.open(new Identifier("squire:building_catalog/keepitlevel.json"))) {
            variants = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("variants");
        }
        var world = context.getWorld(); var origin = new BlockPos(16000, 70, 16000);
        for (int x = -1; x <= 3; x++) for (int z = -1; z <= 3; z++) world.setChunkForced((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z, true);
        var worker = SquireEntities.AVATAR.create(world);
        List<BlockPos> changed = new ArrayList<>(); JsonArray results = new JsonArray();
        int[] passed = {0};
        for (int i = 0; i < variants.size(); i++) {
            var v = variants.get(i).getAsJsonObject();
            final int index = i;
            JsonObject result = new JsonObject(); result.addProperty("id", v.get("id").getAsString());
            result.addProperty("convertedSha256", v.get("convertedSha256").getAsString());
            result.addProperty("validation", "vanilla-fluid-escrow-access-v5");
            result.add("conversionRulesSha256", v.get("conversionRulesSha256"));
            List<String> errors = new ArrayList<>(); Blueprint.Resolved[] resolved = {null};
            context.runAtTick(1 + i * 200, () -> {
                changed.forEach(p -> world.setBlockState(p, Blocks.AIR.getDefaultState(), 18)); changed.clear();
                if (!v.get("conversionStatus").getAsString().equals("CONVERTED") || v.get("status").getAsString().equals("INTERNAL")) {
                    errors.add("SOURCE_CONVERSION_BLOCKED"); return;
                }
                try {
                    Blueprint bp;
                    try (var in = RESOURCES.open(new Identifier("squire:building_catalog/variants/keepitlevel/" + v.get("sourcePath").getAsString().replace(".blueprint", ".json")))) {
                        bp = new BlueprintLoader().load(v.get("id").getAsString(), new String(in.readAllBytes(), StandardCharsets.UTF_8), RESOURCES);
                    }
                    for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST})
                        for (var c : bp.resolve(BlockPos.ORIGIN, direction).toPlace()) BlueprintManager.targetState(c);
                    var r = bp.resolve(origin, Direction.NORTH); resolved[0] = r;
                    int floor = r.bounds().min().getY() - 1;
                    for (int x = -12; x <= 44; x++) for (int z = -12; z <= 44; z++) {
                        var p = new BlockPos(origin.getX() + x, floor, origin.getZ() + z);
                        world.setBlockState(p, Blocks.STONE.getDefaultState(), 18); changed.add(p);
                    }
                    Set<BlockPos> environmentWater = new HashSet<>();
                    r.siteRequirements().stream().filter(q -> q.kind().equals("fluid")).forEach(q -> environmentWater.add(q.pos()));
                    for (BlockPos p : environmentWater) for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                        BlockPos n = p.offset(d);
                        if (!environmentWater.contains(n)) { world.setBlockState(n, Blocks.STONE.getDefaultState(), 18); changed.add(n); }
                    }
                    for (var requirement : r.siteRequirements()) {
                        world.setBlockState(requirement.pos(), requirement.kind().equals("solid") ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState(), 18); changed.add(requirement.pos());
                    }
                    worker.refreshPositionAndAngles(origin.getX() - 4.5, floor + 1, origin.getZ() - 4.5, 0, 0);
                    var access = ConstructionAccessPlan.plan(world, r, worker);
                    if (!access.valid()) throw new IllegalArgumentException("ACCESS: " + access.failure);
                    // Validate the exact planned ordering (including real supports), not a FORCE_STATE paste.
                    var cells = access.work.isEmpty() ? BlueprintManager.constructionPlan(world, r, true).cells()
                        : access.work.stream().map(ConstructionAccessPlan.Work::cell).toList();
                    var cost = ConstructionCostPlan.create(world, r, 10, 0);
                    int real = cost.totals(false).values().stream().mapToInt(Integer::intValue).sum();
                    result.addProperty("realMaterials", real); result.addProperty("temporaryBlocks", access.temporaryCount());
                    result.addProperty("assistedSteps", access.assistedCount()); result.addProperty("routeFailure", access.routeFailure.name());
                    for (var support : BlueprintManager.automaticSiteSupports(world, r)) {
                        changed.add(support.pos());
                        if (!BlueprintAssembly.place(world, List.of(support))) throw new IllegalArgumentException("FOUNDATION_FAILED");
                    }
                    int workIndex = 0;
                    for (var cell : cells) {
                        var work = access.work.isEmpty() ? null : access.work.get(workIndex++);
                        var station = work == null ? null : work.station();
                        if (work != null && (work.mode() != ConstructionAccessPlan.Mode.WALK || !worker.isSafeWorkPosition(station))) {
                            var mode = dev.squire.server.task.executors.ConstructionRecovery.prepare(worker, access.bounds, cell.pos(),
                                dev.squire.server.task.executors.ConstructionRecovery.changes(cell, r));
                            if (mode == null) throw new IllegalArgumentException("NO_SAFE_ANCHOR: " + cell.pos().subtract(origin));
                            station = worker.getBlockPos();
                        }
                        if (cell.blockId().equals("minecraft:air")) {
                            if (!ConstructionAccessPlan.diggable(world, cell.pos())) throw new IllegalArgumentException("UNSAFE_EXCAVATION: " + cell.pos());
                            changed.add(cell.pos()); world.setBlockState(cell.pos(), Blocks.AIR.getDefaultState(), 3); continue;
                        }
                        if (BlueprintManager.matches(world.getBlockState(cell.pos()), cell)) continue;
                        var assembly = BlueprintAssembly.pending(world, cell, r);
                        assembly.forEach(c -> changed.add(c.pos()));
                        if (!BlueprintAssembly.place(world, assembly)) throw new IllegalArgumentException("UNSUPPORTED_ASSEMBLY: " + cell.blockId() + " " + cell.pos().subtract(origin).toShortString());
                        if (station != null && !worker.isSafeWorkPosition(station))
                            throw new IllegalArgumentException("UNSAFE_STATION_AFTER: " + station.subtract(origin).toShortString());
                    }
                    for (var cell : ConstructionFluids.operations(r)) {
                        if (ConstructionFluids.matches(world, cell)) continue;
                        String unsafe = ConstructionFluids.safety(world, r, cell);
                        if (!unsafe.isEmpty()) throw new IllegalArgumentException(unsafe);
                        changed.add(cell.pos());
                        if (!ConstructionFluids.pour(world, cell)) throw new IllegalArgumentException("FLUID_POUR_FAILED");
                    }
                } catch (Exception failure) { errors.add(failure.getMessage() == null ? failure.toString() : failure.getMessage()); }
            });
            context.runAtTick(180 + i * 200, () -> {
                if (resolved[0] != null && errors.isEmpty()) {
                    resolved[0].toPlace().stream().filter(c -> !BlueprintManager.matches(world.getBlockState(c.pos()), c)).limit(3)
                        .forEach(c -> errors.add("PHYSICS_CHANGED: " + c.blockId() + " " + c.pos().subtract(origin).toShortString()));
                }
                result.addProperty("passed", errors.isEmpty()); JsonArray messages = new JsonArray(); errors.forEach(messages::add); result.add("errors", messages);
                if (errors.isEmpty()) passed[0]++; results.add(result);
                if (index % 50 == 0) org.slf4j.LoggerFactory.getLogger(getClass()).info("Catalog qualification {}/750, passed {}", index + 1, passed[0]);
            });
        }
        context.runAtTick(150001, () -> {
            try {
                var report = new JsonObject(); report.addProperty("schemaVersion", 1); report.addProperty("test", "M30CatalogQualificationGameTests"); report.add("results", results);
                java.nio.file.Files.writeString(java.nio.file.Path.of("catalog-qualification.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
            } catch (Exception bad) { throw new IllegalStateException(bad); }
            changed.forEach(p -> world.setBlockState(p, Blocks.AIR.getDefaultState(), 18)); worker.discard();
            context.assertTrue(results.size() == 750, "every source has an explicit qualification result");
            context.assertTrue(passed[0] > 0, "at least one converted asset is buildable"); context.complete();
        });
    }
}
