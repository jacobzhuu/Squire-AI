package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.BlockPos;

class ConstructionSnapshotTest {
    @Test void recoveryProgressAndModesSurviveGeometrySplitWithoutChangingLedger() {
        var p = new BlockPos(0, 8, 0);
        var cell = new Blueprint.Cell(p, "minecraft:stone", Map.of(), 0, "assist", false);
        var bounds = new BoundedRegion(BlockPos.ORIGIN, new BlockPos(10, 20, 10));
        var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, BlockPos.ORIGIN, false, ConstructionAccessPlan.Mode.SERVER_ASSIST)), bounds, "");
        access.assistance = true; access.recoveries = 2; access.recoveryReason = "ACCESS_ROUTE_BLOCKED";
        access.routeFailure = ConstructionAccessPlan.RouteFailure.RETURN;
        var r = new Blueprint.Resolved(bounds, List.of(cell), List.of(), access);
        var geometry = ConstructionSnapshotCodec.writeGeometry(r);
        assertFalse(geometry.getAsJsonObject("access").get("assistance").getAsBoolean());
        ConstructionSnapshotCodec.mergeProgress(geometry, ConstructionSnapshotCodec.writeProgress(r));
        var restored = ConstructionSnapshotCodec.read(geometry).access();
        assertTrue(restored.assistance); assertEquals(2, restored.recoveries);
        assertEquals(access.work, restored.work); assertEquals(access.routeFailure, restored.routeFailure);
        assertEquals(access.recoveryReason, restored.recoveryReason); assertEquals(1, restored.assistedCount());
    }
    @Test void legacyWorkDefaultsToWalkingAndUnknownModeIsRejected() {
        var cell = new Blueprint.Cell(BlockPos.ORIGIN, "minecraft:stone", Map.of(), 0, "test", false);
        var bounds = new BoundedRegion(BlockPos.ORIGIN, new BlockPos(3, 3, 3));
        var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, BlockPos.ORIGIN, false)), bounds, "");
        var json = ConstructionSnapshotCodec.write(new Blueprint.Resolved(bounds, List.of(cell), List.of(), access));
        var saved = json.getAsJsonObject("access"); var work = saved.getAsJsonArray("work").get(0).getAsJsonObject();
        work.remove("mode"); saved.remove("assistance"); saved.remove("recoveries"); saved.remove("recoveryReason"); saved.remove("routeFailure");
        assertEquals(ConstructionAccessPlan.Mode.WALK, ConstructionSnapshotCodec.read(json).access().work.get(0).mode());
        assertFalse(ConstructionSnapshotCodec.read(json).access().assistance);
        work.addProperty("mode", "UNRECOGNIZED");
        assertThrows(IllegalArgumentException.class, () -> ConstructionSnapshotCodec.read(json));
    }
    @Test void excavationAndExtendedHeightSurviveRestartWithoutMaterialBilling() {
        var entrance = new BlockPos(0, 30, 0);
        var cut = new Blueprint.Cell(new BlockPos(1, 29, 0), "minecraft:air", Map.of(), Integer.MIN_VALUE, "access excavation", false);
        var support = new Blueprint.Cell(new BlockPos(2, 29, 0), "minecraft:scaffolding", Map.of(), Integer.MIN_VALUE, "access", false);
        var bounds = new BoundedRegion(new BlockPos(-6, 0, -6), new BlockPos(6, 32, 6));
        var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cut, entrance, false),
            new ConstructionAccessPlan.Work(support, entrance, true)), bounds, "", entrance);
        access.cursor = 1;
        var restored = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(new Blueprint.Resolved(
            new BoundedRegion(BlockPos.ORIGIN, BlockPos.ORIGIN), List.of(), List.of(), access))).access();
        assertEquals(access.work, restored.work);
        assertEquals(bounds, restored.bounds);
        assertEquals(entrance, restored.entrance);
        assertEquals(1, restored.cursor);
        assertEquals(1, restored.excavationCount());
        assertEquals(1, restored.remainingTemporary());
        assertEquals(support, restored.temporaryCell(support.pos()));
        assertNull(restored.temporaryCell(cut.pos()));
    }
    @Test void scaffoldOwnershipAndMaterialIdentitySurviveRestart() {
        var p = new BlockPos(1, 1, 1);
        var cell = new Blueprint.Cell(p, "minecraft:scaffolding", Map.of(), Integer.MIN_VALUE, "access", false);
        var bounds = new BoundedRegion(BlockPos.ORIGIN, new BlockPos(3, 8, 3));
        var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, p, true)), bounds, "");
        access.cursor = 1; access.placed.put(p, false);
        var restored = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(new Blueprint.Resolved(bounds, List.of(), List.of(), access))).access();
        assertEquals(cell, restored.temporaryCell(p));
        assertEquals(Map.of(p, false), restored.placed);
        assertEquals(0, restored.remainingTemporary());
    }
    @Test void committedGeometryRejectsEditsEvenIfLegacyStateWasResetToReady() {
        var p = new BlueprintPlacement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test",
            "minecraft:overworld", BlockPos.ORIGIN, net.minecraft.util.math.Direction.NORTH, 0);
        p.snapshot(new Blueprint.Resolved(new BoundedRegion(BlockPos.ORIGIN, BlockPos.ORIGIN), List.of(), List.of()), true);
        p.setState(BlueprintPlacement.State.READY);
        assertFalse(p.relocate(new BlockPos(8, 0, 0), net.minecraft.util.math.Direction.EAST));
        assertFalse(p.changeBlueprint("other")); assertFalse(p.setMaterial("wall", "stone"));
        assertFalse(p.resetMaterials(Map.of())); assertEquals(BlockPos.ORIGIN, p.origin);
    }
    @Test void geometryAndOwnedTemporaryBlocksRoundTripWithoutReplanning() {
        var cell = new Blueprint.Cell(new BlockPos(1, 8, 2), "minecraft:cobblestone", Map.of(), 0, "access", false);
        var bounds = new BoundedRegion(BlockPos.ORIGIN, new BlockPos(8, 12, 8));
        var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, new BlockPos(0, 8, 2), true)), bounds, "", new BlockPos(3, 0, 3));
        access.cursor = 1; access.placed.put(cell.pos(), true); access.cleanup = true; access.cancelRequested = true;
        var source = new Blueprint.Resolved(bounds, List.of(cell), List.of(new BlockPos(2, 8, 2)), access);
        var restored = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(source));
        assertEquals(source.toPlace(), restored.toPlace()); assertEquals(source.toClear(), restored.toClear());
        assertEquals(access.work, restored.access().work); assertEquals(access.placed, restored.access().placed);
        assertEquals(access.entrance, restored.access().entrance);
        assertEquals(1, restored.access().cursor); assertTrue(restored.access().cancelRequested); assertTrue(restored.access().cleanup);
        assertEquals(0, restored.access().remainingTemporary());
    }
    @Test void unfinishedTemporaryAllowanceExcludesCompletedProgramPrefix() {
        var cell = new Blueprint.Cell(new BlockPos(1, 1, 0), "minecraft:cobblestone", Map.of(), 0, "access", false);
        var bounds = new BoundedRegion(BlockPos.ORIGIN, new BlockPos(3, 3, 3));
        var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, BlockPos.ORIGIN, true)), bounds, "");
        assertEquals(1, access.remainingTemporary()); access.cursor = 1; assertEquals(0, access.remainingTemporary());
    }
}
