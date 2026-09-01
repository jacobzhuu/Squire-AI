package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SquirePersonalityServiceTest {

	@Test
	void anInsufficientCombinedTotalProducesNoCommitPlan() {
		int[] available = {1, 2};
		assertNull(SquirePersonalityService.debitPlan(available, 4));
		assertArrayEquals(new int[] {1, 2}, available);
	}

	@Test
	void exactlyFourUsesSquireSlotsBeforePlayerSlots() {
		assertArrayEquals(new int[] {2, 1, 1},
			SquirePersonalityService.debitPlan(new int[] {2, 1, 8}, 4));
	}
}
