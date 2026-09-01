package dev.squire.server.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.squire.server.agent.SquireAgentStateStore;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.task.executors.RuntimeServices;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

/**
 * 本地战斗运行时（方案 D1）。持久 {@link GuardPolicy} 在这里被真正执行：目标选择、
 * 寻路、攻击节奏、换装备和撤退全部在 Java 里逐 tick 完成，LLM 不参与任何一拍。
 *
 * <p>Owner 离线或 Avatar 未物化时策略只是休眠，不被删除；玩家回来即刻恢复。</p>
 */
public final class GuardRuntime {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(GuardRuntime.class);

	/** 已经咬住目标时的重扫间隔；攻击本身每 tick 都尝试。 */
	private static final int SCAN_INTERVAL_TICKS = 10;
	/**
	 * 空闲（暂时没有目标）时的重扫间隔。
	 *
	 * <p>比交战时短，因为这个数字就是「威胁出现到被发现」的上限，直接决定护卫看起来
	 * 反应快不快——尤其是主人正在挨打的那一刻。但它也绝不能是「每 tick」：那正是
	 * 原来的 bug（见 {@code tickPolicy} 里的说明）。四拍是个折中：反应上限 0.2 秒，
	 * 玩家察觉不到，扫描量却只剩五分之一。</p>
	 */
	private static final int IDLE_SCAN_INTERVAL_TICKS = 4;
	/** 目标离 owner 超过 radius * 这个系数就放弃追击，优先回到 owner 身边。 */
	private static final double CHASE_SLACK = 1.5;
	/** 无威胁时的护航距离（平方）。 */
	private static final double ESCORT_REPATH_DISTANCE_SQ = 100.0;
	/** 低于这个血量比例就先撤回 owner 身边而不是继续拼刀。 */
	private static final float RETREAT_HEALTH_FRACTION =
		dev.squire.server.profile.Trait.BASE_RETREAT_FRACTION;
	/** 「谨慎」特质：更早收手。 */
	private static final float CAUTIOUS_RETREAT_FRACTION =
		dev.squire.server.profile.Trait.CAUTIOUS_RETREAT_FRACTION;
	/** 「莽撞」特质：更晚收手。 */
	private static final float RECKLESS_RETREAT_FRACTION =
		dev.squire.server.profile.Trait.RECKLESS_RETREAT_FRACTION;
	/** 「护送」能力：护卫半径扩大一半。 */
	private static final double ESCORT_RADIUS_FACTOR = 1.5;

	private final RuntimeServices services;
	private final java.util.function.Supplier<SquireAgentStateStore> storeSupplier;

	/** agentId -> policy（内存索引；事实来源仍是 AgentRecord）。 */
	private final Map<UUID, GuardPolicy> policies = new LinkedHashMap<>();
	/** agentId -> 当前目标与上次扫描 tick。 */
	private final Map<UUID, Engagement> engagements = new LinkedHashMap<>();

	/** 「还没扫过」的哨兵。见 {@link #dueForRescan} 说明为什么它必须单独判。 */
	private static final long NEVER_SCANNED = Long.MIN_VALUE;

	/** 近战够得着的距离（平方）。举盾/拦截都拿它当「已经贴上了」的判据。 */
	private static final double MELEE_REACH_SQ = 9.0;
	/** 拦截时站在主人前方多远。再远就不是「挡在前面」，是「跑去打架」了。 */
	private static final double INTERCEPT_STANDOFF = 3.0;
	/** 已经站在连线上多近算「挡住了」，不必再重新走位。 */
	private static final double INTERCEPT_TOLERANCE_SQ = 4.0;

	/**
	 * @param ownerThreats 上一次扫描时有几个敌人锁着主人。缓存下来，好让每一拍都能
	 *                     回答「主人现在危不危急」而不必重扫一遍世界。
	 */
	private record Engagement(UUID targetId, long lastScanTick, int ownerThreats) {

		Engagement(UUID targetId, long lastScanTick) {
			this(targetId, lastScanTick, 0);
		}
	}

	/** agentId -> 已经举着盾到哪一拍为止。见 {@link #raiseShieldIfUseful}。 */
	private final Map<UUID, Long> shieldUntil = new LinkedHashMap<>();

	public GuardRuntime(RuntimeServices services,
			java.util.function.Supplier<SquireAgentStateStore> storeSupplier) {
		this.services = services;
		this.storeSupplier = storeSupplier;
	}

	// ------------------------------------------------------------------ 策略生命周期

