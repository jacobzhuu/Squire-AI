package dev.squire.gametest;

import java.util.*;
import dev.squire.server.blueprint.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.*;
import net.minecraft.test.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import dev.squire.server.world.BoundedRegion;

public final class M33ScaffoldingGameTests implements FabricGameTest {
    @GameTest(templateName = M16ProjectGameTests.FLOOR, tickLimit = 2400, batchId = "access-underground")
    public void buriedTargetDigsItsOwnStairway(TestContext c) { remoteTarget(c, true); }

    @GameTest(templateName = M16ProjectGameTests.FLOOR, tickLimit = 2400, batchId = "access-floating")
    public void oneBlockFloatingTargetBuildsAndReclaimsScaffolding(TestContext c) { remoteTarget(c, false); }

    private void remoteTarget(TestContext c, boolean buried) {
        var world = c.getWorld();
        dev.squire.server.runtime.SquireRuntime.ensureInitialized(world.getServer());
        var rt = dev.squire.server.runtime.SquireRuntime.get();
        String id = buried ? "gametest_buried_access" : "gametest_floating_access";
        var owner = net.fabricmc.fabric.api.entity.FakePlayer.get(world,
            new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)), id));
        BlockPos origin = new BlockPos(buried ? 8192 : 8256, c.getAbsolutePos(new BlockPos(0, 2, 0)).getY(), 8192);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.setChunkForced((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z, true);
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++)
            for (int y = -1; y <= (buried ? 8 : -1); y++) world.setBlockState(origin.add(x, y, z), Blocks.STONE.getDefaultState(), 2);
        BlockPos target = buried ? origin : origin.up(10);
        BlockPos start = origin.add(-4, buried ? 9 : 0, -4);
        owner.refreshPositionAndAngles(start.getX() - 1.5, start.getY(), start.getZ() - 1.5, 0, 0);
        world.spawnEntity(owner);
        var avatar = rt.summonFirstAt(owner, start);
        c.assertTrue(avatar != null, "engineer summoned");
        rt.profileOf(avatar).profession.setProfession(dev.squire.server.profession.SquireProfession.ENGINEER);
        rt.profileOf(avatar).profession.level = 10;
        avatar.items().insert(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_PICKAXE));
        avatar.setIdleMode();
        rt.blueprints().registry().register(new Blueprint(id, id, 1, Blueprint.Category.DEFENCE, 1, 1, 1,
            List.of(BlueprintStep.place(0, 0, 0, 0, 0, 0, 0, "minecraft:glowstone", "target", false)), Set.of()));
        UUID[] projectId = new UUID[1];
        c.runAtTick(100, () -> {
            rt.agents().resolveForOwnerNow(owner.getUuid());
            var preview = rt.projectStart(owner, id); c.assertTrue(preview.success(), preview.message());
            var placement = rt.blueprints().activeOf(owner.getUuid()).orElseThrow();
            c.assertTrue(placement.relocate(target, net.minecraft.util.math.Direction.NORTH), "relocate");
            var lightweight = rt.blueprints().preview(placement).orElseThrow();
            c.assertTrue(lightweight.access() == null,
                "moving preview must defer expensive access pathfinding");
            var resolved = rt.blueprints().resolve(placement).orElseThrow();
            var access = resolved.access();
            c.assertTrue(access != null && access.valid(), "access plan: " + (access == null ? "null" : access.failure));
            c.assertTrue(buried ? access.excavationCount() > 0 : access.temporaryCount() > 0, "required access work exists");
            c.assertTrue(world.getBlockState(target).isOf(buried ? Blocks.STONE : Blocks.AIR), "preview did not edit target");
            BlueprintManager.requiredProjectMaterials(world, resolved).forEach((item, count) -> {
                for (int left = count; left > 0; left -= 64) avatar.items().insert(new net.minecraft.item.ItemStack(net.minecraft.registry.Registries.ITEM.get(item), Math.min(64, left)));
            });
            var confirmed = rt.blueprintBuild(owner); c.assertTrue(confirmed.success(), confirmed.message());
            projectId[0] = rt.projects().activeOf(owner.getUuid()).orElseThrow().projectId;
        });
        c.runAtTick(2200, () -> {
            var project = rt.projects().project(projectId[0]).orElseThrow();
            var access = rt.blueprints().placement(project.placementId).flatMap(rt.blueprints()::resolve).orElseThrow().access();
            c.assertTrue(project.state() == dev.squire.server.project.Project.State.DONE,
                "project did not finish: " + rt.projects().describe(project) + " access=" + access.cursor + "/" + access.work.size() + " worker=" + avatar.getPos()
                    + " cleanup=" + access.cleanup + " pending=" + access.placed + " task=" + rt.scheduler().current(avatar.agentId()).map(t -> executionDetails(t.executionState())).orElse("none"));
            c.assertTrue(world.getBlockState(target).isOf(Blocks.GLOWSTONE), "real target built");
            c.assertTrue(access.placed.isEmpty(), "scaffolding reclaimed");
            c.assertTrue(avatar.items().countOf(new Identifier("minecraft:scaffolding")) <= access.temporaryCount(), "cleanup never duplicates scaffolding refunds");
            if (buried) c.assertTrue(avatar.items().countOf(new Identifier("minecraft:cobblestone")) > 0, "excavation drops retained");
            rt.executeControl(owner, dev.squire.server.runtime.SquireRuntime.ControlIntent.DISMISS);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.setChunkForced((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z, false);
            c.complete();
        });
    }
    private static String executionDetails(Object progress) {
        if (progress == null) return "null";
        var out = new ArrayList<String>();
        for (var field : progress.getClass().getDeclaredFields()) try {
            field.setAccessible(true); out.add(field.getName() + "=" + field.get(progress));
        } catch (ReflectiveOperationException e) { out.add(e.toString()); }
        return out.toString();
    }
    @GameTest(templateName = M16ProjectGameTests.FLOOR, tickLimit = 2200, batchId = "scaffold-climb")
    public void towerClimbsAndReclaims(TestContext context) {
        new M16ProjectGameTests().tallTowerBuildsWithRealStepsAndReclaimsThem(context, "scaffold-tower");
    }
    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void stableColumnAndSafeReclaim(TestContext c) {
        BlockPos base = c.getAbsolutePos(new BlockPos(3, 2, 3));
        c.getWorld().setBlockState(base.down(), Blocks.STONE.getDefaultState());
        for (int y = 0; y < 5; y++) c.assertTrue(BlueprintAssembly.place(c.getWorld(), List.of(cell(base.up(y), "scaffolding"))), "grounded column placed");
        c.assertTrue(!ConstructionScaffolding.safeRemoval(c.getWorld(), base), "cannot collapse upper scaffolds");
        c.assertTrue(ConstructionScaffolding.safeRemoval(c.getWorld(), base.up(4)), "top can be recovered");
        BlockPos bridge = base.up(4).east();
        c.assertTrue(BlueprintAssembly.place(c.getWorld(), List.of(cell(bridge, "scaffolding"))), "player's attached bridge placed");
        c.assertTrue(!ConstructionScaffolding.safeRemoval(c.getWorld(), base.up(4)), "unowned dependent bridge also prevents reclaim");
        c.getWorld().setBlockState(bridge, Blocks.AIR.getDefaultState());
        c.runAtTick(20, () -> {
            for (int y = 0; y < 5; y++) c.assertTrue(ConstructionScaffolding.scaffold(c.getWorld(), base.up(y)), "survives native scheduled ticks");
            c.complete();
        });
    }
    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void floatingScaffoldRejectedAndLegacyBillPreserved(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 6, 3));
        c.assertTrue(!BlueprintAssembly.place(c.getWorld(), List.of(cell(p, "scaffolding"))), "floating scaffold rejected");
        c.assertTrue(c.getWorld().isAir(p), "failed placement restored");
        var a = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell(p, "cobblestone"), p.down(), true),
            new ConstructionAccessPlan.Work(cell(p.up(), "scaffolding"), p.down(), true)), new BoundedRegion(p.down(), p.up(2)), "");
        c.assertTrue(a.remainingTemporaryMaterials().equals(Map.of(new Identifier("minecraft:cobblestone"), 1, new Identifier("minecraft:scaffolding"), 1)), "bill follows saved work, not new default");
        a.cursor = 1;
        c.assertTrue(a.remainingTemporaryMaterials().equals(Map.of(new Identifier("minecraft:scaffolding"), 1)), "settled item not billed twice");
        c.complete();
    }
    private static Blueprint.Cell cell(BlockPos pos, String block) { return new Blueprint.Cell(pos, "minecraft:" + block, Map.of(), Integer.MIN_VALUE, "test", false); }
    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void horizontalSpanStopsAtSixAndHazardsRemainUnsafe(TestContext c) {
        var world = c.getWorld();
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 4, 3));
        Map<BlockPos, BlockState> changes = new HashMap<>();
        changes.put(p.down(), Blocks.STONE.getDefaultState());
        var view = new ConstructionBlockView(world, changes);
        for (int x = 0; x <= 7; x++) {
            BlockPos q = p.east(x);
            changes.put(q.down(), x == 0 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
            var state = ConstructionScaffolding.placement(view, q);
            c.assertTrue(state.get(ScaffoldingBlock.DISTANCE) == x, "native horizontal distance " + x);
            changes.put(q, state);
        }
        c.assertTrue(!ConstructionScaffolding.scaffold(view, p.east(7)), "seventh overhang is not a safe floor");
        changes.put(p.up().east(), Blocks.LAVA.getDefaultState());
        c.assertTrue(!view.safeStanding(p.up(), false), "scaffold must not bypass adjacent lava hazard");
        changes.put(p.up().east(), Blocks.AIR.getDefaultState());
        changes.put(p.down(), Blocks.MAGMA_BLOCK.getDefaultState());
        c.assertTrue(!view.safeStanding(p, false), "a climbable block must not hide a hazardous real floor");
        c.complete();
    }

    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void accessDiggingPreservesContainersFluidsAndUnstableCeilings(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(4, 3, 4));
        Map<BlockPos, BlockState> changes = new HashMap<>();
        var view = new ConstructionBlockView(c.getWorld(), changes);
        changes.put(p, Blocks.STONE.getDefaultState());
        c.assertTrue(ConstructionAccessPlan.diggable(view, p), "ordinary stone can become a tunnel");
        changes.put(p, Blocks.SCAFFOLDING.getDefaultState());
        c.assertTrue(ConstructionAccessPlan.diggable(view, p), "abandoned scaffolding can be cleared for a later project");
        for (Block block : List.of(Blocks.BEDROCK, Blocks.CHEST, Blocks.WATER, Blocks.LAVA, Blocks.MAGMA_BLOCK)) {
            changes.put(p, block.getDefaultState());
            c.assertTrue(!ConstructionAccessPlan.diggable(view, p), "must preserve " + block);
        }
        changes.put(p, Blocks.STONE.getDefaultState());
        changes.put(p.up(), Blocks.GRAVEL.getDefaultState());
        c.assertTrue(!ConstructionAccessPlan.diggable(view, p), "cannot release a falling ceiling");
        changes.put(p.up(), Blocks.AIR.getDefaultState());
        changes.put(p.east(), Blocks.WATER.getDefaultState());
        c.assertTrue(!ConstructionAccessPlan.diggable(view, p), "cannot breach a reservoir");
        c.complete();
    }
    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void graniteIsMineableAndMatchingGeometryIsNotExcavation(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(4, 3, 4));
        c.getWorld().setBlockState(p, Blocks.GRANITE.getDefaultState(), 2);
        c.assertTrue(ConstructionAccessPlan.diggable(c.getWorld(), p), "granite is safely mineable");
        var cell = cell(p, "granite");
        var r = new Blueprint.Resolved(new BoundedRegion(p, p.up(6)), List.of(cell), List.of(p));
        c.assertTrue(BlueprintManager.pendingClear(c.getWorld(), r).isEmpty(), "matching blueprint geometry wins over overlapping clear marker");
        c.getWorld().setBlockState(p.up(), Blocks.GRAVEL.getDefaultState(), 2);
        var clearing = new Blueprint.Resolved(new BoundedRegion(p, p.up()), List.of(), List.of(p, p.up()));
        c.assertTrue(BlueprintManager.pendingClear(c.getWorld(), clearing).get(0).equals(p.up()), "gravel cleared before granite underneath");
        c.assertTrue(ConstructionAccessPlan.diggable(c.getWorld(), p.up()), "top gravel can be harvested");
        c.complete();
    }

    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void plannedFoundationDoesNotBlockItsOwnExcavation(TestContext c) {
        BlockPos p = c.getAbsolutePos(new BlockPos(5, 2, 5));
        c.getWorld().setBlockState(p, Blocks.SCAFFOLDING.getDefaultState(), 2);
        var avatar = c.spawnEntity(dev.squire.server.registry.SquireEntities.AVATAR, new BlockPos(2, 2, 2));
        var fill = new Blueprint.Cell(p, "minecraft:dirt", Map.of(), Integer.MIN_VALUE,
            GroundPreparation.FILL, false);
        var resolved = new Blueprint.Resolved(new BoundedRegion(p, p.up(6)), List.of(fill), List.of(p));
        var access = ConstructionAccessPlan.plan(c.getWorld(), resolved, avatar);
        c.assertTrue(access.valid(), "future foundation must not mask live obstruction: " + access.failure);
        c.assertTrue(access.work.stream().anyMatch(w -> w.excavation() && w.cell().pos().equals(p)),
            "left-over scaffold is explicitly cleared before the foundation is simulated");
        c.complete();
    }

    @GameTest(templateName = M16ProjectGameTests.FLOOR)
    public void ShortBlueprintStillReceivesExecutableAccessWork(TestContext c) {
        var avatar = c.spawnEntity(dev.squire.server.registry.SquireEntities.AVATAR, new BlockPos(2, 2, 2));
        BlockPos worker = avatar.getBlockPos();
        BlockPos target = worker.east(2);
        var resolved = new Blueprint.Resolved(new BoundedRegion(target, target),
            List.of(cell(target, "glowstone")), List.of());
        var access = ConstructionAccessPlan.plan(c.getWorld(), resolved, avatar);
        c.assertTrue(access.valid(), "short blueprint needs a real access plan: " + access.failure);
        c.assertTrue(access.work.stream().anyMatch(w -> !w.temporary() && w.cell().pos().equals(target)),
            "short blueprint cannot bypass dig/scaffold execution");
        c.complete();
    }
}
