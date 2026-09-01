package dev.squire.server.fastpath;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.squire.api.body.EmoteType;
import dev.squire.server.fastpath.FastPathIntent.Control.Kind;
import dev.squire.server.i18n.ItemAliasResolver;
import dev.squire.server.i18n.Vocabulary;
import dev.squire.server.nlu.ItemOperation;
import dev.squire.server.nlu.ItemOperationParser;

/**
 * Deterministic phrase table for P0 control phrases (spec section 14.1).
 *
 * <p>FastPath never touches the LLM and never touches the world: it maps a trusted
 * phrase to a structured {@link FastPathIntent} and hands it to the runtime pipeline.
 * A small, non-destructive subset accepts one typo; everything else stays exact and
 * falls through to the LLM path (or nowhere, if no provider).</p>
 */
public final class FastPath {
	/** Insertion order matters only for readability; matches are exact after normalization. */
	private static final Map<String, FastPathIntent> PHRASES = new LinkedHashMap<>();
	/**
	 * Only low-risk, argument-free phrases may be guessed locally. Do not add world
	 * edits, inventory changes, stop/stay, dismissal, combat, or teleportation here.
	 */
	private static final Set<String> TYPO_TOLERANT_PHRASES = Set.of(
		"跟着我", "跟我来", "follow me", "come here",
		"你在哪", "where are you", "status",
		"看着我", "look at me",
		"wave", "跳一下", "jump", "nod", "shake head");

	static {
		// follow
		register("跟着我", control(Kind.FOLLOW));
		register("跟我来", control(Kind.FOLLOW));
		register("过来", control(Kind.FOLLOW));
		register("follow me", control(Kind.FOLLOW));
		register("come here", control(Kind.FOLLOW));
		register("come", control(Kind.FOLLOW));
		// stay
		register("待在这里", control(Kind.STAY));
		register("待在这", control(Kind.STAY));
		register("别动", control(Kind.STAY));
		register("站住", control(Kind.STAY));
		register("原地站住", control(Kind.STAY));
		register("原地别动", control(Kind.STAY));
		register("不要走", control(Kind.STAY));
		register("待命", control(Kind.STAY));
		register("stay here", control(Kind.STAY));
		register("stay", control(Kind.STAY));
		// stop
		register("停止", control(Kind.STOP));
		register("停下", control(Kind.STOP));
		register("stop", control(Kind.STOP));
		// durable project control (context validation happens in SquireProjectService)
		register("暂停工程", projectControl(FastPathIntent.ProjectControl.Kind.PAUSE));
		register("先停工", projectControl(FastPathIntent.ProjectControl.Kind.PAUSE));
		register("停工", projectControl(FastPathIntent.ProjectControl.Kind.PAUSE));
		register("暂停", projectControl(FastPathIntent.ProjectControl.Kind.PAUSE));
		register("继续工程", projectControl(FastPathIntent.ProjectControl.Kind.RESUME));
		register("继续施工", projectControl(FastPathIntent.ProjectControl.Kind.RESUME));
		register("复工", projectControl(FastPathIntent.ProjectControl.Kind.RESUME));
		register("重启工程", projectControl(FastPathIntent.ProjectControl.Kind.RESUME));
		register("接着做", projectControl(FastPathIntent.ProjectControl.Kind.RESUME));
		register("继续", projectControl(FastPathIntent.ProjectControl.Kind.RESUME));
		// home
		register("回家", control(Kind.HOME_RETURN));
		register("go home", control(Kind.HOME_RETURN));
		register("home", control(Kind.HOME_RETURN));
		// status (read-only, allowed for any sender)
		register("你在哪", control(Kind.STATUS));
		register("状态", control(Kind.STATUS));
		register("where are you", control(Kind.STATUS));
		register("status", control(Kind.STATUS));
		// look（方案 A3：可玩入口）
		register("看着我", FastPathIntent.LookAt.me());
		register("look at me", FastPathIntent.LookAt.me());
		// emotes（方案 A3）
		register("挥手", new FastPathIntent.Emote(EmoteType.WAVE));
		register("wave", new FastPathIntent.Emote(EmoteType.WAVE));
		register("跳一下", new FastPathIntent.Emote(EmoteType.JUMP));
		register("jump", new FastPathIntent.Emote(EmoteType.JUMP));
		register("点头", new FastPathIntent.Emote(EmoteType.NOD));
		register("nod", new FastPathIntent.Emote(EmoteType.NOD));
		register("摇头", new FastPathIntent.Emote(EmoteType.SHAKE_HEAD));
		register("shake head", new FastPathIntent.Emote(EmoteType.SHAKE_HEAD));
	}

