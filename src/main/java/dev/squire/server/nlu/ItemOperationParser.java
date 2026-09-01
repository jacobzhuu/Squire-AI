package dev.squire.server.nlu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.squire.server.i18n.Vocabulary;

/**
 * 把玩家原话解析成 {@link ItemOperation}：<b>从哪拿 × 哪几件 × 做什么 × 归谁</b>。
 *
 * <p>解析顺序是有意的：<b>先定动作，再定作用域</b>。「取消」「去掉」「修复」这类动词
 * 一出现，就说明说的是玩家<em>已经有</em>的东西——凭空造出来的东西没有附魔可以取消。
 * 这条判据把「给我一套满配下界合金套装」和「取消我套装的荆棘附魔」在第一步就分开，
 * 而不是等到查不到物品名时才发现不对。</p>
 *
 * <p>纪律不变：物品 id 过 {@code ItemAliasResolver} 的注册表校验，附魔 id 过
 * {@code EnchantmentAliasResolver} 的注册表校验，两边都查不到就返回空、交给 LLM。</p>
 */
public final class ItemOperationParser {

	/** 一次请求的件数上限，和 {@code minecraft.command.give} 的口径一致。 */
	public static final int MAX_TOTAL_ITEMS = 4096;

	/** 一次最多几套。 */
	public static final int MAX_SETS = 16;

	/** 盔甲部位，顺序即穿戴顺序。 */
	private static final String[] ARMOR_PIECES = {
		"helmet", "chestplate", "leggings", "boots"};

	/**
	 * 材质词 → 注册表前缀。<b>长词优先</b>匹配：「下界合金」里就含着一个「金」，
	 * 靠长度排序才不会把下界合金套装解析成金套装。
	 */
	private static final Map<String, String> MATERIALS = new LinkedHashMap<>();

	/** 注册表前缀 → 回执里用的中文名。 */
	private static final Map<String, String> MATERIAL_NAMES = new LinkedHashMap<>();

	static {
		MATERIALS.put("下界合金", "netherite");
		MATERIALS.put("netherite", "netherite");
		MATERIALS.put("钻石", "diamond");
		MATERIALS.put("diamond", "diamond");
		MATERIALS.put("锁链", "chainmail");
		MATERIALS.put("链甲", "chainmail");
		MATERIALS.put("chainmail", "chainmail");
		MATERIALS.put("皮革", "leather");
		MATERIALS.put("leather", "leather");
		MATERIALS.put("黄金", "golden");
		MATERIALS.put("golden", "golden");
		MATERIALS.put("gold", "golden");
		MATERIALS.put("铁", "iron");
		MATERIALS.put("iron", "iron");
		MATERIALS.put("金", "golden");
		MATERIALS.put("皮", "leather");

		MATERIAL_NAMES.put("netherite", "下界合金");
		MATERIAL_NAMES.put("diamond", "钻石");
		MATERIAL_NAMES.put("iron", "铁");
		MATERIAL_NAMES.put("golden", "金");
		MATERIAL_NAMES.put("leather", "皮革");
		MATERIAL_NAMES.put("chainmail", "锁链");
	}

	/** 「一整套」的各种说法。命中其一 + 有材质 = 四件盔甲。 */
	private static final String[] SET_WORDS = {
		"套装", "全套", "整套", "一套", "一身", "防具", "盔甲", "甲胄",
		"armor set", "armour set", "full set", "armor", "armour"};

	/** 明说「给伙伴自己」的词。这些和「给我」意思完全相反，必须先判。 */
	private static final String[] SELF_MARKERS = {
		"给自己", "给你自己", "你自己", "自己穿", "自己装备", "自己拿", "自己戴"};

	/** 以装备动词开头也算「给他自己」。 */
	private static final String[] SELF_VERB_PREFIXES = {
		"装备上", "装备", "穿戴", "穿上", "穿好", "戴上", "换上", "拿起", "拿上",
		"equip", "wear", "hold"};

	/** 明说「给玩家」的前缀。长的排前面，否则「给我」会先吃掉「给我拿」。 */
	private static final String[] PLAYER_PREFIXES = {
		"给我拿", "给我来", "给我搞", "给我弄", "给我", "拿给我", "帮我拿",
		"帮我砍", "帮我收集", "帮我做", "帮我制作", "帮我搞", "帮我弄",
		"制作", "收集", "砍", "做",
		"bring me", "give me", "craft me", "make me", "gather", "collect"};

	/** 「满配附魔」的各种写法。拆成「程度词」和「附魔」两半，不再整串枚举。 */
	private static final String[] ENCHANT_DEGREES = {
		"满配", "满级", "满魔", "顶配", "顶级", "全附魔", "满附魔", "拉满", "max", "full"};

