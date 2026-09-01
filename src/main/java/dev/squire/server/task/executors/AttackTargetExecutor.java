package dev.squire.server.task.executors;

import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import dev.squire.server.task.TaskStateStore;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

/**
 * 「打那只苦力怕」——指定一类目标，打掉最近的一只。
 *
 * <p>{@code GuardRuntime} 一直有完整的战斗机能，但只能<b>被动</b>护卫：站在玩家旁边
 * 等威胁靠近。玩家最自然的一句「把那只羊杀了 / 帮我清掉那几只僵尸」以前没有任何
 * 出口。</p>
 *
 * <p>安全边界写在选目标这一步，不在措辞上：</p>
 * <ul>
 *   <li><b>绝不攻击玩家</b>——任何玩家，包括主人和别人。这条是硬的。</li>
 *   <li>非敌对生物（牛羊猪）只有玩家<b>点名了这个种类</b>时才打，不会被
 *       「清一下附近」误伤。</li>
 *   <li>只打指定半径内的，打完一只就结束——不会变成一场无休止的屠杀。</li>
 * </ul>
 */
public final class AttackTargetExecutor implements dev.squire.server.task.TaskExecutor {

	public static final String TYPE = "combat.attack";

	public static final String PARAM_ENTITY_ID = "entityId";
	public static final String PARAM_RADIUS = "radius";
	public static final String PARAM_MAX_KILLS = "maxKills";

	private static final int DEFAULT_RADIUS = 16;
	private static final int MAX_RADIUS = 32;
	private static final int DEFAULT_MAX_KILLS = 1;
	private static final int MAX_KILLS_CEILING = 8;
	/** 打不到就别一直杵着：超过这么久没造成任何结果就如实失败。 */
	private static final long GIVE_UP_TICKS = 600L;

	record Progress(UUID targetId, int killed, long startedTick) { }

	private final RuntimeServices services;

	public AttackTargetExecutor(RuntimeServices services) {
		this.services = services;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public boolean requiresBody() {
		return true;
	}

	@Override
	public void start(Task task) {
		task.setExecutionState(new Progress(null, 0, 0L));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()
				|| !(avatar.getWorld() instanceof ServerWorld world)) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		Progress progress = task.executionState() instanceof Progress p ? p
			: new Progress(null, 0, tick);
		if (progress.startedTick() == 0L) {
			progress = new Progress(progress.targetId(), progress.killed(), tick);
			task.setExecutionState(progress);
		}
		int maxKills = clamp(task.intParam(PARAM_MAX_KILLS) == null
			? DEFAULT_MAX_KILLS : task.intParam(PARAM_MAX_KILLS), 1, MAX_KILLS_CEILING);
		if (progress.killed() >= maxKills) {
			return StepOutcome.WORK_DONE;
		}

		LivingEntity target = alive(world, progress.targetId());
		if (target == null && progress.targetId() != null) {
			// 上一个目标没了 —— 记一次战果，再看要不要继续。
			progress = new Progress(null, progress.killed() + 1, progress.startedTick());
			task.setExecutionState(progress);
			if (progress.killed() >= maxKills) {
				task.setWorkReport(new Task.WorkReport(
					dev.squire.server.profile.Track.COMBAT.id(), progress.killed(), null));
				return StepOutcome.WORK_DONE;
			}
		}
		if (target == null) {
			int radius = clamp(task.intParam(PARAM_RADIUS) == null
				? DEFAULT_RADIUS : task.intParam(PARAM_RADIUS), 4, MAX_RADIUS);
			target = nearestMatch(world, avatar, task.stringParam(PARAM_ENTITY_ID), radius);
			if (target == null) {
				if (progress.killed() > 0) {
					task.setWorkReport(new Task.WorkReport(
						dev.squire.server.profile.Track.COMBAT.id(),
						progress.killed(), null));
					return StepOutcome.WORK_DONE; // 该打的都打完了
				}
				task.setLastErrorCode("NO_MATCHING_TARGET");
				return StepOutcome.FAILED;
			}
			task.setExecutionState(new Progress(target.getUuid(), progress.killed(),
				progress.startedTick()));
		}
		if (tick - progress.startedTick() > GIVE_UP_TICKS) {
			task.setLastErrorCode("TARGET_UNREACHABLE");
			return StepOutcome.FAILED;
		}
		// 用什么打和护卫那条路共用同一份判据——包括职业闸门：一只还没学会用弓的
		// 守卫（Lv.1–3）不会因为走的是「去打那只怪」这条路就突然会用弓了。
		avatar.setTarget(target);
		boolean shoot = dev.squire.server.combat.CombatStyle.prepare(avatar, target,
			avatar.combatStyle(), dev.squire.server.combat.CombatStyle.gatesFor(
				services.professionData(task.agentId())));
		if (shoot) {
			var shotResult = avatar.shoot(target.getUuid());
			String code = shotResult.errorCode();
			if (shotResult.success() || "DRAWING_BOW".equals(code)) {
				avatar.stopMoving();
				return StepOutcome.CONTINUE;
			}
			// 视线被挡就绕过去，别换武器（换了下一拍还会换回来，每 tick 抖一次）。
			if (!"NO_LINE_OF_SIGHT".equals(code)) {
				dev.squire.server.combat.CombatStyle.degradeToMelee(avatar);
			}
		}
		// 靠近并挥砍由真身自己完成；下一拍继续，直到目标死亡或超时。
		avatar.attack(target.getUuid());
		return StepOutcome.CONTINUE;
	}

