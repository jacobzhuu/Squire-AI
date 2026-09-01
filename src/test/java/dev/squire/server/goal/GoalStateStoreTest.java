package dev.squire.server.goal;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalStateStoreTest {
	@TempDir Path dir;

	@Test
	void activeGoalRoundTripsWithRemainingDagAndReplanBudget() {
		UUID goalId = UUID.randomUUID();
		UUID taskId = UUID.randomUUID();
		GoalRecord record = new GoalRecord(goalId, UUID.randomUUID(), UUID.randomUUID(),
			GoalRecord.Kind.ACQUIRE_AND_GIVE, "minecraft:torch", 32, 40,
			10, 12, GoalRecord.State.RUNNING, 1, "AGENT_STUCK", List.of(taskId));
		GoalStateStore store = new GoalStateStore(() -> dir.resolve("goals.json"));
		store.save(List.of(record));

		GoalRecord loaded = store.load().get(0);
		assertEquals(goalId, loaded.goalId());
		assertEquals(32, loaded.requestedCount());
		assertEquals(40, loaded.targetFinalCount());
		assertEquals(1, loaded.replans());
		assertEquals(List.of(taskId), loaded.remainingTaskIds());
	}

	@Test
	void futureSchemaFailsClosedWithoutOverwritingIt() throws Exception {
		Path file = dir.resolve("goals.json");
		String future = "{\"version\":999,\"goals\":[]}";
		Files.writeString(file, future);
		GoalStateStore store = new GoalStateStore(() -> file);
		assertTrue(store.load().isEmpty());
		store.save(List.of());
		assertEquals(future, Files.readString(file));
	}
}
