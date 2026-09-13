package dev.squire.gametest;

import java.util.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.project.Project;
import dev.squire.server.world.BoundedRegion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.*;
import net.minecraft.test.*;
import net.minecraft.util.math.BlockPos;

public final class M34FluidConstructionGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "shepherd-tier5-access")
    public void shepherdTier5CompletesWithConstructionAndRecoveryRoutes(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/agriculture/husbandry/shepherd5", 65, 10, ignored -> {});
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "fluid-native-shepherd")
    public void nativeShepherdTierOneBuildsWithPaidWater(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/agriculture/husbandry/shepherd1", 43, 10, ticks -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void flowingWaterCannotSatisfyAnAuthoredSource(TestContext c) {
        var cell = new Blueprint.Cell(c.getAbsolutePos(new BlockPos(3, 3, 3)), "minecraft:water", Map.of(), 0, "source", false);
        c.assertTrue(!BlueprintManager.matches(Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL, 1), cell), "implicit level zero requires a real source");
        c.assertTrue(BlueprintManager.matches(Blocks.WATER.getDefaultState(), cell), "still source accepted");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "fluid-npc-artificial")
    public void realEngineerExcavatesArtificialWaterSiteAndFetchesWater(TestContext c) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer());
        String id = "gametest_artificial_water";
        var metadata = new Blueprint.Metadata("test", "", "", "", "", Set.of(), "squire:steps", 1,
            List.of(new SiteRequirement(new BlockPos(3, 0, 3), "fluid"), new SiteRequirement(new BlockPos(4, 0, 3), "fluid")));
        SquireRuntime.get().blueprints().registry().register(new Blueprint(id, id, 1, Blueprint.Category.DECORATION, 6, 2, 6,
            List.of(BlueprintStep.place(0, 0, 0, 0, 0, 1, 0, "minecraft:stone_bricks", "marker", false)), Set.of(), List.of(), metadata));
        M16ProjectGameTests.importedBuild(c, id, 42, 10, (avatar, placement) -> {
            avatar.items().insert(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_PICKAXE));
            BlockPos source = placement.origin.add(-8, 0, -8);
            var world = c.getWorld();
            for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++)
                world.setBlockState(source.add(x, 0, z), x == -1 || x == 2 || z == -1 || z == 2 ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState());
            c.assertTrue(placement.configureWater(true, source), "explicit paid basin and approved source");
        }, ticks -> {
            var avatar = SquireRuntime.get().resolveAvatarFor(UUID.nameUUIDFromBytes("kit-42-lv10".getBytes())).orElseThrow();
            c.assertTrue(avatar.items().countOf(ConstructionFluids.BUCKET) == 1, "single reusable bucket returned after real round trips");
            c.assertTrue(avatar.items().countOf(ConstructionFluids.WATER_BUCKET) == 0, "no free stored full bucket");
        });
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "fluid-npc-water")
    public void realEngineerBuildsWaterBasin(TestContext c) { npc(c, false, 40); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "fluid-npc-lava")
    public void realEngineerBuildsLavaBasin(TestContext c) { npc(c, true, 41); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "fluid-npc-waterlogged")
    public void realEngineerBuildsAndWaterlogsSlab(TestContext c) { npc(c, false, 44); }
    private void npc(TestContext c, boolean lava, int index) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer());
        String id = (lava ? "gametest_lava_basin" : "gametest_water_basin") + index;
        List<BlueprintStep> steps = new ArrayList<>();
        steps.add(BlueprintStep.place(0, 0, 0, 0, 4, 0, 4, "minecraft:stone_bricks", "pool floor", false));
        steps.add(BlueprintStep.place(1, 0, 1, 0, 4, 1, 0, "minecraft:stone_bricks", "north", false));
        steps.add(BlueprintStep.place(2, 0, 1, 4, 4, 1, 4, "minecraft:stone_bricks", "south", false));
        steps.add(BlueprintStep.place(3, 0, 1, 1, 0, 1, 3, "minecraft:stone_bricks", "west", false));
        steps.add(BlueprintStep.place(4, 4, 1, 1, 4, 1, 3, "minecraft:stone_bricks", "east", false));
        steps.add(BlueprintStep.place(5, 1, 1, 1, 3, 1, 3, lava ? "minecraft:lava" : "minecraft:water", "pool liquid", false));
        if (index == 44) steps.add(new BlueprintStep(6, 2, 1, 2, 2, 1, 2,
            "minecraft:oak_slab", "wet slab", false, false, "", "block", Map.of("type", "bottom", "waterlogged", "true")));
        SquireRuntime.get().blueprints().registry().register(new Blueprint(id, id, 1, Blueprint.Category.DECORATION, 5, 2, 5, steps, Set.of()));
        M16ProjectGameTests.importedBuild(c, id, index, 10, ticks -> {
            var rt = SquireRuntime.get();
            var ownerId = UUID.nameUUIDFromBytes(("kit-" + index + "-lv10").getBytes());
            var avatar = rt.resolveAvatarFor(ownerId).orElseThrow();
            c.assertTrue(avatar.items().countOf(ConstructionFluids.BUCKET) + avatar.items().countOf(lava ? ConstructionFluids.LAVA_BUCKET : ConstructionFluids.WATER_BUCKET) == 9,
                "each full bucket is either used and returned empty, or remains full when nature satisfied a goal");
        });
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void wetBlockCostsSolidPlusOneBucketWithoutBucketWaste(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 3, 3));
        var cell = new Blueprint.Cell(p, "minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "true"), 0, "wet slab", false);
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(cell), List.of());
        var plan = ConstructionCostPlan.create(c.getWorld(), r, 1, 2000);
        c.assertTrue(plan.operations().stream().filter(ConstructionCostPlan.Operation::fluid).count() == 1, "separate fill operation");
        c.assertTrue(plan.totals(true).get(ConstructionFluids.WATER_BUCKET) == 1, "no water or iron-container waste");
        c.assertTrue(!BlueprintAssembly.place(c.getWorld(), List.of(cell)), "ordinary assembly cannot create free water");
        var restored = ConstructionCostPlan.read(plan.write());
        c.assertTrue(restored.fluidOperation(cell).outputs().equals(Map.of(ConstructionFluids.BUCKET, 1)), "bucket output survives reload");
        restored.waterSource(p.east(20));
        restored.settleOperation(restored.fluidOperation(cell));
        var paid = ConstructionCostPlan.read(restored.write());
        c.assertTrue(paid.settled(paid.fluidOperation(cell)) && paid.waterSource().equals(p.east(20)), "settlement and chosen source persist independently of the world");
        c.assertTrue(!paid.remaining(c.getWorld()).containsKey(ConstructionFluids.WATER_BUCKET), "damaged paid source never becomes an automatic free refill");
        var checkpoint = r.withCostPlan(paid);
        var geometry = ConstructionSnapshotCodec.writeGeometry(checkpoint);
        ConstructionSnapshotCodec.mergeProgress(geometry, ConstructionSnapshotCodec.writeProgress(checkpoint));
        var restoredCheckpoint = ConstructionSnapshotCodec.read(geometry).costPlan();
        c.assertTrue(restoredCheckpoint.settled(restoredCheckpoint.fluidOperation(cell)) && restoredCheckpoint.waterSource().equals(p.east(20)),
            "split geometry/progress checkpoint preserves paid fill and approved source");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void wetPreviewRequotesRemovedDryContainer(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 3, 3));
        var cell = new Blueprint.Cell(p, "minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "true"), 0, "wet slab", false);
        c.getWorld().setBlockState(p.down(), Blocks.STONE.getDefaultState());
        c.getWorld().setBlockState(p, Blocks.OAK_SLAB.getDefaultState());
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(cell), List.of());
        var plan = ConstructionCostPlan.create(c.getWorld(), r, 10, 0);
        c.assertTrue(plan.sameFoundations(c.getWorld(), r), "existing dry slab and quoted fill cover both phases");
        c.getWorld().setBlockState(p, Blocks.AIR.getDefaultState());
        c.assertTrue(!plan.sameFoundations(c.getWorld(), r), "a fluid quote cannot hide a missing solid quote");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void exhaustedSourceQuotesSuppliedWaterAndCanRecover(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 3, 3)), source = p.east(3);
        var cell = new Blueprint.Cell(p, "minecraft:water", Map.of(), 0, "source", false);
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(cell), List.of());
        var plan = ConstructionCostPlan.create(c.getWorld(), r, 10, 0);
        plan.waterSource(source);
        c.assertTrue(plan.remaining(c.getWorld()).getOrDefault(ConstructionFluids.WATER_BUCKET, 0) == 1, "exhausted source accepts real full-bucket fallback through normal escrow funding");
        c.getWorld().setBlockState(source, Blocks.WATER.getDefaultState());
        c.assertTrue(plan.remaining(c.getWorld()).getOrDefault(ConstructionFluids.BUCKET, 0) == 1, "restored source reuses one container");
        c.assertTrue(!plan.remaining(c.getWorld()).containsKey(ConstructionFluids.WATER_BUCKET), "same CostPlan switches funding without repricing settled construction");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void bucketExchangePreservesOriginAndCannotRepeatWithoutInput(TestContext c) {
        var project = new Project(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test", "test", UUID.randomUUID(), "minecraft:overworld", 0, List.of());
        project.reserve(Map.of(ConstructionFluids.WATER_BUCKET, 1), Map.of(ConstructionFluids.LAVA_BUCKET, 1));
        c.assertTrue(project.exchangeContainer(ConstructionFluids.WATER_BUCKET, ConstructionFluids.BUCKET), "owner exchange");
        c.assertTrue(project.ownerSupply().equals(Map.of(ConstructionFluids.BUCKET, 1)), "owner retains iron");
        c.assertTrue(!project.exchangeContainer(ConstructionFluids.WATER_BUCKET, ConstructionFluids.BUCKET), "no duplicate output");
        c.assertTrue(project.exchangeContainer(ConstructionFluids.LAVA_BUCKET, ConstructionFluids.BUCKET), "agent exchange");
        c.assertTrue(project.agentSupply().equals(Map.of(ConstructionFluids.BUCKET, 1)), "agent retains iron"); c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void reusableBucketInTransitDoesNotRequestAnotherBucket(TestContext c) {
        var project = new Project(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test", "test", UUID.randomUUID(), "minecraft:overworld", 0, List.of());
        project.reserve(Map.of(), Map.of(ConstructionFluids.BUCKET, 1));
        c.assertTrue(project.exchangeContainer(ConstructionFluids.BUCKET, ConstructionFluids.WATER_BUCKET), "real bucket filled");
        c.assertTrue(project.missingFrom(Map.of(ConstructionFluids.BUCKET, 1)).isEmpty(), "in-transit full bucket covers reusable container requirement");
        c.assertTrue(project.exchangeContainer(ConstructionFluids.WATER_BUCKET, ConstructionFluids.BUCKET), "pour returns bucket");
        c.assertTrue(project.missingFrom(Map.of(ConstructionFluids.BUCKET, 1)).isEmpty(), "returned bucket covers next trip");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void lavaNearWoodIsRejectedBeforeWorldMutation(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 3, 3));
        var cell = new Blueprint.Cell(p, "minecraft:lava", Map.of(), 0, "lava", false);
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(cell), List.of());
        for (var d : new net.minecraft.util.math.Direction[]{net.minecraft.util.math.Direction.DOWN, net.minecraft.util.math.Direction.NORTH,
                net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST})
            c.getWorld().setBlockState(p.offset(d), Blocks.STONE.getDefaultState());
        c.getWorld().setBlockState(p.up(2), Blocks.OAK_PLANKS.getDefaultState());
        c.assertTrue(ConstructionFluids.safety(c.getWorld(), r, cell).startsWith("FLUID_FIRE_RISK"), "wood risk rejected even in a sealed basin");
        c.assertTrue(c.getWorld().isAir(p), "validation never pours lava");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void artificialMaskProducesPaidLiningAndNoMutation(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 3, 3));
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(), List.of(), null, null, List.of(new SiteRequirement(p, "fluid")));
        var prepared = ArtificialWaterSite.prepare(r);
        c.assertTrue(prepared.toPlace().size() == 6 && prepared.siteRequirements().isEmpty(), "source plus five permanent lining blocks");
        c.assertTrue(c.getWorld().isAir(p), "preview must never mutate");
        c.assertTrue(ConstructionFluids.safety(c.getWorld(), prepared, prepared.toPlace().stream().filter(ConstructionFluids::source).findFirst().orElseThrow()).startsWith("FLUID_CONTAINMENT_OPEN"), "cannot pour before lining exists"); c.complete();
    }
}