	private static LivingEntity alive(ServerWorld world, UUID id) {
		return id != null && world.getEntity(id) instanceof LivingEntity living
			&& living.isAlive() ? living : null;
	}

	/**
	 * 半径内最近的合法目标。
	 *
	 * <p>{@code entityId} 为空表示「附近的敌对生物」；给了具体种类时才允许打
	 * 非敌对生物——这样「清一下附近」永远不会顺手把你的牛宰了。</p>
	 */
	private static LivingEntity nearestMatch(ServerWorld world, AvatarEntity avatar,
			String entityId, int radius) {
		Identifier wanted = null;
		if (entityId != null && !entityId.isBlank()) {
			try {
				wanted = entityId.contains(":") ? new Identifier(entityId)
					: new Identifier("minecraft", entityId);
			} catch (RuntimeException e) {
				return null;
			}
		}
		final Identifier filter = wanted;
		Box area = avatar.getBoundingBox().expand(radius);
		LivingEntity best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (LivingEntity candidate : world.getEntitiesByClass(LivingEntity.class, area,
				LivingEntity::isAlive)) {
			if (candidate == avatar || !isAllowedTarget(candidate, filter)) {
				continue;
			}
			double distSq = candidate.squaredDistanceTo(avatar);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	/** 允许打谁。这段是安全边界本身，不要为了少写一行而合并进选择循环。 */
	private static boolean isAllowedTarget(LivingEntity candidate, Identifier wanted) {
		// 玩家永远不是目标。无论谁点名、怎么点名。
		if (candidate instanceof PlayerEntity || candidate instanceof AvatarEntity) {
			return false;
		}
		if (wanted == null) {
			return candidate instanceof HostileEntity; // 没点名 = 只清敌对
		}
		return Registries.ENTITY_TYPE.getId(candidate.getType()).equals(wanted);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	@Override
	public void cancel(Task task) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar != null) {
			avatar.stopMoving();
		}
	}

	/** 目标死了才算完成，由执行器自己的战果计数判定。 */
	public static TaskCondition targetDown() {
		return TaskCondition.of(ctx -> true, "requested target is down");
	}

	@Override
	public TaskCondition recoverySuccessCondition(TaskStateStore.Snapshot snapshot) {
		return null; // 重启后不重放一次攻击
	}
}
