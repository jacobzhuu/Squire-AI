package dev.squire.server.nlu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.squire.server.i18n.Vocabulary;

/**
 * 槽位解析器：<b>从哪拿 × 哪几件 × 做什么 × 归谁</b>。
 *
 * <p>这一层最重要的用例是下面两条「改动已有装备」的：它们以前不是解析失败，而是
 * 整个能力模型里没有「修改」这个动词、没有「玩家已有的物品」这个作用域、也没有
 * 一张附魔词表。三样缺一，整类请求就永远做不到。</p>
 */
class ItemOperationParserTest {

	private static final Set<String> ARMOUR_MATERIALS =
		Set.of("netherite", "diamond", "iron", "golden", "chainmail", "leather");

	/** 假注册表：认得全套盔甲、几件常见工具，和全部原版附魔。 */
	private static Vocabulary vocabulary() {
		return Vocabulary.fromResources(id -> {
			for (String material : ARMOUR_MATERIALS) {
				for (String piece : new String[] {
						"helmet", "chestplate", "leggings", "boots"}) {
					if (id.equals("minecraft:" + material + "_" + piece)) {
						return true;
					}
				}
			}
			return Set.of("minecraft:torch", "minecraft:oak_log",
				"minecraft:iron_pickaxe", "minecraft:netherite_sword",
				"minecraft:iron_sword", "minecraft:bread").contains(id);
		}, id -> id.startsWith("minecraft:"));
	}

	private static ItemOperation parse(String phrase) {
		var parsed = ItemOperationParser.parse(phrase, vocabulary());
		assertTrue(parsed.isPresent(), () -> "should parse: " + phrase);
		return parsed.get();
	}

	private static List<String> ids(ItemOperation operation) {
		return operation.lines().stream().map(ItemOperation.Line::itemId).toList();
	}

	// ================================================== 改动玩家已经有的装备

	/**
	 * 玩家实测失败的第一句。做不到的原因不是没听懂「荆棘」，而是<b>没有</b>一张
	 * 附魔词表可查，也没有任何动作能表达「去掉一条附魔」。
	 */
	@Test
	void removingOneNamedEnchantmentFromWornArmour() {
		ItemOperation op = parse("取消我附魔套装的荆棘附魔");
		assertEquals(ItemOperation.Scope.PLAYER_EQUIPPED, op.scope());
		assertEquals(ItemOperation.Transform.REMOVE_ENCHANT, op.transform());
		assertEquals(List.of("minecraft:thorns"), op.enchantmentIds());
		assertEquals(ItemOperation.Delivery.IN_PLACE, op.delivery());
		assertArmorSelector(op);
	}

	/** 玩家实测失败的第二句。 */
	@Test
	void clearingEveryEnchantmentOnWornArmour() {
		ItemOperation op = parse("把我身上套装的附魔全部取消");
		assertEquals(ItemOperation.Scope.PLAYER_EQUIPPED, op.scope());
		assertEquals(ItemOperation.Transform.CLEAR_ENCHANTS, op.transform());
		assertTrue(op.enchantmentIds().isEmpty());
		assertArmorSelector(op);
	}

	@Test
	void removalWordingVariantsAllLandOnTheSameOperation() {
		for (String phrase : new String[] {
				"取消我套装的荆棘附魔", "去掉我身上盔甲的荆棘", "把我身上的荆棘去除掉",
				"移除我盔甲上的反伤"}) {
			ItemOperation op = parse(phrase);
			assertEquals(ItemOperation.Transform.REMOVE_ENCHANT, op.transform(),
				() -> "should remove: " + phrase);
			assertEquals(List.of("minecraft:thorns"), op.enchantmentIds(),
				() -> "should name thorns: " + phrase);
		}
	}

	@Test
	void severalEnchantmentsCanBeNamedAtOnce() {
		ItemOperation op = parse("取消我套装的荆棘和摔落缓冲");
		assertEquals(ItemOperation.Transform.REMOVE_ENCHANT, op.transform());
		assertEquals(2, op.enchantmentIds().size());
		assertTrue(op.enchantmentIds().contains("minecraft:thorns"));
		assertTrue(op.enchantmentIds().contains("minecraft:feather_falling"));
	}

	/** 「火焰保护」里含着一个「保护」，长词必须赢。 */
	@Test
	void longestEnchantmentNameWins() {
		assertEquals(List.of("minecraft:fire_protection"),
			parse("去掉我套装的火焰保护").enchantmentIds());
		assertEquals(List.of("minecraft:protection"),
			parse("去掉我套装的保护").enchantmentIds());
	}

