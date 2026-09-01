package dev.squire.server.fastpath;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.squire.api.body.EmoteType;
import dev.squire.server.fastpath.FastPathIntent.Control.Kind;
import dev.squire.server.i18n.Vocabulary;
import dev.squire.server.nlu.ItemOperation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec section 14.1 + 方案 A3/B1: P0 phrases map deterministically to structured
 * {@link FastPathIntent}s, never consult the LLM, and ordinary conversation falls
 * through (empty match). Resource sentences stay unmatched until B1 adds them.
 */
class FastPathTest {
	// ------------------------------------------------------------------ control

	@Test
	void chineseControlPhrasesMapToIntents() {
		assertEquals(Kind.FOLLOW, control("跟着我"));
		assertEquals(Kind.FOLLOW, control("跟我来"));
		assertEquals(Kind.FOLLOW, control("过来"));
		assertEquals(Kind.STAY, control("待在这里"));
		assertEquals(Kind.STAY, control("待在这"));
		assertEquals(Kind.STAY, control("别动"));
		assertEquals(Kind.STAY, control("站住"));
		assertEquals(Kind.STAY, control("原地站住"));
		assertEquals(Kind.STAY, control("原地别动"));
		assertEquals(Kind.STAY, control("不要走"));
		assertEquals(Kind.STAY, control("待命"));
		assertEquals(Kind.STOP, control("停止"));
		assertEquals(Kind.STOP, control("停下"));
		assertEquals(Kind.HOME_RETURN, control("回家"));
		assertEquals(Kind.STATUS, control("你在哪"));
		assertEquals(Kind.STATUS, control("状态"));
	}

	@Test
	void englishControlPhrasesMapToIntents() {
		assertEquals(Kind.FOLLOW, control("follow me"));
		assertEquals(Kind.FOLLOW, control("come here"));
		assertEquals(Kind.STAY, control("stay here"));
		assertEquals(Kind.STAY, control("stay"));
		assertEquals(Kind.STOP, control("stop"));
		assertEquals(Kind.HOME_RETURN, control("go home"));
		assertEquals(Kind.STATUS, control("where are you"));
		assertEquals(Kind.STATUS, control("status"));
	}

	@Test
	void normalizationToleratesCaseAndTrailingPunctuation() {
		assertEquals(Kind.FOLLOW, control("Follow Me!"));
		assertEquals(Kind.STAY, control("  Stay "));
		assertEquals(Kind.STOP, control("STOP。"));
	}

	@Test
	void projectPauseAndResumeAreDeterministic() {
		assertEquals(FastPathIntent.ProjectControl.Kind.PAUSE,
			projectControl("暂停工程"));
		assertEquals(FastPathIntent.ProjectControl.Kind.PAUSE,
			projectControl("先停工"));
		for (String phrase : new String[] {"继续工程", "复工", "重启工程", "接着做"}) {
			assertEquals(FastPathIntent.ProjectControl.Kind.RESUME,
				projectControl(phrase));
		}
	}

	@Test
	void safeShortCommandsTolerateOneUnambiguousTypo() {
		assertEquals(Kind.FOLLOW, control("跟这我"));
		assertEquals(Kind.FOLLOW, control("follow mw!"));
		assertEquals(Kind.STATUS, control("你在那"));
		assertTrue(FastPath.match("看这我").orElseThrow()
			instanceof FastPathIntent.LookAt);
		assertEquals(EmoteType.JUMP, emote("跳亿下"));
	}

	@Test
	void stateChangingCommandsAreNeverFuzzyGuessed() {
		assertTrue(FastPath.match("待在那里").isEmpty());
		assertTrue(FastPath.match("go homf").isEmpty());
		assertTrue(FastPath.match("stoo").isEmpty());
	}

	// ------------------------------------------------------- look & emote（A3）

