package dev.squire.server.cbp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.util.math.BlockPos;

/**
 * M5b CBP tests (spec §50/§89/§94): command policy whitelist, spec validation,
 * workspace containment + persistence, registry lifecycle, and the materializer's
 * plan/refusal/expiry paths. Real-world placement is proven in GameTests — every
 * path here is world-free by design.
 */
class CbpTest {

	// ------------------------------------------------------------------ policy

	@Test
	void commandPolicyAcceptsWhitelistedFamilies() {
		assertTrue(CbpCommandPolicy.validate("give @p minecraft:bread 8").isEmpty());
		assertTrue(CbpCommandPolicy.validate("give @p bread 64").isEmpty());
		assertTrue(CbpCommandPolicy.validate(
			"effect give @p minecraft:speed 30 1").isEmpty());
		assertTrue(CbpCommandPolicy.validate(
			"effect give @p night_vision 300").isEmpty());
		assertTrue(CbpCommandPolicy.validate("summon minecraft:wolf ~ ~ ~").isEmpty());
		assertTrue(CbpCommandPolicy.validate("summon minecraft:villager ~2 ~ ~-3")
			.isEmpty());
		assertTrue(CbpCommandPolicy.validate("summon minecraft:cow").isEmpty());
	}

	@Test
	void commandPolicyRefusesEverythingElse() {
		assertPolicyError("say hello");                       // wrong family
		assertPolicyError("give @a bread 1");                 // wrong target
		assertPolicyError("give @p bread 0");                 // zero count
		assertPolicyError("give @p bread 65");                // over count
		assertPolicyError("give @p TNT_BOMB 1");              // junk id
		assertPolicyError("effect give @p wither 30");        // not beneficial
		assertPolicyError("effect give @p speed 0");          // zero duration
		assertPolicyError("effect give @p speed 999999");     // absurd duration
		assertPolicyError("effect clear @p");                 // wrong shape
		assertPolicyError("summon minecraft:creeper ~ ~ ~");  // hostile entity
		assertPolicyError("summon wolf 100 ~ ~");             // absolute coord
		assertPolicyError("summon wolf ~500 ~ ~");            // out-of-range offset
		assertPolicyError("give @p bread 1; say x");          // statement injection
		assertPolicyError("");                                // empty
		assertPolicyError(null);                              // null
	}

	private void assertPolicyError(String command) {
		assertTrue(CbpCommandPolicy.validate(command).isPresent(),
			"expected refusal for: " + command);
	}

	// ------------------------------------------------------------------ spec

	@Test
	void specValidationCatchesShapeProblems() {
		CbpSpec empty = CbpSpec.builder(owner(), agent(), "empty").build();
		assertFalse(empty.validate().isEmpty(), "no entries");

		BlockPos pos = new BlockPos(0, 64, 0);
		CbpSpec dup = CbpSpec.builder(owner(), agent(), "dup")
			.entry(pos, "minecraft:command_block", "give @p bread 1", false)
			.entry(pos, "minecraft:command_block", "give @p bread 2", false)
			.build();
		assertTrue(dup.validate().stream().anyMatch(e -> e.contains("duplicate")),
			"duplicate position refused");

		CbpSpec badType = CbpSpec.builder(owner(), agent(), "tnt")
			.entry(pos, "minecraft:tnt", "summon minecraft:cow", false)
			.build();
		assertTrue(badType.validate().size() == 1
			&& badType.validate().get(0).contains("command block"),
			"non-command-block type refused");

		var builder = CbpSpec.builder(owner(), agent(), "big");
		for (int i = 0; i < CbpSpec.MAX_BLOCKS + 1; i++) {
			builder.entry(new BlockPos(i % 8, 64, i / 8),
				"minecraft:command_block", "give @p bread 1", false);
		}
		assertTrue(builder.build().validate().stream()
			.anyMatch(e -> e.contains("max")), "over-cap refused");

		CbpSpec good = oneBlockSpec();
		assertTrue(good.validate().isEmpty());
	}

	@Test
	void footprintSpansAllEntries() {
		CbpSpec spec = CbpSpec.builder(owner(), agent(), "l-shape")
			.entry(new BlockPos(-3, 60, 10), "minecraft:command_block",
				"give @p bread 1", false)
			.entry(new BlockPos(4, 68, -7), "minecraft:command_block",
				"give @p bread 1", false)
			.build();
		var region = spec.footprint();
		assertEquals(new BlockPos(-3, 60, -7), region.min());
		assertEquals(new BlockPos(4, 68, 10), region.max());
	}

	// ------------------------------------------------------------------ workspace

	@Test
	void workspacePersistsAcrossRestart(@TempDir Path dir) {
		UUID owner = owner();
		CbpWorkspace first = new CbpWorkspace(() -> dir.resolve("workspaces.json"));
		assertTrue(first.set(owner, "minecraft:overworld",
			dev.squire.server.world.BoundedRegion.ofCorners(0, 0, 0, 10, 10, 10))
			.isEmpty());
		assertTrue(first.areaOf(owner).isPresent());

		CbpWorkspace recovered = new CbpWorkspace(() -> dir.resolve("workspaces.json"));
		assertEquals(1, recovered.load());
		var area = recovered.areaOf(owner).orElseThrow();
		assertEquals("minecraft:overworld", area.dimension());
		assertEquals(dev.squire.server.world.BoundedRegion.ofCorners(
			0, 0, 0, 10, 10, 10), area.region());

		assertTrue(recovered.clear(owner).isEmpty());
		assertTrue(recovered.areaOf(owner).isEmpty());
	}