	@Test
	void handHeldScopeIsRecognisedSeparatelyFromWornArmour() {
		ItemOperation op = parse("去掉我手上这把剑的击退");
		assertEquals(ItemOperation.Scope.PLAYER_HELD, op.scope());
		assertEquals(List.of("minecraft:knockback"), op.enchantmentIds());
	}

	@Test
	void addingAnEnchantmentNeedsAnExistingItemScope() {
		ItemOperation op = parse("给我手上的剑加上锋利");
		assertEquals(ItemOperation.Scope.PLAYER_HELD, op.scope());
		assertEquals(ItemOperation.Transform.ADD_ENCHANT, op.transform());
		assertEquals(List.of("minecraft:sharpness"), op.enchantmentIds());
		// 反过来：没有"已有物品"作用域时，「加」不能把取物变成改动。
		ItemOperation give = parse("给我32个火把");
		assertTrue(give.isConjure());
	}

	@Test
	void repairIsItsOwnTransform() {
		ItemOperation op = parse("修一下我身上的装备");
		assertEquals(ItemOperation.Transform.REPAIR, op.transform());
		assertEquals(ItemOperation.Scope.PLAYER_EQUIPPED, op.scope());
	}

	/** 点不出要去掉什么就别猜——交给 LLM 问清楚，比乱改玩家的东西强。 */
	@Test
	void anUnspecifiedRemovalIsNotGuessed() {
		assertTrue(ItemOperationParser.parse("取消一下", vocabulary()).isEmpty());
		assertTrue(ItemOperationParser.parse("把那个去掉", vocabulary()).isEmpty());
	}

