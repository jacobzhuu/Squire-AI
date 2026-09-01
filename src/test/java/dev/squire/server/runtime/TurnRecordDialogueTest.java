package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TurnRecordDialogueTest {
	private static TurnRecord turn() {
		return new TurnRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			"盖一座房子", "local", 10, 10, 1210, TurnRecord.State.UNDERSTAND,
			0, 0, 0, 0, null, List.of(), List.of(), List.of(), false);
	}

	@Test
	void clarificationIsResumableAndCarriesAVisiblePlan() {
		TurnRecord turn = turn();
		turn.setPlan(List.of("确认材料", "施工"), 11);
		turn.awaitClarification("墙用什么材料？", 12011, 11);
		assertEquals(TurnRecord.State.CLARIFYING, turn.state());
		assertFalse(turn.isTerminal());
		assertEquals("墙用什么材料？", turn.pendingQuestion());
		assertEquals(List.of("确认材料", "施工"), turn.planSteps());

		turn.resumeClarification(1212, 12);
		assertEquals(TurnRecord.State.REPLAN, turn.state());
		assertNull(turn.pendingQuestion());
	}

	@Test
	void switchingProviderResetsOnlyThePerEndpointRetryBudget() {
		TurnRecord turn = turn();
		turn.incrementProviderRetry(11);
		assertEquals(1, turn.providerRetries());
		turn.switchProvider("cloud", 12);
		assertEquals("cloud", turn.providerId());
		assertEquals(0, turn.providerRetries());
	}
}