	private FastPath() {
	}

	private static void register(String phrase, FastPathIntent intent) {
		PHRASES.put(normalize(phrase), intent);
	}

	private static FastPathIntent control(Kind kind) {
		return new FastPathIntent.Control(kind);
	}

	private static FastPathIntent projectControl(FastPathIntent.ProjectControl.Kind kind) {
		return new FastPathIntent.ProjectControl(kind);
	}

	private static String normalize(String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT);
		// strip common trailing punctuation so "follow me!" still matches
		while (!s.isEmpty() && "!.?！？。，, ".indexOf(s.charAt(s.length() - 1)) >= 0) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	/**
	 * Match raw chat text against the P0 table.
	 *
	 * @return the matched intent, or empty when this is ordinary conversation
	 *         (which must fall through to the LLM path).
	 */
	public static Optional<FastPathIntent> match(String rawMessage) {
		if (rawMessage == null || rawMessage.isBlank()) {
			return Optional.empty();
		}
		String normalized = normalize(rawMessage);
		FastPathIntent exact = PHRASES.get(normalized);
		return exact == null ? typoTolerantMatch(normalized) : Optional.of(exact);
	}

	/**
	 * Accepts exactly one insertion, deletion, or substitution, and only when all
	 * equally-close candidates mean the same thing. This makes “跟这我” useful while
	 * preventing a misspelled state-changing command from being guessed.
	 */
	private static Optional<FastPathIntent> typoTolerantMatch(String text) {
		if (text.length() < 3) {
			return Optional.empty();
		}
		FastPathIntent matched = null;
		for (String phrase : TYPO_TOLERANT_PHRASES) {
			if (!oneEditApart(text, phrase)) {
				continue;
			}
			FastPathIntent candidate = PHRASES.get(phrase);
			if (matched != null && !matched.equals(candidate)) {
				return Optional.empty();
			}
			matched = candidate;
		}
		return Optional.ofNullable(matched);
	}

	private static boolean oneEditApart(String left, String right) {
		int lengthDifference = Math.abs(left.length() - right.length());
		if (lengthDifference > 1 || left.equals(right)) {
			return false;
		}
		int i = 0;
		int j = 0;
		int edits = 0;
		while (i < left.length() && j < right.length()) {
			if (left.charAt(i) == right.charAt(j)) {
				i++;
				j++;
				continue;
			}
			if (++edits > 1) {
				return false;
			}
			if (left.length() == right.length()) {
				i++;
				j++;
			} else if (left.length() > right.length()) {
				i++;
			} else {
				j++;
			}
		}
		if (i < left.length() || j < right.length()) {
			edits++;
		}
		return edits == 1;
	}

