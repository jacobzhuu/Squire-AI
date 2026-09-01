package dev.squire.server.i18n;

import java.util.function.Predicate;

/**
 * 伙伴认得的<b>全部</b>词汇：物品名、附魔名、状态效果名、结构名。
 *
 * <p>把这几张表绑在一起传，是为了让「加一张词表」这件事只需要改一处。之前解析器
 * 只拿得到物品表，于是「取消荆棘附魔」「加个夜视」「最近的远古城市在哪」在最底层
 * 就无从谈起——不是它解析错了，是它手上根本没有那些词。</p>
 */
public record Vocabulary(ItemAliasResolver items, NameAliasResolver enchantments,
		NameAliasResolver effects, NameAliasResolver structures,
		NameAliasResolver biomes, NameAliasResolver entities) {

	private static final String ITEM_RESOURCE = "/data/squire/aliases/zh_cn.json";
	private static final String ENCHANTMENT_RESOURCE =
		"/data/squire/aliases/enchantments_zh_cn.json";
	private static final String EFFECT_RESOURCE =
		"/data/squire/aliases/effects_zh_cn.json";
	private static final String STRUCTURE_RESOURCE =
		"/data/squire/aliases/structures_zh_cn.json";
	private static final String BIOME_RESOURCE =
		"/data/squire/aliases/biomes_zh_cn.json";
	private static final String ENTITY_RESOURCE =
		"/data/squire/aliases/entities_zh_cn.json";

	/**
	 * 从内置资源装载全部词表，注册表校验由调用方注入。
	 *
	 * <p>结构那张表故意<b>不</b>做注册表校验（{@code alwaysValid}）：结构 id 可能是
	 * 标签（{@code #minecraft:village}），也可能来自数据包，注册表在这一层看不全。
	 * 真正的把关在 {@code /locate} 自己身上——找不到就如实报找不到。</p>
	 */
	public static Vocabulary fromResources(Predicate<String> registryHasItem,
			Predicate<String> registryHasEnchantment,
			Predicate<String> registryHasEffect,
			Predicate<String> registryHasEntity) {
		return new Vocabulary(
			ItemAliasResolver.fromResource(registryHasItem, ITEM_RESOURCE),
			NameAliasResolver.fromResource(registryHasEnchantment, ENCHANTMENT_RESOURCE),
			NameAliasResolver.fromResource(registryHasEffect, EFFECT_RESOURCE),
			NameAliasResolver.fromResource(id -> true, STRUCTURE_RESOURCE),
			// 群系和结构一样不做注册表校验：数据包可以加，而且可能是标签。
			// 真正的把关在 /locate 自己身上——找不到就如实报找不到。
			NameAliasResolver.fromResource(id -> true, BIOME_RESOURCE),
			NameAliasResolver.fromResource(registryHasEntity, ENTITY_RESOURCE));
	}

	/** 测试用：物品与附魔共用一个判据，其余一律放行。 */
	public static Vocabulary fromResources(Predicate<String> registryHasItem,
			Predicate<String> registryHasEnchantment) {
		return fromResources(registryHasItem, registryHasEnchantment,
			id -> true, id -> true);
	}

	public boolean isUsable() {
		return items != null && enchantments != null && effects != null
			&& structures != null && biomes != null && entities != null;
	}
}
