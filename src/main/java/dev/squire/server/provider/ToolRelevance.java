package dev.squire.server.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

import dev.squire.common.protocol.ToolDescriptor;

/**
 * 按这一句话挑要发给模型的工具子集。
 *
 * <p>工具从 34 个涨到 39 个之后，每次请求都把整份目录塞进系统提示词已经明显偏长：
 * 首 token 更慢，而且和这句话无关的四十条说明本身就是干扰。玩家问「最近的樱花林
 * 在哪」时，模型不需要知道怎么改附魔、怎么盖房子。</p>
 *
 * <p><b>裁剪必须是保守的</b>——漏掉一个本该有的工具，表现出来就是「他突然不会这件
 * 事了」。候选由内置领域提示和工具自己的元数据共同评分：</p>
 * <ul>
 *   <li>核心工具永远在（状态查询、取物、说不出话时的兜底）；</li>
 *   <li>一句话命中多个话题就合并候选；</li>
 *   <li>MCP/第三方工具按名称、描述、标签、别名和参数说明参与评分；</li>
 *   <li>未知闲聊只保留安全查询核心，目录始终受候选上限约束。</li>
 * </ul>
 *
 * <p>{@code ToolRelevanceTest} 盯着「每个模型可见工具至少属于一个话题」：
 * 新加工具却忘了归类，测试会红，而不是等玩家发现它从来没被选中过。</p>
 */
public final class ToolRelevance {

	/** 少于这个数就别裁了，直接全发——省不下多少，反而多一份漏掉的风险。 */
	public static final int MIN_CATALOG_TO_TRIM = 14;
	public static final int DEFAULT_CANDIDATE_LIMIT = 16;

	/** 一个话题。{@code prefixes} 匹配工具名的前缀。 */
	private record Topic(String name, List<String> keywords, List<String> prefixes) { }

	/** 任何一次请求都带上的工具：问状态、取物、以及"他能干什么"这类兜底。 */
	private static final List<String> ALWAYS = List.of(
		"query.status", "query.player", "query.inventory", "query.equipment");

	private static final List<Topic> TOPICS = List.of(
		new Topic("locate",
			List.of("在哪", "在什么地方", "找一下", "找找", "帮我找", "最近的", "哪里有",
				"怎么走", "坐标", "村庄", "要塞", "城市", "神殿", "群系", "森林", "沼泽",
				"where", "find", "locate", "coordinates"),
			List.of("world.locate_", "memory.recall_location", "memory.list_locations")),
		new Topic("world_state",
			List.of("时间", "白天", "夜晚", "晚上", "中午", "午夜", "天亮", "天黑",
				"天气", "雨", "晴", "雷", "time", "weather", "rain", "night", "day"),
			List.of("world.set_")),
		new Topic("effects",
			List.of("效果", "夜视", "抗火", "速度", "力量", "急迫", "抗性", "水下呼吸",
				"buff", "effect", "night vision"),
			List.of("minecraft.command.effect")),
		new Topic("items",
			List.of("给我", "拿", "要", "物品", "东西", "装备", "穿", "套装", "盔甲",
				"附魔", "荆棘", "保护", "锋利", "修复", "耐久", "剑", "镐", "斧",
				"火把", "木头", "石头", "铁", "钻石", "下界合金",
				"give", "item", "armor", "enchant", "repair", "equip", "gear"),
			List.of("items.", "inventory.", "minecraft.command.give")),
		new Topic("combat",
			List.of("打", "杀", "怪", "僵尸", "苦力怕", "骷髅", "保护", "护卫", "守",
				"救", "血", "治疗", "受伤", "危险", "安全", "附近",
				"弓", "箭", "射", "近战", "武器", "打法",
				"attack", "kill", "guard", "protect", "heal", "hostile", "danger", "bow"),
			List.of("combat.", "guard.", "aid.owner", "heal.now", "entity.scan_nearby")),
		new Topic("build",
			List.of("盖", "建", "造", "搭", "房", "屋", "蓝图", "工程", "哨塔", "前哨站",
				"施工", "铺", "填", "区域", "选区",
				"build", "house", "blueprint", "project", "fill", "outpost"),
			List.of("blueprint.", "project.", "build.", "minecraft.command.fill",
				"minecraft.command.setblock", "worldedit.")),
		// 蓝图参数。单独一个话题，而不是并进 build——「旋转一下」「改成两层」这类
		// 句子里<b>一个建筑词都没有</b>，原来一个话题都命不中，于是退回全量目录，
		// §23 省下的那点上下文当场又还回去了。
		new Topic("blueprint_params",
			List.of("旋转", "转一下", "转个", "朝向", "转向", "90度", "九十度",
				"镜像", "翻转", "反过来", "对称",
				"楼层", "层", "两层", "三层", "一层", "多层",
				"模块", "门廊", "烟囱", "侧翼", "塔楼", "阳台",
				"材料", "墙", "屋顶", "地基", "地板", "窗", "换成",
				"大小", "尺寸", "放大", "缩小", "大一点", "小一点",
				"预设", "保存", "加载", "之前那个", "上次那个", "存一下",
				"rotate", "rotation", "facing", "turn", "degrees",
				"mirror", "flip", "floor", "floors", "storey", "storeys",
				"module", "porch", "chimney", "wing", "tower", "balcony",
				"material", "wall", "roof", "foundation", "window",
				"size", "bigger", "smaller", "preset", "save", "load"),
			List.of("blueprint.")),
		new Topic("crafting",
			List.of("怎么做", "配方", "合成", "做一个", "制作", "怎么合",
				"recipe", "craft", "how do i make"),
			List.of("crafting.")),
		new Topic("memory",
			List.of("记住", "这里是", "家", "基地", "仓库", "农场", "矿洞", "忘了",
				"remember", "home", "base", "warehouse", "forget"),
			List.of("memory.")),
		new Topic("containers",
			List.of("箱子", "容器", "桶", "熔炉", "存", "放", "整理",
				"chest", "barrel", "container", "sort", "store"),
			List.of("container.")),
		new Topic("movement",
			List.of("过来", "去", "走", "跟", "传送", "移动", "那边", "身边",
				"主世界", "下界", "地狱", "末地", "tp",
				"come", "go", "walk", "move", "teleport", "overworld", "nether"),
			List.of("navigation.", "minecraft.command.teleport", "player.teleport")),
		new Topic("summon",
			List.of("召唤", "生成", "来只", "弄只", "狼", "猫", "村民", "铁傀儡",
				"summon", "spawn"),
			List.of("minecraft.command.summon_safe")));

