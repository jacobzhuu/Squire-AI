package dev.squire.server.item;

import java.util.List;
import java.util.Optional;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

/**
 * 伙伴照顾自己：残血时从<b>自己的背包</b>里挑一件东西吃掉/喝掉。
 *
 * <p>以前这件事只能由玩家喊「治疗自己」来触发一次任务——一个需要玩家盯着血条、
 * 在战斗中还要腾出手打字的能力，等于没有。现在它是一条反射（{@code SelfCareGoal}），
 * 手动那条路仍然保留，两边共用这里的同一套挑选与施用逻辑。</p>
 *
 * <p>挑什么由 {@link Remedies} 决定（够用且最便宜）；怎么用也由它决定——喷溅药水
 * 永远不会被「喝」下去。</p>
 */
public final class SelfCare {

	/**
	 * 掉到最大生命的这个比例以下就自己想办法。
	 *
	 * <p>之前是 0.5，也就是 20 血要掉到 10 以下才动——玩家看着他 14/20 站着不吃东西，
	 * 完全合理地认为「自动进食坏了」。「够用且最便宜」的挑选逻辑本来就会优先啃普通
	 * 食物，所以调高阈值不会变成拿金苹果当饭吃。</p>
	 */
	public static final float TRIGGER_FRACTION = 0.84f;

	/** 两次自救之间的最短间隔，避免一秒吃掉半个背包。 */
	public static final int COOLDOWN_TICKS = 40;

	/** 吃失败之后的重试间隔。要短——失败意味着该换一件试，而不是干等。 */
	public static final int RETRY_TICKS = 5;

	/** 连续失败这么多次就退回长冷却，别把空转跑成每 5 tick 一次的死循环。 */
	public static final int MAX_RETRIES = 3;

	private SelfCare() {
	}

	public static float missingHealth(LivingEntity entity) {
		return Math.max(0f, entity.getMaxHealth() - entity.getHealth());
	}

	/**
	 * 已经在回的血够不够补上缺口。够就先等着，别浪费物品。
	 *
	 * <p><b>不能把吸收当成回血。</b>吸收是护盾不是治疗，而金苹果给整整 2400 tick
	 * （2 分钟）的吸收——之前把它算进来，等于吃一个金苹果就把自救锁死两分钟，
	 * 期间掉到 1 血也不会再吃任何东西。</p>
	 *
	 * <p>再生也要看量：剩两秒的再生 I 只能回不到 1 点血，缺 8 点的时候它不该
	 * 继续挡着。按「再生 I 每 50 tick 回 1 点，每高一级间隔减半」估算。</p>
	 */
	public static boolean stillHealing(LivingEntity entity) {
		var regeneration = entity.getStatusEffect(StatusEffects.REGENERATION);
		if (regeneration == null) {
			return false;
		}
		int interval = Math.max(1, 50 >> regeneration.getAmplifier());
		float pending = (float) regeneration.getDuration() / interval;
		return pending >= missingHealth(entity);
	}

	/**
	 * Whether base health is currently rising over time. Unlike absorption, regeneration
	 * is actual healing; an explicit heal task waits for it to finish before consuming
	 * another non-stacking remedy.
	 */
	public static boolean hasActiveRegeneration(LivingEntity entity) {
		return entity.getStatusEffect(StatusEffects.REGENERATION) != null;
	}

	public static boolean needsCare(LivingEntity entity) {
		return needsCare(entity, TRIGGER_FRACTION);
	}

	public static boolean needsCare(LivingEntity entity, float trigger) {
		return entity.isAlive() && entity.getHealth() < trigger * entity.getMaxHealth();
	}

	/**
	 * 这只随从掉到多少血才自己想办法。
	 *
	 * <p>守卫 Lv.6 起改用配置里的 {@code foodHealThreshold}，好让服主能单独调守卫的
	 * 进食时机。<b>默认值刻意就是通用的 0.84</b>，而不是设计文档提到的 0.65——
	 * 让一个练到 Lv.6 的守卫比没有职业的随从更晚吃东西，是把成长做成了退步。</p>
	 */
	public static float triggerFraction(
			dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config) {
		if (profession == null || config == null
				|| !profession.can(dev.squire.server.profession.ProfessionAbility
					.GUARD_SHIELD_PROFICIENCY)) {
			return TRIGGER_FRACTION;
		}
		return (float) config.guardFoodHealThreshold;
	}

	/** 背包里自己能用的最合适的一件。 */
	public static Optional<Remedies.Remedy> pick(AvatarEntity avatar) {
		return pick(avatar, true);
	}

