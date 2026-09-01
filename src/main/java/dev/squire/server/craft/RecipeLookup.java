package dev.squire.server.craft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * 「这东西怎么做」——<b>只读</b>的配方查询。
 *
 * <p>和 {@code crafting.craft} 的区别是它<b>什么都不消耗</b>：玩家问「信标怎么做」
 * 时想要的是一份材料清单，不是让伙伴现在就去做一个。以前只有"做出来"那条路，
 * 于是这个最常见的新手问题只能靠模型凭记忆回答——而模型记错配方是常事，
 * 尤其是模组配方。</p>
 *
 * <p>数据来自服务器<b>当前实际加载</b>的配方表，所以数据包和模组改过的配方也是对的。</p>
 */
public final class RecipeLookup {

	/** 一次最多列几条配方。同一件东西可能有十几种做法（比如各种木板）。 */
	private static final int MAX_RECIPES = 4;

	private RecipeLookup() {
	}

	/**
	 * 一条配方的可读描述。
	 *
	 * @param kind        crafting / smelting / blasting / smoking / ...
	 * @param ingredients 每种材料的一个代表选项（配方允许多选时取第一个）
	 */
	public record RecipeInfo(String kind, String outputId, int outputCount,
			List<String> ingredients) { }

	/** 查不到时区分「物品不存在」和「这东西做不出来」——两句话该说得不一样。 */
	public record Result(boolean itemExists, String itemId, List<RecipeInfo> recipes) { }

	public static Result of(ServerWorld world, String rawItemId) {
		Identifier id;
		try {
			id = rawItemId != null && rawItemId.contains(":")
				? new Identifier(rawItemId) : new Identifier("minecraft",
					String.valueOf(rawItemId));
		} catch (RuntimeException e) {
			return new Result(false, String.valueOf(rawItemId), List.of());
		}
		if (!Registries.ITEM.containsId(id)) {
			return new Result(false, id.toString(), List.of());
		}
		Item target = Registries.ITEM.get(id);
		List<RecipeInfo> out = new ArrayList<>();
		for (Recipe<?> recipe : world.getServer().getRecipeManager().values()) {
			if (out.size() >= MAX_RECIPES) {
				break;
			}
			ItemStack result;
			try {
				result = recipe.getOutput(world.getRegistryManager());
			} catch (RuntimeException e) {
				continue; // 坏配方不该毁掉整次查询
			}
			if (result.isEmpty() || result.getItem() != target) {
				continue;
			}
			out.add(new RecipeInfo(kindOf(recipe), id.toString(), result.getCount(),
				ingredientsOf(recipe)));
		}
		return new Result(true, id.toString(), List.copyOf(out));
	}

	private static String kindOf(Recipe<?> recipe) {
		RecipeType<?> type = recipe.getType();
		Identifier id = Registries.RECIPE_TYPE.getId(type);
		return id == null ? "crafting" : id.getPath();
	}

	/**
	 * 材料清单。配方里的每一格是一组「可接受的物品」，这里取第一个作为代表——
	 * 玩家要的是「大概需要什么」，不是一份完整的可替换项矩阵。
	 */
	private static List<String> ingredientsOf(Recipe<?> recipe) {
		// 同一种材料占多格时合并计数，别列九遍橡木板。
		java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (var ingredient : recipe.getIngredients()) {
			if (ingredient.isEmpty()) {
				continue;
			}
			ItemStack[] options = ingredient.getMatchingStacks();
			if (options.length == 0) {
				continue;
			}
			String name = Registries.ITEM.getId(options[0].getItem()).toString();
			counts.merge(name, 1, Integer::sum);
		}
		List<String> out = new ArrayList<>();
		for (var entry : counts.entrySet()) {
			out.add(entry.getKey() + " x" + entry.getValue());
		}
		return List.copyOf(out);
	}
}
