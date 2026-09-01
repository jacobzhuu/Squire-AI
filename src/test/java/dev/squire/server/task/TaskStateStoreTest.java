package dev.squire.server.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 A4：任务声明式快照的落盘/恢复往返。字段必须逐项保真；loadAndClear 只消费一次。
 */
class TaskStateStoreTest {
	@TempDir
	Path tempDir;

	@Test
	void snapshotRoundTripsThroughDisk() throws IOException {
		Path file = tempDir.resolve("squire").resolve("tasks.json");
		TaskStateStore store = new TaskStateStore(() -> file);

		UUID taskId = UUID.randomUUID();
		UUID agentId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("x", 12.5);
		params.put("item", "minecraft:oak_log");
		TaskStateStore.Snapshot original = new TaskStateStore.Snapshot(
			taskId, agentId, ownerId, "navigation.move_to", "P3_USER_TASK", "RUNNING",
			2, "walk to the oak grove", params,
			List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
			123456L, 800L, "RETRYING", "{\"step\":3}", 5, 40, false,
			"saved-policy");
		store.save(List.of(original));

		assertTrue(Files.exists(file), "save writes the file");
		List<TaskStateStore.Snapshot> loaded = store.loadAndClear();
		assertEquals(1, loaded.size());
		TaskStateStore.Snapshot s = loaded.get(0);
		assertEquals(taskId, s.taskId());
		assertEquals(agentId, s.agentId());
		assertEquals(ownerId, s.ownerId());
		assertEquals("navigation.move_to", s.type());
		assertEquals("P3_USER_TASK", s.priority());
		assertEquals("RUNNING", s.state());
		assertEquals(2, s.attemptsUsed());
		assertEquals("walk to the oak grove", s.goalDescription());
		assertEquals(12.5, ((Number) s.parameters().get("x")).doubleValue());
		assertEquals("minecraft:oak_log", s.parameters().get("item"));
		assertEquals(2, s.dependencies().size());
		assertEquals(123456L, s.createdTick());
		assertEquals(800L, s.timeoutTicks());
		assertEquals("RETRYING", s.lastErrorCode());
		assertEquals("{\"step\":3}", s.checkpoint());
		assertEquals(5, s.maxRetries());
		assertEquals(40, s.backoffTicks());
		assertEquals(false, s.interruptible());
		assertEquals("saved-policy", s.policySnapshot());

		// one-shot consumption: second load sees an empty world
		assertTrue(store.loadAndClear().isEmpty());
		assertTrue(!Files.exists(file), "consumed file is deleted");
	}

	@Test
	void corruptRowsAreSkippedGoodOnesSurvive() throws IOException {
		Path file = tempDir.resolve("tasks.json");
		TaskStateStore store = new TaskStateStore(() -> file);
		TaskStateStore.Snapshot good = new TaskStateStore.Snapshot(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			"navigation.move_to", "P3_USER_TASK", "READY", 0, null,
			Map.of("x", 1), new ArrayList<>(), 0L, 1200L, null, null,
			3, 20, true, "test");
		store.save(List.of(good));
		// inject a broken row alongside the good JSON
		String json = Files.readString(file);
		json = json.replace("\"tasks\": [", "\"tasks\": [ {\"bad\": true}, ");
		Files.writeString(file, json);

		List<TaskStateStore.Snapshot> loaded = store.loadAndClear();
		assertEquals(1, loaded.size(), "corrupt rows skipped, good row kept");
		assertEquals("restored task", loaded.get(0).goalDescription(),
			"missing goal gets the restore-safe default");
	}

	@Test
	void emptySaveThenLoadYieldsNothing() throws IOException {
		Path file = tempDir.resolve("tasks.json");
		TaskStateStore store = new TaskStateStore(() -> file);
		store.save(new ArrayList<>());
		assertTrue(store.loadAndClear().isEmpty());
	}
}