	@Test
	void lookAtMePhrasesProduceLookIntentWithoutTarget() {
		Optional<FastPathIntent> zh = FastPath.match("看着我");
		assertTrue(zh.isPresent());
		FastPathIntent.LookAt lookZh = assertLook(zh.get());
		assertTrue(lookZh.target().isEmpty(), "'看着我' targets the owner");

		Optional<FastPathIntent> en = FastPath.match("Look at me!");
		assertTrue(en.isPresent());
		assertTrue(assertLook(en.get()).target().isEmpty());
	}

	@Test
	void emotePhrasesProduceEmoteIntents() {
		assertEquals(EmoteType.WAVE, emote("挥手"));
		assertEquals(EmoteType.WAVE, emote("Wave!"));
		assertEquals(EmoteType.JUMP, emote("跳一下"));
		assertEquals(EmoteType.JUMP, emote("jump"));
		assertEquals(EmoteType.NOD, emote("点头"));
		assertEquals(EmoteType.NOD, emote("nod"));
		assertEquals(EmoteType.SHAKE_HEAD, emote("摇头"));
		assertEquals(EmoteType.SHAKE_HEAD, emote("shake head"));
	}

	// ------------------------------------------------- 自己穿装备 vs 送给玩家

	/**
	 * 这两句只差一个"自己"，意思完全相反。之前没有这条规则，
	 * 「给自己装备下界合金套装」被当成了送给玩家，伙伴把整套盔甲扔在地上自己光着。
	 */
	@Test
	void equippingHimselfIsNeverConfusedWithGivingThePlayerGear() {
		// 套装/武器现在也要过注册表校验，所以这条用例需要一份认得盔甲的假注册表。
		var aliases = armourAliases();
		for (String selfEquip : new String[] {
				"给自己装备下界合金套装", "给自己穿上钻石套装", "装备铁套装",
				"穿上下界合金套装", "给自己拿一把下界合金剑", "拿起铁剑" }) {
			var request = fulfil(selfEquip, aliases);
			assertEquals(ItemOperation.Delivery.TO_AGENT_EQUIP, request.delivery(),
				() -> "should equip the squire himself, not the player: " + selfEquip);
		}
		// 反过来，明确要给玩家的句子绝不能被当成"他自己穿"。
		for (String forPlayer : new String[] {"给我32个火把", "给我一把铁镐"}) {
			assertEquals(ItemOperation.Delivery.TO_PLAYER, fulfil(forPlayer, aliases).delivery(),
				() -> "should go to the player: " + forPlayer);
		}
	}

	@Test
	void buildingAHouseIsRecognisedButPlainHouseTalkIsNot() {
		var aliases = testAliases();
		for (String build : new String[] {
				"帮我盖一个房子", "盖间石头房子", "建一个小屋", "build me a house" }) {
			var intent = FastPath.match(build, aliases);
			assertTrue(intent.isPresent(), () -> "should match: " + build);
			assertTrue(intent.get() instanceof FastPathIntent.BuildHouse,
				() -> "should build: " + build);
		}
		// 只是提到"房子"的陈述句必须继续交给 LLM，不能一言不合就开始施工。
		assertTrue(FastPath.match("这个房子真好看", aliases).isEmpty());
	}

	@Test
	void fullEnchantWordingSurvivesAliasLookup() {
		var aliases = testAliases();
		// "满配附魔的" 是修饰语，摘掉之后剩下的才对得上别名表。
		var give = fulfil("给我一把满配附魔的铁镐", aliases);
		assertEquals("minecraft:iron_pickaxe", give.lines().get(0).itemId());
		assertTrue(give.enchanted(), "the 满配附魔 modifier must survive to the runtime");

		assertFalse(fulfil("给我一把铁镐", aliases).enchanted());
	}

	/**
	 * 这句话以前三处同时失手：修饰语词表里没有「满配的」、量词表里没有「套」、
	 * 而「套装 → 四件」的展开只写在给伙伴自己穿那条路上。掉到 LLM 也救不回来，
	 * 因为模型能看见的 give 工具根本没有 enchanted 参数。
	 */
	@Test
	void aWholeEnchantedArmourSetIsUnderstoodOffline() {
		var aliases = armourAliases();
		var request = fulfil("给我一套满配的下界合金套装", aliases);
		assertEquals(ItemOperation.Delivery.TO_PLAYER, request.delivery());
		assertTrue(request.enchanted(), "「满配的」也是满配附魔");
		assertEquals(List.of(
				"minecraft:netherite_helmet", "minecraft:netherite_chestplate",
				"minecraft:netherite_leggings", "minecraft:netherite_boots"),
			request.lines().stream().map(ItemOperation.Line::itemId).toList());
	}

