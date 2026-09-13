package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProviderRequestLimiterTest {
	@Test
	void appliesIndependentPlayerBudgetsInsideOneServerWindow() {
		ProviderRequestLimiter limiter = new ProviderRequestLimiter();
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();
		for (int i = 0; i < ProviderRequestLimiter.MAX_PER_PLAYER; i++) {
			assertTrue(limiter.tryAcquire(alice, 100 + i));
		}
		assertFalse(limiter.tryAcquire(alice, 120));
		assertTrue(limiter.tryAcquire(bob, 120));
	}

	@Test
	void enforcesServerBudgetAndReleasesSlotsAfterOneMinute() {
		ProviderRequestLimiter limiter = new ProviderRequestLimiter();
		for (int i = 0; i < ProviderRequestLimiter.MAX_PER_SERVER; i++) {
			assertTrue(limiter.tryAcquire(UUID.randomUUID(), 100));
		}
		assertFalse(limiter.tryAcquire(UUID.randomUUID(), 100));
		assertTrue(limiter.tryAcquire(UUID.randomUUID(),
			100 + ProviderRequestLimiter.WINDOW_TICKS));
	}
}
