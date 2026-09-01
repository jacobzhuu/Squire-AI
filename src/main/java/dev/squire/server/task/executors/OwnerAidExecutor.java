package dev.squire.server.task.executors;

import java.util.ArrayList;
import java.util.List;

import dev.squire.api.body.MoveHandle;
import dev.squire.api.body.MoveOptions;
import dev.squire.api.body.TargetPosition;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskCondition;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.PotionUtil;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * 真实玩家救助（方案 D2）。与 {@code heal.self} 严格分开：这里治疗的对象是 OWNER，
 * 消耗的是 Avatar 背包里的真实治疗物品，效果来自物品自身的效果表——绝不调用一个
 * 凭空的 flat heal 来冒充物品作用，也绝不生成物品。
 *
 * <p>优先级：喷溅治疗药水（真实投掷，香草结算） → 可饮用治疗药水（按药水自身效果表
 * 施加） → 金苹果/食物（按其 FoodComponent 自身效果表施加）。没有任何可用物品时
 * 返回 {@code INSUFFICIENT_HEALING_ITEM}，可由上层触发获取任务。</p>
 */
public final class OwnerAidExecutor implements dev.squire.server.task.TaskExecutor {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(OwnerAidExecutor.class);

	public static final String TYPE = "aid.owner";

	public static final String PARAM_OWNER_ID = "ownerId";
	public static final String PARAM_TARGET_FRACTION = "targetHealthFraction";

	/** 交付/投掷距离：与玩家伸手可及一致。 */
	private static final double AID_RANGE_SQ = 16.0;
	/** 每个任务最多使用这么多件治疗物品，避免把整个背包灌进去。 */
	private static final int MAX_ITEMS_PER_TASK = 8;
	private static final double DEFAULT_TARGET_FRACTION = 0.8;

	record Progress(MoveHandle handle, int used) {
	}

	private final RuntimeServices services;

	public OwnerAidExecutor(RuntimeServices services) {
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
		task.setExecutionState(new Progress(null, 0));
	}

	@Override
	public StepOutcome tick(Task task, long tick) {
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		java.util.UUID ownerId = task.stringParam(PARAM_OWNER_ID) == null
			? task.requesterId() : java.util.UUID.fromString(task.stringParam(PARAM_OWNER_ID));
		ServerPlayerEntity owner = services.requester(ownerId);
		if (owner == null || !owner.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return StepOutcome.FAILED;
		}
		double targetFraction = task.parameters().get(PARAM_TARGET_FRACTION)
				instanceof Number n ? n.doubleValue() : DEFAULT_TARGET_FRACTION;
		if (isOwnerSafe(owner, targetFraction)) {
			return StepOutcome.WORK_DONE;
		}

		Progress progress = task.executionState() instanceof Progress p ? p
			: new Progress(null, 0);
		if (progress.used() >= MAX_ITEMS_PER_TASK) {
			task.setLastErrorCode("HEAL_NOT_TAKING_EFFECT");
			return StepOutcome.FAILED;
		}

		AvatarInventory items = avatar.items();
		Remedy remedy = bestRemedy(avatar, ownerMissingHealth(owner));
		if (remedy == null) {
			// 没有物品就如实说没有：上层可以据此发起获取任务，但绝不凭空生成
			task.setLastErrorCode("INSUFFICIENT_HEALING_ITEM");
			return StepOutcome.FAILED;
		}

		if (avatar.squaredDistanceTo(owner) > AID_RANGE_SQ) {
			return navigate(task, avatar, owner, progress);
		}

		ServerWorld world = (ServerWorld) avatar.getWorld();
		List<ItemStack> taken = items.extract(remedy.itemId(), 1);
		if (taken.isEmpty()) {
			task.setLastErrorCode("INSUFFICIENT_HEALING_ITEM");
			return StepOutcome.FAILED;
		}
		ItemStack stack = taken.get(0);
		// 用法由 Remedies 判定：喷溅/滞留药水扔出去，可饮用的递过去，
		// 带效果的食物按它自己的效果表施用。这三条以前是靠"评分最高"猜出来的。
		boolean applied = switch (remedy.use()) {
			case THROW -> throwSplashPotion(world, avatar, owner, stack);
			case DRINK -> applyEffects(owner, PotionUtil.getPotionEffects(stack));
			case EAT -> applyEffects(owner,
				dev.squire.server.item.Remedies.foodEffects(stack));
		};
		if (!applied) {
			items.insert(stack); // 没生效就把物品原样退回，不能白白消耗
			task.setLastErrorCode("HEAL_NOT_TAKING_EFFECT");
			return StepOutcome.FAILED;
		}
		LOG.info("[aid] {} used {} on owner {}", task.taskId(), remedy.itemId(), ownerId);
		task.setExecutionState(new Progress(null, progress.used() + 1));
		return isOwnerSafe(owner, targetFraction)
			? StepOutcome.WORK_DONE : StepOutcome.CONTINUE;
	}

