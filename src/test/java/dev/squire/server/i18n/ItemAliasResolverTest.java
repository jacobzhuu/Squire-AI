package dev.squire.server.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** §74 resolution order: registry id → exact alias → quantity normalization → validation. */
class ItemAliasResolverTest {

	/** Fake registry: only these ids "exist". */
	private static final Set<String> REGISTRY = Set.of(
		"minecraft:bread", "minecraft:torch", "minecraft:oak_log",
		"minecraft:diamond_sword", "minecraft:stone_bricks", "modded:gizmo");

	private static ItemAliasResolver resolver() {
		ItemAliasResolver r = new ItemAliasResolver(REGISTRY::contains);
		// inject a minimal table directly through the resource loader path
		return ItemAliasResolver.fromResource(REGISTRY::contains,
			"/data/squire/aliases/zh_cn.json");
	}

	@Test
	void bundledTableIsOriginalAndSubstantial() {
		ItemAliasResolver r = resolver();
		assertTrue(r.size() >= 100, "original table should carry 100+ entries, got "
			+ r.size());
	}

	@Test
	void constructionIdsHaveReadableChineseNames() {
		assertEquals("石砖", resolver().displayName("minecraft:stone_bricks"));
		assertEquals("火把", resolver().displayName("minecraft:torch"));
	}

	@Test
	void step1RegistryIdPassesThroughWhenItExists() {
		var out = resolver().resolve("minecraft:bread");
		assertTrue(out.isPresent());
		assertEquals("minecraft:bread", out.get().itemId());
		assertEquals(1, out.get().count());
		assertEquals("registry_id", out.get().matchedBy());
	}

	@Test
	void bareEnglishPathResolvesIntoMinecraftNamespace() {
		var out = resolver().resolve("torch");
		assertTrue(out.isPresent());
		assertEquals("minecraft:torch", out.get().itemId());
	}

	@Test
	void step2ExactChineseAlias() {
		var out = resolver().resolve("面包");
		assertTrue(out.isPresent());
		assertEquals("minecraft:bread", out.get().itemId());
	}

	@Test
	void leadingArabicQuantityWithQuantifier() {
		var out = resolver().resolve("3个面包");
		assertTrue(out.isPresent());
		assertEquals("minecraft:bread", out.get().itemId());
		assertEquals(3, out.get().count());
	}

	@Test
	void leadingQuantityAllowsNaturalSpacesBeforeQuantifier() {
		var out = resolver().resolve("32 个火把");
		assertTrue(out.isPresent());
		assertEquals("minecraft:torch", out.get().itemId());
		assertEquals(32, out.get().count());
	}

	@Test
	void cjkNumeralAndGroupMultiplier() {
		var out = resolver().resolve("两组火把");
		assertTrue(out.isPresent());
		assertEquals("minecraft:torch", out.get().itemId());
		assertEquals(128, out.get().count()); // 2 stacks of 64
	}

	@Test
	void trailingXNotationCounts() {
		var out = resolver().resolve("面包x10");
		assertTrue(out.isPresent());
		assertEquals(10, out.get().count());
	}

	@Test
	void unknownItemFailsClosedAtRegistryValidation() {
		// 钻石剑 maps to minecraft:diamond_sword which our fake registry HAS;
		// something absent must produce empty, never an unvalidated guess.
		assertTrue(resolver().resolve("不存在的物品").isEmpty());
	}

	@Test
	void aliasToMissingRegistryEntryIsEmpty() {
		ItemAliasResolver r = new ItemAliasResolver(id -> false); // nothing exists
		assertTrue(r.resolve("面包").isEmpty());
	}

	@Test
	void moddedNamespaceIdValidatesAgainstInjectedRegistry() {
		var out = resolver().resolve("modded:gizmo");
		assertTrue(out.isPresent());
		assertEquals("modded:gizmo", out.get().itemId());
	}

	@Test
	void countNeverExceedsGiveCeiling() {
		var out = resolver().resolve("99999999个面包");
		assertTrue(out.isEmpty(), "absurd counts refuse rather than clamp silently");
	}

	// ------------------------------------------------------------ 注册表译名（模组内容）

	/** 假注册表：两个模组物品 + 一个原版物品，名字就是玩家在物品栏里看到的那几个字。 */
	private static ItemAliasResolver withModdedNames() {
		java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
		names.put("create:andesite_alloy", "安山合金");
		names.put("farmersdelight:cooked_rice", "米饭");
		// 别名表里已经有「火把」，官方译名故意写成另一个词，用来验谁优先。
		names.put("minecraft:torch", "火炬");
		// 故意造一个和原版重名的模组物品，用来验「冲突时原版赢」。
		names.put("modded:gizmo", "面包");
		names.put("minecraft:bread", "面包");
		ItemAliasResolver r = ItemAliasResolver.fromResource(names::containsKey,
			"/data/squire/aliases/zh_cn.json");
		r.useRegistryNames(new ItemAliasResolver.RegistryNames() {
			@Override
			public String nameOf(String itemId) {
				return names.get(itemId);
			}

			@Override
			public java.util.List<String> allItemIds() {
				return java.util.List.copyOf(names.keySet());
			}
		});
		return r;
	}

	/**
	 * 模组物品说得出名字：这是「装了四十个模组、他只认原版」的正面用例。
	 */
	@Test
	void aModdedItemResolvesByItsOwnLocalizedName() {
		var out = withModdedNames().resolve("安山合金");
		assertTrue(out.isPresent(), "模组物品必须能用它自己的中文名叫出来");
		assertEquals("create:andesite_alloy", out.get().itemId());
	}

	/** 数量词照样管用：译名只是多了一条查表的路，不是另一套解析。 */
	@Test
	void aModdedItemStillAcceptsQuantities() {
		var out = withModdedNames().resolve("8个安山合金");
		assertTrue(out.isPresent());
		assertEquals("create:andesite_alloy", out.get().itemId());
		assertEquals(8, out.get().count());
	}

	/** 缺料清单里的模组物品必须写成中文，而不是 create:andesite_alloy。 */
	@Test
	void aModdedItemDisplaysItsLocalizedNameInsteadOfTheRawId() {
		assertEquals("安山合金",
			withModdedNames().displayName("create:andesite_alloy"));
		assertEquals("米饭", withModdedNames().displayName("farmersdelight:cooked_rice"));
	}

	/**
	 * 我们自己挑的说法优先于官方译名。
	 *
	 * <p>别名表是<b>挑过</b>的：同一件东西官方叫「火炬」，玩家嘴里是「火把」。
	 * 接上注册表不能把这份取舍冲掉，只能给它兜底。</p>
	 */
	@Test
	void ourOwnAliasStillWinsOverTheOfficialName() {
		assertEquals("火把", withModdedNames().displayName("minecraft:torch"));
	}

	/** 同名冲突时原版赢——同一句话每次都要解析到同一个物品。 */
	@Test
	void aNameCollisionPrefersVanilla() {
		var out = withModdedNames().resolve("面包");
		assertTrue(out.isPresent());
		assertEquals("minecraft:bread", out.get().itemId());
	}

	/** 没接注册表时行为完全不变（纯 Java 测试、专用服务器都是这个状态）。 */
	@Test
	void withoutRegistryNamesNothingChanges() {
		assertEquals("gizmo", resolver().displayName("modded:gizmo"));
		assertTrue(resolver().resolve("安山合金").isEmpty());
	}
}
