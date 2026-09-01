package dev.squire.server.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.PotionUtil;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 「这件东西能不能治伤、怎么用、能回多少」的<b>唯一</b>判定。
 *
 * <p>以前这套逻辑写在 {@code OwnerAidExecutor} 里，只分三档（喷溅药水 / 可饮用药水 /
 * 带效果的食物），并且有两个真问题：</p>
 * <ul>
 *   <li><b>分不清药水种类。</b>喷溅药水评分最高，于是自救时伙伴会去「喝」一瓶
 *       喷溅药水——原版里根本没有这个动作。速度、力量、抗火这类增益药水和治疗药水
 *       也没有区别对待。</li>
 *   <li><b>看不见普通食物。</b>判据是「食物的状态效果里有治疗成分」，于是牛排、
 *       面包、胡萝卜全部评为 null。伙伴背着一整组熟牛排，却报
 *       {@code INSUFFICIENT_HEALING_ITEM}。</li>
 * </ul>
 *
 * <p>现在按<b>用法</b>（自己喝 / 自己吃 / 扔出去）和<b>疗效</b>（瞬间治疗 / 持续
 * 回复 / 吸收 / 食物）两个维度分类，并给出一个预估回血量，调用方据此挑「够用且最
 * 便宜」的那件，而不是永远挑最猛的。</p>
 */
public final class Remedies {

	/** 怎么用这件东西。 */
	public enum Use {
		/** 自己喝下去（可饮用药水）。 */
		DRINK,
		/** 自己吃下去（食物、金苹果）。 */
		EAT,
		/** 朝目标扔出去（喷溅 / 滞留药水）——<b>永远不能自己喝</b>。 */
		THROW
	}

	/** 疗效类型，纯粹为了让日志和回执说得清楚。 */
	public enum Kind {
		INSTANT_HEALTH, REGENERATION, ABSORPTION, ENCHANTED_FOOD, PLAIN_FOOD
	}

	/**
	 * 一件可用的治疗物品。
	 *
	 * @param expectedHeal 预估能回多少点生命（吸收按等效血量折算），用于「够用就行」
	 * @param cost         珍贵程度，同样够用时挑数字小的（面包 &lt; 牛排 &lt; 药水 &lt; 金苹果）
	 */
	public record Remedy(Identifier itemId, Use use, Kind kind,
			float expectedHeal, int cost) { }

	private Remedies() {
	}

	// ------------------------------------------------------------------ 分类

