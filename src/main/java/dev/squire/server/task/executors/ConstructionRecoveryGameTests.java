package dev.squire.server.task.executors;

import java.util.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.project.Project;
import dev.squire.server.task.*;
import dev.squire.server.world.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Real executor contract tests: assistance cannot bypass payment, protection or write-ahead persistence. */
public final class ConstructionRecoveryGameTests implements FabricGameTest {
    private static final Identifier MATERIAL = new Identifier("minecraft:glowstone");
    private static final class Fixture implements RuntimeServices {
        final TestContext c; final AvatarEntity avatar; final BlueprintManager manager;
        final BlueprintPlacement placement; final Project project; final Task task;
        final AccessBuildExecutor.Progress progress; final Blueprint.Cell cell; final Blueprint.Resolved resolved;
        boolean protectedSite, checkpoint = true;
        Fixture(TestContext c) throws Exception {
            this.c = c; var world = c.getWorld(); var p = c.getAbsolutePos(new BlockPos(3, 2, 3));
            world.setBlockState(p.down(), Blocks.STONE.getDefaultState());
            avatar = dev.squire.server.registry.SquireEntities.AVATAR.create(world);
            avatar.refreshPositionAndAngles(p.getX() + .5, p.getY(), p.getZ() + .5, 0, 0); avatar.setOnGround(true);
            var file = java.nio.file.Files.createTempDirectory("squire-recovery-test-").resolve("blueprints.json");
            manager = new BlueprintManager(new BlueprintPlacementStore(() -> file), world::getServer);
            cell = new Blueprint.Cell(p.up(10), MATERIAL.toString(), Map.of(), 0, "contract", false);
            world.setBlockState(cell.pos(), Blocks.AIR.getDefaultState());
            var bounds = new BoundedRegion(p.add(-2, -1, -2), p.add(2, 12, 2));
            var access = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, p, false,
                ConstructionAccessPlan.Mode.SERVER_ASSIST)), bounds, "", p);
            var raw = new Blueprint.Resolved(bounds, List.of(cell), List.of(), access);
            resolved = raw.withCostPlan(ConstructionCostPlan.create(world, raw, 1, 0));
            placement = new BlueprintPlacement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "contract",
                world.getRegistryKey().getValue().toString(), p, Direction.NORTH, 0);
            placement.snapshot(resolved, true); manager.put(placement);
            project = new Project(UUID.randomUUID(), placement.ownerId, placement.agentId, "contract", "contract",
                placement.placementId, placement.dimensionId, 0, List.of());
            project.reserve(Map.of(MATERIAL, 1), Map.of());
            task = new Task(placement.agentId, placement.ownerId, BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
                "contract", null, null, 2000, RetryPolicy.DEFAULT, true, "test");
            progress = new AccessBuildExecutor.Progress(placement.placementId, project.projectId, null);
        }
        public net.minecraft.server.MinecraftServer server() { return c.getWorld().getServer(); }
        public AvatarEntity avatar(UUID id) { return avatar; }
        public long currentTick() { return 0; }
        public net.minecraft.server.network.ServerPlayerEntity requester(UUID id) { return null; }
        public BlueprintManager blueprints() { return manager; }
        public Project project(UUID id) { return project; }
        public boolean beginProjectMutation(UUID id, String operation) {
            if (!checkpoint) return false; project.pendingMutation(operation); return true;
        }
        public boolean completeProjectMutation(UUID id) { project.pendingMutation(""); return manager.save(); }
        public ProtectionAdapter protection() {
            return new ProtectionAdapter() {
                public PermissionDecision canPlace(ServerWorld w, BlockPos p, UUID id) { return protectedSite ? PermissionDecision.deny("fixture") : PermissionDecision.allow(); }
                public PermissionDecision canBreak(ServerWorld w, BlockPos p, UUID id) { return canPlace(w, p, id); }
                public PermissionDecision canInteract(ServerWorld w, BlockPos p, UUID id) { return canPlace(w, p, id); }
                public PermissionDecision canEditRegion(ServerWorld w, BoundedRegion b, UUID id) { return canPlace(w, b.min(), id); }
            };
        }
        TaskExecutor.StepOutcome tick() { return AccessBuildExecutor.tick(this, task, progress, 100); }
        void unchanged(String code) {
            c.assertTrue(task.lastErrorCode().orElse("").equals(code), "error " + task.lastErrorCode());
            c.assertTrue(c.getWorld().isAir(cell.pos()), "no free world write");
            c.assertTrue(project.reservedCount(MATERIAL) == 1 && resolved.access().cursor == 0, "bill and cursor unchanged");
            avatar.discard(); c.complete();
        }
    }
    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void protectedRemoteWorkDoesNotSpendOrWrite(TestContext c) throws Exception {
        var f = new Fixture(c); f.protectedSite = true; f.tick(); f.unchanged("PROTECTED");
    }
    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void checkpointFailureDoesNotSpendOrWrite(TestContext c) throws Exception {
        var f = new Fixture(c); f.checkpoint = false; f.tick(); f.unchanged("CONSTRUCTION_CHECKPOINT_FAILED");
    }
    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void pendingSettlementCannotBeRecoveredByAssistance(TestContext c) throws Exception {
        var f = new Fixture(c); f.project.pendingMutation("uncertain"); f.tick(); f.unchanged("CONSTRUCTION_RECOVERY_REQUIRED");
    }
    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void remotePlacementChargesExactlyOnceAndRejectsPaidRebuild(TestContext c) throws Exception {
        var f = new Fixture(c);
        c.assertTrue(f.tick() == TaskExecutor.StepOutcome.CONTINUE && c.getWorld().getBlockState(f.cell.pos()).isOf(Blocks.GLOWSTONE), "server assistance placed real block");
        c.assertTrue(f.project.reservedCount(MATERIAL) == 0 && f.resolved.access().cursor == 1, "exact debit and progress");
        var copy = ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(f.resolved));
        copy.access().cursor = 0; f.placement.snapshot(copy, true);
        c.getWorld().setBlockState(f.cell.pos(), Blocks.AIR.getDefaultState());
        f.tick();
        c.assertTrue(f.task.lastErrorCode().orElse("").equals("SETTLED_BUILDING_CHANGED"), "no free rebuild after snapshot recovery");
        c.assertTrue(c.getWorld().isAir(f.cell.pos()) && f.project.reservedCount(MATERIAL) == 0, "no duplicated material");
        f.avatar.discard(); c.complete();
    }
    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void protectedCleanupRetainsOwnershipAndRefund(TestContext c) throws Exception {
        var f = new Fixture(c); f.protectedSite = true;
        var cell = new Blueprint.Cell(f.cell.pos(), "minecraft:cobblestone", Map.of(), 0, "temporary", false);
        c.getWorld().setBlockState(cell.pos(), Blocks.COBBLESTONE.getDefaultState());
        var a = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, f.avatar.getBlockPos(), true)), f.resolved.bounds(), "");
        a.cursor = 1; a.cleanup = true; a.assistance = true; a.placed.put(cell.pos(), true);
        f.placement.snapshot(new Blueprint.Resolved(f.resolved.bounds(), List.of(), List.of(), a, f.resolved.costPlan(), List.of()), true);
        f.tick();
        c.assertTrue(f.task.lastErrorCode().orElse("").equals("PROTECTED") && a.placed.size() == 1, "protected ownership retained");
        c.assertTrue(f.project.reservedCount(new Identifier("minecraft:cobblestone")) == 0
            && c.getWorld().getBlockState(cell.pos()).isOf(Blocks.COBBLESTONE), "no false refund");
        c.assertTrue(ConstructionSnapshotCodec.read(ConstructionSnapshotCodec.write(f.placement.snapshot())).access().placed.containsKey(cell.pos()), "ownership survives reload");
        f.avatar.discard(); c.complete();
    }

    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void remoteCleanupRefundsOwnedMaterialExactlyOnce(TestContext c) throws Exception {
        var f = new Fixture(c);
        var item = new Identifier("minecraft:cobblestone");
        var cell = new Blueprint.Cell(f.cell.pos(), item.toString(), Map.of(), 0, "temporary", false);
        c.getWorld().setBlockState(cell.pos(), Blocks.COBBLESTONE.getDefaultState());
        var a = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, f.avatar.getBlockPos(), true)), f.resolved.bounds(), "");
        a.cursor = 1; a.cleanup = true; a.assistance = true; a.placed.put(cell.pos(), true);
        f.placement.snapshot(new Blueprint.Resolved(f.resolved.bounds(), List.of(), List.of(), a, f.resolved.costPlan(), List.of()), true);
        f.tick();
        c.assertTrue(a.placed.isEmpty() && c.getWorld().isAir(f.cell.pos()), "owned temporary block reclaimed");
        c.assertTrue(f.project.reservedCount(item) == 1, "one refund returned to original owner");
        f.tick();
        c.assertTrue(f.project.reservedCount(item) == 1, "cleanup retry cannot double refund");
        f.avatar.discard(); c.complete();
    }

    @GameTest(templateName = dev.squire.gametest.M0SpikeGameTests.FLOOR)
    public void remoteCleanupPreservesUnownedScaffoldingDependency(TestContext c) throws Exception {
        var f = new Fixture(c); var pos = f.cell.pos(); var world = c.getWorld();
        world.setBlockState(pos.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(pos, ConstructionScaffolding.placement(world, pos));
        world.setBlockState(pos.up(), ConstructionScaffolding.placement(world, pos.up()));
        var cell = new Blueprint.Cell(pos, "minecraft:scaffolding", Map.of(), 0, "temporary", false);
        var a = new ConstructionAccessPlan(List.of(new ConstructionAccessPlan.Work(cell, f.avatar.getBlockPos(), true)), f.resolved.bounds(), "");
        a.cursor = 1; a.cleanup = true; a.assistance = true; a.placed.put(pos, true);
        f.placement.snapshot(new Blueprint.Resolved(f.resolved.bounds(), List.of(), List.of(), a, f.resolved.costPlan(), List.of()), true);
        f.tick();
        c.assertTrue(f.task.lastErrorCode().orElse("").equals("CONSTRUCTION_CLEANUP_DEPENDENCY"), "player structure dependency is a real blocker");
        c.assertTrue(a.placed.containsKey(pos) && world.getBlockState(pos).isOf(Blocks.SCAFFOLDING)
            && world.getBlockState(pos.up()).isOf(Blocks.SCAFFOLDING), "both supports and ownership retained");
        c.assertTrue(f.project.reservedCount(new Identifier("minecraft:scaffolding")) == 0, "no unearned refund");
        f.avatar.discard(); c.complete();
    }
}
