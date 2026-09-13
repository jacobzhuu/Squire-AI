package dev.squire.server.runtime;

import dev.squire.server.blueprint.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/** Owner-only, unconfirmed settings. All derived terrain changes still require normal confirmation. */
public final class EngineerWaterSettings {
    private EngineerWaterSettings() { }
    public static SquireRuntime.ExecutionResult configure(ServerPlayerEntity player, String mode) {
        var rt = SquireRuntime.get();
        var placement = rt.blueprints().activeOf(player.getUuid()).orElse(null);
        if (placement != null && TerrainLeveling.parse(placement.blueprintId).isPresent()) return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "平地不处理液体，请先处理水域后刷新预览。");
        if (placement == null || placement.committed()) return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "请先创建未确认的工程预览；已开工的水域方案不可修改。");
        if (!player.getServerWorld().getRegistryKey().getValue().toString().equals(placement.dimensionId)) return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "请回到工程所在维度配置供水。");
        boolean artificial = placement.artificialWater(); BlockPos source = placement.waterSource();
        if (mode.equals("artificial")) artificial = true;
        else if (mode.equals("existing")) artificial = false;
        else if (mode.equals("toggle")) artificial = !artificial;
        else if (mode.equals("supplied")) source = null;
        else if (mode.equals("source")) {
            HitResult hit = player.raycast(5, 1, true);
            if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "请看向五格内的原版水源方块。");
            source = block.getBlockPos(); var world = player.getServerWorld();
            if (!world.getBlockState(source).isOf(net.minecraft.block.Blocks.WATER) || !world.getFluidState(source).isStill()
                    || source.getSquaredDistance(placement.origin) > 64 * 64)
                return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "只接受工程原点 64 格内的水源；不自动搜索或创造水源。");
            if (!rt.protectionAdapter.canBreak(world, source, player.getUuid()).allowed()) return SquireRuntime.ExecutionResult.fail("feedback.protected", "该水源不允许取水。");
        } else return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", "未知供水设置。");
        boolean oldArtificial = placement.artificialWater(); BlockPos oldSource = placement.waterSource();
        placement.configureWater(artificial, source);
        try {
            var resolved = rt.blueprints().preview(placement).orElseThrow();
            if (source != null && (BlueprintManager.footprint(resolved).contains(source) || resolved.bounds().contains(source))) throw new IllegalArgumentException("供水点必须在最终工程范围外，防止施工破坏或取走工程自身的水");
            if (!rt.blueprints().save()) throw new IllegalArgumentException("预览设置保存失败");
        } catch (RuntimeException invalid) {
            placement.configureWater(oldArtificial, oldSource);
            return SquireRuntime.ExecutionResult.fail("feedback.site_unsafe", invalid.getMessage());
        }
        return SquireRuntime.ExecutionResult.ok("feedback.project_preview", (artificial ? "人工水域：挖池、永久石砖围护和注水均计入预览。" : "保留现有水域选址要求。")
            + (source == null ? "分批交付满桶，成功放置后返还空桶。" : "指定取水点 " + source.toShortString() + "；至少准备一个空桶，按原版规则实际取水。"));
    }
}
