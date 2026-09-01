package dev.squire.server.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.squire.server.nlu.ItemOperation;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 把一个 {@link ItemOperation.Transform} 真正落到一件 {@link ItemStack} 上。
 *
 * <p>这是原来整条链路缺掉的那一半：以前只有「凭空造一件带满配附魔的新物品」
 * （{@code StructuredCommandCompiler.AcquireIntent}），没有任何代码能改动一件
 * 已经存在的物品。附魔的增删改、耐久修复都收在这里，凭空造和原地改于是共用
 * 同一套语义——「满配附魔」不再是两处各写一遍的特例。</p>
 *
 * <p>纯 Minecraft 侧逻辑，不碰任务、权限和玩家；调用方负责快照与撤销。</p>
 */
public final class ItemTransforms {

	/** 单件物品最多挂这么多条附魔，和取物那边一致。 */
	private static final int MAX_ENCHANTMENTS = 10;

	private ItemTransforms() {
	}

	/** 一次改动的结果，用来生成玩家看得懂的回执。 */
	public record Outcome(boolean changed, String detail) {
		public static Outcome unchanged(String detail) {
			return new Outcome(false, detail);
		}

		public static Outcome changed(String detail) {
			return new Outcome(true, detail);
		}
	}

	/**
	 * 就地改动一件物品。
	 *
	 * @param stack          会被直接修改的物品（调用方应已存好快照）
	 * @param transform      要做什么
	 * @param enchantmentIds {@code ADD_ENCHANT} / {@code REMOVE_ENCHANT} 的目标附魔
	 * @return 是否真的改了，以及一句人话说明
	 */
	public static Outcome apply(ItemStack stack, ItemOperation.Transform transform,
			List<String> enchantmentIds) {
		if (stack == null || stack.isEmpty()) {
			return Outcome.unchanged("空槽位");
		}
		return switch (transform) {
			case NONE -> Outcome.unchanged("没有要改的");
			case REPAIR -> repair(stack);
			case CLEAR_ENCHANTS -> clearEnchants(stack);
			case REMOVE_ENCHANT -> removeEnchants(stack, enchantmentIds);
			case ADD_ENCHANT -> addEnchants(stack, enchantmentIds);
			case SET_MAX_ENCHANTS -> setMaxEnchants(stack);
		};
	}

	// ------------------------------------------------------------------- 耐久

	private static Outcome repair(ItemStack stack) {
		if (!stack.isDamageable()) {
			return Outcome.unchanged("不会损坏");
		}
		if (stack.getDamage() == 0) {
			return Outcome.unchanged("耐久已满");
		}
		stack.setDamage(0);
		return Outcome.changed("耐久修满");
	}

	// ------------------------------------------------------------------- 附魔

	private static Outcome clearEnchants(ItemStack stack) {
		Map<Enchantment, Integer> current = EnchantmentHelper.get(stack);
		if (current.isEmpty()) {
			return Outcome.unchanged("本来就没有附魔");
		}
		int removed = current.size();
		EnchantmentHelper.set(Map.of(), stack);
		return Outcome.changed("清掉 " + removed + " 条附魔");
	}

	private static Outcome removeEnchants(ItemStack stack, List<String> enchantmentIds) {
		Map<Enchantment, Integer> current = new LinkedHashMap<>(EnchantmentHelper.get(stack));
		if (current.isEmpty()) {
			return Outcome.unchanged("本来就没有附魔");
		}
		List<String> removed = new java.util.ArrayList<>();
		for (String id : enchantmentIds) {
			Enchantment enchantment = lookup(id);
			if (enchantment != null && current.remove(enchantment) != null) {
				removed.add(shortName(id));
			}
		}
		if (removed.isEmpty()) {
			return Outcome.unchanged("没有这条附魔");
		}
		EnchantmentHelper.set(current, stack);
		return Outcome.changed("去掉 " + String.join("、", removed));
	}

	private static Outcome addEnchants(ItemStack stack, List<String> enchantmentIds) {
		Map<Enchantment, Integer> current = new LinkedHashMap<>(EnchantmentHelper.get(stack));
		List<String> added = new java.util.ArrayList<>();
		List<String> refused = new java.util.ArrayList<>();
		for (String id : enchantmentIds) {
			Enchantment enchantment = lookup(id);
			if (enchantment == null) {
				continue;
			}
			// 挂不上去的就如实说挂不上去，而不是硬写进 NBT 造出一件原版做不出的东西。
			if (!enchantment.isAcceptableItem(stack)) {
				refused.add(shortName(id) + "（这件东西挂不了）");
				continue;
			}
			boolean conflicts = current.keySet().stream()
				.anyMatch(existing -> existing != enchantment
					&& !existing.canCombine(enchantment));
			if (conflicts) {
				refused.add(shortName(id) + "（和已有附魔冲突）");
				continue;
			}
			if (current.size() >= MAX_ENCHANTMENTS
					&& !current.containsKey(enchantment)) {
				refused.add(shortName(id) + "（附魔条数已满）");
				continue;
			}
			Integer existingLevel = current.get(enchantment);
			int level = enchantment.getMaxLevel();
			if (existingLevel != null && existingLevel >= level) {
				refused.add(shortName(id) + "（已经是最高级）");
				continue;
			}
			current.put(enchantment, level);
			added.add(shortName(id) + " " + level);
		}
		if (added.isEmpty()) {
			return Outcome.unchanged(refused.isEmpty() ? "没有可加的附魔"
				: String.join("、", refused));
		}
		EnchantmentHelper.set(current, stack);
		String tail = refused.isEmpty() ? "" : "；跳过 " + String.join("、", refused);
		return Outcome.changed("加上 " + String.join("、", added) + tail);
	}

	private static Outcome setMaxEnchants(ItemStack stack) {
		// 「满配」到底是哪几条，只在 StructuredCommandCompiler 里定义一次：凭空造的
		// 新物品和原地改的旧物品必须得到完全一样的结果，否则同一个词又有了两种含义。
		Map<Enchantment, Integer> chosen = new LinkedHashMap<>();
		for (var spec : dev.squire.server.command.StructuredCommandCompiler
				.maxEnchantmentsFor(Registries.ITEM.getId(stack.getItem()))) {
			Enchantment enchantment = Registries.ENCHANTMENT.get(spec.enchantmentId());
			if (enchantment != null) {
				chosen.put(enchantment, spec.level());
			}
		}
		if (chosen.isEmpty()) {
			return Outcome.unchanged("没有可以附的魔");
		}
		if (chosen.equals(EnchantmentHelper.get(stack))) {
			return Outcome.unchanged("已经是满配");
		}
		EnchantmentHelper.set(chosen, stack);
		return Outcome.changed("附满 " + chosen.size() + " 条");
	}

	// ------------------------------------------------------------------ 工具

	private static Enchantment lookup(String id) {
		try {
			return Registries.ENCHANTMENT.get(new Identifier(id));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String shortName(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}
}
