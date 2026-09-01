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

	public static SiteAssessment assess(ServerWorld world, Blueprint.Resolved resolved,
			Set<UUID> ignoredEntities) {
		BoundedRegion bounds = resolved.bounds();
		int liquids = 0;
		for (BlockPos cursor : bounds.cells()) {
			if (!world.getFluidState(cursor).isEmpty()) liquids++;
		}

		int unsupported = 0;
		int floorY = bounds.min().getY() - 1;
		for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
			for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
				BlockPos below = new BlockPos(x, floorY, z);
				if (world.getBlockState(below).isAir()
						|| !world.getFluidState(below).isEmpty()) unsupported++;
			}
		}

		Box box = new Box(bounds.min().getX(), bounds.min().getY(), bounds.min().getZ(),
			bounds.max().getX() + 1.0, bounds.max().getY() + 1.0,
			bounds.max().getZ() + 1.0);
		Set<UUID> ignored = ignoredEntities == null ? Set.of() : ignoredEntities;
		int entities = world.getOtherEntities(null, box,
			entity -> entity instanceof LivingEntity && !ignored.contains(entity.getUuid()))
			.size();
		int occupied = BlueprintManager.pendingClear(world, resolved).size();

		List<String> issues = new ArrayList<>();
		if (liquids > 0) issues.add("工地范围内有 " + liquids + " 格液体，请换到干燥地面");
		if (unsupported > 0) issues.add("地基下有 " + unsupported + " 格悬空或积水");
		if (entities > 0) issues.add("工地内有 " + entities + " 个生物，请先让开");
		if (occupied > 0) issues.add("开工前会清理 " + occupied + " 个冲突方块");
		return new SiteAssessment(liquids == 0 && unsupported == 0 && entities == 0,
			issues, liquids, unsupported, occupied, entities);
	}
}
