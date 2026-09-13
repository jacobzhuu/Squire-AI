package dev.squire.gametest;

import dev.squire.server.blueprint.BuildingCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.*;
import net.minecraft.util.math.Direction;

/** Each enabled variant resumes in assisted mode with a real NPC and exact paid bill in all four rotations.
 * Unsupported conversion/material resources are explicitly recorded, never counted as built. */
public final class M41ConstructionMatrixGameTests implements FabricGameTest {
    private static final java.util.Map<String, String> results = new java.util.TreeMap<>();
    private static void record(String id, Direction facing, String result) {
        results.put(id + "/" + facing, result);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("construction-matrix.json"),
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results)); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void build(TestContext c, String id, Direction facing, int index) {
        var variant = BuildingCatalog.bundled().variant(id).orElseThrow();
        if (!variant.buildable()) {
            record(id, facing, "UNSUPPORTED: " + variant.reason());
            c.assertTrue(!id.contains("/graveyard") && !id.matches(".*townhall(_d)?[12]$"),
                "previously qualified variant must remain buildable: " + id + " " + variant.reason());
            c.complete(); return;
        }
        record(id, facing, "RUNNING");
        // Platform variants extend below their origin. The default tiny fixture is
        // near y=-58 and would put their foundations below the world's y=-64 limit.
        M16ProjectGameTests.importedBuild(c, id, index, 10, 80,
            (a, p) -> {
                c.assertTrue(p.relocate(p.origin, facing), "rotation");
                var r = dev.squire.server.runtime.SquireRuntime.get().blueprints().resolve(p).orElseThrow();
                c.assertTrue(r.access().valid(), "planning: " + r.access().failure);
                // Exercise recovery for every topology/rotation. M40 separately covers
                // automatic detection from ordinary walking; this matrix starts at the
                // same persisted assistance flag a blocked project's Resume sets.
                r.access().assistance = true;
                r.access().recoveries = 1;
                r.access().recoveryReason = "MATRIX_BLOCKED_ROUTE";
                record(id, facing, "PLANNED: assisted=" + r.access().assistedCount());
            },
            ticks -> record(id, facing, "BUILT: ticks=" + ticks));
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-1")
    public void graveyard1_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard1", Direction.NORTH, 100); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-1")
    public void graveyard1_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard1", Direction.EAST, 101); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-1")
    public void graveyard1_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard1", Direction.SOUTH, 102); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-1")
    public void graveyard1_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard1", Direction.WEST, 103); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-2")
    public void graveyard2_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard2", Direction.NORTH, 104); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-2")
    public void graveyard2_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard2", Direction.EAST, 105); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-2")
    public void graveyard2_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard2", Direction.SOUTH, 106); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-2")
    public void graveyard2_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard2", Direction.WEST, 107); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-3")
    public void graveyard3_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard3", Direction.NORTH, 108); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-3")
    public void graveyard3_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard3", Direction.EAST, 109); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-3")
    public void graveyard3_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard3", Direction.SOUTH, 110); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-3")
    public void graveyard3_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard3", Direction.WEST, 111); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-4")
    public void graveyard4_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard4", Direction.NORTH, 112); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-4")
    public void graveyard4_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard4", Direction.EAST, 113); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-4")
    public void graveyard4_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard4", Direction.SOUTH, 114); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-4")
    public void graveyard4_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard4", Direction.WEST, 115); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-5")
    public void graveyard5_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard5", Direction.NORTH, 116); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-5")
    public void graveyard5_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard5", Direction.EAST, 117); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-5")
    public void graveyard5_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard5", Direction.SOUTH, 118); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-standard-5")
    public void graveyard5_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard5", Direction.WEST, 119); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-1")
    public void graveyard_d1_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d1", Direction.NORTH, 120); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-1")
    public void graveyard_d1_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d1", Direction.EAST, 121); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-1")
    public void graveyard_d1_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d1", Direction.SOUTH, 122); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-1")
    public void graveyard_d1_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d1", Direction.WEST, 123); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-2")
    public void graveyard_d2_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d2", Direction.NORTH, 124); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-2")
    public void graveyard_d2_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d2", Direction.EAST, 125); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-2")
    public void graveyard_d2_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d2", Direction.SOUTH, 126); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-2")
    public void graveyard_d2_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d2", Direction.WEST, 127); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-3")
    public void graveyard_d3_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d3", Direction.NORTH, 128); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-3")
    public void graveyard_d3_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d3", Direction.EAST, 129); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-3")
    public void graveyard_d3_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d3", Direction.SOUTH, 130); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-3")
    public void graveyard_d3_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d3", Direction.WEST, 131); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-4")
    public void graveyard_d4_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d4", Direction.NORTH, 132); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-4")
    public void graveyard_d4_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d4", Direction.EAST, 133); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-4")
    public void graveyard_d4_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d4", Direction.SOUTH, 134); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-4")
    public void graveyard_d4_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d4", Direction.WEST, 135); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-5")
    public void graveyard_d5_north(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d5", Direction.NORTH, 136); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-5")
    public void graveyard_d5_east(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d5", Direction.EAST, 137); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-5")
    public void graveyard_d5_south(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d5", Direction.SOUTH, 138); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-graveyard-_d-5")
    public void graveyard_d5_west(TestContext c) { build(c, "squire:keepitlevel/mystic/graveyard_d5", Direction.WEST, 139); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-1")
    public void townhall1_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall1", Direction.NORTH, 140); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-1")
    public void townhall1_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall1", Direction.EAST, 141); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-1")
    public void townhall1_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall1", Direction.SOUTH, 142); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-1")
    public void townhall1_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall1", Direction.WEST, 143); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-2")
    public void townhall2_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall2", Direction.NORTH, 144); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-2")
    public void townhall2_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall2", Direction.EAST, 145); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-2")
    public void townhall2_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall2", Direction.SOUTH, 146); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-2")
    public void townhall2_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall2", Direction.WEST, 147); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-3")
    public void townhall3_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall3", Direction.NORTH, 148); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-3")
    public void townhall3_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall3", Direction.EAST, 149); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-3")
    public void townhall3_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall3", Direction.SOUTH, 150); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-3")
    public void townhall3_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall3", Direction.WEST, 151); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-4")
    public void townhall4_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall4", Direction.NORTH, 152); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-4")
    public void townhall4_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall4", Direction.EAST, 153); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-4")
    public void townhall4_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall4", Direction.SOUTH, 154); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-4")
    public void townhall4_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall4", Direction.WEST, 155); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-5")
    public void townhall5_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall5", Direction.NORTH, 156); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-5")
    public void townhall5_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall5", Direction.EAST, 157); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-5")
    public void townhall5_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall5", Direction.SOUTH, 158); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-standard-5")
    public void townhall5_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall5", Direction.WEST, 159); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-1")
    public void townhall_d1_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d1", Direction.NORTH, 160); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-1")
    public void townhall_d1_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d1", Direction.EAST, 161); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-1")
    public void townhall_d1_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d1", Direction.SOUTH, 162); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-1")
    public void townhall_d1_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d1", Direction.WEST, 163); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-2")
    public void townhall_d2_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d2", Direction.NORTH, 164); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-2")
    public void townhall_d2_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d2", Direction.EAST, 165); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-2")
    public void townhall_d2_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d2", Direction.SOUTH, 166); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-2")
    public void townhall_d2_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d2", Direction.WEST, 167); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-3")
    public void townhall_d3_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d3", Direction.NORTH, 168); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-3")
    public void townhall_d3_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d3", Direction.EAST, 169); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-3")
    public void townhall_d3_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d3", Direction.SOUTH, 170); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-3")
    public void townhall_d3_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d3", Direction.WEST, 171); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-4")
    public void townhall_d4_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d4", Direction.NORTH, 172); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-4")
    public void townhall_d4_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d4", Direction.EAST, 173); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-4")
    public void townhall_d4_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d4", Direction.SOUTH, 174); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-4")
    public void townhall_d4_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d4", Direction.WEST, 175); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-5")
    public void townhall_d5_north(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d5", Direction.NORTH, 176); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-5")
    public void townhall_d5_east(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d5", Direction.EAST, 177); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-5")
    public void townhall_d5_south(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d5", Direction.SOUTH, 178); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "matrix-townhall-_d-5")
    public void townhall_d5_west(TestContext c) { build(c, "squire:keepitlevel/fundamentals/townhall_d5", Direction.WEST, 179); }
}