	private StepOutcome navigate(Task task, AvatarEntity avatar, ServerPlayerEntity owner,
			Progress progress) {
		MoveHandle handle = progress.handle();
		if (handle != null && handle.state() == MoveHandle.State.FAILED) {
			task.setLastErrorCode(avatar.lastMoveErrorCode() == null
				? "UNREACHABLE" : avatar.lastMoveErrorCode());
			return StepOutcome.FAILED;
		}
		if (handle == null || handle.state() != MoveHandle.State.MOVING) {
			MoveHandle started = avatar.moveTo(new TargetPosition(
				avatar.getWorld().getRegistryKey().getValue().toString(),
				owner.getX(), owner.getY(), owner.getZ()), MoveOptions.WALK);
			if (started.state() == MoveHandle.State.FAILED) {
				task.setLastErrorCode("PATH_NOT_FOUND");
				return StepOutcome.FAILED;
			}
			task.setExecutionState(new Progress(started, progress.used()));
		}
		return StepOutcome.CONTINUE;
	}

	// ------------------------------------------------------------------ 物品选择

	/** 判定搬到了 {@link dev.squire.server.item.Remedies}，这里只留门面。 */
	public static final class Remedy {
		private final dev.squire.server.item.Remedies.Remedy delegate;

		Remedy(dev.squire.server.item.Remedies.Remedy delegate) {
			this.delegate = delegate;
		}

		public net.minecraft.util.Identifier itemId() {
			return delegate.itemId();
		}

		public dev.squire.server.item.Remedies.Use use() {
			return delegate.use();
		}

		public dev.squire.server.item.Remedies.Kind kind() {
			return delegate.kind();
		}
	}

	/**
	 * 背包里最适合<b>救别人</b>的一件。
	 *
	 * <p>以前这里自己写了一套评分：喷溅药水恒定最高分，于是自救那条路复用它时会去
	 * 「喝」一瓶喷溅药水——原版里没有这个动作。现在用法（扔/喝/吃）由
	 * {@link dev.squire.server.item.Remedies} 判定，两条路各取所需。</p>
	 */
	public static Remedy bestRemedy(AvatarEntity avatar, float missingHealth) {
		return dev.squire.server.item.Remedies.bestForOther(
				dev.squire.server.item.SelfCare.stacks(avatar), missingHealth)
			.map(Remedy::new).orElse(null);
	}

	/** 兼容旧调用点：不知道缺多少血时按「能救多少算多少」处理。 */
	public static Remedy bestRemedy(AvatarEntity avatar) {
		return bestRemedy(avatar, Float.MAX_VALUE);
	}

	private static float ownerMissingHealth(ServerPlayerEntity owner) {
		return Math.max(0f, owner.getMaxHealth() - owner.getHealth());
	}

	// ------------------------------------------------------------------ 真实施用

	/** 真实投掷一瓶喷溅药水，效果完全由香草 {@link PotionEntity} 结算。 */
	private static boolean throwSplashPotion(ServerWorld world, AvatarEntity avatar,
			ServerPlayerEntity owner, ItemStack stack) {
		PotionEntity potion = new PotionEntity(world, avatar);
		potion.setItem(stack.copy());
		potion.setPos(avatar.getX(), avatar.getEyeY() - 0.1, avatar.getZ());
		double dx = owner.getX() - avatar.getX();
		double dy = owner.getBodyY(0.3) - potion.getY();
		double dz = owner.getZ() - avatar.getZ();
		potion.setVelocity(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 0.6f, 2.0f);
		return world.spawnEntity(potion);
	}

	/** 按物品自身的效果表施加到 owner（不是任意 flat heal）。 */
	private static boolean applyEffects(LivingEntity owner,
			List<StatusEffectInstance> effects) {
		boolean any = false;
		for (StatusEffectInstance effect : effects) {
			if (effect.getEffectType().isInstant()) {
				effect.getEffectType().applyInstantEffect(null, null, owner,
					effect.getAmplifier(), 1.0);
				any = true;
			} else if (owner.addStatusEffect(new StatusEffectInstance(effect))) {
				any = true;
			}
		}
		return any;
	}

	// ------------------------------------------------------------------ 验证

	private static boolean isOwnerSafe(ServerPlayerEntity owner, double fraction) {
		if (owner.getHealth() >= fraction * owner.getMaxHealth()) {
			return true;
		}
		// 再生/吸收正在生效也算"状态已改善"——治疗过程本来就是持续的
		return owner.hasStatusEffect(StatusEffects.REGENERATION)
			|| owner.hasStatusEffect(StatusEffects.ABSORPTION);
	}

	@Override
	public void cancel(Task task) {
		if (task.executionState() instanceof Progress progress && progress.handle() != null) {
			progress.handle().cancel();
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar != null) {
			avatar.stopMoving();
		}
	}

	@Override
	public TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object fraction = snapshot.parameters().get(PARAM_TARGET_FRACTION);
		return ownerImproved(services, snapshot.ownerId(),
			fraction instanceof Number n ? n.doubleValue() : DEFAULT_TARGET_FRACTION);
	}

	/**
	 * Goal condition over the OWNER (方案 D3 / B06): the player — not the avatar —
	 * must end up healthier or under a real healing effect.
	 */
	public static TaskCondition ownerImproved(RuntimeServices services,
			java.util.UUID ownerId, double fraction) {
		return TaskCondition.of(ctx -> {
			ServerPlayerEntity owner = services.requester(ownerId);
			return owner != null && isOwnerSafe(owner, fraction);
		}, "owner health at least " + Math.round(fraction * 100) + "% or healing");
	}
}
