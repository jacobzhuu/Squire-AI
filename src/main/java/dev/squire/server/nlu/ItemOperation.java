package dev.squire.server.nlu;

import java.util.ArrayList;
import java.util.List;

/**
 * 「对物品做一件事」的完整描述：<b>从哪拿 × 哪几件 × 做什么 × 归谁</b>。
 *
 * <p>这个模型替掉了原来的 {@code ItemRequest}。后者只能表达一个动作——「凭空造一件
 * 新物品，可选满配附魔」——所以任何「改动玩家已经有的东西」的请求都不是解析失败，
 * 而是根本没有对应的能力：</p>
 * <ul>
 *   <li>没有「修改」这个动词，只有 {@code AcquireIntent} 的凭空生成；</li>
 *   <li>没有「玩家已有的物品」这个作用域，全仓库唯一碰玩家物品的地方是交付验收的
 *       {@code count()}；</li>
 *   <li>{@code enchant: NONE|MAX} 是个伪装成枚举的布尔值。</li>
 * </ul>
 *
 * <p>拆成四个正交的维度之后，「给我一套满配下界合金」和「取消我套装的荆棘附魔」
 * 只是同一个模型里的两组取值，而不是两条要分别实现的特例。</p>
 *
 * <p>纯数据，不碰 Minecraft 类型，解析器因此可以在普通单测里跑。</p>
 */