	/**
	 * 同上，但可以把<b>药水</b>排除在外。
	 *
	 * <p>守卫 Lv.6「治疗时机」用它执行设计文档 §19 的那条规矩：<b>先吃饭，再喝药</b>。
	 * 「够用且最便宜」已经会优先啃面包，但缺口一大它就会去开药水——而战斗中掉六格血
	 * 是常态，不是急救。把药水在高血量段直接拿掉，才真的挡得住「掉一点血就灌一瓶」。</p>
	 */
	public static Optional<Remedies.Remedy> pick(AvatarEntity avatar,
			boolean potionsAllowed) {
		Iterable<ItemStack> available = stacks(avatar);
		if (!potionsAllowed) {
			List<ItemStack> withoutPotions = new java.util.ArrayList<>();
			for (ItemStack stack : available) {
				if (!(stack.getItem() instanceof net.minecraft.item.PotionItem)) {
					withoutPotions.add(stack);
				}
			}
			available = withoutPotions;
		}
		return Remedies.bestForSelf(available, missingHealth(avatar));
	}

	/**
	 * 现在允许喝药吗。
	 *
	 * <p>没有守卫 Lv.6 的一律允许——这条能力<b>只收紧</b>已经解锁它的那些随从，
	 * 绝不让别人因为职业系统上线而突然不会喝药了。</p>
	 */
	public static boolean potionsAllowed(AvatarEntity avatar,
			dev.squire.server.profession.ProfessionData profession,
			dev.squire.server.profession.ProfessionConfig config) {
		if (avatar == null || profession == null || config == null
				|| !profession.can(dev.squire.server.profession.ProfessionAbility
					.GUARD_SHIELD_PROFICIENCY)) {
			return true;
		}
		float max = avatar.getMaxHealth();
		return max <= 0 || avatar.getHealth() / max <= config.guardPotionHealThreshold;
	}

	/** 背包的可迭代视图，供 {@link Remedies} 扫描。 */
	public static Iterable<ItemStack> stacks(AvatarEntity avatar) {
		var items = avatar.items();
		List<ItemStack> out = new java.util.ArrayList<>();
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack stack = items.getStack(slot);
			if (!stack.isEmpty()) {
				out.add(stack);
			}
		}
		return out;
	}

	/**
	 * 取出并使用一件治疗物品。
	 *
	 * @return 真的产生了效果才返回 true；没有效果时物品已原样退回背包
	 */
	public static boolean useOnSelf(AvatarEntity avatar, Identifier itemId) {
		var items = avatar.items();
		List<ItemStack> taken = items.extract(itemId, 1);
		if (taken.isEmpty()) {
			return false;
		}
		ItemStack stack = taken.get(0);
		if (consume(avatar, stack)) {
			return true;
		}
		items.insert(stack); // 没有产生任何效果就退回物品
		return false;
	}

	/**
	 * 通过物品<b>自己的</b> {@code finishUsing} 路径使用一件东西：再生、吸收、瞬间
	 * 治疗与玩家用下去时完全一致，不存在「金苹果 = 固定 4 点」这种假疗效。
	 *
	 * <p>唯一的补充是普通食物。伙伴是生物、没有饥饿条，香草的进食路径对它<b>不回
	 * 任何血</b>——这正是「背着一组熟牛排却说没有治疗物品」的根因。这里按营养值补上
	 * 一段<b>再生</b>效果，而不是直接加血：机制看得见、能被牛奶清掉，和玩家吃饱后
	 * 自然回复是同一套东西。</p>
	 */
	public static boolean consume(AvatarEntity avatar, ItemStack stack) {
		Optional<Remedies.Remedy> remedy = Remedies.classify(stack);
		float missingBefore = missingHealth(avatar);
		boolean plainFood = remedy.isPresent()
			&& remedy.get().kind() == Remedies.Kind.PLAIN_FOOD;
		int regenerationTicks = plainFood
			? Remedies.plainFoodRegenerationTicks(stack) : 0;

		float healthBefore = avatar.getHealth();
		int effectsBefore = avatar.getStatusEffects().size();
		// 香草的进食路径要求物品拿在手上，但主手可能正握着武器——先存下来，
		// 用完原样放回去，绝不能把装备覆盖掉。
		ItemStack held = avatar.items().equipped(EquipmentSlot.MAINHAND);
		avatar.setStackInHand(Hand.MAIN_HAND, stack);
		ItemStack leftover;
		try {
			leftover = stack.finishUsing(avatar.getWorld(), avatar);
		} finally {
			avatar.setStackInHand(Hand.MAIN_HAND, held);
		}
		if (leftover != null && !leftover.isEmpty() && leftover != stack) {
			avatar.items().insert(leftover); // 空瓶/空碗回到背包
		}
		if (regenerationTicks > 0) {
			avatar.addStatusEffect(new StatusEffectInstance(
				StatusEffects.REGENERATION, regenerationTicks, 0, false, true, true));
		}
		boolean used = avatar.getHealth() > healthBefore
			|| avatar.getStatusEffects().size() > effectsBefore
			|| regenerationTicks > 0;
		if (used) {
			remedy.ifPresent(value -> dev.squire.server.combat.CombatBalanceTelemetry
				.recordConsumable(avatar.agentId(), value, missingBefore));
		}
		return used;
	}

	/** 食物自身的效果表（救助他人时按同一份表施用）。 */
	public static List<StatusEffectInstance> foodEffects(ItemStack stack) {
		return Remedies.foodEffects(stack);
	}
}
