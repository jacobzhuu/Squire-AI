package dev.squire.server.runtime;

import dev.squire.server.blueprint.*;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.security.PermissionNodes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import java.util.*;

/** Owner-bound UI actions; terrain work uses the existing durable project escrow. */
public final class TerrainLevelingService {
    public enum Action { OPEN, WIDTH_DOWN, WIDTH_UP, DEPTH_DOWN, DEPTH_UP, DOWN, UP, NORTH, SOUTH, WEST, EAST, REFRESH }
    private TerrainLevelingService() { }
    private static SquireRuntime.ExecutionResult fail(String text) { return SquireRuntime.ExecutionResult.fail("feedback.terrain_blocked", text); }

    public static SquireRuntime.ExecutionResult act(ServerPlayerEntity owner, AvatarEntity avatar, Action action) {
        var rt = SquireRuntime.get();
        if (avatar == null || !rt.agents().isOwnerOf(owner.getUuid(), avatar)
                || avatar.getWorld() != owner.getWorld()) return fail("请从自己的工程师面板操作");
        var data = rt.professionOf(avatar);
        if (data == null || data.profession() != SquireProfession.ENGINEER || data.level < 1)
            return fail("平整地基需要工程师，Lv1 起所有等级均可使用");
        var world = (ServerWorld) owner.getWorld();
        var placement = rt.blueprints().activeOf(owner.getUuid()).orElse(null);
        if (action == Action.OPEN) {
            if (placement != null || rt.projects().activeOf(owner.getUuid()).isPresent()) return fail("请先完成或取消当前工地");
            Vec3d eye = owner.getCameraPosVec(1f);
            var hit = world.raycast(new RaycastContext(eye, eye.add(owner.getRotationVec(1f).multiply(32)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, owner));
            BlockPos selected = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : owner.getBlockPos().down();
            placement = new BlueprintPlacement(UUID.randomUUID(), owner.getUuid(), avatar.agentId(),
                new TerrainLeveling.Spec(7, 7).id(), world.getRegistryKey().getValue().toString(),
                selected.add(-3, 0, -3), Direction.NORTH, rt.currentTick());
            rt.blueprints().put(placement);
        } else {
            if (placement == null || !placement.agentId.equals(avatar.agentId()) || placement.committed()
                    || !placement.dimensionId.equals(world.getRegistryKey().getValue().toString())) return fail("没有属于这名工程师的可调整平地预览");
            var spec = TerrainLeveling.parse(placement.blueprintId).orElse(null);
            if (spec == null) return fail("当前预览不是平地工程");
            int width = spec.width(), depth = spec.depth();
            BlockPos origin = placement.origin;
            switch (action) {
                case WIDTH_DOWN -> width--; case WIDTH_UP -> width++;
                case DEPTH_DOWN -> depth--; case DEPTH_UP -> depth++;
                case DOWN -> origin = origin.down(); case UP -> origin = origin.up();
                case NORTH -> origin = origin.north(); case SOUTH -> origin = origin.south();
                case WEST -> origin = origin.west(); case EAST -> origin = origin.east();
                default -> { }
            }
            try { spec = new TerrainLeveling.Spec(width, depth); }
            catch (IllegalArgumentException invalid) { return fail(invalid.getMessage()); }
            placement.changeBlueprint(spec.id());
            placement.relocate(origin, Direction.NORTH);
        }
        refresh(owner, placement);
        rt.blueprints().save();
        return SquireRuntime.ExecutionResult.ok("feedback.terrain_preview", action == Action.OPEN
            ? "平地预览已建立，按实际地形挖高填低；在面板调整范围后即可开工。" : "");
    }

    public static TerrainLeveling.Plan refresh(ServerPlayerEntity owner, BlueprintPlacement placement) {
        var world = (ServerWorld) owner.getWorld();
        var plan = TerrainLeveling.plan(world, placement);
        var r = plan.resolved();
        String blocked = permissionIssue(owner, r);
        if (!blocked.isEmpty()) r = new Blueprint.Resolved(r.bounds(), r.toPlace(), r.toClear(),
            new ConstructionAccessPlan(List.of(), r.bounds(), blocked + (r.access().valid() ? "" : "；" + r.access().failure)), r.costPlan(), r.siteRequirements());
        placement.snapshot(r, false);
        placement.terrainReview = plan.fingerprint();
        return plan;
    }

    public static String permissionIssue(ServerPlayerEntity owner, Blueprint.Resolved r) {
        var rt = SquireRuntime.get(); var world = (ServerWorld) owner.getWorld();
        if (!BlueprintManager.pendingPlacements(world, r).isEmpty() && !rt.permissions().has(owner, PermissionNodes.WORLD_PLACE))
            return "填补需要放置方块权限";
        if (!BlueprintManager.pendingClear(world, r).isEmpty() && !rt.permissions().has(owner, PermissionNodes.WORLD_BREAK))
            return "挖除需要破坏方块权限";
        var region = rt.protectionAdapter.canEditRegion(world, r.bounds(), owner.getUuid());
        if (!region.allowed()) return "区域保护：" + region.reason();
        for (var p : r.toClear()) if (!world.getBlockState(p).isAir()
                && !rt.protectionAdapter.canBreak(world, p, owner.getUuid()).allowed()) return "不可挖除：" + p.toShortString();
        for (var c : r.toPlace()) if (!rt.protectionAdapter.canPlace(world, c.pos(), owner.getUuid()).allowed()
                || !world.getBlockState(c.pos()).isAir() && !rt.protectionAdapter.canBreak(world, c.pos(), owner.getUuid()).allowed())
            return "不可填补：" + c.pos().toShortString();
        return "";
    }

    /** Only expansion beyond the reviewed bounds requires another confirmation. */
    public static SquireRuntime.ExecutionResult checkConfirmation(ServerPlayerEntity owner, BlueprintPlacement placement) {
        var old = placement.snapshot();
        var plan = refresh(owner, placement);
        if (!placement.snapshot().access().valid()) return fail(placement.snapshot().access().failure);
        if (old != null && !old.bounds().contains(plan.resolved().bounds()))
            return fail("实际挖填范围变大，预览已刷新，请查看扩展范围后再次确认");
        if (plan.dig() == 0 && plan.fill() == 0) return fail("地面已平整，无需创建工程");
        return null;
    }
}
