package dev.squire.server.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Read-only safety and usability assessment for a blueprint ghost. */
public record SiteAssessment(boolean executable, List<String> issues,
		int liquidCells, int unsupportedFloorCells, int occupiedCells,
		int livingEntities) {

	public SiteAssessment {
		issues = issues == null ? List.of() : List.copyOf(issues);
	}

	/** Conditions that need the player to intervene before work can safely begin. */
	public List<String> blockingIssues() {
		return livingEntities <= 0 ? List.of()
			: List.of("工地内有 " + livingEntities + " 个生物，请先让开");
	}

	public static SiteAssessment assess(ServerWorld world, Blueprint.Resolved resolved,
			Set<UUID> ignoredEntities) {
		BoundedRegion bounds = resolved.bounds();
		int liquids = 0;
		for (BlockPos cursor : BlueprintManager.footprint(resolved)) {
			if (!world.getFluidState(cursor).isEmpty()) liquids++;
		}

		int unsupported = BlueprintManager.automaticSiteSupports(world, resolved).size();

		Box box = new Box(bounds.min().getX(), bounds.min().getY(), bounds.min().getZ(),
			bounds.max().getX() + 1.0, bounds.max().getY() + 1.0,
			bounds.max().getZ() + 1.0);
		Set<UUID> ignored = ignoredEntities == null ? Set.of() : ignoredEntities;
		int entities = world.getOtherEntities(null, box,
			entity -> entity instanceof LivingEntity && !ignored.contains(entity.getUuid()))
			.size();
		int occupied = BlueprintManager.pendingClear(world, resolved).size();

		List<String> issues = new ArrayList<>();
		if (liquids > 0) issues.add("工地内有 " + liquids + " 格液体；蓝图目标液体和水域要求保留，仅清理确认范围内的冲突");
		if (unsupported > 0) issues.add("地基下有 " + unsupported
			+ " 格缺失或积水，将自动补齐已计价的承重基础");
		if (entities > 0) issues.add(TerrainLeveling.isTerrain(resolved)
			? "范围内有 " + entities + " 个生物，不影响开工；填补时会检查具体施工位置"
			: "工地内有 " + entities + " 个生物，请先让开");
		if (occupied > 0) issues.add("开工前会清理 " + occupied + " 个冲突方块");
		return new SiteAssessment(entities == 0 || TerrainLeveling.isTerrain(resolved),
			issues, liquids, unsupported, occupied, entities);
	}
}