	private static final String[] ENCHANT_PHRASES = {
		"fully enchanted", "max enchanted", "fully enchant", "max enchant"};

	/** 送去玩家那边时，只摘修饰语；量词交给别名表（「火把」里也有个「把」）。 */
	private static final String[] ENCHANT_STRIP = {
		"满配附魔的", "满级附魔的", "顶配附魔的", "附魔满级的", "全附魔的", "满附魔的",
		"满配附魔", "满级附魔", "顶配附魔", "附魔满级", "全附魔", "满附魔",
		"满配的", "满级的", "顶配的", "顶级的", "满魔的",
		"满配", "满级", "顶配", "顶级", "满魔", "拉满",
		"附魔的", "附魔",
		"fully enchanted", "max enchanted", "fully enchant", "max enchant",
		"enchanted", "enchant"};

	/** 只在「他自己穿」这条路上摘的动词/量词——这条路不需要数量。 */
	private static final String[] EQUIP_FILLER = {
		"给自己", "给你自己", "你自己", "自己", "装备上", "装备", "穿戴", "穿上",
		"穿好", "穿", "戴上", "戴", "换上", "拿起", "拿上", "拿", "一把", "一件",
		"一顶", "一双", "一套", "整套", "全套", "把", "的",
		"equip", "wear", "hold", "a ", "an ", "the "};

	// —— 改动类动词。它们一出现就说明说的是「已经有的东西」 ——

	private static final String[] REMOVE_VERBS = {
		"取消", "去掉", "去除", "移除", "删掉", "删除", "清除", "清掉", "洗掉",
		"洗去", "拿掉", "撤掉", "remove", "strip", "clear"};

	private static final String[] REPAIR_VERBS = {
		"修复", "修一下", "修好", "修满", "补一下", "补满", "repair", "fix", "mend"};

	private static final String[] ADD_VERBS = {
		"加上", "附上", "加个", "加一个", "添加", "打上", "上一个", "加满", "附满", "add"};

	/** 「全部」——没点名具体附魔时，决定 REMOVE 要不要升级成 CLEAR。 */
	private static final String[] ALL_WORDS = {
		"全部", "所有", "都", "统统", "一切", "everything", "all"};

	// —— 作用域词 ——

	private static final String[] PLAYER_EQUIPPED_WORDS = {
		"我身上", "身上", "我穿的", "穿着的", "我穿着", "我的装备", "我的套装",
		"我的盔甲", "我的防具", "on me", "i'm wearing"};

	private static final String[] PLAYER_HELD_WORDS = {
		"我手上", "手上", "我手里", "手里", "手持", "这把", "这件", "in my hand"};

	private static final String[] PLAYER_INVENTORY_WORDS = {
		"我背包", "背包里", "我的背包", "in my bag", "inventory"};

	private static final String[] AGENT_SCOPE_WORDS = {
		"你身上", "你穿的", "你自己身上", "你的装备"};

	private static final Pattern SET_COUNT =
		Pattern.compile("^(\\d+|[一二两三四五六七八九十]+)\\s*[套身]");