	@Test
	void setWordingVariantsAllLandOnTheSameRequest() {
		var aliases = armourAliases();
		for (String phrase : new String[] {
				"给我一套下界合金套装", "给我下界合金全套", "给我一身下界合金盔甲",
				"给我下界合金防具", "下界合金全套 满附魔"}) {
			var request = fulfil(phrase, aliases);
			assertEquals(4, request.lines().size(), () -> "should be a full set: " + phrase);
			assertEquals("minecraft:netherite_boots",
				request.lines().get(3).itemId(), () -> "wrong material for: " + phrase);
		}
		// 「下界合金」里就含着一个「金」，材质必须按最长词匹配。
		assertEquals("minecraft:golden_helmet",
			fulfil("给我一套金套装", aliases).lines().get(0).itemId());
	}

	/** 只是提到套装的陈述句不能开工——没有明确去向时只认"整句都是槽位"的命令。 */
	@Test
	void chatAboutArmourIsNotAnOrder() {
		var aliases = armourAliases();
		assertTrue(FastPath.match("这个下界合金套装真好看", aliases).isEmpty());
		assertTrue(FastPath.match("我的铁盔甲坏了", aliases).isEmpty());
	}

	// ------------------------------------------------ 以前完全答不上来的三类问题

	/**
	 * 「最近的远古城市在哪」以前会被位置记忆的问句语法抢走，去查一个叫
	 * 「最近远古城市」的地点记忆——查不到，然后什么都不发生。
	 */
	@Test
	void nearbyStructuresAreLocatedInsteadOfBeingMistakenForSavedPlaces() {
		var aliases = testAliases();
		for (String[] probe : new String[][] {
				{"最近的远古城市在哪", "minecraft:ancient_city"},
				{"远古城市在哪", "minecraft:ancient_city"},
				{"帮我找一下要塞", "minecraft:stronghold"},
				{"最近的村庄在哪", "#minecraft:village"}}) {
			var intent = FastPath.match(probe[0], aliases);
			assertTrue(intent.isPresent(), () -> "should match: " + probe[0]);
			assertTrue(intent.get() instanceof FastPathIntent.LocateStructure,
				() -> "should locate a structure: " + probe[0]);
			assertEquals(probe[1],
				((FastPathIntent.LocateStructure) intent.get()).structureId());
		}
		// 玩家自己标记过的地点仍然优先，也仍然只认地点类型词。
		assertTrue(FastPath.match("仓库在哪", aliases).orElseThrow()
			instanceof FastPathIntent.RecallLocation);
		// 只提到结构名不算命令——一句闲聊不该触发一次全生成器搜索。
		assertTrue(FastPath.match("远古城市真吓人", aliases).isEmpty());
	}

	@Test
	void statusEffectsAreUnderstoodAndKeptApartFromPotionItems() {
		var aliases = testAliases();
		var effect = (FastPathIntent.GiveEffect) FastPath
			.match("给我加上夜视效果", aliases).orElseThrow();
		assertEquals("minecraft:night_vision", effect.effectId());
		assertEquals("minecraft:fire_resistance", ((FastPathIntent.GiveEffect) FastPath
			.match("来个抗火", aliases).orElseThrow()).effectId());
		// 「给我一瓶速度药水」要的是物品，不是给他挂个速度效果。
		// （这份假注册表里没有药水这件物品，所以整句落空是对的——
		// 要紧的是它绝不能变成一个 GiveEffect。）
		assertFalse(FastPath.match("给我一瓶速度药水", aliases)
			.map(i -> i instanceof FastPathIntent.GiveEffect).orElse(false),
			"提到药水就该走取物，不该变成挂效果");
	}

