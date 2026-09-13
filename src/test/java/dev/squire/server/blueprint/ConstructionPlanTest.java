package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.squire.server.world.BoundedRegion;
import net.minecraft.util.math.BlockPos;

class ConstructionPlanTest {

	private static Blueprint.Cell cell(int x, int y, int z, boolean optional) {
		return new Blueprint.Cell(new BlockPos(x, y, z), "minecraft:stone", Map.of(),
			0, "test", optional);
	}

	@Test
	void supportFoundationStructureAndDetailsHaveSeparatePhases() {
		Blueprint.Cell foundation = cell(0, 0, 0, false);
		Blueprint.Cell wall = cell(0, 1, 0, false);
		Blueprint.Cell detail = cell(1, 1, 0, true);
		Blueprint.Cell support = new Blueprint.Cell(new BlockPos(0, -1, 0),
			"minecraft:stone", Map.of(), Integer.MIN_VALUE, "support", false);
		Blueprint.Resolved resolved = new Blueprint.Resolved(
			BoundedRegion.ofCorners(0, 0, 0, 1, 1, 0),
			List.of(foundation, wall, detail), List.of());

		ConstructionPlan plan = ConstructionPlan.create(resolved,
			List.of(detail, wall, support, foundation));

		assertEquals(List.of(ConstructionPlan.Phase.SUPPORT,
			ConstructionPlan.Phase.FOUNDATION, ConstructionPlan.Phase.STRUCTURE,
			ConstructionPlan.Phase.DETAIL),
			plan.entries().stream().map(ConstructionPlan.Entry::phase).toList());
		assertEquals(-1, plan.bounds().min().getY(),
			"the shared project bounds include generated foundation support");
	}

	@Test
	void verticalDependenciesAreAlwaysPlannedBeforeTheirConsumers() {
		List<Blueprint.Cell> tower = List.of(cell(0, 0, 0, false),
			cell(0, 1, 0, false), cell(0, 2, 0, false));
		Blueprint.Resolved resolved = new Blueprint.Resolved(
			BoundedRegion.ofCorners(0, 0, 0, 0, 2, 0), tower, List.of());

		ConstructionPlan plan = ConstructionPlan.create(resolved,
			List.of(tower.get(2), tower.get(0), tower.get(1)));
		for (ConstructionPlan.Entry entry : plan.entries()) {
			if (entry.supportBelow() == null) continue;
			int dependency = indexOf(plan, entry.supportBelow());
			assertTrue(dependency >= 0 && dependency < entry.index(),
				"a falling/attached upper cell cannot precede its structural support");
		}
	}

	@Test
	void rowsRunFromTheBackTowardsTheLowestBoundaryOpeningDeterministically() {
		List<Blueprint.Cell> floor = new ArrayList<>();
		for (int z = 0; z < 3; z++) {
			for (int x = 0; x < 3; x++) floor.add(cell(x, 0, z, false));
		}
		Blueprint.Resolved resolved = new Blueprint.Resolved(
			BoundedRegion.ofCorners(0, 0, 0, 2, 2, 2), floor,
			List.of(new BlockPos(1, 1, 0)));
		List<Blueprint.Cell> shuffled = new ArrayList<>(floor);
		Collections.reverse(shuffled);

		ConstructionPlan first = ConstructionPlan.create(resolved, floor);
		ConstructionPlan second = ConstructionPlan.create(resolved, shuffled);
		assertEquals(first.cells(), second.cells(), "input iteration order must not leak into recovery");
		assertEquals(2, first.cells().get(0).pos().getZ(),
			"with a north-side door, the far southern row is built first");
		assertEquals(0, first.cells().get(first.size() - 1).pos().getZ(),
			"the row beside the exit is closed last");
	}

	private static int indexOf(ConstructionPlan plan, BlockPos pos) {
		for (ConstructionPlan.Entry entry : plan.entries()) {
			if (entry.cell().pos().equals(pos)) return entry.index();
		}
		return -1;
	}
}
