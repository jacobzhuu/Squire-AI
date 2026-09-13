package dev.squire.gametest;

import java.util.*;
import java.nio.charset.StandardCharsets;
import dev.squire.server.blueprint.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import dev.squire.server.world.BoundedRegion;

/** Full-library parsing audit plus real-world fee/settlement invariants. */
public final class M29BuildingCatalogGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 40)
    public void unconfirmedQuoteDetectsRemovedPreexistingBlocks(TestContext context) {
        var world = context.getWorld(); var p = context.getAbsolutePos(new BlockPos(3, 2, 3));
        world.setBlockState(p.down(), Blocks.STONE.getDefaultState(), 2);
        world.setBlockState(p, Blocks.OAK_PLANKS.getDefaultState(), 2);
        var cell = new Blueprint.Cell(p, "minecraft:oak_planks", Map.of(), 0, "wall", false);
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(cell), List.of());
        var quote = ConstructionCostPlan.create(world, r, 10, 0);
        context.assertTrue(quote.totals(true).isEmpty() && quote.sameFoundations(world, r), "existing work needs no new items");
        world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
        context.assertTrue(!quote.sameFoundations(world, r), "ghost must invalidate its quote after existing work is removed");
        context.assertTrue(ConstructionCostPlan.create(world, r, 10, 0).quote(List.of(cell)) == 1, "new quote includes the real replacement item");
        context.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 40)
    public void tallPlantsUseOneRealItemAndAtomicHalves(TestContext context) {
        var world = context.getWorld(); var p = context.getAbsolutePos(new BlockPos(2, 2, 2));
        world.setBlockState(p.down(), Blocks.DIRT.getDefaultState(), 2);
        var lower = new Blueprint.Cell(p, "minecraft:sunflower", Map.of("half", "lower"), 0, "plant", false);
        var upper = new Blueprint.Cell(p.up(), "minecraft:sunflower", Map.of("half", "upper"), 1, "plant", false);
        var r = new Blueprint.Resolved(new BoundedRegion(p, p.up()), List.of(lower, upper), List.of());
        var plan = ConstructionCostPlan.create(world, r, 10, 0);
        context.assertTrue(plan.totals(true).get(new Identifier("minecraft:sunflower")) == 1, "one real item for a tall plant");
        context.assertTrue(BlueprintAssembly.place(world, BlueprintAssembly.cells(lower, r)), "both halves survive placement");
        context.assertTrue(BlueprintManager.matches(world.getBlockState(p.up()), upper), "upper half preserved");
        var incomplete = new Blueprint.Resolved(new BoundedRegion(p, p.up()), List.of(lower), List.of());
        boolean rejected = false;
        try { BlueprintAssembly.cells(lower, incomplete); } catch (IllegalArgumentException expected) { rejected = true; }
        context.assertTrue(rejected, "incomplete pairs are rejected before billing"); context.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 1600, batchId = "squire-catalog-audit")
    public void all750ConvertedStructuresHaveExplicitValidationResults(TestContext context) {
        var definitions = BuildingCatalog.bundled().variants();
        var loader = new BlueprintLoader();
        BlueprintImporter.ResourceProvider resources = id -> {
            var input = getClass().getClassLoader().getResourceAsStream("data/" + id.getNamespace() + "/" + id.getPath());
            if (input == null) throw new java.io.IOException("missing " + id);
            return input;
        };
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            var v = definitions.get(i);
            context.runAtTick(i + 1, () -> {
                try (var input = resources.open(new Identifier("squire:building_catalog/variants/keepitlevel/"
                        + v.source().substring(v.source().indexOf("/blueprints/keepitlevel/") + "/blueprints/keepitlevel/".length()).replace(".blueprint", ".json")))) {
                    var b = loader.load(v.id(), new String(input.readAllBytes(), StandardCharsets.UTF_8), resources);
                    for (var c : b.resolve(BlockPos.ORIGIN, net.minecraft.util.math.Direction.NORTH).toPlace()) {
                        if (!c.blockId().startsWith("minecraft:")) throw new IllegalArgumentException("external block leaked");
                        // Fluids and quarantined non-item states are recorded, not claimed buildable.
                        if (BlueprintManager.placeable(c.blockId())) BlueprintManager.targetState(c);
                    }
                } catch (Exception bad) { errors.add(v.id() + ": " + bad); }
            });
        }
        context.runAtTick(755, () -> {
            try {
                var report = java.nio.file.Path.of("catalog-audit.txt");
                java.nio.file.Files.write(report, errors, StandardCharsets.UTF_8);
            } catch (java.io.IOException bad) { throw new IllegalStateException(bad); }
            context.assertTrue(errors.isEmpty(), "NBT conversion failures: " + errors.stream().limit(8).toList());
            context.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 60)
    public void frozenWasteSurvivesSnapshotsAndCannotRebuildPaidBlocksForFree(TestContext context) {
        checkFrozenBill(context, 1, 2000, 120);
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 60)
    public void apprenticePaysOriginalMaterials(TestContext context) {
        checkFrozenBill(context, 1, 0, 100);
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 60)
    public void masterSavingsSurviveSnapshotsAndZeroCostCellsSettle(TestContext context) {
        checkFrozenBill(context, 10, -5000, 50);
    }
    private static void checkFrozenBill(TestContext context, int level, int rate, int expected) {
        var world = context.getWorld(); var origin = context.getAbsolutePos(new BlockPos(1, 2, 1));
        List<Blueprint.Cell> cells = new ArrayList<>();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) {
            var p = origin.add(x, 0, z); world.setBlockState(p.down(), Blocks.STONE.getDefaultState(), 2);
            world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            cells.add(new Blueprint.Cell(p, "minecraft:oak_planks", Map.of(), x * 10 + z, "planks", false));
        }
        var resolved = new Blueprint.Resolved(new BoundedRegion(origin, origin.add(9, 0, 9)), cells, List.of());
        var plan = ConstructionCostPlan.create(world, resolved, level, rate);
        var item = new Identifier("minecraft:oak_planks");
        context.assertTrue(plan.totals(false).get(item) == 100 && plan.totals(true).get(item) == expected, "frozen adjusted bill");
        int consumed = 0;
        for (var c : cells) {
            int fee = plan.quote(List.of(c)); consumed += fee;
            context.assertTrue(fee >= 0 && BlueprintAssembly.place(world, List.of(c)), "paid operation is placeable");
            plan.settle(List.of(c));
        }
        context.assertTrue(consumed == expected && plan.remaining(world).isEmpty(), "sum of operation fees equals frozen bill");
        var copy = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(resolved.withCostPlan(plan))).costPlan();
        world.setBlockState(cells.get(0).pos(), Blocks.AIR.getDefaultState(), 2);
        context.assertTrue(copy.quote(List.of(cells.get(0))) == -1, "settled cells cannot be generated again");
        context.assertTrue(copy.wasteBasisPoints() == rate && copy.level() == level, "snapshot retains cost level");
        context.complete();
    }
}