	@Test
	void timeCommandsAreUnderstoodButQuestionsAreNot() {
		var aliases = testAliases();
		assertEquals("day", ((FastPathIntent.SetTime) FastPath
			.match("把时间调成白天", aliases).orElseThrow()).preset());
		assertEquals("night", ((FastPathIntent.SetTime) FastPath
			.match("把时间调成夜晚", aliases).orElseThrow()).preset());
		assertEquals("noon", ((FastPathIntent.SetTime) FastPath
			.match("时间调成正午", aliases).orElseThrow()).preset());
		// 陈述句不是命令，别一提到「白天」就动手改时间。
		assertTrue(FastPath.match("白天的时候僵尸会烧起来", aliases).isEmpty());
	}

	@Test
	void heIsAskedForHisName() {
		var aliases = testAliases();
		for (String ask : new String[] {"你叫什么", "你叫什么名字", "你的名字是什么"}) {
			assertTrue(FastPath.match(ask, aliases).orElseThrow()
				instanceof FastPathIntent.WhatIsYourName, () -> "should match: " + ask);
		}
	}

	@Test
	void undoIsRecognisedBeforeTheEditVerbsThatShareItsWords() {
		var aliases = testAliases();
		// 「撤掉」既是撤销的说法，也在移除附魔的动词表里——顺序反了就变成又改一次。
		assertTrue(FastPath.match("撤销", aliases).orElseThrow()
			instanceof FastPathIntent.UndoItemEdit);
		assertTrue(FastPath.match("还原回去", aliases).orElseThrow()
			instanceof FastPathIntent.UndoItemEdit);
	}

	@Test
	void biomesAreLocatedLikeStructures() {
		var aliases = testAliases();
		assertEquals("minecraft:cherry_grove", ((FastPathIntent.LocateBiome) FastPath
			.match("最近的樱花林在哪", aliases).orElseThrow()).biomeId());
		assertEquals("minecraft:mushroom_fields", ((FastPathIntent.LocateBiome) FastPath
			.match("帮我找一下蘑菇岛", aliases).orElseThrow()).biomeId());
		// 只提到群系名不是问路。
		assertTrue(FastPath.match("我最喜欢樱花林了", aliases).isEmpty());
	}

	@Test
	void weatherCommandsAreUnderstoodButQuestionsAreNot() {
		var aliases = testAliases();
		assertEquals("clear", ((FastPathIntent.SetWeather) FastPath
			.match("把雨停了", aliases).orElseThrow()).preset());
		assertEquals("rain", ((FastPathIntent.SetWeather) FastPath
			.match("来场雨", aliases).orElseThrow()).preset());
		// 「外面在下雨吗」是提问，不该动手改天气。
		assertTrue(FastPath.match("外面在下雨吗", aliases).isEmpty());
	}

	/** 这四类问题服务端本来就知道答案，不该为它们走一趟大模型。 */
	@Test
	void readOnlyQuestionsAreAnsweredOffline() {
		var aliases = testAliases();
		record Probe(String said, FastPathIntent.Inspect.Kind kind) { }
		for (Probe probe : new Probe[] {
				new Probe("你背包里有什么", FastPathIntent.Inspect.Kind.AGENT_INVENTORY),
				new Probe("你穿的什么", FastPathIntent.Inspect.Kind.AGENT_EQUIPMENT),
				new Probe("我血量多少", FastPathIntent.Inspect.Kind.OWNER_STATE),
				new Probe("附近有怪吗", FastPathIntent.Inspect.Kind.NEARBY)}) {
			var intent = FastPath.match(probe.said(), aliases);
			assertTrue(intent.isPresent(), () -> "should match: " + probe.said());
			assertTrue(intent.get() instanceof FastPathIntent.Inspect,
				() -> "should be a read-only inspect: " + probe.said());
			assertEquals(probe.kind(),
				((FastPathIntent.Inspect) intent.get()).kind(),
				() -> "wrong kind for: " + probe.said());
		}
		// 「你在哪」是控制类 STATUS，不能被这一组抢走。
		assertTrue(FastPath.match("你在哪", aliases).orElseThrow()
			instanceof FastPathIntent.Control);
	}

