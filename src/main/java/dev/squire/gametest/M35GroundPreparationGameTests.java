package dev.squire.gametest;

import java.util.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.world.BoundedRegion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.*;
import net.minecraft.test.*;
import net.minecraft.util.math.BlockPos;

public final class M35GroundPreparationGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 1500, batchId = "fulfil-resumes")
    public void fulfilDepositsAndResumesPausedProject(TestContext c) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer());
        var rt = SquireRuntime.get();
        var owner = net.fabricmc.fabric.api.entity.FakePlayer.get(c.getWorld(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fulfil-project"));
        var p = c.getAbsolutePos(new BlockPos(2, 2, 2));
        owner.refreshPositionAndAngles(p.getX() + .5, p.getY(), p.getZ() + .5, 0, 0);
        c.getWorld().spawnEntity(owner);
        rt.permissions().grant(owner.getUuid(), dev.squire.server.security.PermissionNodes.COMMAND_GIVE);
        var avatar = rt.summonFirstAt(owner, p.east(3));
        c.assertTrue(avatar != null, "engineer spawned");
        avatar.profile().profession.setProfession(dev.squire.server.profession.SquireProfession.ENGINEER);
        avatar.profile().profession.level = 10; avatar.setIdleMode();
        String id = "gametest_fulfil_discount";
        rt.blueprints().registry().register(new Blueprint(id, id, 1, Blueprint.Category.DEFENCE, 4, 1, 1,
            java.util.List.of(dev.squire.server.blueprint.BlueprintStep.place(0, 0, 0, 0, 3, 0, 0, "minecraft:glowstone", "test", false)), java.util.Set.of()));
        java.util.UUID[] projectId = new java.util.UUID[1];
        c.runAtTick(20, () -> {
            rt.agents().resolveForOwnerNow(owner.getUuid());
            var preview = rt.projectStart(owner, id); c.assertTrue(preview.success(), preview.message());
            var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
            placement.relocate(c.getAbsolutePos(new BlockPos(3, 2, 6)), net.minecraft.util.math.Direction.NORTH);
            var confirmed = rt.blueprintBuild(owner); c.assertTrue(confirmed.success(), confirmed.message());
            var project = rt.projects().activeOf(owner.getUuid()).orElseThrow(); projectId[0] = project.projectId;
            c.assertTrue(project.state() == dev.squire.server.project.Project.State.PAUSED, "initially paused for materials");
            var fulfil = rt.blueprintFulfil(owner); c.assertTrue(fulfil.success(), fulfil.message());
            var resolved = rt.blueprints().resolve(placement).orElseThrow();
            c.assertTrue(project.missingFrom(BlueprintManager.requiredProjectMaterials(c.getWorld(), resolved)).isEmpty(), "fulfil deposited into escrow");
        });
        c.runAtTick(1200, () -> {
            var project = rt.projects().project(projectId[0]).orElseThrow();
            c.assertTrue(project.state() == dev.squire.server.project.Project.State.DONE, "funded project finished: " + rt.projects().describe(project));
            rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS); c.complete();
        });
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "market-exact-bill")
    public void marketStallCompletesWithExactDiscountedBill(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/decorations/markets/stall_bl", 61, 10, ticks -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "townhall-granite")
    public void townHallClearsGraniteAndCompletes(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/fundamentals/townhall1", 62, 10,
            (avatar, placement) -> {
                var manager = SquireRuntime.get().blueprints();
                var raw = manager.registry().byId(placement.blueprintId).orElseThrow()
                    .resolve(placement.origin, placement.facing, placement.materials(), manager.registry().materials());
                var p = raw.toClear().stream().filter(pos -> pos.getY() == placement.origin.getY() + 1).findFirst().orElseThrow();
                c.getWorld().setBlockState(p, Blocks.GRANITE.getDefaultState(), 2);
                placement.invalidatePreview();
            }, ticks -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void sampledSoilIsPaidAndPreviewDoesNotFillTheWorld(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(5, 3, 5));
        for (int x = -4; x <= 2; x++) for (int z = -4; z <= 2; z++)
            c.getWorld().setBlockState(p.add(x, -1, z), Blocks.GRASS_BLOCK.getDefaultState());
        c.getWorld().setBlockState(p.down(), Blocks.AIR.getDefaultState());
        c.getWorld().setBlockState(p.down(2), Blocks.STONE.getDefaultState());
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(), List.of(p), null, null, List.of(new SiteRequirement(p, "solid")));
        var prepared = GroundPreparation.prepare(c.getWorld(), r);
        c.assertTrue(prepared.toPlace().size() == 2 && prepared.siteRequirements().isEmpty() && prepared.toClear().isEmpty(), "marker becomes two paid fills down to actual ground");
        c.assertTrue(prepared.toPlace().stream().allMatch(cell -> cell.blockId().equals("minecraft:dirt")), "grass terrain uses ordinary soil, not building material or silk-touch grass");
        c.assertTrue(c.getWorld().isAir(p) && c.getWorld().isAir(p.down()), "preview stays read-only");
        var plan = ConstructionCostPlan.create(c.getWorld(), prepared, 10, 0);
        c.assertTrue(plan.totals(true).getOrDefault(new net.minecraft.util.Identifier("minecraft:dirt"), 0) == 2, "both fills enter the authoritative bill");
        var snapshot = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(prepared.withCostPlan(plan)));
        c.assertTrue(snapshot.toPlace().equals(prepared.toPlace()), "ground geometry is frozen across reload");
        c.assertTrue(BlueprintManager.matches(Blocks.GRASS_BLOCK.getDefaultState(), prepared.toPlace().get(0)), "natural grass spread does not invalidate paid dirt");
        c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void deepVoidBecomesElevatedAccessWithoutPermanentFill(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 15, 3));
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(), List.of(), null, null, List.of(new SiteRequirement(p, "solid")));
        var prepared = GroundPreparation.prepare(c.getWorld(), r);
        c.assertTrue(prepared.toPlace().isEmpty(), "deep air is elevated access, not a permanent dirt column");
        c.assertTrue(c.getWorld().isAir(p) && c.getWorld().isAir(p.down(8)), "preview remains read-only across the old depth limit");
        c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "townhall-tier2-access")
    public void townHallTier2CompletesOnFlatGround(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/fundamentals/townhall2", 64, 10, ignored -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-ground-npc")
    public void engineerFillsHolesAndClearsObstructionsWithoutManualPreparation(TestContext c) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer());
        String id = "gametest_automatic_ground";
        var metadata = new Blueprint.Metadata("test", "", "", "", "", Set.of(), "squire:steps", 1,
            List.of(new SiteRequirement(new BlockPos(2, 0, 2), "solid")));
        SquireRuntime.get().blueprints().registry().register(new Blueprint(id, id, 1, Blueprint.Category.DECORATION, 5, 2, 5,
            List.of(BlueprintStep.place(0, 0, 0, 0, 0, 1, 0, "minecraft:stone_bricks", "wall", false),
                BlueprintStep.place(1, 2, 1, 2, 2, 1, 2, "minecraft:stone_bricks", "floor", false),
                BlueprintStep.dig(2, 3, 0, 3, 3, 0, 3, "clear obstacle")), Set.of(), List.of(), metadata));
        M16ProjectGameTests.importedBuild(c, id, 45, 10, (avatar, placement) -> {
            avatar.items().insert(new ItemStack(Items.DIAMOND_SHOVEL));
            avatar.items().insert(new ItemStack(Items.DIAMOND_PICKAXE));
            var p = placement.origin.add(2, 0, 2);
            c.getWorld().setBlockState(p.down(), Blocks.WATER.getDefaultState(), 2);
            c.getWorld().setBlockState(p.down(2), Blocks.WATER.getDefaultState(), 2);
            c.getWorld().setBlockState(p.down(3), Blocks.STONE.getDefaultState());
            c.getWorld().setBlockState(placement.origin.add(3, 0, 3), Blocks.DIRT.getDefaultState());
        }, ticks -> {});
    }
}
