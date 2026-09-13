package dev.squire.gametest;

import java.util.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.runtime.*;
import dev.squire.server.profession.*;
import dev.squire.server.project.*;
import dev.squire.server.body.avatar.AvatarEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.entity.FakePlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.block.Blocks;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.test.*;
import net.minecraft.util.math.*;

public final class M36TerrainLevelingGameTests implements FabricGameTest {
    private static int serial;
    private record Fixture(SquireRuntime rt, FakePlayer owner, AvatarEntity avatar, BlueprintPlacement placement) { }
    private static Fixture site(TestContext c, int width, int depth) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer());
        var rt = SquireRuntime.get(); var world = c.getWorld(); int index = ++serial;
        var origin = new BlockPos(16000 + index * 64, Math.max(world.getBottomY() + 32,
            c.getAbsolutePos(new BlockPos(0, 3, 0)).getY()), 16000);
        for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) world.setChunkForced((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z, true);
        for (int x = -8; x < 40; x++) for (int z = -8; z < 40; z++) {
            world.setBlockState(origin.add(x, -1, z), Blocks.STONE.getDefaultState(), 2);
            world.setBlockState(origin.add(x, 0, z), Blocks.GRASS_BLOCK.getDefaultState(), 2);
        }
        var owner = FakePlayer.get(world, new GameProfile(UUID.randomUUID(), "terrain" + index));
        owner.refreshPositionAndAngles(origin.getX() - 3.5, origin.getY() + 1, origin.getZ() - 3.5, 0, 0);
        world.spawnEntity(owner);
        var avatar = rt.summonFirstAt(owner, origin.add(-2, 1, -2));
        c.assertTrue(avatar != null, "engineer summoned");
        rt.professionOf(avatar).setProfession(SquireProfession.ENGINEER);
        rt.profileOf(avatar).traits.clear(); avatar.setIdleMode();
        avatar.items().insert(new ItemStack(Items.DIAMOND_SHOVEL));
        avatar.items().insert(new ItemStack(Items.DIAMOND_PICKAXE));
        avatar.items().insert(new ItemStack(Items.DIAMOND_AXE));
        var placement = new BlueprintPlacement(UUID.randomUUID(), owner.getUuid(), avatar.agentId(),
            new TerrainLeveling.Spec(width, depth).id(), world.getRegistryKey().getValue().toString(), origin, Direction.NORTH, world.getTime());
        rt.blueprints().put(placement);
        return new Fixture(rt, owner, avatar, placement);
    }
    private static void clean(Fixture f) {
        f.rt.projects().activeOf(f.owner.getUuid()).ifPresent(f.rt.projects()::cancel);
        f.rt.blueprints().remove(f.placement.placementId);
        f.rt.scheduler().cancelAgent(f.avatar.agentId(), "TEST_DONE");
        f.rt.executeControl(f.owner, SquireRuntime.ControlIntent.DISMISS); f.owner.discard();
        var world = (net.minecraft.server.world.ServerWorld) f.avatar.getWorld();
        for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) world.setChunkForced((f.placement.origin.getX() >> 4) + x, (f.placement.origin.getZ() >> 4) + z, false);
    }
    private static SquireRuntime.ExecutionResult confirm(Fixture f) {
        return f.rt.agents().withTarget(f.owner.getUuid(), f.avatar.agentId(), () -> f.rt.projectConfirm(f.owner));
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 200, batchId = "terrain-panel")
    public void panelCreatesAndAdjustsPreviewAndRejectsStaleClicks(TestContext c) {
        var f = site(c, 3, 3);
        c.runAtTick(100, () -> {
            f.rt.blueprints().remove(f.placement.placementId);
            f.owner.setPitch(90);
            var waterSurface = f.owner.getBlockPos().down();
            c.getWorld().setBlockState(waterSurface, Blocks.WATER.getDefaultState(), 2);
            var panel = new dev.squire.server.gui.SquireScreenHandler(91, f.owner.getInventory(), f.avatar.items().mainInventory(),
                new dev.squire.server.gui.AvatarEquipmentInventory(f.avatar, dev.squire.server.gui.SquireScreenHandler.EQUIPMENT_ORDER), f.avatar.backpackSlotInventory(), f.avatar);
            panel.terrainAction(f.owner, dev.squire.server.gui.SquireScreenHandler.BUTTON_TERRAIN_BASE, "");
            var preview = f.rt.blueprints().activeOf(f.owner.getUuid()).orElseThrow();
            c.assertTrue(preview.agentId.equals(f.avatar.agentId()) && preview.blueprintId.equals("terrain_level/7/7"), "panel creates a bound default 7x7 preview");
            c.assertTrue(preview.origin.equals(waterSurface.add(-3, 0, -3)), "water preview targets the surface, not the bed");
            String old = preview.placementId + "/" + preview.terrainReview;
            panel.terrainAction(f.owner, dev.squire.server.gui.SquireScreenHandler.BUTTON_TERRAIN_BASE + TerrainLevelingService.Action.WIDTH_UP.ordinal(), old);
            c.assertTrue(preview.blueprintId.equals("terrain_level/8/7"), "panel adjusts width at level one");
            panel.terrainAction(f.owner, dev.squire.server.gui.SquireScreenHandler.BUTTON_TERRAIN_BASE + TerrainLevelingService.Action.WIDTH_UP.ordinal(), old);
            c.assertTrue(preview.blueprintId.equals("terrain_level/8/7"), "stale packet does not adjust a new range");
            c.assertTrue(panel.state().placementBlueprintId().equals(preview.blueprintId), "authoritative panel state contains updated spec");
            f.rt.blueprints().remove(preview.placementId); clean(f); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-max")
    public void largeJobsAndOrdinaryWorksiteChangesAreAllowed(TestContext c) {
        var f = site(c, 32, 32); var p = f.placement.origin; var w = c.getWorld();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            for (int y = 1; y <= 8; y++) w.setBlockState(p.add(x, y, z), Blocks.STONE.getDefaultState(), 2);
        c.assertTrue(TerrainLeveling.plan(w, f.placement).resolved().access().valid(), "8192 removals allowed");
        w.setBlockState(p, Blocks.AIR.getDefaultState());
        c.assertTrue(TerrainLeveling.plan(w, f.placement).resolved().access().valid(), "more than 8192 changes remain basic terrain work");
        f.placement.changeBlueprint(new TerrainLeveling.Spec(1, 1).id());
        for (int y = 1; y <= 8; y++) w.setBlockState(p.up(y), Blocks.AIR.getDefaultState(), 2);
        var r = TerrainLeveling.plan(w, f.placement).resolved();
        w.setBlockState(p.up(), Blocks.OAK_PLANKS.getDefaultState());
        c.assertTrue(TerrainLeveling.liveBlocker(w, r).isEmpty(), "ordinary new block is handled by excavation instead of blocking start");
        clean(f); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 200, batchId = "terrain-refund")
    public void cancellationRefundsRealEscrowAfterStoreReload(TestContext c) {
        var f = site(c, 2, 2); var p = f.placement.origin;
        c.getWorld().setBlockState(p, Blocks.AIR.getDefaultState());
        c.runAtTick(100, () -> {
            f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
            f.owner.getInventory().insertStack(new ItemStack(Items.DIRT, 1));
            TerrainLevelingService.refresh(f.owner, f.placement);
            var started = confirm(f); c.assertTrue(started.success(), started.message());
            var project = f.rt.projects().activeOf(f.owner.getUuid()).orElseThrow();
            f.rt.projects().pause(project);
            c.assertTrue(f.owner.getInventory().count(Items.DIRT) == 0 && project.reservedMaterials().values().stream().mapToInt(Integer::intValue).sum() == 1, "real inventory escrow");
            f.rt.blueprints().save(); f.rt.projects().save();
            f.rt.blueprints().load(); f.rt.projects().load();
            var restored = f.rt.projects().activeOf(f.owner.getUuid()).orElseThrow();
            c.assertTrue(restored.agentId().equals(f.avatar.agentId()) && restored.stages().size() == 4, "bound terrain pipeline restored");
            f.rt.projects().cancel(restored);
            c.assertTrue(f.owner.getInventory().count(Items.DIRT) == 1 && c.getWorld().isAir(p), "cancel refunds and leaves untouched ground unchanged");
            f.rt.projects().cancel(restored);
            c.assertTrue(f.owner.getInventory().count(Items.DIRT) == 1, "no repeated refund");
            clean(f); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-surface")
    public void mixedSolidSurfaceIsReplacedAndPaidWithoutEditingPreview(TestContext c) {
        var f = site(c, 3, 3); var p = f.placement.origin; var w = c.getWorld();
        w.setBlockState(p, Blocks.OAK_LOG.getDefaultState());
        w.setBlockState(p.down(), Blocks.OAK_LOG.getDefaultState());
        w.setBlockState(p.down(2), Blocks.STONE.getDefaultState());
        w.setBlockState(p.east(), Blocks.STONE.getDefaultState());
        var plan = TerrainLevelingService.refresh(f.owner, f.placement);
        c.assertTrue(plan.fill() == 3 && plan.dig() == 3, "stump, buried trunk and surface stone are cleared and replaced");
        c.assertTrue(plan.resolved().toPlace().stream().allMatch(q -> q.blockId().equals(plan.material())), "uniform replacement material");
        c.assertTrue(BlueprintManager.requiredProjectMaterials(w, plan.resolved()).values().stream().mapToInt(Integer::intValue).sum() == 3, "all replacements billed");
        c.assertTrue(w.getBlockState(p).isOf(Blocks.OAK_LOG) && w.getBlockState(p.east()).isOf(Blocks.STONE), "preview does not edit");
        clean(f); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-plan")
    public void mixedTerrainPreviewIsReadOnlyAndSnapshotSurvivesReload(TestContext c) {
        var f = site(c, 3, 3); var p = f.placement.origin; var w = c.getWorld();
        w.setBlockState(p, Blocks.AIR.getDefaultState());
        w.setBlockState(p.add(2, 1, 2), Blocks.OAK_PLANKS.getDefaultState());
        var plan = TerrainLevelingService.refresh(f.owner, f.placement);
        c.assertTrue(plan.fill() == 1 && plan.dig() == 1 && plan.material().equals("minecraft:dirt"), "exact cut/fill bill with sampled soil");
        c.assertTrue(w.isAir(p) && w.getBlockState(p.add(2, 1, 2)).isOf(Blocks.OAK_PLANKS), "preview never edits");
        var bill = BlueprintManager.requiredProjectMaterials(w, plan.resolved());
        c.assertTrue(bill.size() == 1 && !bill.containsKey(new net.minecraft.util.Identifier("minecraft:torch")), "no lighting or access charges");
        f.placement.snapshot(plan.resolved(), true); f.rt.blueprints().save();
        f.rt.blueprints().load();
        var restored = f.rt.blueprints().placement(f.placement.placementId).orElseThrow();
        var saved = f.rt.blueprints().resolve(restored).orElseThrow();
        c.assertTrue(TerrainLeveling.isTerrain(saved) && saved.toPlace().equals(plan.resolved().toPlace())
            && saved.toClear().equals(plan.resolved().toClear()), "frozen terrain geometry and kind survive real store reload");
        clean(f); c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-blockers")
    public void containersFluidsAndBedrockBlockButOrdinaryBuildingsAreAllowed(TestContext c) {
        var f = site(c, 1, 1); var p = f.placement.origin.up();
        for (var block : List.of(Blocks.CHEST, Blocks.WATER, Blocks.LAVA, Blocks.BEDROCK)) {
            c.getWorld().setBlockState(p, block.getDefaultState());
            var plan = TerrainLevelingService.refresh(f.owner, f.placement);
            c.assertFalse(plan.resolved().access().valid(), "must block " + block);
            c.assertFalse(confirm(f).success(), "blocked confirmation");
            c.assertTrue(f.rt.projects().activeOf(f.owner.getUuid()).isEmpty(), "no project or escrow");
        }
        c.getWorld().setBlockState(p, Blocks.OAK_PLANKS.getDefaultState());
        c.assertTrue(TerrainLeveling.plan(c.getWorld(), f.placement).resolved().access().valid(), "ordinary authored block may be demolished");
        clean(f); c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-depth")
    public void deepPitAndTallColumnArePlannedToTheirActualExtents(TestContext c) {
        var f = site(c, 1, 1); var p = f.placement.origin;
        for (int i = 0; i < 14; i++) c.getWorld().setBlockState(p.down(i), Blocks.AIR.getDefaultState());
        c.getWorld().setBlockState(p.down(14), Blocks.STONE.getDefaultState());
        var deep = TerrainLeveling.plan(c.getWorld(), f.placement);
        c.assertTrue(deep.resolved().access().valid() && deep.fill() == 14, "fill all 14 cells down to real support without the former 8-block gate");
        c.assertTrue(deep.resolved().bounds().contains(p.down(13)), "preview includes the full paid fill depth");
        c.getWorld().setBlockState(p, Blocks.STONE.getDefaultState());
        c.getWorld().setBlockState(p.up(9), Blocks.STONE.getDefaultState());
        c.assertTrue(TerrainLeveling.plan(c.getWorld(), f.placement).resolved().access().valid(), "tall terrain is allowed");
        c.getWorld().setBlockState(p.up(9), Blocks.AIR.getDefaultState());
        c.getWorld().setBlockState(p.up(11), Blocks.OAK_PLANKS.getDefaultState());
        var tall = TerrainLeveling.plan(c.getWorld(), f.placement);
        c.assertTrue(tall.resolved().access().valid() && tall.resolved().toClear().contains(p.up(11)), "overhanging roof is included instead of blocking the whole site");
        clean(f); c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 200, batchId = "terrain-review")
    public void ordinaryChangesWithinReviewedAreaDoNotRequireRepeatedConfirmation(TestContext c) {
        var f = site(c, 2, 2); var p = f.placement.origin;
        c.runAtTick(100, () -> {
        f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
        TerrainLevelingService.refresh(f.owner, f.placement);
        c.assertFalse(confirm(f).success(), "already level creates no project");
        c.getWorld().setBlockState(p.up(), Blocks.DIRT.getDefaultState());
        var confirmed = confirm(f);
        c.assertTrue(confirmed.success(), "ordinary change starts on one confirmation: " + confirmed.message());
        clean(f); c.complete();
        });
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-auth")
    public void editsRequireTheBoundEngineerAndStayInsideSizeLimits(TestContext c) {
        var f = site(c, 128, 1);
        c.assertFalse(TerrainLevelingService.act(f.owner, f.avatar, TerrainLevelingService.Action.WIDTH_UP).success(), "preview resource limit is independent of engineer level");
        f.rt.professionOf(f.avatar).setProfession(SquireProfession.GUARD);
        c.assertFalse(TerrainLevelingService.act(f.owner, f.avatar, TerrainLevelingService.Action.REFRESH).success(), "guard cannot flatten");
        c.assertFalse(confirm(f).success(), "guard cannot confirm old engineer preview");
        var stranger = FakePlayer.get(c.getWorld(), new GameProfile(UUID.randomUUID(), "stranger"));
        c.assertFalse(TerrainLevelingService.act(stranger, f.avatar, TerrainLevelingService.Action.REFRESH).success(), "other owner denied");
        clean(f); c.complete();
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 1800, batchId = "terrain-npc")
    public void levelOneEngineerCutsFillsPausesAndResumesUsingRealMaterials(TestContext c) {
        var f = site(c, 3, 3); var p = f.placement.origin; var w = c.getWorld();
        w.setBlockState(p.add(1, -2, 1), Blocks.STONE.getDefaultState());
        w.setBlockState(p.add(1, -1, 1), Blocks.WATER.getDefaultState(), 2);
        w.setBlockState(p.add(1, 0, 1), Blocks.WATER.getDefaultState(), 2);
        w.setBlockState(p.add(2, 1, 2), Blocks.OAK_PLANKS.getDefaultState());
        w.setBlockState(p.add(2, 0, 2), Blocks.OAK_LOG.getDefaultState());
        w.setBlockState(p.add(2, 1, 2), Blocks.OAK_LOG.getDefaultState());
        w.setBlockState(p.add(3, 2, 2), Blocks.OAK_LOG.getDefaultState());
        w.setBlockState(p.add(4, 2, 2), Blocks.OAK_LEAVES.getDefaultState(), 2);
        var outside = p.add(3, 1, 2); w.setBlockState(outside, Blocks.OAK_PLANKS.getDefaultState());
        w.setBlockState(p.add(2, 9, 2), Blocks.OAK_PLANKS.getDefaultState());
        c.runAtTick(100, () -> {
            f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
            TerrainLevelingService.refresh(f.owner, f.placement);
            c.assertTrue(confirm(f).success(), "create unfunded durable project");
            var project = f.rt.projects().activeOf(f.owner.getUuid()).orElseThrow();
            c.assertTrue(project.state() == Project.State.PAUSED && w.getBlockState(p.add(1, 0, 1)).isOf(Blocks.WATER), "no changes before material funding");
            var resolved = f.rt.blueprints().resolve(f.placement).orElseThrow();
            BlueprintManager.requiredProjectMaterials(w, resolved).forEach((id, count) -> f.owner.getInventory().insertStack(new ItemStack(Registries.ITEM.get(id), count)));
            var resumed = f.rt.projectResume(f.owner); c.assertTrue(resumed.success(), resumed.message());
            f.rt.projects().pause(project);
            c.assertTrue(project.state() == Project.State.PAUSED, "manual pause preserves project");
            f.rt.blueprints().save(); f.rt.projects().save();
            f.rt.blueprints().load(); f.rt.projects().load();
            c.assertTrue(f.rt.projectResume(f.owner).success(), "resume paid project");
        });
        c.runAtTick(1600, () -> {
            var project = f.rt.projects().project(f.rt.projects().all().stream().filter(q -> q.ownerId.equals(f.owner.getUuid())).findFirst().orElseThrow().projectId).orElseThrow();
            c.assertTrue(project.state() == Project.State.DONE, "terrain finished: " + f.rt.projects().describe(project));
            c.assertTrue(w.getBlockState(p.add(1, 0, 1)).isSolidBlock(w, p.add(1, 0, 1)) && w.isAir(p.add(2, 1, 2)), "water filled and ordinary block removed");
            c.assertTrue(w.getBlockState(p.add(1, -1, 1)).isSolidBlock(w, p.add(1, -1, 1)), "underwater foundation reaches solid bed");
            c.assertTrue(!w.getBlockState(p.add(2, 0, 2)).isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                && BlueprintManager.matches(w.getBlockState(p.add(2, 0, 2)), new Blueprint.Cell(p.add(2, 0, 2), "minecraft:dirt", Map.of(), 0, GroundPreparation.FILL, false)), "stump replaced with paid ground material");
            c.assertTrue(w.isAir(p.add(3, 2, 2)) && w.isAir(p.add(4, 2, 2)), "connected branch and canopy outside rectangle removed");
            c.assertTrue(w.getBlockState(outside).isOf(Blocks.OAK_PLANKS), "outside preserved");
            c.assertTrue(w.isAir(p.add(2, 9, 2)), "actual excavation clears above the former 8-block limit");
            c.assertTrue(f.rt.professionOf(f.avatar).xp == 0 && !f.rt.professionOf(f.avatar).hasTrained(TrainingMilestone.BUILD), "no building rewards");
            c.assertTrue(f.owner.getInventory().count(Items.DIRT) == 0, "real soil consumed");
            clean(f); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 1800, batchId = "terrain-npc")
    public void deepFillUsesSafeAssistanceWhenNoWalkableStationCanReachTheTarget(TestContext c) {
        var f = site(c, 1, 1); var p = f.placement.origin; var w = c.getWorld();
        for (int y = 0; y >= -6; y--) w.setBlockState(p.add(0, y, 0), Blocks.AIR.getDefaultState(), 2);
        w.setBlockState(p.add(0, -7, 0), Blocks.STONE.getDefaultState(), 2);
        var expected = TerrainLeveling.plan(w, f.placement).resolved();
        BlueprintManager.requiredProjectMaterials(w, expected).forEach((id, count) ->
            f.owner.getInventory().insertStack(new ItemStack(Registries.ITEM.get(id), count)));
        c.runAtTick(100, () -> {
            f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
            TerrainLevelingService.refresh(f.owner, f.placement);
            var started = confirm(f);
            c.assertTrue(started.success(), started.message());
        });
        c.runAtTick(1400, () -> {
            var project = f.rt.projects().all().stream()
                .filter(q -> q.ownerId.equals(f.owner.getUuid())).findFirst().orElseThrow();
            c.assertTrue(project.state() == Project.State.DONE,
                "deep fill finished through safe assistance: " + f.rt.projects().describe(project));
            var livePlacement = f.rt.blueprints().placement(f.placement.placementId).orElseThrow();
            var finished = f.rt.blueprints().resolve(livePlacement).orElseThrow();
            c.assertTrue(finished.access() != null && finished.access().assistance,
                "the deep fill persisted use of the assisted construction path");
            c.assertTrue(expected.toPlace().stream().allMatch(cell ->
                BlueprintManager.matches(w.getBlockState(cell.pos()), cell)),
                "every reviewed fill cell is built with its reserved material");
            clean(f); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 4800, batchId = "terrain-continuous")
    public void wideUnevenFoundationFinishesWithoutRepeatedRechecks(TestContext c) {
        var f = site(c, 18, 7); var p = f.placement.origin; var w = c.getWorld();
        // Alternate shallow ground and deep trenches, forcing recovery again as
        // placement changes the available standing positions across the site.
        for (int x = 0; x < 18; x++) for (int z = 0; z < 7; z++) {
            int depth = x % 6 == 0 ? 7 : 1;
            for (int y = 0; y > -depth; y--)
                w.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState(), 2);
            w.setBlockState(p.add(x, -depth, z), Blocks.STONE.getDefaultState(), 2);
        }
        var expected = TerrainLeveling.plan(w, f.placement).resolved();
        BlueprintManager.requiredProjectMaterials(w, expected).forEach((id, count) -> {
            for (int remaining = count; remaining > 0; remaining -= 64)
                f.owner.getInventory().insertStack(new ItemStack(Registries.ITEM.get(id), Math.min(64, remaining)));
        });
        c.runAtTick(100, () -> {
            f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
            TerrainLevelingService.refresh(f.owner, f.placement);
            var started = confirm(f); c.assertTrue(started.success(), started.message());
        });
        for (int tick = 120; tick < 4400; tick += 20) c.runAtTick(tick, () -> {
            var project = f.rt.projects().all().stream()
                .filter(q -> q.ownerId.equals(f.owner.getUuid())).findFirst().orElseThrow();
            c.assertTrue(project.state() != Project.State.PAUSED,
                "continuous fill must never require recheck: " + f.rt.projects().describe(project));
        });
        c.runAtTick(4400, () -> {
            var project = f.rt.projects().all().stream()
                .filter(q -> q.ownerId.equals(f.owner.getUuid())).findFirst().orElseThrow();
            c.assertTrue(project.state() == Project.State.DONE,
                "18x7 fill completes without resume: " + f.rt.projects().describe(project));
            c.assertTrue(expected.toPlace().stream().allMatch(cell ->
                BlueprintManager.matches(w.getBlockState(cell.pos()), cell)), "all reviewed fill cells match");
            var live = f.rt.blueprints().placement(f.placement.placementId).orElseThrow();
            c.assertTrue(f.rt.blueprints().resolve(live).orElseThrow().access().assistance,
                "deep trenches exercised assisted construction");
            clean(f); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 1800, batchId = "terrain-tool")
    public void missingToolStopsExcavationAndAddingToolAllowsResume(TestContext c) {
        var f = site(c, 1, 1); var p = f.placement.origin.up();
        c.getWorld().setBlockState(p, Blocks.OBSIDIAN.getDefaultState());
        f.avatar.items().extract(new net.minecraft.util.Identifier("minecraft:diamond_pickaxe"), 1);
        c.runAtTick(100, () -> {
            f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
            TerrainLevelingService.refresh(f.owner, f.placement);
            var started = confirm(f); c.assertTrue(started.success(), started.message());
        });
        c.runAtTick(250, () -> {
            var project = f.rt.projects().activeOf(f.owner.getUuid()).orElseThrow();
            c.assertTrue(project.currentStage().orElseThrow().blockerCode() == Stage.BlockerCode.TOOL_MISSING,
                "missing tool is actionable: " + f.rt.projects().describe(project));
            c.assertTrue(c.getWorld().getBlockState(p).isOf(Blocks.OBSIDIAN), "no block lost without suitable tool");
            f.avatar.items().insert(new ItemStack(Items.DIAMOND_PICKAXE));
            c.assertTrue(f.rt.projectResume(f.owner).success(), "resume with suitable tool");
        });
        c.runAtTick(1600, () -> {
            var project = f.rt.projects().all().stream().filter(q -> q.ownerId.equals(f.owner.getUuid())).findFirst().orElseThrow();
            c.assertTrue(project.state() == Project.State.DONE && c.getWorld().isAir(p), "tool retry finishes actual excavation");
            clean(f); c.complete();
        });
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 1800, batchId = "terrain-snow-tool")
    public void snowRequiresShovelAndNeverRecommendsPickaxe(TestContext c) {
        var f = site(c, 1, 1); var p = f.placement.origin.up();
        c.getWorld().setBlockState(p, Blocks.SNOW_BLOCK.getDefaultState());
        f.avatar.items().extract(new net.minecraft.util.Identifier("minecraft:diamond_shovel"), 1);
        c.runAtTick(100, () -> {
            f.rt.agents().resolveForOwnerNow(f.owner.getUuid());
            TerrainLevelingService.refresh(f.owner, f.placement);
            var started = confirm(f); c.assertTrue(started.success(), started.message());
        });
        c.runAtTick(250, () -> {
            var project = f.rt.projects().activeOf(f.owner.getUuid()).orElseThrow();
            c.assertTrue(project.currentStage().orElseThrow().blockerCode() == Stage.BlockerCode.TOOL_MISSING,
                "missing tool is actionable: " + f.rt.projects().describe(project));
            c.assertTrue(c.getWorld().getBlockState(p).isOf(Blocks.SNOW_BLOCK), "no block lost without suitable tool");
            c.assertTrue(f.rt.projects().describe(project).toString().contains("铲"), "snow blocker recommends a shovel");
            f.avatar.items().insert(new ItemStack(Items.DIAMOND_SHOVEL));
            c.assertTrue(f.rt.projectResume(f.owner).success(), "resume with suitable tool");
        });
        c.runAtTick(1600, () -> {
            var project = f.rt.projects().all().stream().filter(q -> q.ownerId.equals(f.owner.getUuid())).findFirst().orElseThrow();
            c.assertTrue(project.state() == Project.State.DONE && c.getWorld().isAir(p), "tool retry finishes actual excavation");
            clean(f); c.complete();
        });
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "terrain-tool-advice")
    public void harvestAdviceMatchesEveryRegisteredRequiredBlockState(TestContext c) {
        for (var block : Registries.BLOCK) for (var state : block.getStateManager().getStates()) {
            if (!state.isToolRequired() || state.getHardness(c.getWorld(), c.getAbsolutePos(BlockPos.ORIGIN)) < 0) continue;
            var tool = dev.squire.server.body.proxy.FakePlayerInteractionProxy.recommendedHarvestTool(state);
            c.assertTrue(!tool.isEmpty() && dev.squire.server.body.proxy.FakePlayerInteractionProxy.canHarvest(state, tool),
                "recommended tool actually harvests " + state);
        }
        var proxy = dev.squire.server.body.proxy.FakePlayerInteractionProxy.missingHarvestToolMessage(Blocks.OBSIDIAN.getDefaultState());
        c.assertTrue(proxy.contains("钻石镐"), "obsidian advice includes sufficient tier");
        for (var block : List.of(Blocks.SNOW, Blocks.SNOW_BLOCK))
            c.assertTrue(dev.squire.server.body.proxy.FakePlayerInteractionProxy.missingHarvestToolMessage(block.getDefaultState()).contains("铲"), "snow uses shovel advice");
        c.complete();
    }
}
