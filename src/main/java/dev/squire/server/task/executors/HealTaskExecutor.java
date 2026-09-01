package dev.squire.server.task.executors;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.task.Task;
import net.minecraft.util.Identifier;

/**
 * 伙伴自救（spec section 40）：只治疗 Avatar 自己，玩家救助走 {@link OwnerAidExecutor}。
 * 每次从真实背包取出一件治疗物品，并通过物品自身的 {@code finishUsing} 路径生效
 * ——再生、吸收和瞬间治疗与玩家吃下去时完全一致。LLM 不驱动这个循环。
 *
 * <p>方案 D2 取代了 ADR-021 的模拟实现：不再有"金苹果 = 固定 4.0 HP"的 flat heal。
 * 因为效果是持续性的，执行器在上一件物品仍在生效期间不会再吃下一件。</p>
 */
public final class HealTaskExecutor implements dev.squire.server.task.TaskExecutor {

	public static final String TYPE = "heal.self";

	public static final String PARAM_THRESHOLD = "threshold";
	public static final String PARAM_ITEM_ID = "itemId";
	public static final String PARAM_HEAL_PER_ITEM = "healPerItem";

	private static final double DEFAULT_THRESHOLD = 0.95;
	private static final String DEFAULT_ITEM = "minecraft:golden_apple";
	private static final float DEFAULT_HEAL_PER_ITEM = 4.0f;
	private static final int MAX_CONSUMED_PER_TASK = 64;

	/**
	 * 一件治疗物品仍在生效时最多再等这么久才吃下一件——既不浪费物品，也不会因为
	 * 某个长效果（例如金苹果的吸收）而无限期停在原地。
	 */
	private static final int EFFECT_SETTLE_TICKS = 120;

	record Progress(double threshold, Identifier itemId, float healPerItem,
			int consumed, long nextAllowedTick, float healthAtLastUse) {
	}

	private final RuntimeServices services;

	public HealTaskExecutor(RuntimeServices services) {
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
		double threshold = task.stringParam(PARAM_THRESHOLD) == null
			? DEFAULT_THRESHOLD
			: Double.parseDouble(task.stringParam(PARAM_THRESHOLD));
		Identifier item = task.stringParam(PARAM_ITEM_ID) == null
			? new Identifier(DEFAULT_ITEM)
			: parseId(task.stringParam(PARAM_ITEM_ID));
		float perItem = task.stringParam(PARAM_HEAL_PER_ITEM) == null
			? DEFAULT_HEAL_PER_ITEM
			: Float.parseFloat(task.stringParam(PARAM_HEAL_PER_ITEM));
		task.setExecutionState(new Progress(threshold, item, perItem, 0, Long.MIN_VALUE,
			-1.0f));
	}

	@Override
	public dev.squire.server.task.TaskExecutor.StepOutcome tick(Task task, long tick) {
		if (!(task.executionState() instanceof Progress progress)) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		AvatarEntity avatar = services.avatar(task.agentId());
		if (avatar == null || !avatar.isAlive()) {
			task.setLastErrorCode("ENTITY_NOT_FOUND");
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		var state = avatar.snapshotState();
		if (state.health() >= progress.threshold() * state.maxHealth()) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.WORK_DONE;
		}
		// Regeneration raises base health and does not stack. Wait for it to settle before
		// consuming another remedy. Absorption is a shield, not healing, and must never
		// keep this task alive after the inventory is empty.
		if (dev.squire.server.item.SelfCare.hasActiveRegeneration(avatar)) {
			return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
		}
		// 方案 D2：自救也必须走物品原生效果路径，不再 flat heal 冒充物品作用。
		// 挑什么、怎么用都交给 SelfCare —— 和自动自救那条反射是同一段代码，
		// 所以这里同样不会去"喝"一瓶喷溅药水，也同样看得见普通食物。
		var remedy = dev.squire.server.item.SelfCare.pick(avatar);
		Identifier chosen = remedy.map(r -> r.itemId()).orElse(progress.itemId());
		if (!avatar.hasAtLeast(chosen, 1)) {
			// 背包空了，但身上还挂着再生：多半是自救反射刚刚先动了手。
			// 这时报 INSUFFICIENT_ITEM 是错的——他确实正在回血，等着就行。
			if (dev.squire.server.item.SelfCare.hasActiveRegeneration(avatar)) {
				return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
			}
			task.setLastErrorCode("INSUFFICIENT_ITEM");
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		if (!dev.squire.server.item.SelfCare.useOnSelf(avatar, chosen)) {
			task.setLastErrorCode("HEAL_NOT_TAKING_EFFECT");
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		int consumed = progress.consumed() + 1;
		if (consumed >= MAX_CONSUMED_PER_TASK) {
			// safety valve: something keeps undoing the healing — fail honestly
			task.setLastErrorCode("HEAL_NOT_TAKING_EFFECT");
			return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
		}
		task.setExecutionState(new Progress(progress.threshold(), progress.itemId(),
			progress.healPerItem(), consumed, tick + EFFECT_SETTLE_TICKS,
			avatar.getHealth()));
		return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
	}

	private static Identifier parseId(String raw) {
		int colon = raw.indexOf(':');
		return colon < 0 ? new Identifier(raw) : new Identifier(raw.substring(0, colon),
			raw.substring(colon + 1));
	}

	@Override
	public void cancel(Task task) {
	}

	@Override
	public dev.squire.server.task.TaskCondition recoverySuccessCondition(
			dev.squire.server.task.TaskStateStore.Snapshot snapshot) {
		Object fraction = snapshot.parameters().get("targetHealthFraction");
		return fraction instanceof Number number
			? hasHealthFraction(number.doubleValue()) : null;
	}

	/** Goal condition over the narrow body interface: health fraction reached. */
	public static dev.squire.server.task.TaskCondition hasHealthFraction(double fraction) {
		return dev.squire.server.task.TaskCondition.of(
			ctx -> ctx.body() != null && ctx.body().snapshotState().health()
				>= fraction * ctx.body().snapshotState().maxHealth(),
			"health at least " + Math.round(fraction * 100) + "%");
	}
}
