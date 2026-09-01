package dev.squire.server.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Chinese item-alias resolution (spec §74). The table at
 * {@code data/squire/aliases/zh_cn.json} is ORIGINAL content — Mojang language
 * resources are never bundled.
 *
 * <p>Resolution order (spec §74):</p>
 * <ol>
 *   <li>Registry ID — input already looks like {@code namespace:path}</li>
 *   <li>exact alias — whole string matches the table</li>
 *   <li>quantity normalization — strip 数量词 ({@code 3个面包}, {@code 两组火把})
 *       and resolve the remainder</li>
 *   <li>typo/fuzzy — RESERVED for v1.x (not deterministic enough to gate actions)</li>
 *   <li>LLM candidate — RESERVED; an LLM suggestion can never bypass validation</li>
 *   <li>Registry validation — EVERY candidate must pass the injected registry
 *       predicate before it becomes a result</li>
 * </ol>
 */
public final class ItemAliasResolver {

	private static final Logger LOG = LoggerFactory.getLogger(ItemAliasResolver.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** A resolved, registry-validated item request. */
	public record Resolution(String itemId, int count, String matchedBy) {
	}

	/** Common CJK numerals accepted before a quantifier. */
	private static final Map<String, Integer> CJK_NUMERALS = Map.ofEntries(
		Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2),
		Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
		Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
		Map.entry("九", 9));

	/**
	 * Quantifier words stripped between number and item name (个/只/把/…).
	 *
	 * <p>「件/套/双/顶/支」是后补的：以前「给我一套下界合金套装」会被切成
	 * {@code count=1} + {@code name="套下界合金套装"}，别名表当然查不到，
	 * 一句完全正常的话就这样掉进了 LLM。</p>
	 */
	public static final Set<String> QUANTIFIERS = Set.of(
		"个", "只", "块", "把", "张", "条", "颗", "根", "枚", "瓶", "组",
		"件", "套", "双", "顶", "支");

	/**
	 * 注册表里那件物品<b>在当前语言下</b>的名字。由外部注入，本类因此仍然零 MC 依赖。
	 *
	 * <p>这是「装了四十个模组、别名表只认原版」的解法：手写一张涵盖机械动力、
	 * 农夫乐事、暮色森林、匠魂……的中文表既写不完也会过期，而每个模组本来就自带
	 * 一份官方中文译名。单人游戏里服务端和客户端共用一份语言文件，
	 * {@code Item.getName()} 拿到的就是玩家在物品栏里看到的那个名字。</p>
	 */
	public interface RegistryNames {
		/** id → 本地化名；不认识返回 null。 */
		String nameOf(String itemId);

		/** 全部物品 id，用来建「中文名 → id」的反查索引。 */
		java.util.List<String> allItemIds();
	}

	private final Map<String, String> aliases = new LinkedHashMap<>();
	private final Predicate<String> registryHasItem;
	private RegistryNames registryNames;
	/** 本地化名 → id 的反查索引，懒建一次。 */
	private Map<String, String> localizedIndex;

	public ItemAliasResolver(Predicate<String> registryHasItem) {
		this.registryHasItem = registryHasItem;
	}

	/** 接上注册表译名。传 null 关掉（纯 Java 测试里就是这个状态）。 */
	public void useRegistryNames(RegistryNames names) {
		this.registryNames = names;
		this.localizedIndex = null;
	}

	/** 反查索引的规模，给诊断命令看。 */
	public int localizedNameCount() {
		return localizedIndex().size();
	}

	/**
	 * 「中文名 → id」的反查索引。
	 *
	 * <p>同名冲突时<b>原版优先</b>，其余按注册表顺序先到先得——注册表顺序在同一套模组
	 * 下是稳定的，所以同一句话每次都解析到同一个物品，不会今天给你机械动力的那个、
	 * 明天给你匠魂的那个。</p>
	 */
	private Map<String, String> localizedIndex() {
		if (localizedIndex != null) {
			return localizedIndex;
		}
		Map<String, String> index = new LinkedHashMap<>();
		if (registryNames != null) {
			for (String id : registryNames.allItemIds()) {
				String name = registryNames.nameOf(id);
				if (name == null || name.isBlank()) {
					continue;
				}
				String key = normalizeName(name);
				String existing = index.get(key);
				if (existing == null
						|| (!existing.startsWith("minecraft:") && id.startsWith("minecraft:"))) {
					index.put(key, id);
				}
			}
		}
		localizedIndex = index;
		return index;
	}

	/** 比对用的规范形式：中文名没有空格，英文名有，统一抹掉再比。 */
	private static String normalizeName(String name) {
		return name.trim().toLowerCase(Locale.ROOT).replace(" ", "");
	}

