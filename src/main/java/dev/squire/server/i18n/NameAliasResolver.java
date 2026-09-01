package dev.squire.server.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 「中文名 → 注册表 id」的通用别名解析（附魔、状态效果、结构各用一份实例）。
 *
 * <p>为什么这些表必须存在：物品一直有别名表，其余全都没有。于是「取消我套装的荆棘
 * 附魔」里的「荆棘」、「加个夜视」里的「夜视」、「最近的远古城市」里的「远古城市」
 * 在系统里全部等同于乱码——不是解析没跟上，是这些词从来不在词汇表里。缺一张表，
 * 整类请求就永远做不到，加多少规则都没用。</p>
 *
 * <p>和物品表一样 fail-closed：每个候选 id 都要过注入的注册表校验，查不到就返回空，
 * 绝不把一个猜出来的 id 交给执行器。</p>
 */
public final class NameAliasResolver {

	private static final Logger LOG =
		LoggerFactory.getLogger(NameAliasResolver.class);

	/** 分隔多个附魔的写法：「荆棘和保护」「荆棘、保护」「thorns, protection」。 */
	private static final String SPLIT = "[、,，/和跟与]|\\band\\b";

	/** 摘掉之后才对得上表的尾缀。 */
	private static final String[] SUFFIXES = {
		"附魔", "效果", "这条", "那条", "buff", "状态"};

	private final Map<String, String> aliases = new LinkedHashMap<>();
	private final Predicate<String> registryHas;
	/** 注册表译名（id → 本地化名）与全部 id；接上之后模组内容自动进词表。 */
	private java.util.function.UnaryOperator<String> localizedName;
	private java.util.function.Supplier<List<String>> allIds;
	private Map<String, String> localizedIndex;

	public NameAliasResolver(Predicate<String> registryHas) {
		this.registryHas = registryHas;
	}

	/**
	 * 接上注册表译名：认得的词从此覆盖<b>所有已安装模组</b>的内容。
	 *
	 * <p>和物品表一样的道理——「打那只淡水鳄」里的「淡水鳄」不该要求我们先为
	 * Alex 的生物手写一张表；那个模组自己就带着这个名字。</p>
	 */
	public void useRegistryNames(java.util.function.UnaryOperator<String> nameOf,
			java.util.function.Supplier<List<String>> ids) {
		this.localizedName = nameOf;
		this.allIds = ids;
		this.localizedIndex = null;
	}

	private Map<String, String> localizedIndex() {
		if (localizedIndex != null) {
			return localizedIndex;
		}
		Map<String, String> index = new LinkedHashMap<>();
		if (localizedName != null && allIds != null) {
			for (String id : allIds.get()) {
				String name = localizedName.apply(id);
				if (name == null || name.isBlank()) {
					continue;
				}
				String key = name.trim().toLowerCase(Locale.ROOT).replace(" ", "");
				String existing = index.get(key);
				// 同名冲突原版优先，其余按注册表顺序先到先得——结果稳定可复现。
				if (existing == null
						|| (!existing.startsWith("minecraft:") && id.startsWith("minecraft:"))) {
					index.put(key, id);
				}
			}
		}
		localizedIndex = index;
		return index;
	}