	/** 从世界存档恢复所有长期护卫策略（重启后立即生效）。 */
	public int load() {
		policies.clear();
		SquireAgentStateStore store = storeSupplier.get();
		if (store == null) {
			return 0;
		}
		for (SquireAgentStateStore.AgentRecord record : store.allRecords()) {
			for (String raw : record.persistentPolicies) {
				GuardPolicy policy = GuardPolicy.parse(raw, record.ownerId, record.agentId);
				if (policy != null && policy.enabled()) {
					policies.put(record.agentId, policy);
				}
			}
		}
		LOG.info("[squire-guard] restored {} persistent guard policies", policies.size());
		return policies.size();
	}

	/** "保护我"：创建或更新长期策略并立即写入档案。 */
	public GuardPolicy enable(UUID ownerId, UUID agentId, int radius) {
		GuardPolicy policy = GuardPolicy.enabled(ownerId, agentId, radius,
			services.currentTick());
		policies.put(agentId, policy);
		persist(agentId, policy);
		return policy;
	}

	/** "停止保护"：策略失效并落盘；不会因为服务器重启又冒出来。 */
	public boolean disable(UUID agentId) {
		GuardPolicy existing = policies.remove(agentId);
		engagements.remove(agentId);
		shieldUntil.remove(agentId);
		AvatarEntity avatar = services.avatar(agentId);
		if (avatar != null) {
			avatar.setTarget(null);
			avatar.stopMoving();
			// 「停止保护」之后还举着盾站在那里，是玩家最容易当成 bug 的一种残留状态。
			avatar.stopBlocking();
		}
		if (existing == null) {
			return false;
		}
		persist(agentId, existing.disable());
		return true;
	}

	public Optional<GuardPolicy> policyOf(UUID agentId) {
		return Optional.ofNullable(policies.get(agentId));
	}

	public List<GuardPolicy> all() {
		return List.copyOf(policies.values());
	}

	private void persist(UUID agentId, GuardPolicy policy) {
		SquireAgentStateStore store = storeSupplier.get();
		if (store != null) {
			store.putPersistentPolicy(agentId, policy.serialize());
		}
	}

	// ------------------------------------------------------------------ 每 tick 执行

	/** Called from the server tick; never from a model turn. */
	public void tick(long tick) {
		if (policies.isEmpty()) {
			return;
		}
		for (GuardPolicy policy : List.copyOf(policies.values())) {
			try {
				tickPolicy(policy, tick);
			} catch (RuntimeException e) {
				LOG.warn("[squire-guard] policy {} failed this tick: {}",
					policy.agentId(), e.toString());
			}
		}
	}

	private void tickPolicy(GuardPolicy policy, long tick) {
		AvatarEntity avatar = services.avatar(policy.agentId());
		ServerPlayerEntity owner = services.requester(policy.ownerId());
		if (avatar == null || !avatar.isAlive() || owner == null || !owner.isAlive()
				|| avatar.getWorld() != owner.getWorld()) {
			return; // 休眠：owner 离线或伙伴未物化，策略保留
		}
		ServerWorld world = (ServerWorld) owner.getWorld();
		Engagement engagement = engagements.getOrDefault(policy.agentId(),
			new Engagement(null, NEVER_SCANNED));

		dev.squire.server.profile.SquireProfile profile =
			services.profile(policy.agentId());
		dev.squire.server.profession.ProfessionData profession =
			services.professionData(policy.agentId());
		dev.squire.server.profession.ProfessionConfig config =
			services.professionConfig();
		dev.squire.server.profession.CombatStance stance = stanceOf(profession, config);
		double radius = guardRadius(policy, profile);
		CombatBalanceTelemetry.observeOwnerDistance(policy.agentId(),
			avatar.distanceTo(owner));
		boolean emergency = ownerInDanger(profession, config, owner,
			engagement.ownerThreats());
		double chaseLimit = chaseLimit(profession, config, radius, stance, emergency,
			profile);

		// Lv.2 装备意识：手上那把快断了就换背包里最好的一把。开打之前先办这件事——
		// 武器碎在挥到一半的那一拍，比什么时候都难看。
		if (has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_EQUIPMENT_AWARENESS)) {
			CombatStyle.swapOutNearlyBrokenWeapon(avatar);
		}