	/** Loads the bundled original alias table (classpath resource). */
	public static ItemAliasResolver fromResource(Predicate<String> registryHasItem,
			String resourcePath) {
		ItemAliasResolver r = new ItemAliasResolver(registryHasItem);
		try (var in = ItemAliasResolver.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				LOG.warn("[alias] resource {} missing; aliases disabled", resourcePath);
				return r;
			}
			r.mergeJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOG.warn("[alias] failed reading {}: {}", resourcePath, e.toString());
		}
		return r;
	}

	/** Loads/merges a user-supplied override file if present. */
	public void loadOverride(Path file) {
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			mergeJson(Files.readString(file, StandardCharsets.UTF_8));
			LOG.info("[alias] merged user overrides from {}", file);
		} catch (Exception e) {
			LOG.warn("[alias] override load failed (kept defaults): {}", e.toString());
		}
	}

	private void mergeJson(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		if (root.has("aliases")) {
			for (Map.Entry<String, com.google.gson.JsonElement> e : root
					.getAsJsonObject("aliases").entrySet()) {
				if (e.getValue().isJsonPrimitive()) {
					aliases.put(e.getKey(), e.getValue().getAsString());
				}
			}
		}
	}

	public int size() {
		return aliases.size();
	}

	/**
	 * Resolve free text to a validated item id with count (default 1).
	 *
	 * @return empty when nothing deterministic matches or fails registry validation
	 */
	/**
	 * 物品 id → 玩家看得懂的名字（取别名表里第一个指向它的中文词）。
	 *
	 * <p>为什么需要：回执里写「已经把 64 个 minecraft:oak_log 给你了」对玩家几乎是
	 * 噪音。三级兜底：</p>
	 * <ol>
	 *   <li>别名表反用——原版物品用我们自己挑的说法（「圆石」而不是「圆石块」）；</li>
	 *   <li>{@link RegistryNames 注册表译名}——模组物品全靠这一层，
	 *       「还差 12 个 create:andesite_alloy」于是变成「还差 12 个安山合金」；</li>
	 *   <li>都没有才退回 id 的 path 段。</li>
	 * </ol>
	 */
	public String displayName(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return "";
		}
		for (var entry : aliases.entrySet()) {
			if (entry.getValue().equals(itemId)) {
				return entry.getKey();
			}
		}
		if (registryNames != null) {
			String localized = registryNames.nameOf(itemId);
			if (localized != null && !localized.isBlank()) {
				return localized;
			}
		}
		int colon = itemId.indexOf(':');
		return colon < 0 ? itemId : itemId.substring(colon + 1);
	}

	public Optional<Resolution> resolve(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String text = raw.trim();

		// (1) already a Registry ID?
		if (text.matches("[a-z0-9_.\\-]+:[a-z0-9_.\\-/]+")) {
			return validated(text, 1, "registry_id");
		}
		// bare path like "bread" → try minecraft namespace
		if (text.matches("[a-z0-9_\\-/]+") && !aliases.containsKey(text)) {
			Optional<Resolution> bare = validated("minecraft:" + text, 1, "registry_id");
			if (bare.isPresent()) {
				return bare;
			}
		}

		int count = 1;
		String name = text;

		// leading quantity: "3个面包" / "两组火把" / "10 bread"
		java.util.regex.Matcher lead = java.util.regex.Pattern
			.compile("^(\\d+|" + "[一二两三四五六七八九十]+)\\s*([" +
				String.join("", QUANTIFIERS) + "]?)\\s*(.+)$")
			.matcher(text);
		if (lead.matches()) {
			Integer parsed = leadingCount(lead.group(1), lead.group(2));
			if (parsed != null) {
				count = parsed;
				name = lead.group(3).trim();
			}
		} else {
			// trailing quantity: "面包x3" / "面包×10"
			java.util.regex.Matcher trail = java.util.regex.Pattern
				.compile("^(.+?)\\s*[x×]\\s*(\\d{1,3})$")
				.matcher(text);
			if (trail.matches()) {
				name = trail.group(1).trim();
				count = Integer.parseInt(trail.group(2));
			}
		}

		// (2) exact alias on the full text first ("面包" itself may be an entry)
		Optional<Resolution> exact = validated(name, count, "exact_alias");
		if (exact.isEmpty() && !name.equals(text)) {
			exact = validated(text, 1, "exact_alias");
		}
		if (exact.isPresent()) {
			return exact;
		}

		// (3) quantity-normalized name against the alias table happens inside
		// validated(); fuzzy (4) and LLM candidates (5) are deliberately RESERVED:
		// a fuzzy or model-proposed guess never gates an action in v1.
		return Optional.empty();
	}

	private Integer leadingCount(String numPart, String quantifier) {
		int n;
		if (Character.isDigit(numPart.charAt(0))) {
			n = Integer.parseInt(numPart);
		} else if ("十".equals(numPart)) {
			n = 10;
		} else {
			n = CJK_NUMERALS.getOrDefault(numPart, -1);
		}
		if (n <= 0 || n > 4096) {
			return null;
		}
		if ("组".equals(quantifier)) {
			n *= 64; // one stack
		}
		return Math.min(n, 4096);
	}

	/** Step 6: alias lookup + final registry validation — nothing leaves unvalidated. */
	private Optional<Resolution> validated(String name, int count, String stage) {
		String mapped = aliases.getOrDefault(name,
			aliases.get(name.toLowerCase(Locale.ROOT)));
		if (mapped == null) {
			// 别名表没有，就问游戏自己：玩家说的往往正是物品栏里印着的那几个字。
			// 模组物品全部走这一条——不需要为每个模组手写一张表。
			mapped = localizedIndex().get(normalizeName(name));
		}
		if (mapped == null) {
			mapped = name;
		}
		String id = mapped.contains(":") ? mapped : "minecraft:" + mapped;
		if (!id.matches("[a-z0-9_.\\-]+:[a-z0-9_.\\-/]+")) {
			return Optional.empty();
		}
		if (!registryHasItem.test(id)) {
			return Optional.empty(); // §74 step 6: registry validation is mandatory
		}
		return Optional.of(new Resolution(id, count, stage));
	}
}
