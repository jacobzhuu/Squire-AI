package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.world.BoundedRegion;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Bounded work-position search used by the blueprint construction executor. */
final class ConstructionWorksite {

	static final double PLACE_REACH = 4.75;
	private static final double PLACE_REACH_SQ = PLACE_REACH * PLACE_REACH;
	private static final int HORIZONTAL_SEARCH = 4;
	private static final int MAX_STATIONS = 48;
	private static final double ESTIMATED_EYE_HEIGHT = 1.62;

	private ConstructionWorksite() { }

	/** Actual placement is gated by eye-to-cell reach, not by one earlier path result. */
	static boolean inReach(AvatarEntity avatar, BlockPos target) {
		return avatar.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target))
			<= PLACE_REACH_SQ;
	}

	/** Refuse to materialize a solid block through the worker's current body. */
	static boolean clearOfWorker(AvatarEntity avatar, BlockState target, BlockPos pos) {
		Box worker = avatar.getBoundingBox().contract(0.001);
		return target.getCollisionShape(avatar.getWorld(), pos, net.minecraft.block.ShapeContext.of(avatar)).getBoundingBoxes().stream()
			.map(box -> box.offset(pos)).noneMatch(worker::intersects);
	}

	/**
	 * Find walkable cells from which the target is within normal player-like reach.
	 * Outside stations receive a small preference so walls tend to be built from the
	 * exterior, but valid interior stations remain available for partitions and roofs.
	 */
	static List<BlockPos> stations(AvatarEntity avatar, BlockPos target,
			BoundedRegion buildingBounds) {
		List<Candidate> candidates = new ArrayList<>();
		// Reach is measured from the eyes: a block five levels above the feet
		// can still be within 4.75 blocks. Include those ground-level stations.
		for (int y = target.getY() - 5; y <= target.getY() + 3; y++) {
			for (int dz = -HORIZONTAL_SEARCH; dz <= HORIZONTAL_SEARCH; dz++) {
				for (int dx = -HORIZONTAL_SEARCH; dx <= HORIZONTAL_SEARCH; dx++) {
					BlockPos pos = new BlockPos(target.getX() + dx, y,
						target.getZ() + dz);
					if (pos.equals(target) || pos.up().equals(target)) continue;
					Vec3d estimatedEye = new Vec3d(pos.getX() + 0.5,
						pos.getY() + ESTIMATED_EYE_HEIGHT, pos.getZ() + 0.5);
					if (estimatedEye.squaredDistanceTo(Vec3d.ofCenter(target))
							> PLACE_REACH_SQ) continue;
					if (!avatar.isSafeWorkPosition(pos)) continue;
					double travel = avatar.squaredDistanceTo(pos.getX() + 0.5,
						pos.getY(), pos.getZ() + 0.5);
					double insidePenalty = buildingBounds != null
						&& buildingBounds.contains(pos) ? 9.0 : 0.0;
					candidates.add(new Candidate(pos.toImmutable(), travel + insidePenalty));
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(Candidate::score)
			.thenComparingInt(candidate -> candidate.pos().getY())
			.thenComparingInt(candidate -> candidate.pos().getX())
			.thenComparingInt(candidate -> candidate.pos().getZ()));
		return candidates.stream().limit(MAX_STATIONS).map(Candidate::pos).toList();
	}

	private record Candidate(BlockPos pos, double score) { }
}