	@Test
	void attackTargetsAreNamedOrDefaultToHostilesOnly() {
		var aliases = testAliases();
		var named = (FastPathIntent.AttackTarget) FastPath
			.match("打那只苦力怕", aliases).orElseThrow();
		assertEquals("minecraft:creeper", named.entityId());
		var generic = (FastPathIntent.AttackTarget) FastPath
			.match("帮我清掉附近的怪", aliases).orElseThrow();
		assertTrue(generic.entityId() == null,
			"没点名时应当只清敌对生物，而不是逮着什么打什么");
		// 没有动词就不是攻击命令。
		assertTrue(FastPath.match("那边有只苦力怕", aliases).isEmpty());
	}

	/**
	 * 短语表是<b>整句精确匹配</b>的，只认得「过来」这么几个字。玩家一说
	 * 「到我身边来」就落空，而模型那边只有需要坐标的 move_to——他并不知道你站在哪。
	 */
	@Test
	void comeHereIsUnderstoodBeyondTheExactPhraseTable() {
		var aliases = testAliases();
		for (String said : new String[] {
				"到我身边来", "来我这儿", "到我这里来", "靠过来", "come to me"}) {
			var intent = FastPath.match(said, aliases);
			assertTrue(intent.isPresent(), () -> "should match: " + said);
			assertTrue(intent.get() instanceof FastPathIntent.ComeHere,
				() -> "should come over: " + said);
		}
		// 精确短语仍然走原来的控制意图，不能被这条抢走。
		assertTrue(FastPath.match("过来", aliases).orElseThrow()
			instanceof FastPathIntent.Control);
	}

	@Test
	void teleportingThePlayerIsToldApartFromTeleportingTheCompanion() {
		var aliases = testAliases();
		var both = (FastPathIntent.TeleportOwner) FastPath
			.match("把我和他都传送回主世界", aliases).orElseThrow();
		assertEquals("minecraft:overworld", both.dimensionId());
		assertTrue(both.bring(), "「我和他」就是要一起走");

		var alone = (FastPathIntent.TeleportOwner) FastPath
			.match("帮我传送到下界", aliases).orElseThrow();
		assertEquals("minecraft:the_nether", alone.dimensionId());

		var place = (FastPathIntent.TeleportOwner) FastPath
			.match("把我传送到基地", aliases).orElseThrow();
		assertEquals("基地", place.place());
		assertTrue(place.dimensionId() == null);
		var correction = (FastPathIntent.TeleportOwner) FastPath
			.match("不是那里，传我去基地", aliases).orElseThrow();
		assertEquals("基地", correction.place(), "an explicit correction replaces '那里'");

		// 只传送伙伴的说法不归这条管。
		assertFalse(FastPath.match("你传送到基地去", aliases)
			.map(i -> i instanceof FastPathIntent.TeleportOwner).orElse(false));
	}

	/**
	 * 「用弓打」说的是<b>打法</b>，不是「现在就去打怪」——这两条规则都含「打」字，
	 * 顺序反了他就会立刻冲出去。
	 */
	@Test
	void weaponPreferenceIsToldApartFromAnAttackOrder() {
		var aliases = testAliases();
		for (String said : new String[] {"用弓打", "拿弓射箭", "远程打", "别近战"}) {
			var intent = FastPath.match(said, aliases);
			assertTrue(intent.isPresent(), () -> "should match: " + said);
			assertTrue(intent.get() instanceof FastPathIntent.SetCombatStyle,
				() -> "should set a style, not start a fight: " + said);
			assertEquals(dev.squire.server.combat.CombatStyle.Style.RANGED,
				((FastPathIntent.SetCombatStyle) intent.get()).style(),
				() -> "should prefer the bow: " + said);
		}
		assertEquals(dev.squire.server.combat.CombatStyle.Style.MELEE,
			((FastPathIntent.SetCombatStyle) FastPath.match("近战就行", aliases)
				.orElseThrow()).style());
		// 「自动选武器」是现在主推的说法——「你自己看着办」谁也看不出它管的是武器。
		// 旧说法继续认，用惯的人不该突然发现不好使了。
		for (String said : new String[] {"自动选武器", "自己挑武器", "你自己看着办"}) {
			assertEquals(dev.squire.server.combat.CombatStyle.Style.AUTO,
				((FastPathIntent.SetCombatStyle) FastPath.match(said, aliases)
					.orElseThrow()).style(), () -> "should mean auto: " + said);
		}
		// 反过来，真的下命令时仍然是攻击意图。
		assertTrue(FastPath.match("打那只苦力怕", aliases).orElseThrow()
			instanceof FastPathIntent.AttackTarget);
	}