	@Test
	void workspaceRefusesOversizeAreas() {
		CbpWorkspace workspace = new CbpWorkspace(() -> null);
		assertTrue(workspace.set(owner(), "minecraft:overworld",
			dev.squire.server.world.BoundedRegion.ofCorners(0, 0, 0, 100, 100, 100))
			.isPresent(), "32^3+ workspace refused");
	}

	// ------------------------------------------------------------------ registry

	@Test
	void registryLifecycleAndPersistence(@TempDir Path dir) {
		UUID owner = owner();
		Path file = dir.resolve("projects.json");
		CbpRegistry first = new CbpRegistry(() -> file);
		var project = new CbpRegistry.Project(UUID.randomUUID(), owner, "gate",
			"minecraft:overworld",
			dev.squire.server.world.BoundedRegion.ofCorners(0, 64, 0, 0, 64, 0),
			List.of(new BlockPos(0, 64, 0)), UUID.randomUUID(), false, 1234L);
		first.register(project);

		CbpRegistry recovered = new CbpRegistry(() -> file);
		assertEquals(1, recovered.load());
		var back = recovered.get(project.id()).orElseThrow();
		assertEquals(project.name(), back.name());
		assertEquals(project.positions(), back.positions());
		assertEquals(project.operationId(), back.operationId());

		recovered.setDisabled(project.id(), true);
		assertTrue(recovered.get(project.id()).orElseThrow().disabled());
		assertNotNull(recovered.remove(project.id()));
		assertTrue(recovered.get(project.id()).isEmpty());
		assertNull(recovered.remove(project.id()));
	}

	// ------------------------------------------------------------------ materializer

	@Test
	void planIsRefusedWhileDisabledOrUnplanned() {
		Harness h = new Harness();
		h.materializer.setEnabled(false); // §94 default anyway
		var disabled = h.materializer.plan(oneBlockSpec(h.owner), 10);
		assertFalse(disabled.ok());

		h.materializer.setEnabled(true);
		var noArea = h.materializer.plan(oneBlockSpec(h.owner), 11);
		assertFalse(noArea.ok());
		assertTrue(noArea.message().contains("workspace"),
			"refusal names the missing workspace");
	}

	@Test
	void planRefusesEntriesOutsideTheWorkspace() {
		Harness h = new Harness();
		h.setWorkspace(dev.squire.server.world.BoundedRegion.ofCorners(
			0, 60, 0, 16, 70, 16));
		CbpSpec outside = CbpSpec.builder(h.owner, h.agent, "far-away")
			.entry(new BlockPos(500, 64, 500), "minecraft:command_block",
				"give @p bread 1", false)
			.build();
		var result = h.materializer.plan(outside, 10);
		assertFalse(result.ok());
		assertTrue(result.message().contains("outside your workspace"));
		assertEquals(0, h.worldLookups.get(),
			"no world access needed to refuse an out-of-workspace entry");
		assertEquals(0, h.materializer.pendingCount(), "nothing pending");
	}

	@Test
	void validPlanIssuesConfirmationAndPlacesNothing() {
		Harness h = new Harness();
		h.setWorkspace(dev.squire.server.world.BoundedRegion.ofCorners(
			0, 60, 0, 16, 70, 16));
		var result = h.materializer.plan(oneBlockSpec(h.owner), 100);
		assertTrue(result.ok(), result.message());
		assertNotNull(result.confirmId(), "confirmation id returned");
		assertEquals(1, result.blocks());
		assertEquals(1, h.materializer.pendingCount());
		assertEquals(0, h.worldLookups.get(), "plan never touches a world");
		assertEquals(List.of(), h.placed, "nothing placed before confirmation");
		assertTrue(h.notifications.isEmpty());

		// ticking without confirmation places nothing and keeps the request alive
		h.materializer.tick(150);
		assertEquals(1, h.materializer.pendingCount());
		assertEquals(List.of(), h.placed);
	}

	@Test
	void unconfirmedPlanExpiresQuietly() {
		Harness h = new Harness();
		h.setWorkspace(dev.squire.server.world.BoundedRegion.ofCorners(
			0, 60, 0, 16, 70, 16));
		var result = h.materializer.plan(oneBlockSpec(h.owner), 0);
		assertTrue(result.ok());
		h.materializer.tick(dev.squire.server.security.ConfirmationService.DEFAULT_TTL_TICKS);
		assertEquals(0, h.materializer.pendingCount(), "expired pendings vanish");
		assertEquals(List.of(), h.placed);
	}

