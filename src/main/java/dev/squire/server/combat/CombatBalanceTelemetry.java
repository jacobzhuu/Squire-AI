package dev.squire.server.combat;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.squire.server.item.Remedies;

/**
 * Opt-in counters used by combat balance GameTests.
 *
 * <p>There is no permanently running profiler and no combat decision reads these
 * values.  A session exists only between {@link #begin} and {@link #finish}; outside
 * that window every hook is a single map lookup and a no-op.  This keeps measurement
 * code from becoming a hidden balance mechanic.</p>
 */
public final class CombatBalanceTelemetry {

	private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();

	private CombatBalanceTelemetry() {
	}

	/** Start a fresh measurement for one materialized Guard. */
	public static void begin(UUID agentId) {
		if (agentId != null) {
			ACTIVE.put(agentId, new Session());
		}
	}

	public static boolean active(UUID agentId) {
		return agentId != null && ACTIVE.containsKey(agentId);
	}

	/** Remove a session without producing a result (setup failure / test cleanup). */
	public static void cancel(UUID agentId) {
		if (agentId != null) {
			ACTIVE.remove(agentId);
		}
	}

	public static void recordDamageTaken(UUID agentId, double actualDamage) {
		Session session = ACTIVE.get(agentId);
		if (session != null && actualDamage > 0) {
			session.damageTaken += actualDamage;
		}
	}

	public static void recordHealing(UUID agentId, double actualHealing) {
		Session session = ACTIVE.get(agentId);
		if (session != null && actualHealing > 0) {
			session.healingAmount += actualHealing;
		}
	}

	public static void recordShieldBlock(UUID agentId) {
		Session session = ACTIVE.get(agentId);
		if (session != null) {
			session.shieldBlocks++;
		}
	}

	public static void recordWeaponSwitch(UUID agentId) {
		Session session = ACTIVE.get(agentId);
		if (session != null) {
			session.weaponSwitches++;
		}
	}

	public static void recordConsumable(UUID agentId, Remedies.Remedy remedy,
			double missingHealthBeforeUse) {
		Session session = ACTIVE.get(agentId);
		if (session == null || remedy == null) {
			return;
		}
		if (remedy.use() == Remedies.Use.DRINK) {
			session.healingPotionsConsumed++;
			session.potionWaste += Math.max(0.0,
				remedy.expectedHeal() - Math.max(0.0, missingHealthBeforeUse));
		} else if (remedy.use() == Remedies.Use.EAT) {
			session.foodConsumed++;
		}
	}

	/** One SelfCareGoal poll that wanted a remedy but was still time-gated. */
	public static void recordCooldownAttempt(UUID agentId) {
		Session session = ACTIVE.get(agentId);
		if (session != null) {
			session.attemptsWhileOnCooldown++;
		}
	}

	/** Count each distinct hostile selected during the fight once. */
	public static void observeTarget(UUID agentId, UUID targetId) {
		Session session = ACTIVE.get(agentId);
		if (session != null && targetId != null) {
			session.engagedTargets.add(targetId);
		}
	}

	public static void recordRetreat(UUID agentId) {
		Session session = ACTIVE.get(agentId);
		if (session != null && !session.retreating) {
			session.retreating = true;
			session.retreats++;
		}
	}

	/** A recovered Guard can later enter a new, separately counted retreat episode. */
	public static void recordCombatReady(UUID agentId) {
		Session session = ACTIVE.get(agentId);
		if (session != null) {
			session.retreating = false;
		}
	}

	public static void observeOwnerDistance(UUID agentId, double distance) {
		Session session = ACTIVE.get(agentId);
		if (session == null || !Double.isFinite(distance) || distance < 0) {
			return;
		}
		session.distanceTotal += distance;
		session.distanceSamples++;
		session.maxDistance = Math.max(session.maxDistance, distance);
	}

	/**
	 * Close the session and combine exact hooks with scenario-level observations.
	 * Damage dealt is supplied by the scenario sampler so arrows, fire and other
	 * vanilla projectile effects are included as well as melee hits.
	 */
	public static CombatBalanceResult finish(UUID agentId, String scenario,
			int guardLevel, String loadout, String stance, String result,
			long fightTicks, double damageDealt, double ownerDamageTaken,
			int arrowsConsumed, int weaponDurabilityLost, double remainingHp,
			double remainingAbsorption, Map<String, Integer> remainingResources) {
		Session session = agentId == null ? null : ACTIVE.remove(agentId);
		if (session == null) {
			session = new Session();
		}
		double averageDistance = session.distanceSamples == 0 ? 0.0
			: session.distanceTotal / session.distanceSamples;
		return new CombatBalanceResult(scenario, guardLevel, loadout, stance, result,
			fightTicks, round(damageDealt), round(session.damageTaken),
			round(ownerDamageTaken), session.foodConsumed,
			session.healingPotionsConsumed, round(session.healingAmount),
			round(session.potionWaste), session.attemptsWhileOnCooldown,
			arrowsConsumed, session.shieldBlocks, session.weaponSwitches,
			session.engagedTargets.size(), session.retreats, round(averageDistance),
			round(session.maxDistance), weaponDurabilityLost, round(remainingHp),
			round(remainingAbsorption), remainingResources);
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	private static final class Session {
		double damageTaken;
		double healingAmount;
		double potionWaste;
		int foodConsumed;
		int healingPotionsConsumed;
		int attemptsWhileOnCooldown;
		int shieldBlocks;
		int weaponSwitches;
		int retreats;
		boolean retreating;
		final Set<UUID> engagedTargets = new LinkedHashSet<>();
		double distanceTotal;
		long distanceSamples;
		double maxDistance;
	}
}