	public static NameAliasResolver fromResource(
			Predicate<String> registryHas, String resourcePath) {
		NameAliasResolver r = new NameAliasResolver(registryHas);
		try (var in = NameAliasResolver.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				LOG.warn("[alias] resource {} missing; enchantment aliases disabled",
					resourcePath);
				return r;
			}
			r.mergeJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOG.warn("[alias] failed reading {}: {}", resourcePath, e.toString());
		}
		return r;
	}

	/** 合并玩家自备的覆盖表（模组附魔靠它补）。 */
	public void loadOverride(Path file) {
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			mergeJson(Files.readString(file, StandardCharsets.UTF_8));
			LOG.info("[alias] merged enchantment overrides from {}", file);
		} catch (Exception e) {
			LOG.warn("[alias] enchantment override load failed (kept defaults): {}",
				e.toString());
		}
	}

	private void mergeJson(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		if (!root.has("aliases")) {
			return;
		}
		for (Map.Entry<String, com.google.gson.JsonElement> e
				: root.getAsJsonObject("aliases").entrySet()) {
			if (e.getValue().isJsonPrimitive()) {
				aliases.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
			}
		}
	}

	public int size() {
		return aliases.size();
	}

	/** 附魔 id → 中文名（回执里写 minecraft:thorns 对玩家等同于噪音）。 */
	public String displayName(String enchantmentId) {
		if (enchantmentId == null || enchantmentId.isBlank()) {
			return "";
		}
		for (var entry : aliases.entrySet()) {
			if (entry.getValue().equals(enchantmentId)) {
				return entry.getKey();
			}
		}
		if (localizedName != null) {
			String localized = localizedName.apply(enchantmentId);
			if (localized != null && !localized.isBlank()) {
				return localized;
			}
		}
		int colon = enchantmentId.indexOf(':');
		return colon < 0 ? enchantmentId : enchantmentId.substring(colon + 1);
	}

	/** 单个附魔名 → 校验过的注册表 id。 */
	public Optional<String> resolve(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String name = raw.trim().toLowerCase(Locale.ROOT);
		for (String suffix : SUFFIXES) {
			if (name.endsWith(suffix) && name.length() > suffix.length()) {
				name = name.substring(0, name.length() - suffix.length()).trim();
			}
		}
		if (name.startsWith("的")) {
			name = name.substring(1).trim();
		}
		String mapped = aliases.get(name);
		if (mapped == null) {
			mapped = localizedIndex().get(name.replace(" ", ""));
		}
		if (mapped == null) {
			mapped = name;
		}
		String id = mapped.contains(":") ? mapped : "minecraft:" + mapped;
		if (!id.matches("[a-z0-9_.\\-]+:[a-z0-9_.\\-/]+")) {
			return Optional.empty();
		}
		return registryHas.test(id) ? Optional.of(id) : Optional.empty();
	}

	/**
	 * 从一句话里挑出<b>所有</b>认得的附魔名。
	 *
	 * <p>先按分隔符切，切不出来就在整句里扫最长匹配——「取消我套装的荆棘附魔」
	 * 没有任何分隔符，但「荆棘」就在里面。</p>
	 */
	public List<String> findAll(String text) {
		List<String> found = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return found;
		}
		// 先按分隔符切一遍，能整段命中的（"荆棘、保护"）就先收下。
		for (String part : text.split(SPLIT)) {
			resolve(part).ifPresent(id -> addOnce(found, id));
		}
		// 然后<b>无论如何</b>再全句扫一遍。「取消我套装的荆棘和摔落缓冲」切完只有后半
		// 段能整段命中，前半段还粘着动词——只信第一遍就会漏掉「荆棘」。
		String lower = text.toLowerCase(Locale.ROOT);
		// 长词优先：「火焰保护」必须赢过它自己里面的「保护」。
		// 注册表译名也一起扫，模组的附魔/生物才不至于「表里没有＝不存在」。
		Map<String, String> candidates = new LinkedHashMap<>(localizedIndex());
		candidates.putAll(aliases); // 我们自己挑的说法优先级更高
		List<String> keys = new ArrayList<>(candidates.keySet());
		keys.sort((a, b) -> b.length() - a.length());
		String remaining = lower;
		for (String key : keys) {
			if (key.isBlank() || !remaining.contains(key)) {
				continue;
			}
			String id = candidates.get(key);
			if (registryHas.test(id)) {
				addOnce(found, id);
				remaining = remaining.replace(key, " ");
			}
		}
		return found;
	}

	private static void addOnce(List<String> target, String id) {
		if (!target.contains(id)) {
			target.add(id);
		}
	}
}