	/**
	 * 否定句不许被读成它的反面。
	 *
	 * <p>「别用弓」{@code contains}「用弓」为真，「别用剑」{@code contains}「用剑」
	 * 为真——一遍扫完的话，这两句话的意思正好各自颠倒一次。</p>
	 */
	@Test
	void negatedWeaponPhrasesMeanTheOpposite() {
		var aliases = testAliases();
		for (String said : new String[] {"别用弓", "别拿弓", "不用弓", "不要用弓", "别射"}) {
			assertEquals(dev.squire.server.combat.CombatStyle.Style.MELEE,
				((FastPathIntent.SetCombatStyle) FastPath.match(said, aliases)
					.orElseThrow()).style(), () -> "说的是别用弓: " + said);
		}
		for (String said : new String[] {"别用剑", "别拿剑", "别近战", "不要近战"}) {
			assertEquals(dev.squire.server.combat.CombatStyle.Style.RANGED,
				((FastPathIntent.SetCombatStyle) FastPath.match(said, aliases)
					.orElseThrow()).style(), () -> "说的是别近战: " + said);
		}
	}

	private static ItemOperation fulfil(String phrase, Vocabulary aliases) {
		var intent = FastPath.match(phrase, aliases);
		assertTrue(intent.isPresent(), () -> "should match: " + phrase);
		assertTrue(intent.get() instanceof FastPathIntent.Fulfil,
			() -> "should be an item request: " + phrase);
		return ((FastPathIntent.Fulfil) intent.get()).operation();
	}

	private static Kind control(String phrase) {
		Optional<FastPathIntent> m = FastPath.match(phrase);
		assertTrue(m.isPresent(), () -> "expected match: " + phrase);
		assertTrue(m.get() instanceof FastPathIntent.Control,
			() -> "expected Control for: " + phrase);
		return ((FastPathIntent.Control) m.get()).kind();
	}

	private static FastPathIntent.ProjectControl.Kind projectControl(String phrase) {
		FastPathIntent intent = FastPath.match(phrase).orElseThrow();
		assertTrue(intent instanceof FastPathIntent.ProjectControl,
			() -> "expected ProjectControl for: " + phrase);
		return ((FastPathIntent.ProjectControl) intent).kind();
	}

	private static FastPathIntent.LookAt assertLook(FastPathIntent intent) {
		assertTrue(intent instanceof FastPathIntent.LookAt, "expected LookAt");
		return (FastPathIntent.LookAt) intent;
	}

	private static EmoteType emote(String phrase) {
		Optional<FastPathIntent> m = FastPath.match(phrase);
		assertTrue(m.isPresent(), () -> "expected match: " + phrase);
		assertTrue(m.get() instanceof FastPathIntent.Emote,
			() -> "expected Emote for: " + phrase);
		return ((FastPathIntent.Emote) m.get()).type();
	}

	// ------------------------------------------------------------- fall-through

	@Test
	void ordinaryConversationDoesNotMatch() {
		// The exact-only overload stays independent of a world registry.
		assertTrue(FastPath.match("给我32个火把").isEmpty());
		assertTrue(FastPath.match("帮我砍20个木头").isEmpty());
		assertTrue(FastPath.match("hello there, how is the weather?").isEmpty());
		assertFalse(FastPath.match("").isPresent());
		assertFalse(FastPath.match(null).isPresent());
	}