	/** 判断一件物品能不能治伤；不能就返回空。 */
	public static Optional<Remedy> classify(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return Optional.empty();
		}
		Identifier id = Registries.ITEM.getId(stack.getItem());
		// —— 药水：先看用法，再看内容 ——
		if (stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION)) {
			return potion(stack, id, Use.THROW, 40);
		}
		if (stack.isOf(Items.POTION)) {
			// 水瓶、增益药水、伤害药水全部会在这里被 potion() 判掉。
			return potion(stack, id, Use.DRINK, 30);
		}
		// —— 食物 ——
		var food = stack.getItem().getFoodComponent();
		if (food == null) {
			return Optional.empty();
		}
		List<StatusEffectInstance> effects = foodEffects(stack);
		if (isHarmful(effects)) {
			return Optional.empty(); // 腐肉、河豚这类：宁可饿着也不能自己吃出毒
		}
		float fromEffects = healAmount(effects);
		if (fromEffects > 0f) {
			// 金苹果一类：既是食物又自带治疗效果。
			return Optional.of(new Remedy(id, Use.EAT, Kind.ENCHANTED_FOOD,
				fromEffects, 60));
		}
		// 普通食物。伙伴没有饥饿条，所以它的营养会被折算成一段再生（见
		// SelfCare），而不是凭空加血——「4.0 HP 的金苹果」那种假疗效不再出现。
		return Optional.of(new Remedy(id, Use.EAT, Kind.PLAIN_FOOD,
			plainFoodHeal(stack), 10));
	}

	private static Optional<Remedy> potion(ItemStack stack, Identifier id, Use use,
			int cost) {
		List<StatusEffectInstance> effects = PotionUtil.getPotionEffects(stack);
		if (effects.isEmpty() || isHarmful(effects)) {
			return Optional.empty(); // 水瓶 / 伤害药水 / 毒药
		}
		float heal = healAmount(effects);
		if (heal <= 0f) {
			return Optional.empty(); // 速度、力量、抗火……是增益，不是治疗
		}
		Kind kind = effects.stream().anyMatch(
				e -> e.getEffectType() == StatusEffects.INSTANT_HEALTH)
			? Kind.INSTANT_HEALTH : Kind.REGENERATION;
		return Optional.of(new Remedy(id, use, kind, heal, cost));
	}

	/** 食物自身的效果表。 */
	public static List<StatusEffectInstance> foodEffects(ItemStack stack) {
		var food = stack.getItem().getFoodComponent();
		if (food == null) {
			return List.of();
		}
		List<StatusEffectInstance> effects = new ArrayList<>();
		for (var pair : food.getStatusEffects()) {
			effects.add(pair.getFirst());
		}
		return effects;
	}

	/** 有没有有害成分。有一条就整件否掉，不做「利大于弊」的权衡。 */
	public static boolean isHarmful(List<StatusEffectInstance> effects) {
		for (StatusEffectInstance effect : effects) {
			StatusEffect type = effect.getEffectType();
			if (type == StatusEffects.INSTANT_DAMAGE || type == StatusEffects.POISON
					|| type == StatusEffects.WITHER
					|| type.getCategory() == StatusEffectCategory.HARMFUL) {
				return true;
			}
		}
		return false;
	}

	/** 这组效果大约能回多少点生命。 */
	public static float healAmount(List<StatusEffectInstance> effects) {
		float total = 0f;
		for (StatusEffectInstance effect : effects) {
			StatusEffect type = effect.getEffectType();
			int amplifier = effect.getAmplifier();
			if (type == StatusEffects.INSTANT_HEALTH) {
				total += 4f * (1 << amplifier); // 香草：4 << amplifier
			} else if (type == StatusEffects.REGENERATION) {
				// 再生 I 每 50 tick 回 1 点，等级每高一级间隔减半
				int interval = Math.max(1, 50 >> amplifier);
				total += (float) effect.getDuration() / interval;
			} else if (type == StatusEffects.ABSORPTION) {
				total += 4f * (amplifier + 1); // 吸收心按等效血量算
			}
		}
		return total;
	}

	/**
	 * 普通食物折算出的回血量。
	 *
	 * <p>伙伴是生物、没有饥饿条，香草的 {@code eatFood} 对它<b>不产生任何回血</b>——
	 * 这正是「背着一组牛排却说没有治疗物品」的根因。这里按营养值折算：一点营养约等于
	 * 半颗心，和玩家吃饱后自然回复的量级相当。</p>
	 */
	public static float plainFoodHeal(ItemStack stack) {
		var food = stack.getItem().getFoodComponent();
		if (food == null) {
			return 0f;
		}
		return Math.max(1f, food.getHunger() / 2f);
	}

	/**
	 * 普通食物折算成的再生时长（tick）。
	 *
	 * <p>回血走香草的再生效果而不是直接加血：一口牛排慢慢回四颗心，看得见、也能被
	 * 牛奶清掉，和玩家吃饱后自然回复是同一套机制。再生 I 每 50 tick 回 1 点，
	 * 所以时长就是折算血量 × 50。</p>
	 */
	public static int plainFoodRegenerationTicks(ItemStack stack) {
		return Math.round(plainFoodHeal(stack) * 50f);
	}

	// ------------------------------------------------------------------ 挑选

	/**
	 * 自己能用的最合适的一件：<b>够用且最便宜</b>。
	 *
	 * <p>刻意不是「最猛的一件」。缺半颗心时灌一瓶治疗药水 II，玩家会觉得伙伴在糟蹋
	 * 家当；而缺八颗心时啃一口面包又等于没治。所以先挑能补上缺口的，再在其中挑最
	 * 不值钱的；一件都补不满时退而求其次挑效果最好的。</p>
	 *
	 * @param missingHealth 还差多少点生命
	 */
	public static Optional<Remedy> bestForSelf(Iterable<ItemStack> inventory,
			float missingHealth) {
		return best(inventory, missingHealth,
			remedy -> remedy.use() == Use.DRINK || remedy.use() == Use.EAT);
	}

	/** 能朝别人扔出去的最合适的一件（喷溅 / 滞留药水）。 */
	public static Optional<Remedy> bestForThrowing(Iterable<ItemStack> inventory,
			float missingHealth) {
		return best(inventory, missingHealth, remedy -> remedy.use() == Use.THROW);
	}

	/**
	 * 救助他人时：能扔就扔，扔不了就把带效果的递过去。
	 *
	 * <p>普通食物在这里<b>不算</b>治疗手段：玩家有自己的饥饿条，塞给他一块牛排并
	 * 不会让他回血，伙伴也没法替他吃。硬把它算成治疗，只会让「救我」变成
	 * 「给你一块肉，然后报告已经救过了」。</p>
	 */
	public static Optional<Remedy> bestForOther(Iterable<ItemStack> inventory,
			float missingHealth) {
		Optional<Remedy> thrown = bestForThrowing(inventory, missingHealth);
		return thrown.isPresent() ? thrown
			: best(inventory, missingHealth, remedy -> remedy.kind() != Kind.PLAIN_FOOD
				&& (remedy.use() == Use.DRINK || remedy.use() == Use.EAT));
	}

	private static Optional<Remedy> best(Iterable<ItemStack> inventory,
			float missingHealth, java.util.function.Predicate<Remedy> usable) {
		Remedy cheapestSufficient = null;
		Remedy strongest = null;
		for (ItemStack stack : inventory) {
			Optional<Remedy> candidate = classify(stack);
			if (candidate.isEmpty() || !usable.test(candidate.get())) {
				continue;
			}
			Remedy remedy = candidate.get();
			if (strongest == null || remedy.expectedHeal() > strongest.expectedHeal()) {
				strongest = remedy;
			}
			if (remedy.expectedHeal() + 0.001f >= missingHealth
					&& (cheapestSufficient == null
						|| remedy.cost() < cheapestSufficient.cost())) {
				cheapestSufficient = remedy;
			}
		}
		return Optional.ofNullable(cheapestSufficient != null ? cheapestSufficient
			: strongest);
	}
}