	private ToolRelevance() {
	}

	/**
	 * 挑出这句话用得上的工具。
	 *
	 * @param message 完整的请求文本（含感知块；关键词只在其中出现一次也算命中）
	 * @param all     全量的模型可见目录
	 * @param full    true 表示调用方明确要求不裁剪
	 */
	public static List<ToolDescriptor> select(String message, List<ToolDescriptor> all,
			boolean full) {
		return select(message, all, full, DEFAULT_CANDIDATE_LIMIT);
	}

	/**
	 * Registry-driven candidate retrieval. Built-in topic aliases provide strong
	 * domain hints, while every contributed tool is also scored from its own name,
	 * description, tags, aliases and argument descriptions. This is what makes an
	 * MCP tool discoverable without editing this class for every server.
	 */
	public static List<ToolDescriptor> select(String message, List<ToolDescriptor> all,
			boolean full, int candidateLimit) {
		if (all == null || all.isEmpty()) {
			return List.of();
		}
		int limit = Math.max(ALWAYS.size(), candidateLimit);
		if (full || all.size() <= limit || all.size() < MIN_CATALOG_TO_TRIM) {
			return all;
		}
		String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
		Set<String> prefixes = new LinkedHashSet<>();
		for (Topic topic : TOPICS) {
			for (String keyword : topic.keywords()) {
				if (text.contains(keyword)) {
					prefixes.addAll(topic.prefixes());
					break;
				}
			}
		}
		List<Scored> scored = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			ToolDescriptor tool = all.get(i);
			int score = score(tool, text, prefixes);
			if (score > 0 || ALWAYS.contains(tool.name())) {
				scored.add(new Scored(tool, score, i));
			}
		}
		scored.sort(Comparator.comparingInt(Scored::score).reversed()
			.thenComparingInt(Scored::ordinal));
		List<ToolDescriptor> out = new ArrayList<>();
		for (Scored candidate : scored) {
			if (out.size() >= limit) break;
			out.add(candidate.tool());
		}
		// Unknown chat gets the safe query core instead of shipping the entire catalog.
		for (String required : ALWAYS) {
			all.stream().filter(tool -> required.equals(tool.name())).findFirst()
				.ifPresent(tool -> { if (!out.contains(tool)) out.add(tool); });
		}
		return List.copyOf(out.size() <= limit ? out : out.subList(0, limit));
	}

	private record Scored(ToolDescriptor tool, int score, int ordinal) { }

	private static int score(ToolDescriptor tool, String text, Set<String> prefixes) {
		if (ALWAYS.contains(tool.name())) return 10_000;
		int score = matchesAny(tool.name(), prefixes) ? 500 : 0;
		String lowerName = tool.name().toLowerCase(Locale.ROOT);
		for (String part : lowerName.split("[.:_\\-]+")) {
			if (part.length() >= 2 && text.contains(part)) score += 80;
		}
		for (String alias : tool.aliases()) {
			String value = alias.toLowerCase(Locale.ROOT).trim();
			if (!value.isEmpty() && text.contains(value)) score += 240;
		}
		for (String tag : tool.tags()) {
			String value = tag.toLowerCase(Locale.ROOT).trim();
			if (!value.isEmpty() && text.contains(value)) score += 160;
		}
		score += lexicalOverlap(text, tool.description(), 24);
		for (ToolDescriptor.ParameterDescriptor parameter : tool.parameters()) {
			score += lexicalOverlap(text, parameter.description(), 8);
		}
		// Follow-up observations contain canonical tool names. Keep them and their
		// topic peers without reverting to an unbounded catalog.
		if (text.contains(lowerName)) score += 600;
		return score;
	}

	private static int lexicalOverlap(String text, String source, int weight) {
		if (source == null || source.isBlank()) return 0;
		int score = 0;
		for (String token : source.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
			if (token.length() >= 2 && text.contains(token)) score += weight;
		}
		return score;
	}

	private static boolean matchesAny(String name, Set<String> prefixes) {
		for (String prefix : prefixes) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** 每个工具归属的话题（诊断与测试用）；没有话题的会以空集合出现。 */
	public static Map<String, Set<String>> topicsOf(List<ToolDescriptor> all) {
		Map<String, Set<String>> out = new LinkedHashMap<>();
		for (ToolDescriptor tool : all) {
			Set<String> topics = new LinkedHashSet<>();
			if (ALWAYS.contains(tool.name())) {
				topics.add("always");
			}
			for (Topic topic : TOPICS) {
				if (matchesAny(tool.name(), Set.copyOf(topic.prefixes()))) {
					topics.add(topic.name());
				}
			}
			out.put(tool.name(), topics);
		}
		return out;
	}
}
