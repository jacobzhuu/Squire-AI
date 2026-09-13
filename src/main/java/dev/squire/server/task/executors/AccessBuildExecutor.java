package dev.squire.server.task.executors;

import java.util.*;
import dev.squire.api.body.*;
import dev.squire.server.blueprint.*;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.project.Project;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskExecutor.StepOutcome;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Executes the reviewed access program using ordinary navigation and real item debits. */
final class AccessBuildExecutor {
    static final class Progress {
        final UUID placementId, projectId, operationId;
        MoveHandle move;
        BlockPos movingTo;
        BlockPos walkTarget;
        List<BlockPos> route = List.of();
        int waypoint;
        boolean directSteps;
        int reroutes;
        BlockPos rerouteTarget;
        int replacementCursor = -1;
        BlockPos replacementStation;
        ConstructionAccessPlan program;
        long since;
        long retryAt;
        int attempts;
        long airborneSince = -1;
        long edgeSince = -1;
        BlockPos measuredTarget;
        double bestDistance;
        long lastMovement;
        int placed;
        boolean recoverySaved;
        double nextPlacementTick;
        BlockPos cleanupBlock, cleanupStation;
        Progress(UUID placementId, UUID projectId, UUID operationId) { this.placementId = placementId; this.projectId = projectId; this.operationId = operationId; }
    }
    private AccessBuildExecutor() { }
    static StepOutcome tick(RuntimeServices services, Task task, Progress progress, long tick) {
        AvatarEntity avatar = services.avatar(task.agentId());
        var placement = services.blueprints().placement(progress.placementId).orElse(null);
        Project project = services.project(progress.projectId);
        if (avatar == null || placement == null || project == null || !(avatar.getWorld() instanceof ServerWorld world)
                || !world.getRegistryKey().getValue().toString().equals(placement.dimensionId)) return fail(task, "ENTITY_NOT_FOUND");
        var resolved = services.blueprints().resolve(placement).orElseThrow();
        if (!project.pendingMutation().isEmpty()) return fail(task, "CONSTRUCTION_RECOVERY_REQUIRED");
        var access = resolved.access();
        if (access == null || !access.valid()) return fail(task, "ACCESS_PLAN_INVALID");
        progress.program = access;
        // Completed work has no station requirement. This also repairs old saved programs.
        if (!access.cleanup && !access.cancelRequested && access.cursor < access.work.size()
                && completed(world, access.work.get(access.cursor), resolved)) {
            access.cursor++; progress.movingTo = null; avatar.stopMoving();
            save(services); return StepOutcome.CONTINUE;
        }
        boolean assisted = access.assistance || !access.cleanup && !access.cancelRequested && access.cursor < access.work.size()
            && access.work.get(access.cursor).mode() != ConstructionAccessPlan.Mode.WALK;
        if (access.assistance && !progress.recoverySaved) {
            if (!services.blueprints().save()) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            progress.recoverySaved = true;
        }
        if (progress.movingTo != null && progress.walkTarget != null) {
            for (BlockPos doorPos : List.of(progress.walkTarget, progress.walkTarget.up())) {
                var doorState = world.getBlockState(doorPos);
                if (access.bounds.contains(doorPos) && AccessRetreat.woodenDoor(world, doorPos)
                        && !doorState.get(net.minecraft.block.DoorBlock.OPEN) && ConstructionWorksite.inReach(avatar, doorPos)) {
                    if (!services.protection().canInteract(world, doorPos, project.ownerId).allowed()) return fail(task, "ACCESS_DOOR_PROTECTED");
                    ((net.minecraft.block.DoorBlock) doorState.getBlock()).setOpen(avatar, world, doorState, doorPos, true);
                }
            }
        }
        boolean onScaffold = ConstructionScaffolding.scaffold(world, avatar.getBlockPos())
            || ConstructionScaffolding.scaffold(world, avatar.getBlockPos().down());
        boolean verticalDescent = progress.movingTo != null && progress.walkTarget != null
            && Math.abs(progress.walkTarget.getX() + .5 - avatar.getX()) < .15
            && Math.abs(progress.walkTarget.getZ() + .5 - avatar.getZ()) < .15;
        avatar.setSneaking(progress.movingTo != null && progress.walkTarget != null
            && progress.walkTarget.getY() < avatar.getY() - .1 && onScaffold);
        if (onScaffold) {
            double dy = (progress.walkTarget != null && progress.movingTo != null ? progress.walkTarget.getY() : Math.rint(avatar.getY())) - avatar.getY();
            avatar.setSneaking(verticalDescent && dy < -.1);
            // Ladder-constrained movement only: never impart vertical motion outside a real scaffold.
            var velocity = avatar.getVelocity();
            avatar.setVelocity(velocity.x, Math.max(-.15, Math.min(.15, dy)), velocity.z);
            avatar.fallDistance = 0;
        }
        if (!assisted && !avatar.isOnGround() && !onScaffold) {
            if (progress.walkTarget != null && (progress.directSteps || progress.move != null && progress.move.arrived())) {
                var p = progress.walkTarget;
                avatar.getMoveControl().moveTo(p.getX() + .5, p.getY(), p.getZ() + .5, .5);
            }
            if (progress.airborneSince < 0) progress.airborneSince = tick;
            return tick - progress.airborneSince <= ConstructionRecovery.STALL_TICKS ? StepOutcome.CONTINUE
                : recover(services, task, progress, "ACCESS_UNSAFE_FALL");
        }
        progress.airborneSince = -1;
        // On a block edge, isOnGround can be true while the feet's integer cell has
        // no floor. Recenter on the actual neighbouring support before asking for a path.
        if (!assisted && progress.movingTo == null && world.getBlockState(avatar.getBlockPos().down()).isAir()) {
            BlockPos edge = java.util.Arrays.stream(Direction.values()).filter(d -> d.getAxis().isHorizontal())
                .map(d -> avatar.getBlockPos().offset(d)).filter(avatar::isSafeWorkPosition)
                .filter(s -> access.bounds.contains(s) && avatar.squaredDistanceTo(Vec3d.ofBottomCenter(s)) <= 2.25)
                .min(Comparator.comparingDouble(s -> avatar.squaredDistanceTo(Vec3d.ofBottomCenter(s)))).orElse(null);
            if (edge != null) {
                if (progress.edgeSince < 0) progress.edgeSince = tick;
                if (tick - progress.edgeSince > ConstructionRecovery.STALL_TICKS) return recover(services, task, progress, "ACCESS_ROUTE_BLOCKED");
                avatar.stopMoving(); progress.movingTo = null;
                progress.walkTarget = edge; progress.directSteps = true;
                avatar.getMoveControl().moveTo(edge.getX() + .5, edge.getY(), edge.getZ() + .5, .5);
                return StepOutcome.CONTINUE;
            }
        }
        progress.edgeSince = -1;
        if (!access.cancelRequested && !access.cleanup && access.cursor >= access.work.size()) {
            var liquid = FluidBuildExecutor.tick(services, task, project, resolved, progress, tick);
            if (liquid != StepOutcome.WORK_DONE) return liquid;
        }
        if (access.cancelRequested || access.cursor >= access.work.size()) access.cleanup = true;
        if (access.cleanup) {
            if (access.assistance) return assistedCleanup(services, task, progress, avatar, world, project, access, tick);
            avatar.setActivityDetail("回收施工设施");
            var pending = access.work.stream().filter(ConstructionAccessPlan.Work::temporary)
                .filter(w -> access.placed.containsKey(w.cell().pos()))
                .sorted(Comparator.<ConstructionAccessPlan.Work>comparingInt(w -> w.cell().pos().getY())
                    // Traversed backwards below: nearest safe support first within a layer.
                    .thenComparingDouble(w -> -avatar.squaredDistanceTo(Vec3d.ofCenter(w.cell().pos())))).toList();
            if (pending.isEmpty()) {
                if (!access.cancelRequested) {
                    var missing = resolved.toPlace().stream().filter(c -> !c.optional() && !BlueprintManager.matches(world.getBlockState(c.pos()), c)).limit(5).toList();
                    if (!missing.isEmpty()) org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access final mismatch {}", missing.stream().map(c -> c.pos() + " expected=" + BlueprintManager.targetState(c) + " actual=" + world.getBlockState(c.pos())).toList());
                }
                avatar.stopMoving();
                task.setWorkReport(new Task.WorkReport(dev.squire.server.profile.Track.BUILD.id(), progress.placed, progress.operationId));
                return StepOutcome.WORK_DONE;
            }
            for (var w : pending) if (!BlueprintManager.matches(world.getBlockState(w.cell().pos()), w.cell())) {
                access.placed.remove(w.cell().pos()); save(services); return StepOutcome.CONTINUE;
            }
            if (progress.cleanupBlock == null || !access.placed.containsKey(progress.cleanupBlock)) {
                progress.cleanupBlock = null; progress.cleanupStation = null; progress.movingTo = null;
                for (int i = pending.size() - 1; i >= 0; i--) {
                    var w = pending.get(i); BlockPos candidate = w.cell().pos();
                    Set<BlockPos> retreat = AccessRetreat.afterRemoval(world, access, candidate);
                    if (retreat.isEmpty()) continue;
                    List<BlockPos> stations = new ArrayList<>();
                    // Prefer the current feet cell even when the avatar is leaning
                    // across its edge. The execution step recenters the body before
                    // removal; rejecting this station here can force an unnecessary
                    // one-block descent and strand the worker during cleanup.
                    if (retreat.contains(avatar.getBlockPos()) && ConstructionWorksite.inReach(avatar, candidate))
                        stations.add(avatar.getBlockPos());
                    stations.add(w.station());
                    stations.addAll(ConstructionWorksite.stations(avatar, candidate, resolved.bounds()));
                    // AccessRetreat already computed the complete safe component after
                    // removal. Include its nearest cells directly so cleanup is not
                    // constrained by the normal 48 construction-station candidates.
                    stations.addAll(retreat.stream()
                        .sorted(Comparator.comparingDouble(s -> avatar.squaredDistanceTo(Vec3d.ofBottomCenter(s))))
                        .limit(96).toList());
                    stations.sort(Comparator.comparingDouble(s -> avatar.squaredDistanceTo(Vec3d.ofBottomCenter(s))));
                    for (BlockPos station : stations) {
                        if (!retreat.contains(station) || station.equals(candidate) || station.down().equals(candidate) || !AccessRetreat.eyeReach(station, candidate)) continue;
                        if (!station.equals(avatar.getBlockPos())) {
                            if (!avatar.isSafeWorkPosition(station)) continue;
                            boolean adjacentLevelStep = station.getY() == avatar.getBlockY()
                                && avatar.squaredDistanceTo(Vec3d.ofBottomCenter(station)) <= 4.0;
                            if (!adjacentLevelStep && AccessRetreat.path(world, access, avatar.getBlockPos(), station) == null) {
                                var path = avatar.getNavigation().findPathTo(station, 0);
                                if (path == null || !path.reachesTarget()) continue;
                            }
                        }
                        progress.cleanupBlock = candidate; progress.cleanupStation = station; break;
                    }
                    if (progress.cleanupBlock != null) break;
                }
                if (progress.cleanupBlock == null) {
                    // If the conservative post-removal component has no candidate,
                    // retreat along the still-intact reviewed route first. From the
                    // entrance a stable top-down column can be reclaimed directly;
                    // this avoids making completion depend on a global connectivity
                    // proof for every remaining scaffold branch.
                    // A worker inside a vertical scaffold may dismantle the current
                    // segment and settle onto the scaffold (or solid floor) below.
                    // This is the natural top-down descent and avoids spending the
                    // rest of the task timeout walking to the bottom first.
                    BlockPos feet = avatar.getBlockPos();
                    if (access.placed.containsKey(feet) && ConstructionScaffolding.safeRemoval(world, feet)
                            && safeToReclaimHere(world, avatar, feet)) {
                        progress.cleanupBlock = feet;
                        progress.cleanupStation = feet;
                    }
                    for (int i = pending.size() - 1; progress.cleanupBlock == null && i >= 0; i--) {
                        BlockPos candidate = pending.get(i).cell().pos();
                        if (!ConstructionScaffolding.safeRemoval(world, candidate)
                                || !ConstructionWorksite.inReach(avatar, candidate)
                                || !safeToReclaimHere(world, avatar, candidate)) continue;
                        progress.cleanupBlock = candidate;
                        progress.cleanupStation = avatar.getBlockPos();
                        break;
                    }
                }
                if (progress.cleanupBlock == null) {
                    org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access cleanup diagnosis {}", AccessRetreat.diagnose(world, access));
                    org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access cleanup blocked worker={} entrance={} floor={} pending={}",
                        avatar.getPos(), access.entrance, world.getBlockState(access.entrance.down()),
                        pending.stream().limit(8).map(w -> w.cell().pos() + " reachable=" + AccessRetreat.afterRemoval(world, access, w.cell().pos()).size()).toList());
                    // Keep ownership and the refund ledger; bounded assistance can
                    // reclaim unreachable pieces without requiring a walking escape.
                    return recover(services, task, progress, "ACCESS_CLEANUP_ROUTE");
                }
            }
            BlockPos pos = progress.cleanupBlock;
            if (!services.protection().canBreak(world, pos, project.ownerId).allowed()) {
                return fail(task, "PROTECTED");
            }
            boolean canRemoveHere = at(avatar, progress.cleanupStation)
                && ConstructionWorksite.inReach(avatar, pos) && safeToReclaimHere(world, avatar, pos);
            if (!canRemoveHere) {
                // The broad arrival tolerance is useful for ordinary work, but while
                // reclaiming an adjacent scaffold the avatar's bounding box may still
                // touch the block above it. Recenter instead of abandoning a perfectly
                // recoverable paid support.
                if (!at(avatar, progress.cleanupStation))
                    return move(avatar, task, progress, progress.cleanupStation, tick);
                if (!centeredAt(avatar, progress.cleanupStation))
                    return recenter(avatar, progress.cleanupStation);
                return recover(services, task, progress, "ACCESS_CLEANUP_POSITION");
            }
            avatar.stopMoving(); progress.movingTo = null;
            if (!ConstructionWorksite.inReach(avatar, pos) || !safeToReclaimHere(world, avatar, pos)) {
                org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Scaffold reclaim position unsafe worker={} target={} station={}", avatar.getPos(), pos, progress.cleanupStation);
                return recover(services, task, progress, "ACCESS_CLEANUP_POSITION");
            }
            boolean owner = access.placed.get(pos);
            var recovered = access.temporaryCell(pos);
            boolean controlledDescent = avatar.getBlockPos().equals(pos)
                && (world.getBlockState(pos.down()).isOf(Blocks.SCAFFOLDING)
                    || world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP));
            if (recovered == null || !BlueprintManager.matches(world.getBlockState(pos), recovered)
                    || !controlledDescent && AccessRetreat.afterRemoval(world, access, pos).isEmpty()) {
                org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Scaffold reclaim dependency changed target={} actual={} expected={} remaining={}", pos, world.getBlockState(pos), recovered, access.placed.keySet());
                return recover(services, task, progress, "ACCESS_CLEANUP_DEPENDENCY");
            }
            Identifier refund = BlueprintManager.itemId(recovered.blockId());
            if (!services.beginProjectMutation(project.projectId, "reclaim:" + pos.asLong())) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            if (!world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL)) { services.completeProjectMutation(project.projectId); return fail(task, "PLACE_FAILED"); }
            access.placed.remove(pos);
            project.reserve(owner ? Map.of(refund, 1) : Map.of(), owner ? Map.of() : Map.of(refund, 1));
            if (!services.completeProjectMutation(project.projectId)) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            save(services); return StepOutcome.CONTINUE;
        }
        var work = access.work.get(access.cursor);
        var changes = ConstructionRecovery.changes(work.cell(), resolved);
        if (ConstructionRecovery.occupied(world, avatar, changes)) return fail(task, "CONSTRUCTION_OCCUPIED");
        if (assisted) {
            var mode = ConstructionRecovery.prepare(avatar, access.bounds, work.cell().pos(), changes);
            if (mode == null) return fail(task, "CONSTRUCTION_NO_SAFE_ANCHOR");
            avatar.setActivityDetail(mode == ConstructionAccessPlan.Mode.SERVER_ASSIST ? "辅助施工" : "辅助移动后施工");
            progress.movingTo = null; progress.walkTarget = null;
        }
        BlockPos effectiveStation = work.station();
        if (progress.replacementCursor == access.cursor && progress.replacementStation != null)
            effectiveStation = progress.replacementStation;
        if (!assisted && !safeRouteStation(avatar, effectiveStation)) {
            effectiveStation = replacementStation(world, avatar, access, work, resolved);
            if (effectiveStation == null) {
                org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn(
                    "No live replacement station cursor={} target={} planned={} worker={} targetState={} plannedFeet={} plannedFloor={}",
                    access.cursor, work.cell().pos(), work.station(), avatar.getPos(), world.getBlockState(work.cell().pos()),
                    world.getBlockState(work.station()), world.getBlockState(work.station().down()));
                return recover(services, task, progress, "ACCESS_ROUTE_BLOCKED");
            }
            progress.replacementCursor = access.cursor;
            progress.replacementStation = effectiveStation;
            progress.movingTo = null;
            progress.walkTarget = null;
        }
        var effectiveWork = effectiveStation.equals(work.station()) ? work
            : new ConstructionAccessPlan.Work(work.cell(), effectiveStation, work.temporary());
        var cell = work.cell();
        BlockPos pos = cell.pos();
        if (completed(world, work, resolved)) {
            if (work.temporary() && !access.placed.containsKey(pos)) {
                // An externally supplied identical support is usable but never owned/refunded.
            }
            access.cursor++; save(services); return StepOutcome.CONTINUE;
        }
        boolean atReviewedStation = at(avatar, effectiveStation);
        if (!assisted && !atReviewedStation && !canWorkHere(world, avatar, access, effectiveWork, resolved))
            return move(avatar, task, progress, effectiveStation, tick);
        if (!assisted && atReviewedStation && !centeredAt(avatar, effectiveStation))
            return recenter(avatar, effectiveStation);
        avatar.stopMoving(); progress.movingTo = null;
        if (!assisted) avatar.setActivityDetail(work.temporary() ? (cell.blockId().equals("minecraft:scaffolding") ? "搭建脚手架" : "搭建旧施工通道") : "高处施工");
        if (!assisted && !ConstructionWorksite.inReach(avatar, pos)) {
            org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn(
                "Reviewed work out of reach cursor={} worker={} station={} target={} eyeDistance={}",
                access.cursor, avatar.getPos(), effectiveStation, pos,
                avatar.getEyePos().distanceTo(Vec3d.ofCenter(pos)));
            return recover(services, task, progress, "ACCESS_OUT_OF_REACH");
        }
        if (work.excavation()) {
            avatar.setActivityDetail(assisted ? "辅助开挖施工通路" : "开挖施工通路");
            var before = world.getBlockState(pos);
            if (!access.bounds.contains(pos) || !ConstructionAccessPlan.diggable(world, pos)
                    || avatar.getBoundingBox().intersects(new Box(pos))
                    || avatar.getBoundingBox().intersects(new Box(pos.up()))) return fail(task, "ACCESS_DIG_UNSAFE");
            if (!services.protection().canBreak(world, pos, project.ownerId).allowed()) return fail(task, "PROTECTED");
            var items = avatar.items();
            var slot = items.bestToolSlot(before);
            var tool = items.stackAt(slot);
            if (!dev.squire.server.body.proxy.FakePlayerInteractionProxy.canHarvest(before, tool)) {
                avatar.setActivityDetail(dev.squire.server.body.proxy.FakePlayerInteractionProxy.missingHarvestToolMessage(before));
                return fail(task, "HARVEST_TOOL_MISSING:" + net.minecraft.registry.Registries.BLOCK.getId(before.getBlock()));
            }
            if (tick < progress.nextPlacementTick) return StepOutcome.CONTINUE;
            if (progress.operationId != null && services.worldEditor() != null)
                services.worldEditor().journal().record(new dev.squire.server.world.UndoJournal.Entry(
                    progress.operationId, task.taskId(), pos, before, null, Blocks.AIR.getDefaultState(), tick));
            if (!services.beginProjectMutation(project.projectId, "access-dig:" + pos.asLong())) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            var result = dev.squire.server.body.proxy.FakePlayerInteractionProxy.breakAndCollect(world, pos, tool, used -> { });
            items.writeBackTool(slot, tool);
            for (var drop : result.drops()) {
                var remainder = items.insert(drop);
                if (!remainder.isEmpty()) Block.dropStack(world, pos, remainder);
            }
            if (result.broken()) access.cursor++;
            if (!services.completeProjectMutation(project.projectId)) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            save(services);
            if (!result.broken()) return fail(task, "BREAK_FAILED");
            progress.nextPlacementTick = dev.squire.server.profession.EngineerProgression.nextWorkTick(tick,
                progress.nextPlacementTick, BlueprintBuildExecutor.placementInterval(avatar.profile()));
            avatar.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            return StepOutcome.CONTINUE;
        }
        var target = BlueprintManager.targetState(cell);
        if (!ConstructionWorksite.clearOfWorker(avatar, target, pos)) {
            org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access collision {} station={} target={}", avatar.getPos(), work.station(), pos);
            return recover(services, task, progress, "ACCESS_WORKER_COLLISION");
        }
        if (!world.getBlockState(pos).isAir() && !world.getBlockState(pos).isReplaceable()) {
            org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access changed {} expected={} actual={}", pos, target, world.getBlockState(pos));
            return fail(task, "ACCESS_SITE_CHANGED");
        }
        if (!services.protection().canPlace(world, pos, project.ownerId).allowed()) return fail(task, "PROTECTED");
        Identifier item = BlueprintManager.itemId(cell.blockId());
        List<Blueprint.Cell> assembly;
        try { assembly = BlueprintAssembly.pending(world, cell, resolved); }
        catch (IllegalArgumentException bad) { return fail(task, "BLUEPRINT_ASSEMBLY_INVALID"); }
        int cost = BlueprintAssembly.cost(assembly);
        var costPlan = work.temporary() || cell.optional() ? null : resolved.costPlan();
        if (costPlan != null) cost = costPlan.quote(assembly);
        if (cost < 0) return fail(task, "SETTLED_BUILDING_CHANGED");
        for (var part : assembly) {
            var existing = world.getBlockState(part.pos());
            if ((!existing.isAir() && !existing.isReplaceable())
                || !assisted && !ConstructionWorksite.inReach(avatar, part.pos())
                || !ConstructionWorksite.clearOfWorker(avatar, BlueprintManager.targetState(part), part.pos())) {
                org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access assembly blocked {} expected={} actual={} worker={}", part.pos(), BlueprintManager.targetState(part), existing, avatar.getPos());
                return fail(task, "ACCESS_SITE_CHANGED");
            }
            if (!services.protection().canPlace(world, part.pos(), project.ownerId).allowed()) return fail(task, "PROTECTED");
        }
        if (cell.optional() && avatar.items().countOf(item) < cost) {
            access.cursor++; save(services); return StepOutcome.CONTINUE;
        }
        if (!cell.optional() && project.reservedCount(item) < cost) return fail(task, "INSUFFICIENT_ITEM");
        if (tick < progress.nextPlacementTick) return StepOutcome.CONTINUE;
        boolean owner = project.agentSupply().getOrDefault(item, 0) == 0;
        Map<BlockPos, net.minecraft.block.BlockState> before = new LinkedHashMap<>();
        assembly.forEach(c -> before.put(c.pos(), world.getBlockState(c.pos())));
        if (!work.temporary() && progress.operationId != null && services.worldEditor() != null)
            for (var c : assembly) {
                var be = world.getBlockEntity(c.pos());
                services.worldEditor().journal().record(new dev.squire.server.world.UndoJournal.Entry(
                    progress.operationId, task.taskId(), c.pos(), before.get(c.pos()), be == null ? null : be.createNbtWithId(), BlueprintManager.targetState(c), tick));
            }
        if (!services.beginProjectMutation(project.projectId, "place:" + pos.asLong())) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
        if (!BlueprintAssembly.place(world, assembly)) { services.completeProjectMutation(project.projectId); return fail(task, "BLUEPRINT_SUPPORT_MISSING"); }
        boolean paid = cost == 0 || (cell.optional()
            ? avatar.items().extract(item, cost).stream().mapToInt(net.minecraft.item.ItemStack::getCount).sum() == cost
            : project.consume(item, cost));
        if (!paid) { BlueprintAssembly.restore(world, before); services.completeProjectMutation(project.projectId); return fail(task, "INSUFFICIENT_ITEM"); }
        if (costPlan != null) costPlan.settle(assembly);
        progress.nextPlacementTick = dev.squire.server.profession.EngineerProgression.nextWorkTick(tick, progress.nextPlacementTick, BlueprintBuildExecutor.placementInterval(avatar.profile()));
        if (work.temporary()) access.placed.put(pos, owner); else progress.placed++;
        access.cursor++;
        if (!services.completeProjectMutation(project.projectId)) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
        save(services);
        avatar.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        return StepOutcome.CONTINUE;
    }
    private static boolean completed(ServerWorld world, ConstructionAccessPlan.Work work, Blueprint.Resolved resolved) {
        return work.excavation() ? world.isAir(work.cell().pos()) : BlueprintAssembly.cells(work.cell(), resolved).stream()
            .allMatch(c -> BlueprintManager.matches(world.getBlockState(c.pos()), c));
    }
    private static boolean at(AvatarEntity avatar, BlockPos station) {
        return Math.abs(avatar.getY() - station.getY()) < .35
            && Math.abs(avatar.getX() - station.getX() - .5) < .35
            && Math.abs(avatar.getZ() - station.getZ() - .5) < .35;
    }
    private static boolean canWorkHere(ServerWorld world, AvatarEntity avatar, ConstructionAccessPlan access,
            ConstructionAccessPlan.Work work, Blueprint.Resolved resolved) {
        var profile = avatar.profile();
        if (work.temporary() || work.excavation() || profile == null || profile.profession.profession() != dev.squire.server.profession.SquireProfession.ENGINEER
                || profile.profession.level < 2 || !at(avatar, avatar.getBlockPos()) || !avatar.isSafeWorkPosition(avatar.getBlockPos())) return false;
        List<Blueprint.Cell> assembly;
        try { assembly = BlueprintAssembly.cells(work.cell(), resolved); }
        catch (IllegalArgumentException bad) { return false; }
        if (assembly.stream().anyMatch(c -> !ConstructionWorksite.inReach(avatar, c.pos())
                || !ConstructionWorksite.clearOfWorker(avatar, BlueprintManager.targetState(c), c.pos()))) return false;
        Set<BlockPos> added = new HashSet<>(); assembly.forEach(c -> added.add(c.pos()));
        Map<BlockPos, net.minecraft.block.BlockState> future = new HashMap<>();
        assembly.forEach(c -> future.put(c.pos(), BlueprintManager.targetState(c)));
        if (!new dev.squire.server.blueprint.ConstructionBlockView(world, future).safeStanding(avatar.getBlockPos(), false)) return false;
        // Preserve both the reviewed next station and escape route before avoiding a walk.
        return AccessRetreat.path(world, access, avatar.getBlockPos(), work.station(), added) != null
            && AccessRetreat.path(world, access, avatar.getBlockPos(), access.entrance, added) != null;
    }
    private static double movementSpeed(AvatarEntity avatar, BlockPos target) {
        // Retain braking on narrow elevated paths; full walking speed is for broad, flat ground.
        if (target.getY() != avatar.getBlockPos().getY()) return .5;
        double dx = target.getX() + .5 - avatar.getX(), dz = target.getZ() + .5 - avatar.getZ();
        if (dx * dx + dz * dz < .25) return .5; // Brake before entering the precise station tolerance.
        for (BlockPos centre : List.of(avatar.getBlockPos(), target)) {
            for (var direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                BlockPos floor = centre.offset(direction).down();
                if (!avatar.getWorld().getBlockState(floor).isSideSolidFullSquare(
                        avatar.getWorld(), floor, net.minecraft.util.math.Direction.UP)) return .5;
            }
        }
        return EngineerMovement.speed(avatar.profile());
    }
    static StepOutcome move(AvatarEntity avatar, Task task, Progress p, BlockPos to, long tick) {
        BlockPos measured = !to.equals(p.movingTo) || p.walkTarget == null ? to : p.walkTarget;
        double distance = avatar.squaredDistanceTo(Vec3d.ofBottomCenter(measured));
        if (!measured.equals(p.measuredTarget) || distance < p.bestDistance - .04) {
            p.measuredTarget = measured; p.bestDistance = distance; p.lastMovement = tick;
        }
        if (tick - p.lastMovement >= ConstructionRecovery.STALL_TICKS) return reroute(avatar, task, p, tick);
        if (!to.equals(p.movingTo)) {
            if (!to.equals(p.rerouteTarget)) {
                p.rerouteTarget = to;
                p.reroutes = 0;
            }
            if (!safeRouteStation(avatar, to)) {
                org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Unsafe reviewed station {} cursor={} worker={} floor={} feet={} head={} node={} empty={}",
                    to, p.program.cursor, avatar.getPos(), avatar.getWorld().getBlockState(to.down()), avatar.getWorld().getBlockState(to), avatar.getWorld().getBlockState(to.up()),
                    net.minecraft.entity.ai.pathing.LandPathNodeMaker.getLandNodeType(avatar.getWorld(), to.mutableCopy()),
                    avatar.getWorld().isSpaceEmpty(avatar, avatar.getBoundingBox().offset(Vec3d.ofBottomCenter(to).subtract(avatar.getPos()))));
                return reroute(avatar, task, p, tick);
            }
            if (p.move != null) p.move.cancel();
            p.movingTo = to; p.since = tick; p.attempts = 0; p.retryAt = tick + 10; p.waypoint = 0;
            var route = AccessRetreat.path((ServerWorld) avatar.getWorld(), p.program, avatar.getBlockPos(), to);
            double directX = to.getX() + .5 - avatar.getX();
            double directZ = to.getZ() + .5 - avatar.getZ();
            // Entity Y can settle a few ulps below an integer, making getBlockPos()
            // report the cell below and causing vanilla/path review to return
            // PATH_NOT_FOUND for an ordinary adjacent step on a finished floor.
            boolean nearbyLevelStep = Math.abs(to.getY() - avatar.getY()) < .35
                && directX * directX + directZ * directZ <= 4.0;
            p.directSteps = route != null || nearbyLevelStep;
            p.route = route == null || route.isEmpty() ? List.of(to) : route;
            p.walkTarget = p.route.get(0);
            if (p.directSteps) { avatar.stopMoving(); p.move = null; }
            else p.move = walk(avatar, p.walkTarget);
        }
        if (at(avatar, p.walkTarget) && p.waypoint + 1 < p.route.size()) {
            // Brake at a reviewed corner before changing direction on a narrow platform.
            avatar.setVelocity(0, avatar.getVelocity().y, 0);
            p.walkTarget = p.route.get(++p.waypoint); p.since = tick; p.attempts = 0;
            if (!p.directSteps) p.move = walk(avatar, p.walkTarget);
        }
        if (p.directSteps) {
            var waypointState = avatar.getWorld().getBlockState(p.walkTarget);
            boolean openWoodenDoor = AccessRetreat.woodenDoor((ServerWorld) avatar.getWorld(), p.walkTarget)
                && waypointState.get(net.minecraft.block.DoorBlock.OPEN);
            if (tick - p.since > 80 && (safeRouteStation(avatar, p.walkTarget) || openWoodenDoor)) {
                // Last-resort correction for a single already-reviewed edge. Mob
                // movement controls can continuously overwrite velocity on freshly
                // changed floors even though both cells and the swept route are safe.
                // Snap only to this adjacent waypoint; never skip route nodes or
                // bypass the planner's excavation/scaffold/protection checks.
                avatar.repositionForConstruction(p.walkTarget.getX() + .5, p.walkTarget.getY(),
                    p.walkTarget.getZ() + .5);
                avatar.setVelocity(Vec3d.ZERO);
                p.since = tick;
                return StepOutcome.CONTINUE;
            }
            if (tick - p.since > 240) {
                org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access step blocked {} -> {} via {} feet={} floor={} targetFeet={} targetFloor={} sneak={}", avatar.getPos(), to, p.walkTarget,
                    avatar.getWorld().getBlockState(avatar.getBlockPos()), avatar.getWorld().getBlockState(avatar.getBlockPos().down()), avatar.getWorld().getBlockState(p.walkTarget), avatar.getWorld().getBlockState(p.walkTarget.down()), avatar.isSneaking());
                return reroute(avatar, task, p, tick);
            }
            var target = p.walkTarget;
            if (ConstructionScaffolding.scaffold(avatar.getWorld(), avatar.getBlockPos())
                    || ConstructionScaffolding.scaffold(avatar.getWorld(), avatar.getBlockPos().down())) {
                // Vanilla land MoveControl treats the scaffold outline as a full obstacle and
                // repeatedly jumps into the ceiling. Use constrained climbing locomotion here;
                // Minecraft still resolves every collision and all motion stops outside the ladder.
                avatar.setJumping(false);
                double dx = target.getX() + .5 - avatar.getX(), dz = target.getZ() + .5 - avatar.getZ();
                EngineerMovement.directStep(avatar, dx, dz, .5);
                return StepOutcome.CONTINUE;
            }
            double dx = target.getX() + .5 - avatar.getX(), dz = target.getZ() + .5 - avatar.getZ();
            // This edge has already passed the construction route's collision,
            // support and return checks. Vanilla navigation can still return
            // PATH_NOT_FOUND on a newly completed floor, so execute the short edge
            // directly while Minecraft remains responsible for all collisions.
            EngineerMovement.directStep(avatar, dx, dz, movementSpeed(avatar, target));
            if (avatar.isOnGround() && target.getY() > avatar.getY() + .5 && dx * dx + dz * dz < 2.0)
                avatar.getJumpControl().setActive();
            return StepOutcome.CONTINUE;
        }
        if (p.move != null && (p.move.state() == MoveHandle.State.FAILED || p.move.state() == MoveHandle.State.CANCELLED)
                && tick - p.since <= 240) {
            if (!avatar.isOnGround() || tick < p.retryAt) return StepOutcome.CONTINUE;
            if (p.attempts++ < 3) {
                p.move = walk(avatar, p.walkTarget); p.retryAt = tick + 20; return StepOutcome.CONTINUE;
            }
        }
        if (tick - p.since > 240 || p.move == null || p.move.state() == MoveHandle.State.FAILED
                || p.move.state() == MoveHandle.State.CANCELLED) {
            org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).warn("Access move blocked {} -> {} via {} ({})", avatar.getPos(), to, p.walkTarget, p.move == null ? "no handle" : p.move.errorCode());
            return reroute(avatar, task, p, tick);
        }
        if (p.move.arrived() && avatar.squaredDistanceTo(Vec3d.ofBottomCenter(p.walkTarget)) <= 2.25)
            avatar.getMoveControl().moveTo(p.walkTarget.getX() + .5, p.walkTarget.getY(), p.walkTarget.getZ() + .5,
                movementSpeed(avatar, p.walkTarget));
        return StepOutcome.CONTINUE;
    }
    private static MoveHandle walk(AvatarEntity avatar, BlockPos to) {
        return avatar.moveTo(new TargetPosition(avatar.getWorld().getRegistryKey().getValue().toString(),
            to.getX() + .5, to.getY(), to.getZ() + .5), EngineerMovement.options(avatar.profile()));
    }
    private static boolean centeredAt(AvatarEntity avatar, BlockPos station) {
        return Math.abs(avatar.getY() - station.getY()) < .12
            && Math.abs(avatar.getX() - station.getX() - .5) < .12
            && Math.abs(avatar.getZ() - station.getZ() - .5) < .12;
    }
    private static boolean safeRouteStation(AvatarEntity avatar, BlockPos station) {
        if (avatar.isSafeWorkPosition(station)) return true;
        // Scaffolding is intentionally occupied while climbing. The avatar's generic
        // standing test rejects its non-empty outline even though Minecraft permits
        // the entity inside it, which made a reviewed descent become "route damaged"
        // after the neighbouring temporary pieces had already been reclaimed.
        return ConstructionScaffolding.scaffold(avatar.getWorld(), station)
            && avatar.getWorld().getBlockState(station.up()).getCollisionShape(avatar.getWorld(), station.up()).isEmpty();
    }
    private static BlockPos replacementStation(ServerWorld world, AvatarEntity avatar,
            ConstructionAccessPlan access, ConstructionAccessPlan.Work work, Blueprint.Resolved resolved) {
        List<Blueprint.Cell> assembly;
        try { assembly = BlueprintAssembly.cells(work.cell(), resolved); }
        catch (IllegalArgumentException bad) { return null; }
        return ConstructionWorksite.stations(avatar, work.cell().pos(), resolved.bounds()).stream()
            .filter(candidate -> assembly.stream().allMatch(cell -> AccessRetreat.eyeReach(candidate, cell.pos())))
            .filter(candidate -> work.excavation() || assembly.stream().noneMatch(cell ->
                cell.pos().equals(candidate) || cell.pos().equals(candidate.up())))
            .filter(candidate -> {
                if (candidate.equals(avatar.getBlockPos())) return true;
                if (AccessRetreat.path(world, access, avatar.getBlockPos(), candidate) != null) return true;
                var path = avatar.getNavigation().findPathTo(candidate, 0);
                return path != null && path.reachesTarget();
            })
            .findFirst().orElse(null);
    }
    private static StepOutcome recenter(AvatarEntity avatar, BlockPos station) {
        avatar.stopMoving();
        // This method is only called after the body is already within the reviewed
        // station's 0.35-block arrival tolerance. A precise same-cell correction is
        // deterministic and prevents MoveControl from oscillating at block edges.
        avatar.repositionForConstruction(station.getX() + .5, station.getY(), station.getZ() + .5);
        avatar.setVelocity(Vec3d.ZERO);
        return StepOutcome.CONTINUE;
    }
    private static boolean safeToReclaimHere(ServerWorld world, AvatarEntity avatar, BlockPos pos) {
        if (!avatar.getBoundingBox().intersects(new Box(pos.up()))) return true;
        // Scaffolding is passable. At the base of a column an engineer may stand
        // inside it and remove it while the ordinary solid floor below remains.
        return avatar.getBlockPos().equals(pos)
            && (world.getBlockState(pos.down()).isOf(Blocks.SCAFFOLDING)
                || world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP));
    }
    private static StepOutcome reroute(AvatarEntity avatar, Task task, Progress p, long tick) {
        // Navigation can report AGENT_STUCK while the entity is still settling on a
        // newly placed scaffold. Recompute from the current feet cell inside this
        // execution attempt; a reviewed route should not pause the entire project for
        // one transient path-handle failure.
        if (++p.reroutes > 1) {
            p.program.assistance = true;
            p.program.recoveries++;
            p.program.recoveryReason = "ACCESS_ROUTE_BLOCKED";
            p.recoverySaved = false;
        }
        if (p.move != null) p.move.cancel();
        avatar.stopMoving();
        p.move = null;
        p.movingTo = null;
        p.walkTarget = null;
        p.route = List.of();
        p.waypoint = 0;
        p.since = tick;
        p.retryAt = tick + 5;
        p.measuredTarget = null; p.lastMovement = tick;
        return StepOutcome.CONTINUE;
    }
    private static StepOutcome recover(RuntimeServices services, Task task, Progress p, String reason) {
        if (!p.program.assistance) {
            p.program.assistance = true; p.program.recoveries++; p.program.recoveryReason = reason;
            org.slf4j.LoggerFactory.getLogger(AccessBuildExecutor.class).info(
                "Construction recovery project={} cursor={} reason={}", p.projectId, p.program.cursor, reason);
        }
        if (p.move != null) p.move.cancel();
        p.movingTo = null; p.walkTarget = null;
        if (!services.blueprints().save()) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
        p.recoverySaved = true;
        return StepOutcome.CONTINUE;
    }

    private static StepOutcome assistedCleanup(RuntimeServices services, Task task, Progress p, AvatarEntity avatar,
            ServerWorld world, Project project, ConstructionAccessPlan access, long tick) {
        if (access.placed.isEmpty()) {
            avatar.stopMoving();
            task.setWorkReport(new Task.WorkReport(dev.squire.server.profile.Track.BUILD.id(), p.placed, p.operationId));
            return StepOutcome.WORK_DONE;
        }
        if (tick < p.nextPlacementTick) return StepOutcome.CONTINUE;
        var positions = access.placed.keySet().stream().sorted(Comparator.comparingInt(BlockPos::getY).reversed()).toList();
        for (BlockPos pos : positions) {
            var cell = access.temporaryCell(pos);
            if (cell == null || !BlueprintManager.matches(world.getBlockState(pos), cell)) {
                access.placed.remove(pos); save(services); return StepOutcome.CONTINUE;
            }
            if (!ConstructionScaffolding.safeRemoval(world, pos)) continue;
            if (!services.protection().canBreak(world, pos, project.ownerId).allowed()) return fail(task, "PROTECTED");
            var changes = Map.of(pos, Blocks.AIR.getDefaultState());
            if (ConstructionRecovery.occupied(world, avatar, changes)) return fail(task, "CONSTRUCTION_OCCUPIED");
            if (ConstructionRecovery.prepare(avatar, access.bounds, pos, changes) == null) return fail(task, "CONSTRUCTION_NO_SAFE_ANCHOR");
            avatar.setActivityDetail("辅助回收施工设施");
            boolean owner = access.placed.get(pos);
            if (!services.beginProjectMutation(project.projectId, "reclaim:" + pos.asLong())) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            if (!world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL)) {
                services.completeProjectMutation(project.projectId); return fail(task, "PLACE_FAILED");
            }
            access.placed.remove(pos);
            var item = BlueprintManager.itemId(cell.blockId());
            project.reserve(owner ? Map.of(item, 1) : Map.of(), owner ? Map.of() : Map.of(item, 1));
            if (!services.completeProjectMutation(project.projectId)) return fail(task, "CONSTRUCTION_CHECKPOINT_FAILED");
            p.nextPlacementTick = tick + BlueprintBuildExecutor.placementInterval(avatar.profile());
            save(services); return StepOutcome.CONTINUE;
        }
        return fail(task, "CONSTRUCTION_CLEANUP_DEPENDENCY");
    }
    private static StepOutcome fail(Task task, String code) { task.setLastErrorCode(code); return StepOutcome.FAILED; }
    private static void save(RuntimeServices services) { services.blueprints().save(); services.saveProjects(); }
}
