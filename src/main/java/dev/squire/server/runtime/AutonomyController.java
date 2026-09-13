package dev.squire.server.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.combat.CombatStyle;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.task.executors.OwnerAidExecutor;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Makes the first three autonomy levels concrete while never editing the world. */
public final class AutonomyController {

	private static final double DEFENCE_RANGE_SQ = 24.0 * 24.0;
	private final SquireRuntime runtime;
	private final Map<UUID, net.minecraft.util.math.Vec3d> defenceAnchors = new HashMap<>();
    private final Map<UUID, Integer> defenceAttackTimes = new HashMap<>();
    private final Map<UUID, Boolean> defenceAbandoned = new HashMap<>();
    private final Map<UUID, Long> nextAidTick = new HashMap<>();
	private final Map<UUID, Long> nextThreatReportTick = new HashMap<>();
	private final Map<UUID, Integer> lastOwnerAttackTime = new HashMap<>();
	private final Map<UUID, UUID> cooperativeHuntTarget = new HashMap<>();

	AutonomyController(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	void tick(long tick) {
		for (UUID ownerId : runtime.agents().knownOwners()) {
			ServerPlayerEntity owner = runtime.server.getPlayerManager().getPlayer(ownerId);
			if (owner == null || !owner.isAlive()) {
				continue;
			}
			for (AvatarEntity avatar : runtime.agents().resolveAllForOwner(ownerId)) {
			if (!avatar.isAlive() || avatar.oathActive()) continue;
			var profile = runtime.profileOf(avatar);
			boolean engineer = profile != null && profile.profession.profession()
				== dev.squire.server.profession.SquireProfession.ENGINEER;
			// Untrained companions retain the legacy generalist behavior.  Choosing
			// ENGINEER is the point where proactive guard duties are removed.
			boolean guard = !engineer;
			AutonomyLevel level = profile == null ? AutonomyLevel.STANDARD
				: profile.autonomyLevel();
			if (!level.atLeast(AutonomyLevel.STANDARD)) continue;
			boolean idle = runtime.scheduler().current(avatar.agentId()).isEmpty();
			if (!canHunt(profile) || !level.atLeast(AutonomyLevel.PROACTIVE) || !idle) {
				cooperativeHuntTarget.remove(avatar.agentId());
				lastOwnerAttackTime.put(avatar.agentId(), owner.getLastAttackTime());
			}

			// Rescue is a real P1 task and consumes a real remedy from the companion bag.
			if (guard && tick >= nextAidTick.getOrDefault(avatar.agentId(), 0L)
					&& avatar.getWorld() == owner.getWorld()
					&& owner.getHealth() <= owner.getMaxHealth() * 0.45f
					&& idle
					&& OwnerAidExecutor.bestRemedy(avatar,
						owner.getMaxHealth() - owner.getHealth()) != null) {
				runtime.startAidOwner(owner);
				nextAidTick.put(avatar.agentId(), tick + 200L);
				continue;
			}

			// Explicit work wins. Otherwise defend self first, then an attacked owner.
			LivingEntity threat = null;
			if (idle) {
				threat = engineer ? engineerThreat(avatar) : validThreat(avatar, avatar.getAttacker());
				if (!engineer && threat == null && avatar.getWorld() == owner.getWorld()) {
					threat = validThreat(avatar, owner.getAttacker());
				}
				if (threat != null) {
					fight(avatar, threat, CombatStyle.gatesFor(
						profile == null ? null : profile.profession));
				}
			}

			// Cooperative hunting is a Lv3 Guard ability, enabled by PROACTIVE.
            // The shared weapon gate still controls bow proficiency.
			if (canHunt(profile) && level.atLeast(AutonomyLevel.PROACTIVE) && idle && threat == null
					&& runtime.permissions().has(owner,
						dev.squire.server.security.PermissionNodes.TASK_GUARD)) {
				LivingEntity target = cooperativeHuntTarget(owner, avatar);
				if (target != null) {
					fight(avatar, target, CombatStyle.gatesFor(
						profile == null ? null : profile.profession));
				}
			}
			// 自主反射<b>绝不</b>把还手对象留成主人。真正的闸在
			// {@code AvatarEntity.attack} 里，这里只是别让他站在原地瞪着你。
			if (avatar.getAttacker() == owner) {
				avatar.setAttacker(null);
			}

			if (guard && level.atLeast(AutonomyLevel.PROACTIVE) && tick % 100 == 0) {
				reportThreats(tick, owner, avatar);
			}
			}
		}
	}

	public static boolean canHunt(dev.squire.server.profile.SquireProfile profile) {
        return profile != null && profile.profession.can(
            dev.squire.server.profession.ProfessionAbility.GUARD_COOPERATIVE_HUNT);
    }

    private LivingEntity cooperativeHuntTarget(ServerPlayerEntity owner,
			AvatarEntity avatar) {
		int attackTime = owner.getLastAttackTime();
		Integer consumed = lastOwnerAttackTime.put(avatar.agentId(), attackTime);
		if (consumed == null || consumed.intValue() != attackTime) {
			LivingEntity struck = owner.getAttacking();
			// getAttacking is retained briefly by vanilla. Only accept the fresh hit,
			// never an entity the player fought before enabling PROACTIVE.
			if (owner.age - attackTime <= 10
					&& isCooperativeHuntTarget(owner, avatar, struck)) {
				cooperativeHuntTarget.put(avatar.agentId(), struck.getUuid());
			} else {
				cooperativeHuntTarget.remove(avatar.agentId());
			}
		}
		UUID targetId = cooperativeHuntTarget.get(avatar.agentId());
		if (targetId == null || !(avatar.getWorld()
				instanceof net.minecraft.server.world.ServerWorld world)) return null;
		net.minecraft.entity.Entity found = world.getEntity(targetId);
		LivingEntity target = found instanceof LivingEntity living ? living : null;
		if (!isCooperativeHuntTarget(owner, avatar, target)) {
			cooperativeHuntTarget.remove(avatar.agentId());
			return null;
		}
		return target;
	}

	/** GameTest seam: FakePlayer is intentionally absent from PlayerManager. */
	void tickCooperativeHuntForTest(ServerPlayerEntity owner, AvatarEntity avatar) {
		var profile = runtime.profileOf(avatar);
		if (!canHunt(profile) || avatar.oathActive() || !profile.autonomyLevel().atLeast(AutonomyLevel.PROACTIVE)
				|| runtime.scheduler().current(avatar.agentId()).isPresent()
				|| !runtime.permissions().has(owner,
					dev.squire.server.security.PermissionNodes.TASK_GUARD)) return;
		LivingEntity target = cooperativeHuntTarget(owner, avatar);
		if (target != null) {
			fight(avatar, target, CombatStyle.gatesFor(profile.profession));
		}
	}

	/** Pure eligibility seam for safety and regression tests. */
	public static boolean isCooperativeHuntTarget(ServerPlayerEntity owner,
			AvatarEntity avatar, LivingEntity target) {
		if (owner == null || avatar == null || target == null || !target.isAlive()
				|| target == avatar || target == owner
				|| target instanceof net.minecraft.entity.player.PlayerEntity
				|| target instanceof AvatarEntity
				|| avatar.getWorld() != target.getWorld()
				|| owner.getWorld() != target.getWorld()) return false;
		if (owner.isTeammate(target)) return false;
		if (target instanceof net.minecraft.entity.passive.TameableEntity tameable
				&& (tameable.isOwner(owner)
					|| owner.getUuid().equals(tameable.getOwnerUuid()))) return false;
		if (target instanceof net.minecraft.entity.Ownable ownable
				&& ownable.getOwner() == owner) return false;
		return avatar.squaredDistanceTo(target) <= DEFENCE_RANGE_SQ
			|| owner.squaredDistanceTo(target) <= DEFENCE_RANGE_SQ;
	}

	/**
	 * 这个目标值得自主反击吗。
	 *
	 * <p><b>人一律不算。</b>这条反射的输入是 {@code getAttacker()}，而香草在任何一次
	 * 伤害之后都会写这个字段——包括玩家自己误挥了一下。原来这里只排除了「他自己」，
	 * 于是「主人不小心打了他一下」和「一只僵尸在咬他」被判成同一件事，他每 tick
	 * 还一次手，直到主人死。</p>
	 *
	 * <p>不只排除主人，是因为一只<b>自己决定</b>去打人的伙伴，在任何情况下都是错的：
	 * 多人服上那是刷屏级的纠纷，单人里那是一次谁也没要求过的意外。要打谁，
	 * 玩家会明说（{@code /squire 打那只…}），那条路走的是任务执行器，不是这里。</p>
	 */
	private LivingEntity engineerThreat(AvatarEntity avatar) {
        UUID id = avatar.agentId();
        int hitTime = avatar.getLastAttackedTime();
        Integer previous = defenceAttackTimes.put(id, hitTime);
        if (previous == null || previous != hitTime) {
            defenceAnchors.put(id, avatar.selfDefenceOrigin());
            defenceAbandoned.put(id, false);
        }
        var threat = validThreat(avatar, avatar.getAttacker(), true);
        var anchor = defenceAnchors.get(id);
        if (!engineerCanPursue(avatar, threat, anchor)) {
            if (!defenceAbandoned.getOrDefault(id, true)) avatar.getNavigation().stop();
            defenceAbandoned.put(id, true);
            return null;
        }
        return defenceAbandoned.getOrDefault(id, false) ? null : threat;
    }

    /** Self-defence stays tethered to the actual hit position, not the moving target. */
    public static boolean engineerCanPursue(AvatarEntity avatar, LivingEntity target,
            net.minecraft.util.math.Vec3d origin) {
        double radius = dev.squire.server.combat.ProfessionCombatRules.config().engineerSelfDefenceRadius;
        return origin != null && validThreat(avatar, target, true) != null
            && avatar.squaredDistanceTo(origin) <= radius * radius
            && target.squaredDistanceTo(origin) <= radius * radius;
    }

    private static LivingEntity validThreat(AvatarEntity avatar, LivingEntity target) {
		return validThreat(avatar, target, false);
	}

	private static LivingEntity validThreat(AvatarEntity avatar, LivingEntity target,
			boolean engineer) {
		return target != null && target.isAlive() && target != avatar
            && !(target instanceof AvatarEntity) && !avatar.isTeammate(target)
            && !(target instanceof net.minecraft.entity.passive.TameableEntity tameable
                && avatar.ownerId() != null && avatar.ownerId().equals(tameable.getOwnerUuid()))
			&& !(target instanceof net.minecraft.entity.player.PlayerEntity)
			&& avatar.getWorld() == target.getWorld()
			&& avatar.squaredDistanceTo(target) <= (engineer ? Math.pow(dev.squire.server.combat.ProfessionCombatRules.config().engineerSelfDefenceRadius, 2) : DEFENCE_RANGE_SQ)
			? target : null;
	}

	/**
	 * 测试用的接缝：他会不会自己决定去还手打这个目标。
	 *
	 * <p>存在的理由是 {@link #tick} 整条路径在 GameTest 里<b>跑不起来</b>——
	 * 它用 {@code PlayerManager.getPlayer(ownerId)} 解析主人，而 GameTest 的
	 * {@code FakePlayer} 从来不在 PlayerManager 里，于是循环第一句就 continue 了。
	 * 一个「打完不掉血」的端到端断言在这里会<b>永远为真</b>，那种测试比没有更糟：
	 * 它看起来在守着一条不变量，实际什么都没验。所以把判据本身暴露出来直接验。</p>
	 */
	public static boolean wouldRetaliateAgainst(AvatarEntity avatar,
			LivingEntity target) {
		return validThreat(avatar, target) != null;
	}

	/**
	 * 自主反击。{@code gates} 不能省：一只还没学会用弓的守卫（Lv.1–3）在这条路上
	 * 也不该掏弓——这里原来调的是不带闸门的重载，等级限制只拦住了护卫那一条路。
	 */
	private static void fight(AvatarEntity avatar, LivingEntity target,
			CombatStyle.Gates gates) {
		target = dev.squire.server.combat.GuardSelfDefense.target(avatar, target);
		boolean ranged = CombatStyle.prepare(avatar, target, avatar.combatStyle(),
			gates);
		if (ranged) {
			var shot = avatar.shoot(target.getUuid());
			if (shot.success() || "DRAWING_BOW".equals(shot.errorCode())) return;
			if (!"NO_LINE_OF_SIGHT".equals(shot.errorCode())) {
				CombatStyle.degradeToMelee(avatar);
			}
		}
		avatar.attack(target.getUuid());
	}

	private void reportThreats(long tick, ServerPlayerEntity owner,
			AvatarEntity avatar) {
		if (tick < nextThreatReportTick.getOrDefault(avatar.agentId(), 0L)
				|| !(avatar.getWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
			return;
		}
		int count = world.getEntitiesByClass(HostileEntity.class,
			avatar.getBoundingBox().expand(16.0), LivingEntity::isAlive).size();
		if (count <= 0) return;
		nextThreatReportTick.put(avatar.agentId(), tick + 1200L);
		runtime.notifier().send(owner.getUuid(),
			"[Squire] 我在附近发现了 " + count + " 个敌对生物，已经提高警戒。");
	}
}
