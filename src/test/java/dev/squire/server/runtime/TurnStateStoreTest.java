package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TurnStateStoreTest {
	@TempDir Path dir;

	@Test
	void waitingTurnRoundTripsCorrelationObservationsAndBudgets() {
		UUID turnId = UUID.randomUUID();
		UUID taskId = UUID.randomUUID();
		UUID callId = UUID.randomUUID();
		TurnRecord record = new TurnRecord(turnId, UUID.randomUUID(), UUID.randomUUID(),
			"检查机器", "provider", 10, 20, 1210,
			TurnRecord.State.WAIT_TOOL, 2, 7, 900, 1, "PATH_NOT_FOUND",
			List.of(taskId), List.of(callId),
			List.of("{\"status\":\"RUNNING\"}"), true);
		TurnStateStore store = new TurnStateStore(() -> dir.resolve("turns.json"));
		store.save(List.of(record));

		TurnRecord loaded = store.load().get(0);
		assertEquals(turnId, loaded.turnId());
		assertEquals(TurnRecord.State.WAIT_TOOL, loaded.state());
		assertEquals(2, loaded.replans());
		assertEquals(7, loaded.toolCalls());
		assertEquals(900, loaded.estimatedTokens());
		assertEquals(1, loaded.highRiskCalls());
		assertEquals(List.of(taskId), loaded.waitingTaskIds());
		assertEquals(List.of(callId), loaded.waitingCallIds());
		assertEquals(record.observations(), loaded.observations());
		assertEquals(true, loaded.waitingFailed());
	}

	@Test
	void futureSchemaFailsClosedAndIsNeverOverwritten() throws Exception {
		Path file = dir.resolve("turns.json");
		String future = "{\"version\":999,\"turns\":[]}";
		Files.writeString(file, future);
		TurnStateStore store = new TurnStateStore(() -> file);
		assertFalse(store.load().iterator().hasNext());
		store.save(List.of());
		assertEquals(future, Files.readString(file));
	}

	@Test
	void clarificationPlanRoundTripsWithoutPersistingRawTranscriptField() throws Exception {
		TurnRecord record = new TurnRecord(UUID.randomUUID(), UUID.randomUUID(),
			UUID.randomUUID(), "  给我   盖一座两层房子  ", "local", 10, 11, 12011,
			TurnRecord.State.CLARIFYING, 0, 0, 0, 0, null, List.of(), List.of(),
			List.of(), false, 1, "用什么墙体材料？", List.of("确认材料", "施工"));
		Path file = dir.resolve("turns.json");
		TurnStateStore store = new TurnStateStore(() -> file);
		store.save(List.of(record));

		String json = Files.readString(file);
		assertFalse(json.contains("originalInput"));
		assertTrue(json.contains("goalSummary"));
		TurnRecord loaded = store.load().get(0);
		assertEquals(TurnRecord.State.CLARIFYING, loaded.state());
		assertEquals("用什么墙体材料？", loaded.pendingQuestion());
		assertEquals(List.of("确认材料", "施工"), loaded.planSteps());
		assertEquals(1, loaded.providerRetries());
		assertEquals("给我 盖一座两层房子", loaded.originalInput());
	}
}
