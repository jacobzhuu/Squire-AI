package dev.squire.gametest;

import java.util.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.executors.ConstructionRecovery;
import dev.squire.server.world.BoundedRegion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.*;
import net.minecraft.util.math.*;

public final class M40ConstructionRecoveryGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "recovery-graveyard")
    public void graveyardFiveWestCompletes(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/mystic/graveyard5", 81, 10,
            (a, p) -> p.relocate(p.origin, Direction.WEST), t -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "recovery-townhall")
    public void townhallTwoWestCompletes(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/fundamentals/townhall2", 82, 10,
            (a, p) -> p.relocate(p.origin, Direction.WEST), t -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "recovery-completed-station")
    public void completedStepIgnoresBuriedOldStation(TestContext c) { isolated(c, true); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "recovery-remote-paid")
    public void remoteWorkKeepsBillAndCompletes(TestContext c) { isolated(c, false); }
    private void isolated(TestContext c, boolean completed) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer()); var rt = SquireRuntime.get();
        String id = "gametest_recovery_" + completed;
        rt.blueprints().registry().register(new Blueprint(id, id, 1, Blueprint.Category.DEFENCE, 1, 16, 1,
            List.of(BlueprintStep.place(0, 0, 15, 0, 0, 15, 0, "minecraft:glowstone", "remote", false)), Set.of()));
        M16ProjectGameTests.importedBuild(c, id, completed ? 83 : 84, 10, (avatar, placement) -> {
            var r = rt.blueprints().resolve(placement).orElseThrow();
            var target = r.toPlace().get(0);
            if (completed) c.getWorld().setBlockState(target.pos(), Blocks.GLOWSTONE.getDefaultState());
            var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(target, placement.origin.down(), false,
                completed ? ConstructionAccessPlan.Mode.WALK : ConstructionAccessPlan.Mode.SERVER_ASSIST)), r.access().bounds, "", r.access().entrance);
            placement.snapshot(new Blueprint.Resolved(r.bounds(), r.toPlace(), r.toClear(), access, r.costPlan(), r.siteRequirements()), false);
        }, t -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void assistanceCannotStandOnRemovedSupportOrInsideNewBlock(TestContext c) {
        var world = c.getWorld(); var avatar = dev.squire.server.registry.SquireEntities.AVATAR.create(world);
        var p = c.getAbsolutePos(new BlockPos(3, 2, 3));
        world.setBlockState(p.down(), Blocks.STONE.getDefaultState());
        avatar.refreshPositionAndAngles(p.getX() + .5, p.getY(), p.getZ() + .5, 0, 0);
        var bounds = new BoundedRegion(p.add(-2, -2, -2), p.add(2, 3, 2));
        c.assertTrue(ConstructionRecovery.safe(avatar, bounds, p, Map.of()), "ordinary anchor accepted");
        c.assertTrue(!ConstructionRecovery.safe(avatar, bounds, p, Map.of(p.down(), Blocks.AIR.getDefaultState())), "cannot remove support");
        c.assertTrue(!ConstructionRecovery.safe(avatar, bounds, p, Map.of(p, Blocks.STONE.getDefaultState())), "cannot place through body");
        c.assertTrue(!ConstructionRecovery.safe(avatar, bounds, p.east(20), Map.of()), "bounded teleport");
        avatar.discard(); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void distantWorkerProducesReloadableAssistedPlan(TestContext c) {
        var world = c.getWorld(); var avatar = dev.squire.server.registry.SquireEntities.AVATAR.create(world);
        var p = c.getAbsolutePos(new BlockPos(3, 15, 3));
        avatar.refreshPositionAndAngles(p.getX() + 100.5, p.getY(), p.getZ() + 100.5, 0, 0);
        var cell = new Blueprint.Cell(p, "minecraft:glowstone", Map.of(), 0, "remote", false);
        var r = new Blueprint.Resolved(new BoundedRegion(p, p), List.of(cell), List.of());
        var access = ConstructionAccessPlan.plan(world, r, avatar);
        c.assertTrue(access.valid() && access.assistedCount() > 0, "disconnected worker selects assistance");
        var restored = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(new Blueprint.Resolved(r.bounds(), r.toPlace(), r.toClear(), access)));
        c.assertTrue(restored.access().valid() && restored.access().work.stream().allMatch(w -> access.bounds.contains(w.station())), "all saved station placeholders stay in authorized bounds");
        avatar.discard(); c.complete();
    }
}