	/**
	 * Production matcher: exact control phrases always win, then deterministic
	 * registry-validated domain grammar. Empty means the sentence is not trusted
	 * enough for local execution and may fall through to the LLM.
	 */
	public static Optional<FastPathIntent> match(String rawMessage,
			Vocabulary vocabulary) {
		Optional<FastPathIntent> exact = match(rawMessage);
		if (exact.isPresent() || rawMessage == null || rawMessage.isBlank()
				|| vocabulary == null || !vocabulary.isUsable()) {
			return exact;
		}
		ItemAliasResolver aliases = vocabulary.items();
		String text = normalize(rawMessage).replaceAll("\\s+", " ");
		// 「撤销」必须排在改动类规则之前，否则「撤掉」这个词会被当成又一次改动。
		if (mentionsUndo(text)) {
			return Optional.of(new FastPathIntent.UndoItemEdit());
		}
		if (text.equals("保护我") || text.equals("护卫我")
				|| text.equals("protect me") || text.equals("guard me")) {
			return Optional.of(new FastPathIntent.Guard(16, true));
		}
		if (text.equals("停止保护") || text.equals("不用保护了")
				|| text.equals("别保护我了") || text.equals("解除护卫")
				|| text.equals("stop protecting me") || text.equals("stop guarding")) {
			return Optional.of(new FastPathIntent.GuardStop());
		}
		if (text.equals("我快死了") || text.equals("救我")
				|| text.equals("help me i'm dying") || text.equals("i'm dying")) {
			return Optional.of(new FastPathIntent.AidOwner());
		}
		// 方案 F2/B07：区域编辑只引用 server 拥有的选区，绝不接受句子里的坐标
		java.util.regex.Matcher fill = java.util.regex.Pattern
			.compile("^把(?:选定|选中|选区)(?:的)?(?:区域|范围)?(?:都)?(?:铺|填|变)成(.+)$")
			.matcher(text);
		if (fill.matches()) {
			var block = aliases.resolve(fill.group(1).trim());
			if (block.isPresent()) {
				return Optional.of(new FastPathIntent.FillSelection(block.get().itemId()));
			}
		}
		// 方案 E2：位置记忆的高频句式
		java.util.regex.Matcher remember = java.util.regex.Pattern
			.compile("^(?:这里是|这儿是|记住这里是)(.+)$").matcher(text);
		if (remember.matches()) {
			return Optional.of(new FastPathIntent.RememberLocation(
				remember.group(1).trim()));
		}
		java.util.regex.Matcher recallRecent = java.util.regex.Pattern
			.compile("^(上次|最近)(?:的)?(.+?)(?:在哪|在哪里|在什么地方)$").matcher(text);
		if (recallRecent.matches()
				// 「最近的远古城市在哪」以前会被这条抢走，变成去查一个叫
				// 「最近远古城市」的位置记忆——查不到，然后什么都不发生。
				// 位置记忆只认它自己的类型词，别的问法留给下面的结构查找。
				&& dev.squire.server.memory.LocationMemory.Type
					.isTypePhrase(recallRecent.group(2).trim())) {
			return Optional.of(new FastPathIntent.RecallLocation(
				recallRecent.group(1) + recallRecent.group(2).trim()));
		}
		java.util.regex.Matcher recall = java.util.regex.Pattern
			.compile("^(.+?)(?:在哪|在哪里|在什么地方)$").matcher(text);
		if (recall.matches()) {
			String subject = recall.group(1).trim();
			// 只接受确实是地点类型/名字的问法，普通闲聊仍然交给 LLM
			if (dev.squire.server.memory.LocationMemory.Type.isTypePhrase(subject)) {
				return Optional.of(new FastPathIntent.RecallLocation(subject));
			}
		}
		// 「（最近的）远古城市在哪」——结构查找。放在位置记忆之后：玩家自己标过的
		// 「仓库」优先于世界生成的结构。
		Optional<FastPathIntent> structure = matchStructure(text, vocabulary);
		if (structure.isPresent()) {
			return structure;
		}
		// 「到我身边来」——短语表是精确匹配，只认得「过来」「跟着我」这几个字，
		// 稍微换个说法就掉进 LLM，而模型那边又只有需要坐标的 move_to。
		if (mentionsComeHere(text)) {
			return Optional.of(new FastPathIntent.ComeHere());
		}
		// 「把我们俩都传送过去」「帮我回主世界」
		Optional<FastPathIntent> teleport = matchTeleportOwner(text);
		if (teleport.isPresent()) {
			return teleport;
		}
		// 「最近的樱花林在哪」——群系查找，和结构同一类问题。
		Optional<FastPathIntent> biome = matchBiome(text, vocabulary);
		if (biome.isPresent()) {
			return biome;
		}
		// 「把时间调成白天」
		Optional<FastPathIntent> time = matchTime(text);
		if (time.isPresent()) {
			return time;
		}
		// 「把雨停了」
		Optional<FastPathIntent> weather = matchWeather(text);
		if (weather.isPresent()) {
			return weather;
		}
		// 只读查询：这些问题走一趟 LLM 纯属浪费，答案服务端本来就有。
		Optional<FastPathIntent> inspect = matchInspect(text);
		if (inspect.isPresent()) {
			return inspect;
		}
		// 「用弓打」——必须排在攻击规则之前：这句话里也有「打」。
		Optional<FastPathIntent> style = matchCombatStyle(text);
		if (style.isPresent()) {
			return style;
		}
		// 「打那只苦力怕」
		Optional<FastPathIntent> attack = matchAttack(text, vocabulary);
		if (attack.isPresent()) {
			return attack;
		}
		// 「给我加个夜视」
		Optional<FastPathIntent> effect = matchEffect(text, vocabulary);
		if (effect.isPresent()) {
			return effect;
		}
		// 「你叫什么」
		if (mentionsNameQuestion(text)) {
			return Optional.of(new FastPathIntent.WhatIsYourName());
		}
		// "你能做什么" —— 放在最前面。这是玩家卡住时唯一的出口，不能被别的规则截胡。
		if (mentionsHelp(text)) {
			return Optional.of(new FastPathIntent.Help());
		}
		// "治疗自己"（他给自己回血）区别于上面的 "救我"（他来治玩家）。两者不会打架：
		// "救我" 走的是精确相等匹配，不会把这句话吞掉。
		if (mentionsSelfHeal(text)) {
			return Optional.of(new FastPathIntent.HealSelf());
		}
		// 一切和物品有关的话统一走槽位解析：从哪拿 × 哪几件 × 做什么 × 归谁。
		// 「给我一套满配下界合金」和「取消我套装的荆棘附魔」只是同一个模型里的
		// 两组取值，而不是两条要分别写规则的特例。
		// 必须在盖房子/工程之前判"给自己装备…"，否则那句会被当成给玩家一套装备，
		// 伙伴把整套盔甲扔在地上、自己还是光着。解析不出来就原样落到后面的规则。
		Optional<ItemOperation> items = ItemOperationParser.parse(text, vocabulary);
		if (items.isPresent()) {
			return Optional.of(new FastPathIntent.Fulfil(items.get()));
		}
		// “帮我准备一个矿井前哨站” —— 必须在盖房子之前判：它也是一句盖东西的话，
		// 但要的是一整套流程（备料 → 交料 → 掘进 → 施工 → 点灯 → 验收）。
		if (mentionsProject(text)) {
			return Optional.of(new FastPathIntent.StartProject(text));
		}
		// "帮我盖一个房子/石头房子" —— 形状由模板决定，这里只把风格词带过去。
		if (mentionsHouse(text)) {
			return Optional.of(new FastPathIntent.BuildHouse(text));
		}
		return Optional.empty();
	}

