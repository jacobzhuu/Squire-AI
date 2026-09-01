package dev.squire.server.profile;

import java.util.List;
import java.util.Locale;

import net.minecraft.util.Identifier;

/**
 * 「通用建材」能力用的等价组：缺一种木板时，另一种木板顶得上。
 *
 * <p>刻意<b>窄</b>。等价组一旦放宽到「差不多的方块」，玩家就会拿到一栋自己没设计过的
 * 房子——花色不对比缺料更难受，而且没法撤销回自己想要的样子。所以只收那些
 * 「换了也是同一种建筑」的：各种木板互通、常见石料互通、石砖互通。
 * 玻璃、门、箱子这类形状或功能有区别的一律不进组。</p>
 *
 * <p>是纯函数、纯字符串，所以判定规则能被普通单测盖住，不必起一个服务器。</p>
 */
public final class MaterialSubstitutes {

	private static final List<List<String>> GROUPS = List.of(
		List.of("minecraft:oak_planks", "minecraft:spruce_planks",
			"minecraft:birch_planks", "minecraft:jungle_planks",
			"minecraft:acacia_planks", "minecraft:dark_oak_planks",
			"minecraft:mangrove_planks", "minecraft:cherry_planks",
			"minecraft:bamboo_planks", "minecraft:crimson_planks",
			"minecraft:warped_planks"),
		List.of("minecraft:cobblestone", "minecraft:stone", "minecraft:andesite",
			"minecraft:diorite", "minecraft:granite", "minecraft:deepslate",
			"minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:blackstone"),
		List.of("minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
			"minecraft:cracked_stone_bricks", "minecraft:deepslate_bricks",
			"minecraft:polished_deepslate", "minecraft:polished_blackstone_bricks"),
		List.of("minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
			"minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
			"minecraft:mangrove_log", "minecraft:cherry_log"));

	private MaterialSubstitutes() {
	}

	/**
	 * 可以顶替 {@code wanted} 的物品，<b>按组内顺序</b>，不含 wanted 自己。
	 * 不在任何组里就返回空——没有等价物比乱找一个像的强。
	 */
	public static List<Identifier> substitutesFor(Identifier wanted) {
		if (wanted == null) {
			return List.of();
		}
		String id = wanted.toString().toLowerCase(Locale.ROOT);
		for (List<String> group : GROUPS) {
			if (group.contains(id)) {
				return group.stream()
					.filter(candidate -> !candidate.equals(id))
					.map(Identifier::new)
					.toList();
			}
		}
		return List.of();
	}

	/** 这两样属于同一个等价组吗。施工成功条件用它判「盖的是不是同一种建筑」。 */
	public static boolean equivalent(Identifier a, Identifier b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.equals(b)) {
			return true;
		}
		String left = a.toString().toLowerCase(Locale.ROOT);
		String right = b.toString().toLowerCase(Locale.ROOT);
		for (List<String> group : GROUPS) {
			if (group.contains(left) && group.contains(right)) {
				return true;
			}
		}
		return false;
	}
}
