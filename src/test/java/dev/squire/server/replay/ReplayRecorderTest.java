package dev.squire.server.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** §76 replay: redaction, schemaVersion, enable-flag default OFF, bounded files. */
class ReplayRecorderTest {

	@TempDir
	Path dir;

	@Test
	void disabledByDefaultRecordsNothing() {
		Path file = dir.resolve("replay.jsonl");
		ReplayRecorder r = new ReplayRecorder(() -> file);
		assertFalse(r.isEnabled());
		r.recordSimple("turn_start", "input", "hello");
		assertEquals(0, r.flush());
	}

	@Test
	void eventsLandAsSchemaVersionedJsonLines() throws Exception {
		Path file = dir.resolve("replay.jsonl");
		ReplayRecorder r = new ReplayRecorder(() -> file);
		r.setEnabled(true);
		r.recordSimple("turn_start", "input", "给我面包");
		r.recordSimple("tool_decision", "tool", "give");
		assertEquals(2, r.flush());
		List<String> lines = Files.readAllLines(dir.resolve("replay-1.jsonl"),
			StandardCharsets.UTF_8);
		assertEquals(2, lines.size());
		for (String line : lines) {
			var o = JsonParser.parseString(line).getAsJsonObject();
			assertEquals(1, o.get("schemaVersion").getAsInt());
			assertTrue(o.has("type"));
			assertTrue(o.has("data"));
		}
		assertEquals("turn_start",
			JsonParser.parseString(lines.get(0)).getAsJsonObject().get("type").getAsString());
	}

	@Test
	void sessionFilesRotateByNumber() throws Exception {
		Path file = dir.resolve("replay-session.jsonl");
		ReplayRecorder r = new ReplayRecorder(() -> file);
		r.setEnabled(true);
		r.recordSimple("e", "k", "v1");
		r.flush();
		r.setEnabled(false);
		r.setEnabled(true); // session 2
		r.recordSimple("e", "k", "v2");
		r.flush();
		List<Path> files = Files.list(dir).collect(Collectors.toList());
		assertEquals(2, files.size(), files.toString());
		assertTrue(Files.exists(dir.resolve("replay-session-1.jsonl")));
		assertTrue(Files.exists(dir.resolve("replay-session-2.jsonl")));
	}

	@Test
	void redactorMasksCredentialShapedKeysRecursively() {
		JsonObject inner = new JsonObject();
		inner.addProperty("mcp_credential", "super-secret-value");
		inner.addProperty("harmless", "visible");
		JsonObject payload = new JsonObject();
		payload.addProperty("apiKey", "sk-abc123");
		payload.addProperty("Authorization", "Bearer xyz");
		payload.add("nested", inner);

		JsonObject scrubbed = ReplayRecorder.Redactor.scrub(payload);
		assertEquals("[REDACTED]", scrubbed.get("apiKey").getAsString());
		assertEquals("[REDACTED]", scrubbed.get("Authorization").getAsString());
		assertEquals("[REDACTED]",
			scrubbed.getAsJsonObject("nested").get("mcp_credential").getAsString());
		assertEquals("visible",
			scrubbed.getAsJsonObject("nested").get("harmless").getAsString());
	}

	@Test
	void recordedPayloadsNeverContainSecretValues() throws Exception {
		Path file = dir.resolve("replay.jsonl");
		ReplayRecorder r = new ReplayRecorder(() -> file);
		r.setEnabled(true);
		JsonObject payload = new JsonObject();
		payload.addProperty("api_key", "sk-live-secret");
		payload.addProperty("input", "normal text stays");
		r.record("turn_start", payload);
		r.flush();
		String disk = Files.readString(dir.resolve("replay-1.jsonl"),
			StandardCharsets.UTF_8);
		assertFalse(disk.contains("sk-live-secret"), "secret leaked to disk!");
		assertTrue(disk.contains("normal text stays"));
		assertTrue(disk.contains("[REDACTED]"));
	}
}