	/** 让伙伴给<b>他自己</b>回血的说法。 */
	private static boolean mentionsSelfHeal(String text) {
		for (String phrase : new String[] {
				"治疗自己", "自己治疗", "给自己治疗", "自己回血", "给自己回血",
				"自己吃药", "自己吃点东西", "heal yourself", "heal up"}) {
			if (text.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 「最近的远古城市在哪」「找一下要塞」。
	 *
	 * <p>要求句子里既有<b>结构名</b>又有<b>询问/查找的意思</b>：单说一句「远古城市」
	 * 可能只是在聊天，不该让他去跑一次全生成器搜索。</p>
	 */
	private static Optional<FastPathIntent> matchStructure(String text,
			Vocabulary vocabulary) {
		if (!mentionsAsking(text)) {
			return Optional.empty();
		}
		var found = vocabulary.structures().findAll(text);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		String id = found.get(0);
		return Optional.of(new FastPathIntent.LocateStructure(id,
			vocabulary.structures().displayName(id)));
	}

	/**
	 * 「到我身边来」的各种说法。
	 *
	 * <p>{@code PHRASES} 那张表是<b>整句精确匹配</b>的，只认得「过来」「跟着我」
	 * 这么几个字。玩家一说「到我身边来」「来我这儿」就落空——这条用包含匹配补上。</p>
	 */
	private static boolean mentionsComeHere(String text) {
		for (String phrase : new String[] {
				"到我身边", "到我这", "来我这", "来我身边", "过来我这", "回我身边",
				"到我旁边", "靠过来", "come to me", "come here", "get over here"}) {
			if (text.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 「把我传送到 X」「帮我回主世界」「把我们俩都送过去」。
	 *
	 * <p>要求同时出现<b>传送动词</b>和<b>传送对象是玩家</b>：「把他传送过去」只动
	 * 伙伴，那条走别的路，不能被这里抢走。</p>
	 */
	private static Optional<FastPathIntent> matchTeleportOwner(String text) {
		boolean teleporting = false;
		for (String verb : new String[] {
				"传送", "传我", "tp", "送我", "送过去", "带我", "teleport"}) {
			if (text.contains(verb)) {
				teleporting = true;
				break;
			}
		}
		if (!teleporting) {
			return Optional.empty();
		}
		// 必须是在说"我"。「把他传送到基地」不归这条管。
		boolean aboutOwner = text.contains("我");
		if (!aboutOwner) {
			return Optional.empty();
		}
		// 「我和他」「我们俩」「一起」= 把伙伴也带上。
		boolean bring = text.contains("我们") || text.contains("我和")
			|| text.contains("和我") || text.contains("一起") || text.contains("都");
		String dimension = dev.squire.server.runtime.SquireRuntime.dimensionIdOf(text);
		if (dimension != null) {
			return Optional.of(new FastPathIntent.TeleportOwner(dimension, null, bring,
				dimensionLabel(dimension)));
		}
		// 没点维度：可能是「传送到基地」这种记过的地点。
		java.util.regex.Matcher place = java.util.regex.Pattern
			.compile(".*(?:传送|传我|tp|送|带我)(?:我们|我)?(?:都)?(?:一起)?"
				+ "(?:到|去|回|回到)(.+?)(?:那儿|那里|去)?$").matcher(text);
		if (place.matches()) {
			String named = place.group(1).trim();
			if (!named.isEmpty()) {
				return Optional.of(new FastPathIntent.TeleportOwner(null, named, bring,
					named));
			}
		}
		return Optional.empty();
	}

	private static String dimensionLabel(String dimensionId) {
		return switch (dimensionId) {
			case "minecraft:overworld" -> "主世界";
			case "minecraft:the_nether" -> "下界";
			case "minecraft:the_end" -> "末地";
			default -> dimensionId;
		};
	}

	/** 「最近的樱花林在哪」。判据和结构查找一样：要有群系名 + 有询问的意思。 */
	private static Optional<FastPathIntent> matchBiome(String text,
			Vocabulary vocabulary) {
		if (!mentionsAsking(text)) {
			return Optional.empty();
		}
		var found = vocabulary.biomes().findAll(text);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		String id = found.get(0);
		return Optional.of(new FastPathIntent.LocateBiome(id,
			vocabulary.biomes().displayName(id)));
	}

	/** 结构和群系共用的「这是在问路」判据。 */
	private static boolean mentionsAsking(String text) {
		for (String word : new String[] {
				"在哪", "在什么地方", "找一下", "找找", "找个", "帮我找", "最近的",
				"哪里有", "有没有", "怎么走", "坐标", "where", "find", "locate"}) {
			if (text.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/** 「把雨停了」「下场雨」。 */
	private static Optional<FastPathIntent> matchWeather(String text) {
		String preset = null;
		if (text.contains("雷") || text.contains("打雷") || text.contains("thunder")) {
			preset = "thunder";
		} else if (text.contains("停雨") || text.contains("雨停") || text.contains("放晴")
				|| text.contains("晴天") || text.contains("天晴")
				|| text.contains("clear") || text.contains("stop the rain")) {
			preset = "clear";
		} else if (text.contains("雨") || text.contains("rain")) {
			// 「停雨/放晴」在上面已经先判掉了，所以到这里的「雨」就是在要雨。
			preset = "rain";
		}
		if (preset == null) {
			return Optional.empty();
		}
		// 「外面在下雨吗」不是命令。要么有明确动词，要么整句就是那个诉求。
		boolean commanding = text.contains("把") || text.contains("调") || text.contains("变")
			|| text.contains("改") || text.contains("来") || text.contains("停")
			|| text.contains("下场") || text.contains("set") || text.contains("make");
		if (!commanding || text.endsWith("吗") || text.contains("是不是")) {
			return Optional.empty();
		}
		return Optional.of(new FastPathIntent.SetWeather(preset));
	}

	/**
	 * 只读查询：背包 / 装备 / 玩家状态 / 附近有什么。
	 *
	 * <p>这四类问题服务端本来就知道答案，走一趟 LLM 既慢又可能被编造。</p>
	 */
	private static Optional<FastPathIntent> matchInspect(String text) {
		for (String phrase : new String[] {
				"你背包里有什么", "你背包有什么", "你身上有什么", "你带了什么",
				"你有什么东西", "你的背包"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.Inspect(
					FastPathIntent.Inspect.Kind.AGENT_INVENTORY));
			}
		}
		for (String phrase : new String[] {
				"你穿的什么", "你穿着什么", "你身上穿的", "你的装备", "你拿的什么",
				"你手上拿的"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.Inspect(
					FastPathIntent.Inspect.Kind.AGENT_EQUIPMENT));
			}
		}
		for (String phrase : new String[] {
				"我血量", "我多少血", "我还有多少血", "我的状态", "我饿不饿",
				"我在哪", "我手上拿的是什么", "我现在在哪"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.Inspect(
					FastPathIntent.Inspect.Kind.OWNER_STATE));
			}
		}
		for (String phrase : new String[] {
				"附近有什么", "附近有怪", "周围有什么", "周围有怪", "有怪吗",
				"安全吗", "附近安全"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.Inspect(
					FastPathIntent.Inspect.Kind.NEARBY));
			}
		}
		return Optional.empty();
	}

	/**
	 * 「用弓打」/「近战就行」/「你自己看着办」。
	 *
	 * <p>必须排在攻击规则之前判：这几句里也有「打」，顺序反了就变成"现在就去打怪"。
	 * 判据是<b>说的是打法</b>（用弓/远程/近战），而不是<b>说的是目标</b>。</p>
	 */
	private static Optional<FastPathIntent> matchCombatStyle(String text) {
		// 三遍扫描，顺序就是规则：<b>否定句排在它否定的那个词前面</b>。
		// 「别用弓」contains「用弓」为真，「别用剑」contains「用剑」为真——一遍扫完
		// 的话，这两句话的意思都会被读成它们的反面。
		for (String phrase : new String[] {
				"别近战", "别用剑", "别拿剑", "别贴脸", "不要近战", "不用近战"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.SetCombatStyle(
					dev.squire.server.combat.CombatStyle.Style.RANGED));
			}
		}
		for (String phrase : new String[] {
				"用近战", "近战就行", "别用弓", "别拿弓", "别射", "不要用弓", "不用弓",
				"拿剑", "用剑", "melee", "use the sword", "no bow", "don't use the bow"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.SetCombatStyle(
					dev.squire.server.combat.CombatStyle.Style.MELEE));
			}
		}
		for (String phrase : new String[] {
				"用弓", "拿弓", "射箭", "远程", "远战", "用箭", "拉开打",
				"use the bow", "use bow", "ranged"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.SetCombatStyle(
					dev.squire.server.combat.CombatStyle.Style.RANGED));
			}
		}
		// 这一档原来只认「你自己看着办」——一句听着莫名其妙、也说不清它到底管什么的
		// 话。现在主推「自动选武器」，把意思写在字面上；旧说法继续认，免得已经用惯的
		// 人突然发现不好使了。
		for (String phrase : new String[] {
				"自动选武器", "自动换武器", "武器自动", "自动打", "按情况打",
				"自己挑武器", "自己选武器",
				"自己看着办", "自己决定", "随便你打", "auto"}) {
			if (text.contains(phrase)) {
				return Optional.of(new FastPathIntent.SetCombatStyle(
					dev.squire.server.combat.CombatStyle.Style.AUTO));
			}
		}
		return Optional.empty();
	}

	/** 「打那只苦力怕」「帮我清掉附近的怪」。 */
	private static Optional<FastPathIntent> matchAttack(String text,
			Vocabulary vocabulary) {
		boolean commanding = false;
		for (String verb : new String[] {
				"打", "杀", "干掉", "清掉", "清理", "解决掉", "揍", "attack", "kill"}) {
			if (text.contains(verb)) {
				commanding = true;
				break;
			}
		}
		if (!commanding) {
			return Optional.empty();
		}
		// 「打那只苦力怕」点了名；「清掉附近的怪」没点名 = 清敌对。
		var found = vocabulary.entities().findAll(text);
		if (!found.isEmpty()) {
			String id = found.get(0);
			return Optional.of(new FastPathIntent.AttackTarget(id,
				vocabulary.entities().displayName(id)));
		}
		for (String generic : new String[] {"附近的怪", "周围的怪", "这些怪", "那些怪",
				"附近的敌人", "怪物"}) {
			if (text.contains(generic)) {
				return Optional.of(new FastPathIntent.AttackTarget(null, "附近的敌对生物"));
			}
		}
		return Optional.empty();
	}

	/** 「把时间调成白天」「天黑吧」。 */
	private static Optional<FastPathIntent> matchTime(String text) {
		boolean commanding = text.contains("时间") || text.contains("调成")
			|| text.contains("改成") || text.contains("变成") || text.contains("设成")
			|| text.contains("set time") || text.contains("time set");
		String preset = null;
		if (text.contains("白天") || text.contains("天亮") || text.contains("早上")
				|| text.contains("日出") || text.contains("daytime")
				|| text.contains("day")) {
			preset = "day";
		} else if (text.contains("正午") || text.contains("中午")
				|| text.contains("noon")) {
			preset = "noon";
		} else if (text.contains("午夜") || text.contains("半夜")
				|| text.contains("midnight")) {
			preset = "midnight";
		} else if (text.contains("晚上") || text.contains("夜晚") || text.contains("天黑")
				|| text.contains("日落") || text.contains("night")) {
			preset = "night";
		}
		if (preset == null) {
			return Optional.empty();
		}
		// 「现在是白天吗」不是命令。必须有明确的动词才动手。
		if (!commanding && !text.startsWith("天亮") && !text.startsWith("天黑")) {
			return Optional.empty();
		}
		return Optional.of(new FastPathIntent.SetTime(preset));
	}

	/** 默认效果时长：5 分钟。够用一段路，又不会让人忘了它还挂着。 */
	private static final int DEFAULT_EFFECT_TICKS = 6000;

	/** 「给我加上夜视效果」「来个抗火」。 */
	private static Optional<FastPathIntent> matchEffect(String text,
			Vocabulary vocabulary) {
		// 「给我一瓶速度药水」要的是<b>物品</b>，不是给他挂个速度效果。提到药水/瓶
		// 就把这句话让给取物那条路——两者的差别玩家一眼就能看出来，弄反很讨厌。
		if (text.contains("药水") || text.contains("potion") || text.contains("瓶")) {
			return Optional.empty();
		}
		boolean asking = false;
		for (String word : new String[] {
				"加上", "加个", "来个", "来一个", "给我", "上个", "上一个", "开个",
				"效果", "buff", "give me", "add"}) {
			if (text.contains(word)) {
				asking = true;
				break;
			}
		}
		if (!asking) {
			return Optional.empty();
		}
		var found = vocabulary.effects().findAll(text);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		String id = found.get(0);
		return Optional.of(new FastPathIntent.GiveEffect(id,
			vocabulary.effects().displayName(id), DEFAULT_EFFECT_TICKS, 0));
	}

	/** 「你叫什么名字」。 */
	private static boolean mentionsNameQuestion(String text) {
		for (String phrase : new String[] {
				"你叫什么", "你的名字", "你叫啥", "你是谁", "你叫什么名字",
				"what is your name", "what's your name", "who are you"}) {
			if (text.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 「撤销」：还原上一次对玩家物品的改动。
	 *
	 * <p>放在改动类规则之前判：「撤掉」既是撤销的说法，也在移除附魔的动词表里，
	 * 顺序反了就会变成"又改一次"。</p>
	 */
	private static boolean mentionsUndo(String text) {
		for (String phrase : new String[] {
				"撤销", "撤回", "还原", "还原回去", "改回去", "变回去", "恢复原样",
				"undo", "revert"}) {
			if (text.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/** 「你能做什么」的各种问法。宁可多认几种，也别让玩家问不出来。 */
	private static boolean mentionsHelp(String text) {
		for (String phrase : new String[] {
				"你能做什么", "你会做什么", "能做什么", "会做什么", "你能干什么",
				"能干什么", "你会干什么", "会干嘛", "能干嘛", "你能干嘛",
				"帮助", "help", "what can you do", "commands", "指令列表", "功能列表"}) {
			if (text.equals(phrase) || text.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 是不是在要一整个工程。
	 *
	 * <p>判据是「动词 + 建筑名」两者都在：单独一个「前哨站」可能只是在聊天。
	 * 到底对应哪份蓝图由服务端查注册表（数据包里的蓝图这边看不到）。</p>
	 */
	private static boolean mentionsProject(String text) {
		boolean verb = text.contains("准备") || text.contains("工程")
			|| text.contains("规划") || text.contains("弄一个")
			|| text.contains("建一个") || text.contains("来一个")
			|| text.contains("build me") || text.contains("set up");
		if (!verb) {
			return false;
		}
		for (String noun : new String[] {"前哨站", "哨塔", "瞭望塔", "仓库",
				"矿井", "营地", "outpost", "watchtower", "storage", "shed"}) {
			if (text.contains(noun)) {
				return true;
			}
		}
		return false;
	}


	/**
	 * 是不是在让伙伴盖房子。这里只做"提到了盖/建 + 房子"这种强信号匹配，
	 * 免得把"这里是我的房子"之类的陈述句也抓进来。
	 */
	private static boolean mentionsHouse(String text) {
		boolean house = text.contains("房子") || text.contains("房屋")
			|| text.contains("小屋") || text.contains("house") || text.contains("hut");
		if (!house) {
			return false;
		}
		return text.contains("盖") || text.contains("建") || text.contains("搭")
			|| text.contains("造") || text.contains("build");
	}

	/** Convenience mapping for callers that only care about basic control kinds. */
	public static Optional<dev.squire.server.runtime.SquireRuntime.ControlIntent>
			matchControl(String rawMessage) {
		return match(rawMessage).flatMap(intent -> intent instanceof FastPathIntent.Control c
			? Optional.of(toControlIntent(c.kind())) : Optional.empty());
	}

	public static dev.squire.server.runtime.SquireRuntime.ControlIntent
			toControlIntent(Kind kind) {
		return switch (kind) {
			case FOLLOW -> dev.squire.server.runtime.SquireRuntime.ControlIntent.FOLLOW;
			case STAY -> dev.squire.server.runtime.SquireRuntime.ControlIntent.STAY;
			case STOP -> dev.squire.server.runtime.SquireRuntime.ControlIntent.STOP;
			case HOME_RETURN -> dev.squire.server.runtime.SquireRuntime.ControlIntent.HOME_RETURN;
			case STATUS -> dev.squire.server.runtime.SquireRuntime.ControlIntent.STATUS;
			case DISMISS -> dev.squire.server.runtime.SquireRuntime.ControlIntent.DISMISS;
		};
	}
}