		LivingEntity target = resolveTarget(world, engagement.targetId(), owner,
			chaseLimit);
		// 这个判据以前是 `target == null || 距上次扫描够久`，而「没有目标」正是绝大多数
		// 时间的状态——于是 SCAN_INTERVAL_TICKS 只在<b>已经打起来</b>时生效，太平无事的
		// 时候反而每 tick 扫一遍周围所有生物。节流写反了，代价还随生物类模组线性上涨。
		if (dueForRescan(engagement, tick)) {
			Selection selection = selectThreat(world, owner, policy, radius, profile,
				profession, config, engagement.targetId(), emergency);
			LivingEntity rescanned = selection.target();
			if (rescanned != null || target == null) {
				target = rescanned;
			}
			engagements.put(policy.agentId(), new Engagement(
				target == null ? null : target.getUuid(), tick,
				selection.ownerThreats()));
		} else if (target == null && engagement.targetId() != null) {
			// 目标刚失效（死了、跑出追击范围）：立刻松口去护航，但不在这一拍补扫，
			// 下一个扫描窗口最多十拍就到。
			engagements.put(policy.agentId(),
				new Engagement(null, engagement.lastScanTick(),
					engagement.ownerThreats()));
		}

		if (target == null) {
			lowerShield(avatar);
			escort(avatar, owner, tick);
			return;
		}
		CombatBalanceTelemetry.observeTarget(policy.agentId(), target.getUuid());
		// 低血量时优先保证自己活着回到 owner 身边，避免无限拼刀送人头。
		// 撤退线是性格对交战行为的修正之一；它不增加伤害，也不解锁职业能力。
		if (avatar.getHealth() <= avatar.getMaxHealth()
				* retreatFraction(profile, profession, config, stance)) {
			CombatBalanceTelemetry.recordRetreat(policy.agentId());
			engagements.put(policy.agentId(),
				new Engagement(null, tick, engagement.ownerThreats()));
			lowerShield(avatar);
			escort(avatar, owner, tick);
			return;
		}
		CombatBalanceTelemetry.recordCombatReady(policy.agentId());
		avatar.setTarget(target);
		noteThreatensOwner(avatar, target, owner, tick);

		// Lv.6 盾牌必须<b>先</b>判：{@code CombatStyle.prepare} 会调用
		// {@code cancelDraw()}，而那一句会把正在使用的物品清掉——包括举着的盾。
		// 先 prepare 再举盾的话，盾每拍被清一次、再举一次，永远到不了香草要求的
		// 5 tick 生效门槛，等于一次都没挡住。
		if (raiseShieldIfUseful(avatar, target, profession, config, tick)) {
			return;
		}
		lowerShield(avatar);

