package dev.squire.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Audit persistence (spec §27/§93): JSONL append, flush cursor never duplicates. */
class AuditPersistenceTest {

	@TempDir
	Path tempDir;

	@Test
	void flushWritesJsonLinesAndNeverDuplicates() throws Exception {
		AuditLog log = new AuditLog();
		log.record(1L, "MODEL", "query.status", true, "SUCCESS");
		log.record(2L, "MODEL", "world.break_block", false, "permission_denied");
		log.record(3L, "ADMIN", "admin.tool", false, "policy_denied");

		Path file = tempDir.resolve("squire").resolve("audit.jsonl");
		assertEquals(3, log.appendJsonl(file));
		assertEquals(0, log.appendJsonl(file), "second flush must be a no-op");

		List<String> lines = Files.readAllLines(file);
		assertEquals(3, lines.size());
		assertTrue(lines.get(0).startsWith("{\"tick\":1,"));
		assertTrue(lines.get(1).contains("\"tool\":\"world.break_block\""));
		assertTrue(lines.get(1).contains("\"executed\":false"));
		assertTrue(lines.get(2).contains("policy_denied"));

		// later entries append after the cursor
		log.record(9L, "MODEL", "crafting.craft", true, "RUNNING");
		assertEquals(1, log.appendJsonl(file));
		lines = Files.readAllLines(file);
		assertEquals(4, lines.size());
		assertFalse(lines.get(3).contains("tick\":1,"));
	}

	@Test
	void emptyFlushCreatesNothing() throws Exception {
		AuditLog log = new AuditLog();
		Path file = tempDir.resolve("audit.jsonl");
		assertEquals(0, log.appendJsonl(file));
		assertFalse(Files.exists(file));
	}
}
