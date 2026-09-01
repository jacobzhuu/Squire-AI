package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;

class ShortTermConversationStateStoreTest {

	@Test
	void locateVillageThenTeleportThereUsesTheStructuredTarget() {
		ShortTermConversationStateStore store = new ShortTermConversationStateStore();
		UUID squire = UUID.randomUUID();
		observeVillage(store, squire, 100L);
		store.observeAssistantMessage(squire,
			"最近的村庄在西北约 1227 格。要不要我带你过去，或者直接传送？", 101L);

		var resolved = store.resolve(squire, "把我传送过去", 102L);

		assertEquals(ShortTermConversationStateStore.ResolutionKind.EXECUTE,
			resolved.kind());
		assertEquals("player.teleport", resolved.toolName());
		assertEquals("minecraft:overworld", resolved.arguments().get("dimension"));
		assertEquals(-940, resolved.arguments().get("x"));
		assertEquals(71, resolved.arguments().get("y"));
		assertEquals(-783, resolved.arguments().get("z"));
		assertEquals(false, resolved.arguments().get("bringCompanion"));
	}

	@Test
	void teleportThereWithoutARecentTargetAsksForDestination() {
		ShortTermConversationStateStore store = new ShortTermConversationStateStore();

		var resolved = store.resolve(UUID.randomUUID(), "传送过去", 50L);

		assertEquals(ShortTermConversationStateStore.ResolutionKind.ASK, resolved.kind());
		assertTrue(resolved.prompt().contains("传到哪里"));
	}

	@Test
	void explicitNewDestinationInvalidatesThePendingTargetAndPassesThrough() {
		ShortTermConversationStateStore store = new ShortTermConversationStateStore();
		UUID squire = UUID.randomUUID();
		observeVillage(store, squire, 100L);
		store.observeAssistantMessage(squire, "要不要我直接传送你过去？", 101L);

		var resolved = store.resolve(squire, "不是那里，传我去基地", 102L);
		var state = store.snapshot(squire, 102L).orElseThrow();

		assertEquals(ShortTermConversationStateStore.ResolutionKind.NONE, resolved.kind(),
			"the explicit place must continue to the normal place-name parser");
		assertNull(state.pendingAction());
		assertNull(state.lastLocation());
		assertNull(state.lastTarget());
		assertNull(state.lastToolResult(), "the old locate payload must not leak back into a prompt");
	}

	@Test
	void stateIsIsolatedBySquireUuid() {
		ShortTermConversationStateStore store = new ShortTermConversationStateStore();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		observeVillage(store, first, 100L);
		store.observeAssistantMessage(first, "要不要我把你传送过去？", 101L);

		assertEquals(ShortTermConversationStateStore.ResolutionKind.EXECUTE,
			store.resolve(first, "传送过去", 102L).kind());
		assertEquals(ShortTermConversationStateStore.ResolutionKind.ASK,
			store.resolve(second, "传送过去", 102L).kind());
	}

	@Test
	void expiredPendingActionNeverExecutesEvenWhileSnapshotStillExists() {
		ShortTermConversationStateStore store =
			new ShortTermConversationStateStore(10L, 20L);
		UUID squire = UUID.randomUUID();
		observeVillage(store, squire, 100L);
		store.observeAssistantMessage(squire, "要不要我把你传送过去？", 101L);

		var resolved = store.resolve(squire, "传送过去", 111L);
		var state = store.snapshot(squire, 111L).orElseThrow();

		assertEquals(ShortTermConversationStateStore.ResolutionKind.ASK, resolved.kind());
		assertNull(state.pendingAction());
		assertTrue(state.lastLocation() != null,
			"the referent may remain available to the LLM after local execution expires");
	}

	@Test
	void completeLeadAndAttackProposalsAlsoResolveWithoutTheLlm() {
		ShortTermConversationStateStore store = new ShortTermConversationStateStore();
		UUID guide = UUID.randomUUID();
		observeVillage(store, guide, 100L);
		store.observeAssistantMessage(guide, "要不要我带你过去？", 101L);
		assertEquals("navigation.move_to",
			store.resolve(guide, "就去那里", 102L).toolName());

		UUID guard = UUID.randomUUID();
		ToolCall seen = ToolCall.of("entity.inspect",
			Map.of("entityId", "minecraft:creeper"));
		store.observeToolResult(guard, seen,
			ToolResult.success(seen.callId(), Map.of("visible", true)), 200L);
		store.observeAssistantMessage(guard, "要不要我帮你打掉它？", 201L);
		var attack = store.resolve(guard, "攻击刚才那个", 202L);
		assertEquals("combat.attack_target", attack.toolName());
		assertEquals("minecraft:creeper", attack.arguments().get("entityId"));
	}

	private static void observeVillage(ShortTermConversationStateStore store,
			UUID squire, long tick) {
		ToolCall locate = ToolCall.of("world.locate_structure",
			Map.of("structure", "#minecraft:village"));
		ToolResult result = ToolResult.success(locate.callId(), Map.of(
			"found", true,
			"structure", "minecraft:village_plains",
			"x", -940,
			"y", 71,
			"z", -783,
			"distance", 1227,
			"dimension", "minecraft:overworld"));
		store.observeToolResult(squire, locate, result, tick);
	}
}
