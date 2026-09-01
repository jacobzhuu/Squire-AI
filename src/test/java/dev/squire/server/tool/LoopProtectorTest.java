package dev.squire.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Loop budget caps from spec section 31. */
class LoopProtectorTest {

	@Test
	void equivalentCallCapTrips() {
		LoopProtector lp = new LoopProtector();
		assertTrue(lp.checkAndCount("t", "a=1").isEmpty());
		assertTrue(lp.checkAndCount("t", "a=1").isEmpty());
		assertTrue(lp.checkAndCount("t", "a=1").isEmpty());
		assertEquals("maxEquivalentCalls=3 exceeded for t|a=1",
			lp.checkAndCount("t", "a=1").orElseThrow());
	}

	@Test
	void sameToolCapTripsAcrossDifferentArgs() {
		LoopProtector lp = new LoopProtector();
		for (int i = 0; i < LoopProtector.MAX_SAME_TOOL_CALLS; i++) {
			assertTrue(lp.checkAndCount("t", "a=" + i).isEmpty(), "call " + i);
		}
		assertTrue(lp.checkAndCount("t", "a=x").isPresent());
		// a different tool is unaffected by t's per-tool counter
		assertTrue(lp.checkAndCount("u", "a=x").isEmpty());
	}

	@Test
	void totalCallCapTrips() {
		LoopProtector lp = new LoopProtector();
		int accepted = 0;
		while (lp.checkAndCount("tool" + accepted, "-").isEmpty()) {
			accepted++;
			assertTrue(accepted <= LoopProtector.MAX_TOOL_CALLS + 1);
		}
		assertEquals(LoopProtector.MAX_TOOL_CALLS, accepted);
	}

	@Test
	void replanCapTripsAndResets() {
		LoopProtector lp = new LoopProtector();
		for (int i = 0; i < LoopProtector.MAX_REPLANS; i++) {
			assertTrue(lp.checkAndCountReplan().isEmpty());
		}
		assertTrue(lp.checkAndCountReplan().isPresent());
		lp.reset();
		assertTrue(lp.checkAndCountReplan().isEmpty());
		assertFalse(lp.isExhausted());
	}
}
