package dev.squire.server.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.squire.server.profession.CombatStance;
import dev.squire.server.profession.ProfessionConfig;

/** Locks down the values measured by Guard Combat Balance Validation v1. */
class GuardCombatBalanceContractTest {

	private final ProfessionConfig config = ProfessionConfig.defaults();

	@Test
	void recordsTheCompleteCurrentHealthAndDamageCurves() {
		assertEquals("20,20,22,22,24,24,26,26,28,30", levels(true));
		assertEquals("0.0,0.0,0.0,0.0,0.5,0.5,0.5,1.0,1.0,1.5",
			levels(false));
	}

	@Test
	void recordsCurrentHealingAndDefensiveTiming() {
		assertEquals(0.84, config.guardFoodHealThreshold);
		assertEquals(0.35, config.guardPotionHealThreshold);
		assertEquals(0.20, config.guardRetreatThreshold);
		assertEquals(60, config.guardConsumableCooldownTicks);
		assertEquals(20, config.guardShieldHoldTicks);
		assertEquals(0.40, config.guardOwnerEmergencyHealthFraction);
		assertEquals(3, config.guardOwnerEmergencyThreatCount);
	}

	@Test
	void recordsCurrentStanceDifferences() {
		assertEquals(0.75, config.chaseFactor(CombatStance.DEFENSIVE));
		assertEquals(1.50, config.chaseFactor(CombatStance.BALANCED));
		assertEquals(2.50, config.chaseFactor(CombatStance.AGGRESSIVE));
		assertEquals(0.10, CombatStance.DEFENSIVE.retreatDelta());
		assertEquals(0.00, CombatStance.BALANCED.retreatDelta());
		assertEquals(-0.08, CombatStance.AGGRESSIVE.retreatDelta());
	}

	@Test
	void structuredResultsDefensivelyCopyRemainingResources() {
		Map<String, Integer> resources = new LinkedHashMap<>();
		resources.put("arrows", 12);
		CombatBalanceResult result = result(resources);
		resources.put("arrows", 99);

		assertEquals(12, result.remainingResources().get("arrows"));
		assertThrows(UnsupportedOperationException.class,
			() -> result.remainingResources().put("food", 1));
	}

	private String levels(boolean health) {
		StringBuilder out = new StringBuilder();
		for (int level = 1; level <= 10; level++) {
			if (level > 1) {
				out.append(',');
			}
			if (health) {
				out.append(config.guardMaxHealth(level));
			} else {
				out.append(config.guardBonusDamage(level));
			}
		}
		return out.toString();
	}

	private static CombatBalanceResult result(Map<String, Integer> resources) {
		return new CombatBalanceResult("test", 1, "Naked", "balanced", "WIN",
			20, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0,
			1, 2, 1, 19, 0, resources);
	}
}