	@Test
	void productionMatcherParsesHighFrequencyResourceAndSurvivalIntents() {
		Vocabulary aliases = testAliases();
		var give = fulfil("给我 32 个火把", aliases).lines().get(0);
		assertEquals("minecraft:torch", give.itemId());
		assertEquals(32, give.count());
		var gather = fulfil("帮我砍 20 个橡木", aliases).lines().get(0);
		assertEquals("minecraft:oak_log", gather.itemId());
		assertEquals(20, gather.count());
		var craft = fulfil("帮我做一把铁镐", aliases).lines().get(0);
		assertEquals("minecraft:iron_pickaxe", craft.itemId());
		assertEquals(1, craft.count());
		assertTrue(FastPath.match("保护我", aliases).orElseThrow()
			instanceof FastPathIntent.Guard);
		assertTrue(FastPath.match("我快死了", aliases).orElseThrow()
			instanceof FastPathIntent.AidOwner);
		// "上次" 前缀必须原样传给位置记忆：解析成 type=MINE 里 lastVisitedAt 最大的一条
		assertEquals("上次矿洞", ((FastPathIntent.RecallLocation) FastPath
			.match("上次矿洞在哪", aliases).orElseThrow()).typeOrName());
		assertTrue(FastPath.match("停止保护", aliases).orElseThrow()
			instanceof FastPathIntent.GuardStop);
	}

	private static Vocabulary testAliases() {
		return Vocabulary.fromResources(id -> java.util.Set.of(
			"minecraft:torch", "minecraft:oak_log", "minecraft:iron_pickaxe")
			.contains(id), id -> true);
	}

	/** 盔甲用例需要注册表里真的有那四件，否则套装解析会（正确地）拒绝。 */
	private static Vocabulary armourAliases() {
		return Vocabulary.fromResources(
			id -> id.matches("minecraft:(netherite|diamond|iron|golden|chainmail|leather)_"
				+ "(helmet|chestplate|leggings|boots|sword|pickaxe)")
				|| java.util.Set.of("minecraft:torch", "minecraft:oak_log").contains(id),
			id -> true);
	}

	@Test
	void locationMemoryPhrasesMapToMemoryIntents() {
		var aliases = testAliases();
		assertEquals("仓库", ((FastPathIntent.RememberLocation) FastPath
			.match("这里是仓库", aliases).orElseThrow()).typeOrName());
		assertEquals("基地", ((FastPathIntent.RememberLocation) FastPath
			.match("这里是基地", aliases).orElseThrow()).typeOrName());
		assertEquals("仓库", ((FastPathIntent.RecallLocation) FastPath
			.match("仓库在哪", aliases).orElseThrow()).typeOrName());
		assertTrue(FastPath.match("把矿放回仓库", aliases).isEmpty(),
			"physical container logistics is no longer a companion FastPath");
		assertTrue(FastPath.match("每天晚上在基地开灯", aliases).isEmpty(),
			"physical automation is no longer a companion FastPath");
		assertTrue(FastPath.match("做一个真实命令方块昼夜控制器", aliases).isEmpty(),
			"materialized CBP projects are no longer a companion FastPath");
		// "你在哪" 是控制类 STATUS，绝不能被位置记忆的问句语法抢走
		assertTrue(FastPath.match("你在哪", aliases).orElseThrow()
			instanceof FastPathIntent.Control);
		// 普通闲聊里的 "在哪" 不是地点类型，仍然交给 LLM
		assertTrue(FastPath.match("那家店在哪", aliases).isEmpty());
	}

	@Test
	void lookAtPositionFactoryCarriesCoordinates() {
		FastPathIntent.LookAt at = FastPathIntent.LookAt.position(1.5, 64.0, -3.25);
		assertArrayEquals(new double[] {1.5, 64.0, -3.25}, at.target().orElseThrow());
	}
}