	@Test
	void confirmingWithWrongPlayerNeverStartsPlacement() {
		Harness h = new Harness();
		h.setWorkspace(dev.squire.server.world.BoundedRegion.ofCorners(
			0, 60, 0, 16, 70, 16));
		var result = h.materializer.plan(oneBlockSpec(h.owner), 10);
		assertTrue(result.ok());
		String forged = h.confirmations.confirm(UUID.randomUUID(), result.confirmId(),
			20);
		assertTrue(forged.contains("another player"), "forged confirm refused: "
			+ forged);
		h.materializer.tick(30);
		assertEquals(List.of(), h.placed);
		assertEquals(1, h.materializer.pendingCount(), "request survives forgery");
	}

	@Test
	void placementWithoutALoadedDimensionFailsCleanly() {
		Harness h = new Harness();
		h.setWorkspace(dev.squire.server.world.BoundedRegion.ofCorners(
			0, 60, 0, 16, 70, 16));
		var result = h.materializer.plan(oneBlockSpec(h.owner), 10);
		assertTrue(h.confirmations.confirm(h.owner, result.confirmId(), 15)
			.startsWith("[Squire] Confirmed"));
		h.materializer.tick(20); // WorldLookup returns null → clean failure
		assertEquals(0, h.materializer.pendingCount());
		assertEquals(List.of(), h.placed);
		assertEquals(1, h.notifications.size(), "owner notified of the failure");
		assertTrue(h.notifications.get(0).contains("NOT materialized"));
		assertTrue(h.auditText().contains("cbp"), "audited under cbp caller");
	}

	@Test
	void removeWithoutUndoDataIsDenied() {
		Harness h = new Harness();
		var stranger = h.materializer.remove(UUID.randomUUID(), h.owner, false);
		assertFalse(stranger.ok());
	}

	@Test
	void fingerprintsBindConfirmationsToExactSpecs() {
		Harness h = new Harness();
		CbpSpec a = oneBlockSpec();
		CbpSpec b = CbpSpec.builder(a.ownerId(), a.agentId(), a.name())
			.entry(new BlockPos(2, 64, 2), "minecraft:command_block",
				"give @p bread 1", false)
			.build(); // different position → different fingerprint
		CbpSpec sameAsA = CbpSpec.builder(a.ownerId(), a.agentId(), a.name())
			.entry(new BlockPos(1, 64, 1), "minecraft:command_block",
				"give @p bread 1", false)
			.build();
		assertNotEquals(CbpMaterializer.fingerprint(a), CbpMaterializer.fingerprint(b));
		assertEquals(CbpMaterializer.fingerprint(a),
			CbpMaterializer.fingerprint(sameAsA));
		assertNotNull(h); // silence unused warning in odd IDE flows
	}

	// ------------------------------------------------------------------ harness

	private final UUID ownerField = UUID.randomUUID();

	private UUID owner() {
		return ownerField;
	}

	private UUID agent() {
		return UUID.nameUUIDFromBytes("agent".getBytes());
	}

	private CbpSpec oneBlockSpec() {
		return oneBlockSpec(ownerField);
	}

	private CbpSpec oneBlockSpec(UUID ownerId) {
		return CbpSpec.builder(ownerId, agent(), "welcome-gate")
			.entry(new BlockPos(1, 64, 1), "minecraft:command_block",
				"give @p bread 1", false)
			.build();
	}

	/** Real security services + recording seams; worlds are absent by design. */
	private static final class Harness {
		final UUID owner = UUID.randomUUID();
		final UUID agent = UUID.nameUUIDFromBytes("harness-agent".getBytes());
		final dev.squire.server.security.CapabilityStore capabilities =
			new dev.squire.server.security.CapabilityStore();
		final dev.squire.server.security.ConfirmationService confirmations =
			new dev.squire.server.security.ConfirmationService();
		final dev.squire.server.world.UndoJournal undo =
			new dev.squire.server.world.UndoJournal();
		final dev.squire.server.tool.AuditLog audit = new dev.squire.server.tool.AuditLog();
		final List<String> notifications = new ArrayList<>();
		final List<BlockPos> placed = new ArrayList<>();
		final java.util.concurrent.atomic.AtomicInteger worldLookups =
			new java.util.concurrent.atomic.AtomicInteger();
		final CbpWorkspace workspace;
		final CbpRegistry registry;
		final CbpMaterializer materializer;

		Harness() {
			Path nullFile = null;
			workspace = new CbpWorkspace(() -> nullFile);
			registry = new CbpRegistry(() -> nullFile);
			materializer = new CbpMaterializer(workspace, capabilities, confirmations,
				undo, registry, audit,
				dimension -> {
					worldLookups.incrementAndGet();
					return null; // unit tests have no loaded worlds
				},
				(ownerId, message) -> notifications.add(message));
			materializer.setEnabled(true);
		}

		void setWorkspace(dev.squire.server.world.BoundedRegion region) {
			assertTrue(workspace.set(owner, "minecraft:overworld", region).isEmpty());
		}

		String auditText() {
			StringBuilder sb = new StringBuilder();
			for (dev.squire.server.tool.AuditLog.Entry e : audit.snapshot()) {
				sb.append(e.callerKind()).append(' ').append(e.toolName())
					.append(' ');
			}
			return sb.toString();
		}
	}
}
