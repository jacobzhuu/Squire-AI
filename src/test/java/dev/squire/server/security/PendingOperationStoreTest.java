package dev.squire.server.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 F3：待确认的高风险操作必须跨重启存活，并且重放的是玩家看过的那一份
 * canonical 参数。参数在往返后仍然必须是数字——Tool 的参数校验按类型判定，
 * 一个变成字符串的坐标会让确认后的重放直接失败。
 */
class PendingOperationStoreTest {

	@TempDir
	Path dir;

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID AGENT = UUID.randomUUID();

	private PendingOperationStore store(String name) {
		return new PendingOperationStore(() -> dir.resolve(name));
	}

	private static PendingOperation fill(long now) {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("x1", 10);
		args.put("y1", 64);
		args.put("z1", -20);
		args.put("x2", 12);
		args.put("y2", 66);
		args.put("z2", -18);
		args.put("blockId", "minecraft:stone");
		return new PendingOperation(UUID.randomUUID(), OWNER, AGENT,
			"minecraft.command.fill", args, "fp-1", "preview text", now, now + 600,
			PendingOperation.Status.PENDING, null);
	}

	@Test
	void canonicalArgumentsSurviveARestartWithTheirTypes() {
		PendingOperationStore first = store("ops.json");
		PendingOperation issued = fill(100);
		first.put(issued);

		PendingOperationStore reloaded = store("ops.json");
		assertEquals(1, reloaded.load());
		PendingOperation back = reloaded.get(issued.confirmId()).orElseThrow();

		assertEquals("minecraft.command.fill", back.operationType());
		assertEquals("fp-1", back.fingerprint());
		assertEquals("preview text", back.preview());
		assertEquals(PendingOperation.Status.PENDING, back.status());
		assertInstanceOf(Integer.class, back.canonicalArguments().get("x1"),
			"coordinates must come back as numbers, not strings");
		assertEquals(10, back.canonicalArguments().get("x1"));
		assertEquals(-18, back.canonicalArguments().get("z2"));
		assertEquals("minecraft:stone", back.canonicalArguments().get("blockId"));
	}

	@Test
	void executedAndDeniedStatusesPersistSoAConfirmCannotBeReplayed() {
		PendingOperationStore first = store("ops.json");
		PendingOperation issued = fill(100);
		first.put(issued);
		first.update(issued.withStatus(PendingOperation.Status.EXECUTED, null));

		PendingOperationStore reloaded = store("ops.json");
		reloaded.load();
		assertEquals(PendingOperation.Status.EXECUTED,
			reloaded.get(issued.confirmId()).orElseThrow().status());
		assertFalse(reloaded.get(issued.confirmId()).orElseThrow().isPending(200));
	}

	@Test
	void expiryMarksPendingOperationsInsteadOfExecutingThem() {
		PendingOperationStore store = store("ops.json");
		PendingOperation issued = fill(100);
		store.put(issued);

		assertEquals(0, store.expireAllBefore(500), "not yet past the deadline");
		assertEquals(1, store.expireAllBefore(1000), "past the deadline it expires");
		assertEquals(PendingOperation.Status.EXPIRED,
			store.get(issued.confirmId()).orElseThrow().status());
		assertTrue(store.pendingFor(OWNER, 1000).isEmpty(),
			"an expired operation is no longer actionable");
	}

	@Test
	void oneOwnerCannotQueueUnboundedConfirmations() {
		PendingOperationStore store = store("ops.json");
		for (int i = 0; i < PendingOperationStore.MAX_PENDING_PER_OWNER + 5; i++) {
			store.put(fill(100 + i));
		}
		assertEquals(PendingOperationStore.MAX_PENDING_PER_OWNER, store.all().size());
	}

	@Test
	void aFutureSchemaLoadsReadOnlyInsteadOfBeingOverwritten() throws Exception {
		Path file = dir.resolve("ops.json");
		Files.writeString(file, "{\"version\":99,\"operations\":[]}",
			StandardCharsets.UTF_8);

		PendingOperationStore store = store("ops.json");
		assertEquals(0, store.load());
		store.put(fill(100));
		assertEquals("{\"version\":99,\"operations\":[]}",
			Files.readString(file, StandardCharsets.UTF_8),
			"a newer format on disk must never be clobbered");
	}

	@Test
	void corruptJsonLeavesAnEmptyStoreRatherThanThrowing() throws Exception {
		Files.writeString(dir.resolve("ops.json"), "{not json at all",
			StandardCharsets.UTF_8);
		assertEquals(0, store("ops.json").load());
	}
}