	private static final Map<String, Integer> CJK_NUMERALS = Map.ofEntries(
		Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2),
		Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
		Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
		Map.entry("九", 9), Map.entry("十", 10));

	private ItemOperationParser() {
	}

	// --------------------------------------------------------------- 自然语言入口

	/**
	 * 解析一句玩家原话。
	 *
	 * @return 解析不出确定结果时返回空——这句话会照常落到 LLM，绝不靠猜执行。
	 */
	public static Optional<ItemOperation> parse(String rawText, Vocabulary vocabulary) {
		if (rawText == null || rawText.isBlank()
				|| vocabulary == null || !vocabulary.isUsable()) {
			return Optional.empty();
		}
		String text = normalize(rawText);
		// 先定动作。改动类动词一出现就不可能是"凭空造一件新的"。
		Optional<ItemOperation> edit = parseEdit(text, vocabulary);
		if (edit.isPresent()) {
			return edit;
		}
		return parseConjure(text, vocabulary);
	}

	// ------------------------------------------------------------- 改动已有的物品

	private static Optional<ItemOperation> parseEdit(String text, Vocabulary vocabulary) {
		boolean remove = containsAny(text, REMOVE_VERBS);
		boolean repair = containsAny(text, REPAIR_VERBS);
		boolean add = containsAny(text, ADD_VERBS);
		ItemOperation.Scope scope = detectExistingScope(text);

		ItemOperation.Transform transform;
		List<String> enchantments = List.of();
		if (repair) {
			transform = ItemOperation.Transform.REPAIR;
		} else if (remove) {
			// 「取消/去掉」永远是对已有物品说的，不需要作用域词也成立。
			List<String> named = vocabulary.enchantments().findAll(text);
			if (!named.isEmpty()) {
				transform = ItemOperation.Transform.REMOVE_ENCHANT;
				enchantments = named;
			} else if (containsAny(text, ALL_WORDS) || text.contains("附魔")) {
				transform = ItemOperation.Transform.CLEAR_ENCHANTS;
			} else {
				return Optional.empty(); // 不知道要去掉什么，宁可交给 LLM 问清楚
			}
		} else if (add) {
			// 「加」类动词太弱，必须配一个"已有物品"的作用域才算改动，
			// 否则「给我加32个火把」会被当成修改而不是取物。
			if (scope == null) {
				return Optional.empty();
			}
			List<String> named = vocabulary.enchantments().findAll(text);
			if (!named.isEmpty()) {
				transform = ItemOperation.Transform.ADD_ENCHANT;
				enchantments = named;
			} else if (mentionsFullEnchant(text)) {
				transform = ItemOperation.Transform.SET_MAX_ENCHANTS;
			} else {
				return Optional.empty();
			}
		} else {
			return Optional.empty(); // 没有改动动词，交给取物那条路
		}

		ItemOperation.Selector selector = detectEditSelector(text, vocabulary);
		if (scope == null) {
			// 没明说在哪：说的是护甲就按身上穿的算，说「这把」就按手上那件算。
			scope = selector instanceof ItemOperation.Selector.Filter filter
					&& filter.kind() == ItemOperation.Selector.Filter.Kind.HELD
				? ItemOperation.Scope.PLAYER_HELD : ItemOperation.Scope.PLAYER_EQUIPPED;
		}
		return Optional.of(ItemOperation.edit(scope, selector, transform, enchantments,
			describeEdit(transform, enchantments, vocabulary)));
	}

	private static ItemOperation.Scope detectExistingScope(String text) {
		if (containsAny(text, AGENT_SCOPE_WORDS)) {
			return ItemOperation.Scope.AGENT_EQUIPPED;
		}
		if (containsAny(text, PLAYER_HELD_WORDS)) {
			return ItemOperation.Scope.PLAYER_HELD;
		}
		if (containsAny(text, PLAYER_INVENTORY_WORDS)) {
			return ItemOperation.Scope.PLAYER_INVENTORY;
		}
		if (containsAny(text, PLAYER_EQUIPPED_WORDS)) {
			return ItemOperation.Scope.PLAYER_EQUIPPED;
		}
		return null;
	}

	private static ItemOperation.Selector detectEditSelector(String text,
			Vocabulary vocabulary) {
		if (containsAny(text, PLAYER_HELD_WORDS)) {
			return ItemOperation.Selector.Filter.of(
				ItemOperation.Selector.Filter.Kind.HELD);
		}
		if (mentionsSet(text)) {
			return ItemOperation.Selector.Filter.of(
				ItemOperation.Selector.Filter.Kind.ARMOR);
		}
		// 点名了某件具体物品？（「去掉我那把下界合金剑的击退」）
		for (String key : new String[] {"的", " "}) {
			for (String part : text.split(Pattern.quote(key))) {
				var resolved = vocabulary.items().resolve(part.trim());
				if (resolved.isPresent()) {
					return ItemOperation.Selector.Filter.item(resolved.get().itemId());
				}
			}
		}
		if (containsAny(text, ALL_WORDS)) {
			return ItemOperation.Selector.Filter.of(
				ItemOperation.Selector.Filter.Kind.ALL);
		}
		return ItemOperation.Selector.Filter.of(
			ItemOperation.Selector.Filter.Kind.ARMOR);
	}

	private static String describeEdit(ItemOperation.Transform transform,
			List<String> enchantments, Vocabulary vocabulary) {
		List<String> names = new ArrayList<>();
		for (String id : enchantments) {
			names.add(vocabulary.enchantments().displayName(id));
		}
		return switch (transform) {
			case REMOVE_ENCHANT -> "去掉「" + String.join("、", names) + "」";
			case ADD_ENCHANT -> "加上「" + String.join("、", names) + "」";
			case CLEAR_ENCHANTS -> "清空全部附魔";
			case SET_MAX_ENCHANTS -> "附满所有可用附魔";
			case REPAIR -> "修满耐久";
			case NONE -> "不做改动";
		};
	}

	// ------------------------------------------------------------------- 凭空取物

	private static Optional<ItemOperation> parseConjure(String text, Vocabulary vocabulary) {
		var aliases = vocabulary.items();
		// 目标槽：「给自己」和「给我」意思相反，必须先定下来再谈物品。
		ItemOperation.Delivery delivery;
		String body;
		String selfBody = stripSelf(text);
		if (selfBody != null) {
			delivery = ItemOperation.Delivery.TO_AGENT_EQUIP;
			body = selfBody;
		} else {
			String playerBody = stripPlayerPrefix(text);
			// 没明说去向时不立刻放弃：整句话如果只剩槽位（「下界合金全套 满附魔」），
			// 那它就是一条命令；只要还夹着别的话（「这个下界合金套装真好看」），
			// 就仍然是闲聊，交给 LLM。
			delivery = ItemOperation.Delivery.TO_PLAYER;
			body = playerBody == null ? text : playerBody;
			if (playerBody == null && !isBareSlotPhrase(body)) {
				return Optional.empty();
			}
		}

		// 附魔槽：程度词 + 「附魔」任一半命中即可。服务端只会推满配，所以没有中间态。
		ItemOperation.Transform transform = mentionsFullEnchant(body)
			? ItemOperation.Transform.SET_MAX_ENCHANTS : ItemOperation.Transform.NONE;
		String plain = stripAll(body, ENCHANT_STRIP).trim();

		// 形状槽：材质 + 「一整套」= 四件盔甲。这一步在单件解析之前，
		// 否则「下界合金套装」会被当成一个查不到的物品名而整句失败。
		String materialKey = longestMaterialKey(plain);
		if (materialKey != null && mentionsSet(plain)) {
			int sets = setCount(plain);
			Optional<ItemOperation> set = armorSet(delivery, MATERIALS.get(materialKey),
				sets, transform, vocabulary);
			if (set.isPresent()) {
				return set;
			}
		}

		// 单件：交给别名表。数量词一直是它在管（「32 个火把」），这里不要抢——
		// 「火把」里就有个「把」，在这儿剥量词会把物品名一起剥掉。
		String remainder = delivery == ItemOperation.Delivery.TO_AGENT_EQUIP
			? stripAll(plain, EQUIP_FILLER).trim() : stripLeadingArticle(plain);
		if (remainder.isEmpty()) {
			return Optional.empty();
		}
		final ItemOperation.Transform finalTransform = transform;
		final ItemOperation.Delivery finalDelivery = delivery;
		return aliases.resolve(remainder).map(r -> ItemOperation.conjureOne(
			r.itemId(), r.count(), finalTransform, finalDelivery, null));
	}

	// ----------------------------------------------------------------- 结构化入口

	/**
	 * 面板按钮和 LLM 工具走的入口：槽位已经是结构化的，不再过一遍自然语言解析。
	 *
	 * @param material 材质词，中文或注册表前缀（{@code 下界合金} / {@code netherite}）
	 */
	public static Optional<ItemOperation> armorSet(ItemOperation.Delivery delivery,
			String material, int sets, ItemOperation.Transform transform,
			Vocabulary vocabulary) {
		String prefix = materialPrefix(material);
		if (prefix == null || vocabulary == null || !vocabulary.isUsable()
				|| sets < 1 || sets > MAX_SETS) {
			return Optional.empty();
		}
		List<ItemOperation.Line> lines = new ArrayList<>();
		for (String piece : ARMOR_PIECES) {
			String id = "minecraft:" + prefix + "_" + piece;
			// 皮革/木头之类没有全套的材质在这里被注册表挡掉，而不是穿到一半才发现。
			if (vocabulary.items().resolve(id).isEmpty()) {
				return Optional.empty();
			}
			lines.add(new ItemOperation.Line(id, sets));
		}
		String name = MATERIAL_NAMES.getOrDefault(prefix, prefix) + "套装"
			+ (sets > 1 ? " ×" + sets : "");
		return Optional.of(ItemOperation.conjure(lines, transform, List.of(),
			delivery, name));
	}

	/** 单件的结构化入口：id 仍然要过注册表校验。 */
	public static Optional<ItemOperation> piece(ItemOperation.Delivery delivery,
			String itemOrAlias, int count, ItemOperation.Transform transform,
			Vocabulary vocabulary) {
		if (itemOrAlias == null || vocabulary == null || !vocabulary.isUsable()
				|| count < 1 || count > MAX_TOTAL_ITEMS) {
			return Optional.empty();
		}
		return vocabulary.items().resolve(itemOrAlias.trim())
			.map(r -> ItemOperation.conjureOne(r.itemId(), count, transform,
				delivery, null));
	}

	/** 材质词 → 注册表前缀；已经是前缀时原样返回。 */
	public static String materialPrefix(String material) {
		if (material == null || material.isBlank()) {
			return null;
		}
		String key = material.trim().toLowerCase(Locale.ROOT);
		if (MATERIAL_NAMES.containsKey(key)) {
			return key;
		}
		return MATERIALS.get(key);
	}

	/** 盔甲部位注册表名，执行器挑「身上穿的」时共用同一份定义。 */
	public static List<String> armorPieces() {
		return List.of(ARMOR_PIECES);
	}

	// --------------------------------------------------------------------- 槽位

	static String normalize(String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT);
		while (!s.isEmpty() && "!.?！？。，, ".indexOf(s.charAt(s.length() - 1)) >= 0) {
			s = s.substring(0, s.length() - 1);
		}
		return s.replaceAll("\\s+", " ");
	}

	/** 命中「给他自己」时返回剥掉标记后的剩余文本，否则 null。 */
	private static String stripSelf(String text) {
		for (String marker : SELF_MARKERS) {
			int at = text.indexOf(marker);
			if (at >= 0) {
				return (text.substring(0, at) + text.substring(at + marker.length())).trim();
			}
		}
		for (String verb : SELF_VERB_PREFIXES) {
			if (text.startsWith(verb)) {
				return text.substring(verb.length()).trim();
			}
		}
		return null;
	}

	private static String stripPlayerPrefix(String text) {
		for (String prefix : PLAYER_PREFIXES) {
			if (text.startsWith(prefix)) {
				return text.substring(prefix.length()).trim();
			}
		}
		return null;
	}

	private static boolean mentionsFullEnchant(String text) {
		for (String phrase : ENCHANT_PHRASES) {
			if (text.contains(phrase)) {
				return true;
			}
		}
		// 「附魔」单独出现也算满配：服务端只推得出满配这一档，
		// 说「附魔的铁剑」却给一把白板，比给满配更违背玩家预期。
		if (text.contains("附魔")) {
			return true;
		}
		return containsAny(text, ENCHANT_DEGREES);
	}

	private static boolean mentionsSet(String text) {
		return containsAny(text, SET_WORDS);
	}

	private static boolean containsAny(String text, String[] needles) {
		for (String needle : needles) {
			if (text.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private static int setCount(String text) {
		Matcher m = SET_COUNT.matcher(text);
		if (!m.find()) {
			return 1;
		}
		String num = m.group(1);
		int n = Character.isDigit(num.charAt(0))
			? safeInt(num) : CJK_NUMERALS.getOrDefault(num, 1);
		return n < 1 || n > MAX_SETS ? 1 : n;
	}

	private static int safeInt(String raw) {
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	/** 命中的材质<b>词</b>（不是前缀）：「下界合金」必须赢过它自己里面的「金」。 */
	private static String longestMaterialKey(String text) {
		String best = null;
		for (String key : MATERIALS.keySet()) {
			if (text.contains(key) && (best == null || key.length() > best.length())) {
				best = key;
			}
		}
		return best;
	}

	/**
	 * 整句话是不是「只剩槽位」——即摘掉材质、形状、附魔、数量之后什么都不剩。
	 *
	 * <p>这条判据决定了没有「给我」时敢不敢执行：「下界合金全套 满附魔」摘完是空的，
	 * 是命令；「这个下界合金套装真好看」摘完还剩「这个真好看」，是闲聊。</p>
	 */
	private static boolean isBareSlotPhrase(String text) {
		String rest = stripAll(text, ENCHANT_STRIP);
		String materialKey = longestMaterialKey(rest);
		if (materialKey == null || !mentionsSet(rest)) {
			return false;
		}
		rest = rest.replace(materialKey, "");
		rest = stripAll(rest, SET_WORDS);
		rest = rest.replaceAll("^(\\d+|[一二两三四五六七八九十]+)\\s*[套身件]?", "");
		return stripAll(rest, new String[] {"的", "件", "套", "身"}).isBlank();
	}

	/** 英文冠词/量词开头（"a fully enchanted 铁镐"）。中文那边由别名表的数量词管。 */
	private static String stripLeadingArticle(String text) {
		for (String article : new String[] {"a ", "an ", "the ", "one ", "some "}) {
			if (text.startsWith(article)) {
				return text.substring(article.length()).trim();
			}
		}
		return text;
	}

	private static String stripAll(String text, String[] words) {
		String out = text;
		for (String word : words) {
			out = out.replace(word, "");
		}
		return out.replaceAll("\\s+", " ").trim();
	}
}
