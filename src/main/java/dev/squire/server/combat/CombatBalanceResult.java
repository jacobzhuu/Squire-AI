package dev.squire.server.combat;

import java.util.Map;

/**
 * One structured, assertion-free combat balance measurement.
 *
 * <p>The GameTest suite writes these records to JSON.  Balance tests deliberately
 * keep outcomes such as fight duration and remaining health as measurements rather
 * than turning an unvalidated number into a pass/fail target.</p>
 */
public record CombatBalanceResult(
		String scenario,
		int guardLevel,
		String loadout,
		String stance,
		String result,
		long fightTicks,
		double damageDealt,
		double damageTaken,
		double ownerDamageTaken,
		int foodConsumed,
		int healingPotionsConsumed,
		double healingAmount,
		double potionWaste,
		int attemptsWhileOnCooldown,
		int arrowsConsumed,
		int shieldBlocks,
		int weaponSwitches,
		int hostileEngagements,
		int retreats,
		double averageDistanceFromOwner,
		double maxDistanceFromOwner,
		int weaponDurabilityLost,
		double remainingHp,
		double remainingAbsorption,
		Map<String, Integer> remainingResources) {

	public CombatBalanceResult {
		scenario = scenario == null ? "" : scenario;
		loadout = loadout == null ? "" : loadout;
		stance = stance == null ? "" : stance;
		result = result == null ? "" : result;
		remainingResources = remainingResources == null
			? Map.of() : Map.copyOf(remainingResources);
	}
}
