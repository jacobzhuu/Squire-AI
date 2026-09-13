package dev.squire.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.*;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import dev.squire.server.blueprint.*;
import java.util.*;

public final class M37EngineerTravelGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-smeltery-apprentice")
    public void apprenticeCompletesStoneSmeltery(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/craftsmanship/masonry/stonesmeltery1", 24, 1, ignored -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-smeltery-master")
    public void masterCompletesStoneSmeltery(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/craftsmanship/masonry/stonesmeltery1", 25, 10, ignored -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-smeltery-layered-excavation")
    public void stoneSmelteryClearsLayeredGravelAndGranite(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/craftsmanship/masonry/stonesmeltery1", 63, 10,
            (avatar, placement) -> {
                var manager = dev.squire.server.runtime.SquireRuntime.get().blueprints();
                var raw = manager.registry().byId(placement.blueprintId).orElseThrow()
                    .resolve(placement.origin, placement.facing, placement.materials(), manager.registry().materials());
                Set<BlockPos> positions = new HashSet<>();
                raw.toPlace().forEach(cell -> positions.add(cell.pos()));
                BlockPos lower = positions.stream().filter(p -> positions.contains(p.up())).findFirst().orElseThrow();
                c.getWorld().setBlockState(lower, Blocks.GRANITE.getDefaultState(), 2);
                c.getWorld().setBlockState(lower.up(), Blocks.GRAVEL.getDefaultState(), 2);
                placement.invalidatePreview();
            }, ignored -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 60)
    public void openedDoorPassesVerificationWithoutRechargingButWrongStructureFails(TestContext c) {
        var world = c.getWorld();
        var pos = c.getAbsolutePos(new BlockPos(2, 2, 2));
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState());
        var lower = new Blueprint.Cell(pos, "minecraft:spruce_door", Map.of("facing", "north", "half", "lower", "hinge", "left", "open", "false", "powered", "false"), 0, "door", false);
        var upper = new Blueprint.Cell(pos.up(), "minecraft:spruce_door", Map.of("facing", "north", "half", "upper", "hinge", "left", "open", "false", "powered", "false"), 1, "door", false);
        var cells = List.of(lower, upper);
        var resolved = new Blueprint.Resolved(new dev.squire.server.world.BoundedRegion(pos, pos.up()), cells, List.of());
        var plan = ConstructionCostPlan.create(world, resolved, 1, 0);
        c.assertTrue(BlueprintAssembly.place(world, cells), "door placed atomically");
        plan.settle(cells);
        for (var cell : cells) {
            var opened = BlueprintManager.targetState(cell).with(Properties.OPEN, true).with(Properties.POWERED, true);
            world.setBlockState(cell.pos(), opened, 2);
            c.assertTrue(BlueprintManager.matches(opened, cell), "operational state is not unfinished construction");
            c.assertTrue(!BlueprintManager.matches(opened.with(Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.SOUTH), cell), "wrong facing still rejected");
        }
        c.assertTrue(plan.remaining(world).isEmpty(), "opened doors do not need replacement materials");
        var recovered = ConstructionCostPlan.read(plan.write());
        c.assertTrue(recovered.remaining(world).isEmpty(), "recheck after snapshot recovery accepts open doors");
        c.assertTrue(!BlueprintManager.matches(Blocks.AIR.getDefaultState(), lower), "missing door still rejected");
        c.assertTrue(!BlueprintManager.matches(Blocks.OAK_DOOR.getDefaultState(), lower), "wrong material still rejected");
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        c.assertTrue(recovered.quote(cells) == -1, "removed settled doors cannot regenerate for free");
        c.complete();
    }
}