	/** 凭空造出来的东西不可能"原地改"，模型层也构造不出这种组合。 */
	@Test
	void conjuredItemsCanNeverBeEditedInPlace() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> new ItemOperation(ItemOperation.Scope.CONJURE,
				new ItemOperation.Selector.Concrete(
					List.of(new ItemOperation.Line("minecraft:torch", 1))),
				ItemOperation.Transform.NONE, List.of(),
				ItemOperation.Delivery.IN_PLACE, null));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> ItemOperation.edit(ItemOperation.Scope.PLAYER_EQUIPPED,
				ItemOperation.Selector.Filter.of(
					ItemOperation.Selector.Filter.Kind.ARMOR),
				ItemOperation.Transform.REMOVE_ENCHANT, List.of(), null));
	}

	// ============================================================ 凭空取物（回归）

	@Test
	void everyWayOfSayingAFullSetProducesTheSameFourPieces() {
		List<String> expected = List.of(
			"minecraft:netherite_helmet", "minecraft:netherite_chestplate",
			"minecraft:netherite_leggings", "minecraft:netherite_boots");
		for (String phrase : new String[] {
				"给我一套满配的下界合金套装",
				"给我一套下界合金套装",
				"给我下界合金全套",
				"给我一身下界合金盔甲",
				"给我下界合金防具",
				"拿给我一整套下界合金装备"}) {
			assertEquals(expected, ids(parse(phrase)), () -> "wrong set for: " + phrase);
		}
	}

	@Test
	void setsCanBeAskedForInPlural() {
		ItemOperation two = parse("给我两套铁套装");
		assertEquals(4, two.lines().size());
		assertEquals(2, two.lines().get(0).count());
		assertEquals(8, two.totalItems());
	}

	/** 「下界合金」里就含着一个「金」——材质必须按最长词匹配。 */
	@Test
	void longestMaterialWordWins() {
		assertEquals("minecraft:netherite_helmet", ids(parse("给我下界合金套装")).get(0));
		assertEquals("minecraft:golden_helmet", ids(parse("给我金套装")).get(0));
	}

	@Test
	void materialsWithoutArmourAreRejectedRatherThanGuessed() {
		assertTrue(ItemOperationParser.parse("给我一套石头套装", vocabulary()).isEmpty());
	}

	@Test
	void enchantWordingIsRecognisedInAllItsForms() {
		for (String phrase : new String[] {
				"给我一套满配的下界合金套装",
				"给我一套满配附魔的下界合金套装",
				"给我一套满级附魔的下界合金套装",
				"给我下界合金全套 满附魔",
				"给我一把附魔的铁镐",
				"give me a fully enchanted 铁镐"}) {
			assertEquals(ItemOperation.Transform.SET_MAX_ENCHANTS,
				parse(phrase).transform(), () -> "should be enchanted: " + phrase);
		}
		assertFalse(parse("给我一套下界合金套装").enchanted());
		assertFalse(parse("给我一把铁镐").enchanted());
	}

	@Test
	void selfEquipAndGivingThePlayerAreNeverConfused() {
		for (String selfish : new String[] {
				"给自己装备下界合金套装", "给自己穿上钻石套装", "装备铁套装",
				"穿上下界合金套装", "给自己拿一把下界合金剑", "拿起铁剑"}) {
			assertEquals(ItemOperation.Delivery.TO_AGENT_EQUIP, parse(selfish).delivery(),
				() -> "gear must stay on the companion: " + selfish);
		}
		for (String forPlayer : new String[] {
				"给我32个火把", "给我一把铁镐", "帮我砍20个橡木", "给我一套下界合金套装"}) {
			assertEquals(ItemOperation.Delivery.TO_PLAYER, parse(forPlayer).delivery(),
				() -> "must be handed to the player: " + forPlayer);
		}
	}

	@Test
	void quantityStaysWithTheAliasResolver() {
		assertEquals(32, parse("给我32个火把").lines().get(0).count());
		assertEquals(20, parse("帮我砍20个橡木").lines().get(0).count());
		assertEquals(1, parse("帮我做一把铁镐").lines().get(0).count());
	}

	// ----------------------------------------------------------------- 不许乱开火

	@Test
	void ordinaryChatNeverBecomesAnOrder() {
		for (String chat : new String[] {
				"这个下界合金套装真好看",
				"我的铁盔甲坏了",
				"你觉得钻石套装和下界合金套装哪个好",
				"hello there",
				"帮我准备一个矿井前哨站",
				"这个房子真好看"}) {
			assertTrue(ItemOperationParser.parse(chat, vocabulary()).isEmpty(),
				() -> "must stay conversational: " + chat);
		}
		assertTrue(ItemOperationParser.parse(null, vocabulary()).isEmpty());
		assertTrue(ItemOperationParser.parse("给我一套下界合金套装", null).isEmpty());
	}

	// ------------------------------------------------------------------ 结构化入口

	@Test
	void structuredEntryMatchesTheParsedPhrase() {
		var typed = ItemOperationParser.armorSet(ItemOperation.Delivery.TO_PLAYER,
			"netherite", 1, ItemOperation.Transform.SET_MAX_ENCHANTS, vocabulary())
			.orElseThrow();
		var spoken = parse("给我一套满配的下界合金套装");
		assertEquals(ids(typed), ids(spoken));
		assertEquals(typed.transform(), spoken.transform());
		assertEquals(typed.delivery(), spoken.delivery());

		assertEquals(ids(typed), ids(ItemOperationParser.armorSet(
			ItemOperation.Delivery.TO_PLAYER, "下界合金", 1,
			ItemOperation.Transform.SET_MAX_ENCHANTS, vocabulary()).orElseThrow()));
		assertTrue(ItemOperationParser.armorSet(ItemOperation.Delivery.TO_PLAYER,
			"想象中的材质", 1, ItemOperation.Transform.NONE, vocabulary()).isEmpty());
	}

	@Test
	void structuredPieceEntryStillValidatesAgainstTheRegistry() {
		assertEquals("minecraft:netherite_sword", ids(ItemOperationParser.piece(
			ItemOperation.Delivery.TO_AGENT_EQUIP, "下界合金剑", 1,
			ItemOperation.Transform.SET_MAX_ENCHANTS, vocabulary()).orElseThrow()).get(0));
		assertTrue(ItemOperationParser.piece(ItemOperation.Delivery.TO_PLAYER,
			"minecraft:not_a_real_item", 1, ItemOperation.Transform.NONE,
			vocabulary()).isEmpty());
	}

	@Test
	void duplicateLinesAreMergedForDelivery() {
		var operation = ItemOperation.conjure(
			List.of(new ItemOperation.Line("minecraft:torch", 32),
				new ItemOperation.Line("minecraft:torch", 32)),
			ItemOperation.Transform.NONE, List.of(),
			ItemOperation.Delivery.TO_PLAYER, null);
		assertEquals(1, operation.mergedLines().size());
		assertEquals(64, operation.mergedLines().get(0).count());
		assertEquals(64, operation.totalItems());
	}

	private static void assertArmorSelector(ItemOperation op) {
		assertNotNull(op.selector());
		assertTrue(op.selector() instanceof ItemOperation.Selector.Filter filter
			&& filter.kind() == ItemOperation.Selector.Filter.Kind.ARMOR,
			"should target the worn armour, not one specific item");
	}
}
