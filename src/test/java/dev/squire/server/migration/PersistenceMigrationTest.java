package dev.squire.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.squire.server.automation.AutomationEngine;
import dev.squire.server.automation.AutomationGraph;
import dev.squire.server.cbp.CbpRegistry;
import dev.squire.server.cbp.CbpWorkspace;
import dev.squire.server.mcp.McpTrustStore;
import dev.squire.server.security.ToolTrust;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.tool.AuditLog;
import dev.squire.server.world.BoundedRegion;

/**
 * §92 migration tests (M6): every persisted store must survive legacy files,
 * future version numbers, corrupt JSON and partially-corrupt entries without
 * throwing or losing the healthy rows. Recovery is always "load what parses,
 * start empty on garbage" — never a crashed server.
 */
class PersistenceMigrationTest {

	@TempDir
	Path dir;

	// ------------------------------------------------------------------ helpers

	private void write(String fileName, String content) throws IOException {
		Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ CBP registry

	private CbpRegistry registry(String file) {
		return new CbpRegistry(() -> dir.resolve(file));
	}

	@Test
	void cbpRegistryGarbageFileStartsEmptyWithoutThrowing() throws IOException {
		write("cbp-projects.json", "{{{not json at all");
		assertEquals(0, registry("cbp-projects.json").load());
	}

	@Test
	void cbpRegistryLegacyFileWithoutVersionStillLoads() throws IOException {
		// a v1.0-rc file written before the "version" field existed
		write("cbp-projects.json", """
			{"projects":[{"id":"%s","owner":"%s","name":"legacy","dimension":"minecraft:overworld",
			"minX":0,"minY":64,"minZ":0,"maxX":2,"maxY":64,"maxZ":2,
			"positions":[{"x":1,"y":64,"z":1}],"operation":"%s",
			"disabled":false,"placedAt":123}]}
			""".formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
		CbpRegistry r = registry("cbp-projects.json");
		assertEquals(1, r.load());
		assertEquals("legacy", r.all().get(0).name());
	}

	@Test
	void cbpRegistryFutureVersionAndCorruptRowKeepHealthyRows() throws IOException {
		String goodId = UUID.randomUUID().toString();
		String goodOwner = UUID.randomUUID().toString();
		String goodOp = UUID.randomUUID().toString();
		write("cbp-projects.json", """
			{"version":999,"projects":[
			{"id":"not-a-uuid"},
			{"id":"%s","owner":"%s","name":"healthy","dimension":"minecraft:overworld",
			"minX":0,"minY":64,"minZ":0,"maxX":1,"maxY":64,"maxZ":1,
			"positions":[],"operation":"%s","disabled":true,"placedAt":7}]}
			"""
			.formatted(goodId, goodOwner, goodOp));
		CbpRegistry r = registry("cbp-projects.json");
		assertEquals(1, r.load()); // the corrupt row is skipped, not fatal
		assertEquals(1, r.all().size());
		assertTrue(r.all().get(0).disabled());
	}

	// ------------------------------------------------------------------ workspaces

	@Test
	void workspaceGarbageFileStartsEmptyWithoutThrowing() throws IOException {
		write("workspaces.json", "][ broken ][");
		assertEquals(0, new CbpWorkspace(() -> dir.resolve("workspaces.json")).load());
	}

	@Test
	void workspaceLegacyFileWithoutVersionStillLoads() throws IOException {
		UUID owner = UUID.randomUUID();
		write("workspaces.json", """
			{"%s":{"dimension":"minecraft:overworld",
			"minX":-4,"minY":60,"minZ":-4,"maxX":4,"maxY":70,"maxZ":4}}
			""".formatted(owner));
		CbpWorkspace ws = new CbpWorkspace(() -> dir.resolve("workspaces.json"));
		assertEquals(1, ws.load());
		assertTrue(ws.areaOf(owner).isPresent());
	}

	@Test
	void workspaceMixedRowsKeepHealthyOnes() throws IOException {
		UUID owner = UUID.randomUUID();
		write("workspaces.json", """
			{"version":1,"%s":{"dimension":"minecraft:overworld",
			"minX":0,"minY":60,"minZ":0,"maxX":3,"maxY":60,"maxZ":3},
			"version-two":{"dimension":"minecraft:overworld","minX":0}}
			""".formatted(owner));
		CbpWorkspace ws = new CbpWorkspace(() -> dir.resolve("workspaces.json"));
		assertEquals(1, ws.load()); // the malformed row is skipped, not fatal
		assertTrue(ws.areaOf(owner).isPresent());
	}

	// ------------------------------------------------------------------ trust store

	@Test
	void trustStoreGarbageFileIsFailClosedUntrusted() throws IOException {
		write("mcp-trust.json", "<<<garbage>>>");
		McpTrustStore store = new McpTrustStore(dir.resolve("mcp-trust.json"));
		assertEquals(ToolTrust.UNTRUSTED, store.trustOf("anything"));
	}

	@Test
	void trustStoreUnknownEnumValueCannotGrantTrust() throws IOException {
		write("mcp-trust.json", "{\"srv\": \"TOTALLY_TRUSTED_LOL\"}");
		McpTrustStore store = new McpTrustStore(dir.resolve("mcp-trust.json"));
		// hand-edited values outside the enum can NEVER raise trust (§52)
		assertEquals(ToolTrust.UNTRUSTED, store.trustOf("srv"));
	}

	@Test
	void trustStoreValidLegacyFlatMapLoads() throws IOException {
		write("mcp-trust.json", "{\"local\":\"ADMIN_APPROVED_LOCAL\",\"bad\":\"BLOCKED\"}");
		McpTrustStore store = new McpTrustStore(dir.resolve("mcp-trust.json"));
		assertEquals(ToolTrust.ADMIN_APPROVED_LOCAL, store.trustOf("local"));
		assertEquals(ToolTrust.BLOCKED, store.trustOf("bad"));
	}

	// ------------------------------------------------------------------ automations

	private AutomationEngine engine(String file) {
		Path nullFile = null;
		return new AutomationEngine(
			(toolName, args, o, a) ->
				dev.squire.common.protocol.ToolResult.success(UUID.randomUUID(), Map.of()),
			(o, message) -> {
			},
			new AutomationEngine.WorldProbe() {
				@Override
				public boolean ownerOnline(UUID id) {
					return false;
				}

				@Override
				public java.util.Set<UUID> playersInRegion(String dimension,
						BoundedRegion region) {
					return java.util.Set.of();
				}

				@Override
				public long dayTime() {
					return 0;
				}
			},
			new TaskScheduler(), new AuditLog(), () -> dir.resolve(file));
	}

	@Test
	void automationsGarbageFileStartsEmptyWithoutThrowing() throws IOException {
		write("automations.json", "nope{");
		assertEquals(0, engine("automations.json").load(1000L));
	}

	@Test
	void automationsMissingArrayKeyStartsEmptyWithoutThrowing() throws IOException {
		write("automations.json", "{\"version\":1}");
		assertEquals(0, engine("automations.json").load(1000L));
	}

	@Test
	void automationsLegacyFileAndCorruptRowKeepHealthyGraph() throws IOException {
		var b = AutomationGraph
			.builder(UUID.randomUUID(), UUID.randomUUID(), "healthy")
			.trigger(dev.squire.server.automation.AutomationTrigger.manual());
		b.node(dev.squire.server.automation.AutomationNode.notify("hi"));
		AutomationGraph good = b.build();
		JsonArray arr = new JsonArray();
		arr.add(new JsonObject()); // structurally valid JSON, semantically empty
		arr.add("just a string"); // wrong element type entirely
		arr.add(good.toJson());
		JsonObject root = new JsonObject(); // no "version": legacy-shaped wrapper
		root.add("automations", arr);
		write("automations.json", root.toString());

		AutomationEngine e = engine("automations.json");
		assertEquals(1, e.load(5000L));
		assertEquals("healthy", e.ownedBy(good.ownerId()).get(0).name());
	}
}