public record ItemOperation(Scope scope, Selector selector, Transform transform,
		List<String> enchantmentIds, Delivery delivery, String label) {

	/** 东西从哪来。 */
	public enum Scope {
		/** 凭空造：伙伴用指令给自己变出来再交接。 */
		CONJURE,
		/** 玩家<b>穿在身上</b>的护甲。 */
		PLAYER_EQUIPPED,
		/** 玩家<b>主手拿着</b>的那一件。 */
		PLAYER_HELD,
		/** 玩家背包里的物品。 */
		PLAYER_INVENTORY,
		/** 伙伴自己穿着/拿着的。 */
		AGENT_EQUIPPED;

		public boolean isPlayerOwned() {
			return this == PLAYER_EQUIPPED || this == PLAYER_HELD
				|| this == PLAYER_INVENTORY;
		}
	}

	/** 对选中的物品做什么。 */
	public enum Transform {
		/** 什么都不改（纯取物）。 */
		NONE,
		/** 挂满所有不冲突的最高级附魔。 */
		SET_MAX_ENCHANTS,
		/** 加上指定的几条（各自最高级）。 */
		ADD_ENCHANT,
		/** 去掉指定的几条。 */
		REMOVE_ENCHANT,
		/** 清空全部附魔。 */
		CLEAR_ENCHANTS,
		/** 修满耐久。 */
		REPAIR;

		/** 需要点名具体附魔的动作。 */
		public boolean needsEnchantmentList() {
			return this == ADD_ENCHANT || this == REMOVE_ENCHANT;
		}
	}

	/** 做完之后东西归谁。 */
	public enum Delivery {
		/** 伙伴走过来丢在玩家脚边。 */
		TO_PLAYER,
		/** 原地改动，物品始终在原来的位置上。 */
		IN_PLACE,
		/** 伙伴自己穿上/拿上。 */
		TO_AGENT_EQUIP
	}

	/** 选中哪些物品。 */
	public sealed interface Selector {

		/** 具体的物品清单——{@link Scope#CONJURE} 用，解析器已把套装展开成四件。 */
		record Concrete(List<Line> lines) implements Selector {
			public Concrete {
				if (lines == null || lines.isEmpty()) {
					throw new IllegalArgumentException("concrete selector needs lines");
				}
				lines = List.copyOf(lines);
			}
		}

		/** 在某个作用域里按条件挑——改动已有物品时用。 */
		record Filter(Kind kind, String itemId) implements Selector {
			public enum Kind {
				/** 该作用域里的全部物品。 */
				ALL,
				/** 只要护甲四件。 */
				ARMOR,
				/** 主手那一件。 */
				HELD,
				/** 指定 id 的那些。 */
				ITEM
			}

			public static Filter of(Kind kind) {
				return new Filter(kind, null);
			}

			public static Filter item(String itemId) {
				return new Filter(Kind.ITEM, itemId);
			}
		}
	}

	/** 清单里的一件物品。 */
	public record Line(String itemId, int count) {
		public Line {
			if (itemId == null || itemId.isBlank()) {
				throw new IllegalArgumentException("itemId must not be blank");
			}
			if (count < 1) {
				throw new IllegalArgumentException("count must be >= 1");
			}
		}
	}

	public ItemOperation {
		if (scope == null || selector == null || transform == null || delivery == null) {
			throw new IllegalArgumentException("scope/selector/transform/delivery required");
		}
		enchantmentIds = enchantmentIds == null ? List.of() : List.copyOf(enchantmentIds);
		if (transform.needsEnchantmentList() && enchantmentIds.isEmpty()) {
			throw new IllegalArgumentException(transform + " needs at least one enchantment");
		}
		// 组合校验放在构造里，执行器就不必再防御这些不可能的取值：
		// 凭空造的东西不可能"原地改"，已有的东西也不该被悄悄换主人。
		if (scope == Scope.CONJURE) {
			if (delivery == Delivery.IN_PLACE) {
				throw new IllegalArgumentException("conjured items need a delivery target");
			}
			if (!(selector instanceof Selector.Concrete)) {
				throw new IllegalArgumentException("conjuring needs a concrete item list");
			}
		} else if (delivery != Delivery.IN_PLACE) {
			throw new IllegalArgumentException(
				"editing existing items is always in place: " + scope);
		}
	}

	// ------------------------------------------------------------------ 便捷构造

	/** 凭空取物（可带附魔变换）。 */
	public static ItemOperation conjure(List<Line> lines, Transform transform,
			List<String> enchantmentIds, Delivery delivery, String label) {
		return new ItemOperation(Scope.CONJURE, new Selector.Concrete(lines),
			transform, enchantmentIds, delivery, label);
	}

	public static ItemOperation conjureOne(String itemId, int count, Transform transform,
			Delivery delivery, String label) {
		return conjure(List.of(new Line(itemId, count)), transform, List.of(),
			delivery, label);
	}

	/** 改动已有物品。 */
	public static ItemOperation edit(Scope scope, Selector selector, Transform transform,
			List<String> enchantmentIds, String label) {
		return new ItemOperation(scope, selector, transform, enchantmentIds,
			Delivery.IN_PLACE, label);
	}

	// ---------------------------------------------------------------------- 查询

	public boolean isConjure() {
		return scope == Scope.CONJURE;
	}

	/** 要不要挂满配附魔。 */
	public boolean enchanted() {
		return transform == Transform.SET_MAX_ENCHANTS;
	}

	/** {@link Scope#CONJURE} 的物品清单；其它作用域为空。 */
	public List<Line> lines() {
		return selector instanceof Selector.Concrete concrete
			? concrete.lines() : List.of();
	}

	public int totalItems() {
		int total = 0;
		for (Line line : lines()) {
			total += line.count();
		}
		return total;
	}

	/** 同一个 id 出现多次时合并数量，交付任务才不会重复提交。 */
	public List<Line> mergedLines() {
		List<Line> out = new ArrayList<>();
		for (Line line : lines()) {
			int existing = -1;
			for (int i = 0; i < out.size(); i++) {
				if (out.get(i).itemId().equals(line.itemId())) {
					existing = i;
					break;
				}
			}
			if (existing < 0) {
				out.add(line);
			} else {
				out.set(existing, new Line(line.itemId(),
					out.get(existing).count() + line.count()));
			}
		}
		return List.copyOf(out);
	}
}