		// 打法由他<b>手上拿着什么</b>决定，prepare 只在手上没有能打的东西时才换手。
		boolean shoot = CombatStyle.prepare(avatar, target, avatar.combatStyle(),
			weaponGates(profession));
		if (shoot) {
			var result = avatar.shoot(target.getUuid());
			String code = result.errorCode();
			if (result.success() || "DRAWING_BOW".equals(code)) {
				// 拉弓期间站住别冲，否则一边跑一边射，箭全歪。
				avatar.stopMoving();
				return;
			}
			// 有人站在射线上：<b>横着挪</b>，而不是压上去。
			//
			// 一开始这里和「视线被挡」共用同一条出路（往目标走，角度一变线就开了）。
			// M23 抓到了那样做的后果：主人在前、他在后、怪在主人正对面，是跟随时最
			// 常见的站位；压上去的结果是他一路走进近战距离，然后换刀——一个 Lv.4
			// 的弓手整场一箭没放。保持距离、往旁边错开一步才是弓手该做的事。
			if (AvatarEntity.FRIENDLY_IN_LINE.equals(code)) {
				sidestepForShot(avatar, target, tick);
				return;
			}
			// 视线被地形挡住：绕过去，但<b>绝不</b>换武器——绕出来他还得是弓手。
			// 换了的话下一拍 prepare 又会把弓换回来，每 tick 抖一次。
			if (!"NO_LINE_OF_SIGHT".equals(code)) {
				// 没箭了/拿不出弓：如实退回近战，而不是站着不动。
				CombatStyle.degradeToMelee(avatar);
			}
		}
		// Lv.7 拦截：目标冲着主人去，就先站到他们中间，而不是绕过主人去追。
		if (intercept(avatar, owner, target, profession, radius)) {
			return;
		}
		avatar.attack(target.getUuid());
	}

	/** 侧移多远。够把主人让出射线，又不至于脱离交战距离。 */
	private static final double SIDESTEP_BLOCKS = 3.0;

	/** 两次重新发起侧移之间至少隔多少拍。 */
	private static final long SIDESTEP_REPATH_TICKS = 10L;

	/** 每只随从下一次可以重新发起侧移的时刻。 */
	private final Map<UUID, Long> nextSidestepTick = new LinkedHashMap<>();

	/**
	 * 垂直于射线错开一步，往<b>远离挡路那个人</b>的一侧。
	 *
	 * <p>不看 {@code isIdle}：跟随主人的那条路几乎永远是活的，等它空下来等于永远不挪
	 * ——那正是第一版写法的下场（M23 里那个弓手整场一箭没放）。改成先停下当前路径、
	 * 再发一条新的，并用一个每 10 拍一次的节流挡住原地抖。</p>
	 */
	private void sidestepForShot(AvatarEntity avatar,
			net.minecraft.entity.LivingEntity target, long tick) {
		avatar.setTarget(target);
		if (tick < nextSidestepTick.getOrDefault(avatar.agentId(), 0L)) {
			return; // 上一次侧移还在走，让它走完
		}
		net.minecraft.util.math.Vec3d toTarget = target.getPos().subtract(avatar.getPos());
		net.minecraft.util.math.Vec3d flat =
			new net.minecraft.util.math.Vec3d(toTarget.x, 0.0, toTarget.z);
		if (flat.lengthSquared() < 1.0e-4) {
			return; // 贴脸站着，没有「旁边」可言
		}
		net.minecraft.util.math.Vec3d side =
			new net.minecraft.util.math.Vec3d(-flat.z, 0.0, flat.x).normalize();
		net.minecraft.util.math.Vec3d blocker = avatar.lastShotBlockerPos();
		if (blocker != null) {
			net.minecraft.util.math.Vec3d toBlocker = blocker.subtract(avatar.getPos());
			if (side.dotProduct(new net.minecraft.util.math.Vec3d(
					toBlocker.x, 0.0, toBlocker.z)) > 0.0) {
				side = side.multiply(-1.0); // 挡路的人在这一侧，就往另一侧去
			}
		}
		net.minecraft.util.math.Vec3d spot =
			avatar.getPos().add(side.multiply(SIDESTEP_BLOCKS));
		avatar.stopMoving(); // 先把跟随那条路掐掉，否则新路会被它当场顶回去
		avatar.getNavigation().startMovingTo(spot.x, spot.y, spot.z, 1.1);
		nextSidestepTick.put(avatar.agentId(), tick + SIDESTEP_REPATH_TICKS);
	}

	// ------------------------------------------------------------------ 职业闸门

	/** 他到这一级了吗。没有职业时一律 false——那条路走的是职业系统出现之前的行为。 */
	private static boolean has(dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionAbility ability) {
		return profession != null && profession.can(ability);
	}

	/**
	 * 选武器的闸门。
	 *
	 * <p><b>没有职业的随从闸全开</b>：职业系统不该让一只昨天还会用弓的伙伴今天忘了怎么用。
	 * 只有真的选了守卫职业的，才从 Lv.1 的「只会近战」开始逐级解锁。</p>
	 */
	static CombatStyle.Gates weaponGates(
			dev.squire.server.profession.ProfessionData profession) {
		// 判据本身住在 CombatStyle 里：护卫不是唯一会选武器的路，自主反击和
		// 「去打那只怪」也得问同一个问题，同一个答案。
		return CombatStyle.gatesFor(profession);
	}

	/** 当前姿态。Lv.8 之前一律均衡——那正好等于姿态系统出现之前的行为。 */
	static dev.squire.server.profession.CombatStance stanceOf(
			dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config) {
		return has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_COMBAT_STANCE)
			? profession.combatStance()
			: dev.squire.server.profession.CombatStance.BALANCED;
	}

	/**
	 * 追击上限（离主人多远就放弃）。
	 *
	 * <p>Lv.3 之前是写死的 1.5 倍，也就是老行为；Lv.3 起由姿态决定；主人危急时
	 * 无论哪一档都收回到护卫半径以内——那一刻追一只跑远的杂鱼是纯粹的失职。</p>
	 */
	static double chaseLimit(dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config, double radius,
			dev.squire.server.profession.CombatStance stance, boolean emergency) {
		return chaseLimit(profession, config, radius, stance, emergency, null);
	}

	/** Personality only modifies the pre-stance chase tendency; it never replaces Lv.8. */
	static double chaseLimit(dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config, double radius,
			dev.squire.server.profession.CombatStance stance, boolean emergency,
			dev.squire.server.profile.SquireProfile profile) {
		boolean hasThreatEvaluation = has(profession,
			dev.squire.server.profession.ProfessionAbility.GUARD_THREAT_EVALUATION);
		double limit = hasThreatEvaluation
			? dev.squire.server.profession.ThreatEvaluator.chaseLimit(config, radius,
				stance)
			: radius * CHASE_SLACK;
		if (!has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_COMBAT_STANCE) && profile != null
				&& profile.hasTrait(dev.squire.server.profile.Trait.RECKLESS)) {
			limit *= dev.squire.server.profile.Trait.RECKLESS_CHASE_FACTOR;
		}
		return emergency ? Math.min(limit, radius) : limit;
	}

	/** 主人危急吗（Lv.7 起才看得出来）。 */
	static boolean ownerInDanger(dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config,
			ServerPlayerEntity owner, int threatsOnOwner) {
		if (!has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_INTERCEPT)) {
			return false;
		}
		float max = owner.getMaxHealth();
		double fraction = max <= 0 ? 1.0 : owner.getHealth() / max;
		return dev.squire.server.profession.ThreatEvaluator.ownerInDanger(config,
			fraction, threatsOnOwner);
	}

	// ------------------------------------------------------------------ 盾 / 拦截

	/**
	 * Lv.6「盾牌」：远程攻击打过来、而自己还没贴上去的那段时间，把盾举起来。
	 *
	 * <p>举盾期间<b>不</b>调用 {@code attack()}：那个方法开头就会
	 * {@code cancelDraw()}，会顺手把正在使用的盾也清掉，于是盾每拍举起又放下，
	 * 一次都挡不住。所以走位在这里自己做。</p>
	 *
	 * @return 这一拍在举盾（调用方应当就此收手）
	 */
	private boolean raiseShieldIfUseful(AvatarEntity avatar, LivingEntity target,
			dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config, long tick) {
		if (!has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_SHIELD_PROFICIENCY)) {
			return false;
		}
		double distSq = avatar.squaredDistanceTo(target);
		boolean rangedThreat = TargetWeapon.of(target) == TargetWeapon.Preference.BOW
			|| target instanceof net.minecraft.entity.ai.RangedAttackMob;
		if (distSq <= MELEE_REACH_SQ || !rangedThreat) {
			return false; // 到了近战窗口就该放盾开打
		}
		// <b>能还手就还手</b>。举盾只是没有别的办法时的答案：一个背着弓的守卫
		// 对着骷髅举一整场盾，看起来就是「他不会用你给的弓」——那正是这个职业
		// 最不该给人的印象。
		if (weaponGates(profession).bow() && CombatStyle.hasWorkingBow(avatar)) {
			return false;
		}
		if (!CombatStyle.equipShield(avatar)) {
			return false; // 身上根本没有盾
		}
		Long until = shieldUntil.get(avatar.agentId());
		if (until == null || tick >= until) {
			avatar.startBlocking();
			shieldUntil.put(avatar.agentId(), tick + config.guardShieldHoldTicks);
		}
		// Vanilla 盾只拦正面的伤害；光举盾、不看着射手会随机漏掉整轮箭。
		avatar.getLookControl().lookAt(target, 30.0F, 30.0F);
		// 举着盾也要往前走，否则就成了站在原地当靶子。
		if (avatar.getNavigation().isIdle() || tick % 10L == 0L) {
			avatar.getNavigation().startMovingTo(target, 1.0);
		}
		return true;
	}

	private void lowerShield(AvatarEntity avatar) {
		if (shieldUntil.remove(avatar.agentId()) != null) {
			avatar.stopBlocking();
		}
	}

	/**
	 * Lv.7「拦截」：目标冲着主人去的时候，先站到 {@code 敌人 → 我 → 主人} 这条线上。
	 *
	 * <p>只在<b>近战型</b>目标上做。对着一个二十格外的骷髅摆造型，结果是谁也打不死；
	 * 那种目标该用弓，或者直接冲上去。</p>
	 *
	 * @return 这一拍在走位（调用方应当就此收手）
	 */
	private boolean intercept(AvatarEntity avatar, ServerPlayerEntity owner,
			LivingEntity target, dev.squire.server.profession.ProfessionData profession,
			double radius) {
		if (!has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_INTERCEPT)) {
			return false;
		}
		if (avatar.squaredDistanceTo(target) <= MELEE_REACH_SQ) {
			return false; // 已经够得着，打就是了
		}
		if (!(target instanceof MobEntity mob) || mob.getTarget() != owner) {
			return false; // 它没冲着主人来
		}
		if (TargetWeapon.of(target) == TargetWeapon.Preference.BOW) {
			return false; // 远程/爆炸型：挡在前面既挡不住也打不着
		}
		double distance = target.distanceTo(owner);
		if (distance > radius || distance < 1.0) {
			return false;
		}
		net.minecraft.util.math.Vec3d toEnemy = target.getPos()
			.subtract(owner.getPos()).normalize();
		net.minecraft.util.math.Vec3d spot = owner.getPos().add(
			toEnemy.multiply(Math.min(distance - 0.5, INTERCEPT_STANDOFF)));
		if (avatar.getPos().squaredDistanceTo(spot) <= INTERCEPT_TOLERANCE_SQ) {
			// 已经挡住了：站定，等它撞上来。
			avatar.getNavigation().stop();
			avatar.getLookControl().lookAt(target);
			return true;
		}
		avatar.getNavigation().startMovingTo(spot.x, spot.y, spot.z, 1.25);
		return true;
	}

	/** 把「这个目标正在盯着主人」记进参与度台账，保护主人加成据此结算。 */
	private void noteThreatensOwner(AvatarEntity avatar, LivingEntity target,
			ServerPlayerEntity owner, long tick) {
		if (!(target instanceof MobEntity mob) || mob.getTarget() != owner) {
			return;
		}
		dev.squire.server.runtime.SquireRuntime.noteThreatensOwner(avatar, target, tick);
	}

	private void escort(AvatarEntity avatar, ServerPlayerEntity owner, long tick) {
		avatar.setTarget(null);
		avatar.cancelDraw();
		// 普通工作正在接管身体时，空闲护航不能每 tick 覆盖它的路径。
		if (avatar.taskDriven()) {
			return;
		}
		if (avatar.squaredDistanceTo(owner) > ESCORT_REPATH_DISTANCE_SQ
				&& (avatar.getNavigation().isIdle() || tick % 10L == 0L)) {
			// 这是持续护航，不是一个有终点的用户任务：直接走原版导航，不创建
			// MoveHandle。旧实现每 tick 调 moveTo，既重建路径又让 taskDriven 永远为真。
			avatar.getNavigation().startMovingTo(owner, 1.15);
		}
	}

	// ------------------------------------------------------------------ 威胁识别

	/**
	 * 到重扫窗口了吗。
	 *
	 * <p>{@code NEVER_SCANNED} 必须单独判：它是 {@code Long.MIN_VALUE}，直接做
	 * {@code tick - lastScanTick} 会溢出成负数，条件永远不成立，新策略于是一次也扫
	 * 不了。以前这个坑被 {@code target == null ||} 的短路挡住了，去掉短路就得自己接住。</p>
	 */
	private static boolean dueForRescan(Engagement engagement, long tick) {
		long last = engagement.lastScanTick();
		if (last == NEVER_SCANNED) {
			return true;
		}
		int interval = engagement.targetId() == null
			? IDLE_SCAN_INTERVAL_TICKS : SCAN_INTERVAL_TICKS;
		return tick - last >= interval;
	}

	/** @param chaseLimit 目标离主人超过这个距离就松口，回去护航 */
	private LivingEntity resolveTarget(ServerWorld world, UUID targetId,
			ServerPlayerEntity owner, double chaseLimit) {
		if (targetId == null) {
			return null;
		}
		if (!(world.getEntity(targetId) instanceof LivingEntity living)
				|| !living.isAlive()) {
			return null;
		}
		return living.squaredDistanceTo(owner) <= chaseLimit * chaseLimit
			? living : null;
	}

	/** 有效护卫半径。「护送」能力把它扩大一半，让他敢跟你走远一点。 */
	static double guardRadius(GuardPolicy policy,
			dev.squire.server.profile.SquireProfile profile) {
		double radius = policy.radius();
		if (profile != null
				&& profile.can(dev.squire.server.profile.Ability.COMBAT_ESCORT)) {
			radius *= ESCORT_RADIUS_FACTOR;
		}
		if (profile != null
				&& profile.hasTrait(dev.squire.server.profile.Trait.RECKLESS)) {
			radius += dev.squire.server.profile.Trait.RECKLESS_AGGRO_RANGE_BONUS;
		}
		return radius;
	}

	/** 撤退线。谨慎的更早退，莽撞的更晚退；两者互斥，不会同时出现。 */
	public static float retreatFraction(dev.squire.server.profile.SquireProfile profile) {
		if (profile == null) {
			return RETREAT_HEALTH_FRACTION;
		}
		if (profile.hasTrait(dev.squire.server.profile.Trait.CAUTIOUS)) {
			return CAUTIOUS_RETREAT_FRACTION;
		}
		if (profile.hasTrait(dev.squire.server.profile.Trait.RECKLESS)) {
			return RECKLESS_RETREAT_FRACTION;
		}
		return RETREAT_HEALTH_FRACTION;
	}

	/**
	 * 带职业与姿态的撤退线。
	 *
	 * <p><b>玩家明确选的姿态说了算</b>（设计文档 §9 Lv.8）：一旦解锁了战斗姿态，
	 * 撤退线以配置里的阈值为基准、按姿态偏移，性格特质不再参与。没解锁的时候
	 * 才退回上面那条只看特质的老规则——那正是姿态系统出现之前的行为。</p>
	 */
	static float retreatFraction(dev.squire.server.profile.SquireProfile profile,
			dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config,
			dev.squire.server.profession.CombatStance stance) {
		if (!has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_COMBAT_STANCE)) {
			return retreatFraction(profile);
		}
		double base = config.guardRetreatThreshold + stance.retreatDelta();
		return (float) Math.max(0.05, Math.min(0.9, base));
	}

	/**
	 * 一次扫描的结论。
	 *
	 * @param ownerThreats 有几个敌人锁着主人（主人危急的判据之一）
	 */
	private record Selection(LivingEntity target, int ownerThreats) { }

	/**
	 * 威胁选择：半径内的敌对生物、正在攻击 owner 的中立生物、朝向 owner 的投射物来源，
	 * 以及 owner 最近的攻击者。
	 *
	 * <h3>怎么排优先级</h3>
	 * <p>Lv.3 之前是「离主人最近的先打」——于是一只正在射你的骷髅会输给一只刚好站得
	 * 更近、还没盯上任何人的僵尸。Lv.3 起改用 {@link
	 * dev.squire.server.profession.ThreatEvaluator} 的评分。没有职业的随从仍然走
	 * 老规则，行为一点不变。</p>
	 *
	 * <h3>Lv.9 高威胁</h3>
	 * <p>监守者这类目标会被直接从候选里剔除：高等级不是「更无脑」，而是「更会判断
	 * 打不过」。剔除之后他会回去护航，而不是站着送死。</p>
	 *
	 * <h3>Lv.10 守护协议</h3>
	 * <p>主人危急的那一刻，候选里<b>只剩下正在威胁主人的目标</b>。这就是设计文档里
	 * 「停止低价值目标 → 返回主人 → 重新计算最高威胁」那三句话的实现：在此之前，
	 * 主人危急只会缩短追击距离，他仍然可能在打一只离主人八格远的僵尸。</p>
	 *
	 * @param emergency 主人现在处于危急状态
	 */
	private Selection selectThreat(ServerWorld world, ServerPlayerEntity owner,
			GuardPolicy policy, double radius,
			dev.squire.server.profile.SquireProfile profile,
			dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config,
			UUID currentTargetId, boolean emergency) {
		Box area = Box.of(owner.getPos(), radius * 2, radius * 2, radius * 2);
		// LinkedHashSet 而不是 ArrayList：原来每加一个候选都要 contains 一遍已有的，
		// 在刷怪塔旁边就是 O(n²)。Set 去重同样保序，优先级判定完全不受影响。
		Set<LivingEntity> candidates = new LinkedHashSet<>();

		boolean hostiles = policy.covers(GuardPolicy.Rule.HOSTILES);
		boolean ownerAttackers = policy.covers(GuardPolicy.Rule.OWNER_ATTACKERS);
		if (hostiles && ownerAttackers) {
			// Monster 是接口（Phantom 等并不继承 HostileEntity），所以查询 MobEntity
			// 后同时覆盖敌对生物和明确锁定主人的攻击者。
			for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, area,
					LivingEntity::isAlive)) {
				if (mob instanceof Monster || mob.getTarget() == owner) {
					candidates.add(mob);
				}
			}
		} else if (hostiles) {
			for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, area,
					LivingEntity::isAlive)) {
				if (mob instanceof Monster) {
					candidates.add(mob);
				}
			}
		} else if (ownerAttackers) {
			for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, area,
					LivingEntity::isAlive)) {
				if (mob.getTarget() == owner) {
					candidates.add(mob);
				}
			}
		}
		if (ownerAttackers) {
			LivingEntity attacker = owner.getAttacker();
			if (attacker != null && attacker.isAlive()
					&& !(attacker instanceof AvatarEntity)) {
				candidates.add(attacker);
			}
		}
		if (policy.covers(GuardPolicy.Rule.PROJECTILES)) {
			for (PersistentProjectileEntity arrow : world.getEntitiesByClass(
					PersistentProjectileEntity.class, area, e -> !e.isOnGround())) {
				Entity shooter = arrow.getOwner();
				if (shooter instanceof LivingEntity living && living != owner
						&& living.isAlive()) {
					candidates.add(living);
				}
			}
		}

		// 锁着主人的有几个——主人危不危急，这就是那个数字。剔除高威胁之前先数，
		// 因为一个不该打的监守者仍然是主人的危险。
		int ownerThreats = 0;
		for (LivingEntity candidate : candidates) {
			if (candidate instanceof MobEntity mob && mob.getTarget() == owner) {
				ownerThreats++;
			}
		}

		// Lv.9 高威胁判断：认得出打不过的东西，就别去打。
		boolean avoidsHighThreat = has(profession,
			dev.squire.server.profession.ProfessionAbility.GUARD_HIGH_THREAT_AWARENESS);
		if (avoidsHighThreat) {
			candidates.removeIf(candidate -> config.isHighThreat(
				dev.squire.server.runtime.SquireRuntime.entityIdOf(candidate)));
		}

		// Lv.10 守护协议：主人危急时，不威胁主人的目标一律不打。
		if (emergency && has(profession, dev.squire.server.profession.ProfessionAbility
				.GUARD_GUARDIAN_PROTOCOL)) {
			candidates.removeIf(candidate -> owner.getAttacker() != candidate
				&& !(candidate instanceof MobEntity mob && mob.getTarget() == owner));
		}

		// 「集火」：主人正在打谁，他就打谁。半径仍然说了算——集火不是脱队追击的借口。
		LivingEntity focus = profile != null && profile.can(
				dev.squire.server.profile.Ability.COMBAT_FOCUS_FIRE)
			? owner.getAttacking() : null;
		if (focus != null && focus.isAlive() && !(focus instanceof AvatarEntity)
				&& focus.squaredDistanceTo(owner) <= radius * radius
				&& !(avoidsHighThreat && config.isHighThreat(
					dev.squire.server.runtime.SquireRuntime.entityIdOf(focus)))) {
			return new Selection(focus, ownerThreats);
		}

		boolean scored = has(profession,
			dev.squire.server.profession.ProfessionAbility.GUARD_THREAT_EVALUATION);
		dev.squire.server.profession.CombatStance stance = stanceOf(profession, config);
		AvatarEntity self = services.avatar(policy.agentId());

		LivingEntity best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		double bestDistSq = Double.MAX_VALUE;
		for (LivingEntity candidate : candidates) {
			if (candidate instanceof AvatarEntity || candidate == owner) {
				continue;
			}
			double distSq = candidate.squaredDistanceTo(owner);
			if (distSq > radius * radius) {
				continue;
			}
			if (!scored) {
				// 老规则：离主人最近的先打。没有职业的随从走的就是这一条。
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = candidate;
				}
				continue;
			}
			boolean targetsOwner = candidate instanceof MobEntity mob
				&& mob.getTarget() == owner;
			// 防守姿态不去招惹「谁也没盯上」的敌人——这就是「更少主动拉怪」。
			boolean idle = !targetsOwner && owner.getAttacker() != candidate
				&& (self == null || !(candidate instanceof MobEntity mob2)
					|| mob2.getTarget() != self);
			if (idle && !stance.pullsAggro()) {
				continue;
			}
			double score = dev.squire.server.profession.ThreatEvaluator.score(
				new dev.squire.server.profession.ThreatEvaluator.Candidate(
					owner.getAttacker() == candidate, targetsOwner,
					Math.sqrt(distSq),
					self == null ? 0.0 : self.distanceTo(candidate),
					config.mobTier(dev.squire.server.runtime.SquireRuntime
						.entityIdOf(candidate)),
					candidate.getUuid().equals(currentTargetId)),
				radius);
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return new Selection(best, ownerThreats);
	}
}
