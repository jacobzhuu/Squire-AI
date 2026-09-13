package dev.squire.server.task.executors;

import java.util.*;
import dev.squire.api.body.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.project.Project;
import dev.squire.server.task.*;
import dev.squire.server.task.TaskExecutor.StepOutcome;
import net.minecraft.block.*;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;

/** Post-structure fluid phase. Real buckets, normal walking, write-ahead settlement. */
final class FluidBuildExecutor {
    private static final Map<Task, Progress> ACTIVE = new WeakHashMap<>();
    private static final class Progress { long waitUntil, since; int stable, unsettled; BlockPos moving; MoveHandle move; }
    static StepOutcome tick(RuntimeServices services, Task task, Project project, Blueprint.Resolved r,
            AccessBuildExecutor.Progress access, long tick) {
        if (!ConstructionFluids.hasFluids(r)) return StepOutcome.WORK_DONE;
        if (project == null || r.costPlan() == null) return fail(task, "FLUID_REQUIRES_PROJECT");
        if (!project.pendingMutation().isEmpty()) return fail(task, "CONSTRUCTION_RECOVERY_REQUIRED");
        AvatarEntity avatar = services.avatar(task.agentId());
        if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) return fail(task, "ENTITY_NOT_FOUND");
        Progress p = ACTIVE.computeIfAbsent(task, k -> new Progress());
        if (tick < p.waitUntil) return StepOutcome.CONTINUE;
        var plan = r.costPlan();
        for (var cell : ConstructionFluids.operations(r)) {
            var op = plan.fluidOperation(cell);
            if (ConstructionFluids.matches(world, cell)) continue;
            if (op == null || plan.settled(op)) return fail(task, "SETTLED_BUILDING_CHANGED");
            boolean fetch = op.item().equals(ConstructionFluids.WATER_BUCKET) && project.reservedCount(op.item()) == 0 && plan.waterSource() != null;
            BlockPos target = fetch ? plan.waterSource() : cell.pos();
            if (!world.isChunkLoaded(target)) return fail(task, "FLUID_SOURCE_UNLOADED");
            if (fetch && (project.reservedCount(ConstructionFluids.BUCKET) == 0)) return fail(task, "INSUFFICIENT_ITEM");
            if (!fetch && project.reservedCount(op.item()) == 0) return fail(task, "INSUFFICIENT_ITEM");
            var state = world.getBlockState(target);
            if (fetch && (!state.isOf(Blocks.WATER) || !state.getFluidState().isStill() || !(state.getBlock() instanceof FluidDrainable)))
                return fail(task, "FLUID_SOURCE_EXHAUSTED");
            if (!services.protection().canInteract(world, target, project.ownerId).allowed()
                    || !(fetch ? services.protection().canBreak(world, target, project.ownerId) : services.protection().canPlace(world, target, project.ownerId)).allowed()) return fail(task, "PROTECTED");
            Set<BlockPos> fluids = new HashSet<>(); ConstructionFluids.goals(r).forEach(c -> fluids.add(c.pos()));
            boolean safeHere = avatar.isSafeWorkPosition(avatar.getBlockPos()) && !fluids.contains(avatar.getBlockPos())
                && !fluids.contains(avatar.getBlockPos().down()) && ConstructionWorksite.inReach(avatar, target);
            if (!safeHere) {
                if (p.moving == null) {
                    for (BlockPos station : ConstructionWorksite.stations(avatar, target, r.bounds())) {
                        if (fluids.contains(station) || fluids.contains(station.down())) continue;
                        if (access != null && access.program.bounds.contains(station)
                                && AccessRetreat.path(world, access.program, avatar.getBlockPos(), station) != null) { p.moving = station; break; }
                        var path = avatar.getNavigation().findPathTo(station, 0);
                        if (path != null && path.reachesTarget()) { p.moving = station; break; }
                    }
                    p.since = tick;
                }
                boolean needsAssist = p.moving == null || tick - p.since > ConstructionRecovery.STALL_TICKS
                    || access != null && access.program.assistance
                    || p.move != null && (p.move.state() == MoveHandle.State.FAILED || p.move.state() == MoveHandle.State.CANCELLED);
                if (needsAssist) {
                    Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
                    ConstructionFluids.goals(r).forEach(c -> changes.put(c.pos(), BlueprintManager.targetState(c)));
                    if (fetch) changes.put(target, Blocks.AIR.getDefaultState());
                    var area = ConstructionRecovery.bounds(r, avatar);
                    // The explicitly reviewed water source may be outside the building footprint.
                    if (fetch) area = new dev.squire.server.world.BoundedRegion(
                        new BlockPos(Math.min(area.min().getX(), target.getX() - 4), Math.min(area.min().getY(), target.getY() - 2), Math.min(area.min().getZ(), target.getZ() - 4)),
                        new BlockPos(Math.max(area.max().getX(), target.getX() + 4), Math.max(area.max().getY(), target.getY() + 4), Math.max(area.max().getZ(), target.getZ() + 4)));
                    if (ConstructionRecovery.occupied(world, avatar, changes)) return fail(task, "CONSTRUCTION_OCCUPIED");
                    if (ConstructionRecovery.prepare(avatar, area, target, changes) == null) return fail(task, "CONSTRUCTION_NO_SAFE_ANCHOR");
                    avatar.setActivityDetail(fetch ? "辅助取水" : "辅助注液");
                } else {
                avatar.setActivityDetail(fetch ? "往指定水源取水" : "前往注液站位");
                if (access != null && access.program.bounds.contains(p.moving)) return AccessBuildExecutor.move(avatar, task, access, p.moving, tick);
                if (p.move == null) p.move = avatar.moveTo(new TargetPosition(world.getRegistryKey().getValue().toString(), p.moving.getX() + .5, p.moving.getY(), p.moving.getZ() + .5), MoveOptions.WALK);
                if (p.move.arrived() && avatar.squaredDistanceTo(Vec3d.ofBottomCenter(p.moving)) < 2.25)
                    avatar.getMoveControl().moveTo(p.moving.getX() + .5, p.moving.getY(), p.moving.getZ() + .5, .5);
                if (p.move.state() == MoveHandle.State.FAILED || p.move.state() == MoveHandle.State.CANCELLED) {
                    org.slf4j.LoggerFactory.getLogger(FluidBuildExecutor.class).warn("Fluid move failed worker={} target={} station={} error={}", avatar.getPos(), target, p.moving, p.move.errorCode());
                    return StepOutcome.CONTINUE;
                }
                return StepOutcome.CONTINUE;
                }
            }
            avatar.stopMoving(); p.moving = null; p.move = null;
            if (access != null) access.movingTo = null;
            if (!fetch) {
                String unsafe = ConstructionFluids.safety(world, r, cell);
                if (!unsafe.isEmpty()) return fail(task, unsafe);
                for (var goal : ConstructionFluids.goals(r)) if (!services.protection().canPlace(world, goal.pos(), project.ownerId).allowed()) return fail(task, "PROTECTED");
            }
            if (!services.beginProjectMutation(project.projectId, (fetch ? "fluid-fetch:" : "fluid-pour:") + op.id() + ":" + target.asLong())) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            if (fetch) {
                var drained = ((FluidDrainable) state.getBlock()).tryDrainFluid(world, target, state);
                if (!drained.isOf(Items.WATER_BUCKET) || drained.getCount() != 1) return fail(task, "CONSTRUCTION_RECOVERY_REQUIRED");
                if (!project.exchangeContainer(ConstructionFluids.BUCKET, ConstructionFluids.WATER_BUCKET)) return fail(task, "CONSTRUCTION_RECOVERY_REQUIRED");
            } else {
                if (!ConstructionFluids.pour(world, cell)) return fail(task, "CONSTRUCTION_RECOVERY_REQUIRED");
                if (!project.exchangeContainer(op.item(), ConstructionFluids.BUCKET)) return fail(task, "CONSTRUCTION_RECOVERY_REQUIRED");
                plan.settleOperation(op);
            }
            if (!services.blueprints().save()) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            if (!services.completeProjectMutation(project.projectId)) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            avatar.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            avatar.setActivityDetail(fetch ? "装满水桶" : "注液并回收空桶");
            p.waitUntil = tick + (fetch ? 20 : 40); p.stable = 0;
            return StepOutcome.CONTINUE;
        }
        // Check native dynamics over multiple updates; never write flowing states to force a pass.
        if (ConstructionFluids.goals(r).stream().anyMatch(c -> !ConstructionFluids.matches(world, c))) {
            p.stable = 0;
            if (++p.unsettled > 30) return fail(task, "FLUID_DID_NOT_SETTLE");
            p.waitUntil = tick + 20; return StepOutcome.CONTINUE;
        }
        if (++p.stable < 10) { p.waitUntil = tick + 20; return StepOutcome.CONTINUE; }
        ACTIVE.remove(task); return StepOutcome.WORK_DONE;
    }
    private static StepOutcome fail(Task t, String code) { ACTIVE.remove(t); t.setLastErrorCode(code); return StepOutcome.FAILED; }
}
