package dev.squire.server.help;

import java.util.List;
import java.util.Locale;

/**
 * 「他到底能干什么」的<b>唯一事实源</b>。
 *
 * <p>这是一个产品问题而不是技术问题：伙伴认得的句式是有限的，但玩家没有任何途径
 * 知道边界在哪，只能一句句试。试错失败又常常是静默的，于是玩家的结论是"这模组是坏的"。
 * 实测里连续问出「给自己装备下界合金套装」「帮我盖一个房子」「满配附魔的下界合金剑」
 * 三句都落空，正是这个原因。</p>
 *
 * <p>帮助命令、首次召唤引导、听不懂时的兜底、右键面板的「能做什么」按钮全部从这里
 * 生成——只有一份清单，就不会出现"文档说能、实际不能"。新增能力时<b>必须</b>同步
 * 往这里加一条，{@code CapabilityGuideTest} 会盯着例句和 FastPath 的实现对齐。</p>
 */
public final class CapabilityGuide {

	/**
	 * 一条能力。
	 *
	 * @param title    玩家看得懂的名字
	 * @param examples 可以照着说的原话，第一条会被当成代表例句
	 * @param note     限制或前提，没有就留 null
	 */
	public record Capability(String title, List<String> examples, String note) { }

	private static final List<Capability> CAPABILITIES = List.of(
		new Capability("跟着我 / 原地待命",
			List.of("跟着我", "待在这里", "停下", "到我身边来"),
			"跟随时落后 12 格（或被墙挡住三秒）就传送到你身边；你换维度他也会跟过来；"
				+ "打架时不会被拽回来。面板右侧常驻跟随/待命/巡逻/回家四个按钮"),
		new Capability("传送你自己",
			List.of("把我和他都传送回主世界", "帮我传送到基地", "把我传送到下界"),
			"只说维度不给坐标时落到你的重生点；说「我和他」就一起过去。"
				+ "地点名用的是你自己标记过的那些"),
		new Capability("要东西（他会走过来交给你）",
			List.of("给我32个火把", "给我一把铁镐", "帮我砍20个橡木"), null),
		new Capability("要带附魔的装备",
			List.of("给我一把满配附魔的下界合金剑", "给我一把附魔的铁镐"),
			"「满配 / 满级 / 附魔」都认；会自动附上所有不冲突的最高级附魔"),
		new Capability("要一整套盔甲",
			List.of("给我一套满配的下界合金套装", "给我一套钻石全套", "下界合金全套 满附魔"),
			"「套装 / 全套 / 一身 / 防具」都认，四件一次给齐"),
		new Capability("改动你已经有的装备",
			List.of("取消我套装的荆棘附魔", "把我身上套装的附魔全部取消",
				"给我手上的剑加上锋利", "修一下我身上的装备"),
			"他会走过来动手；改完说「撤销」可以还原（仅本次游戏运行期间有效）"),
		new Capability("让他自己武装起来",
			List.of("给自己装备下界合金套装", "给自己拿一把下界合金剑"),
			"东西留在他身上，不会给你"),
		new Capability("战斗与救援",
			List.of("保护我", "停止保护", "救我", "治疗自己"),
			"掉到八成血他会自己吃背包里的东西，不用你开口；「治疗自己」是催他现在就吃，"
				+ "「救我」才是他来治你（要治疗药水或金苹果，普通食物只能他自己吃）"),
		new Capability("按蓝图盖房子",
			List.of("帮我盖一个房子", "盖一间石头房子"),
			"先出粒子轮廓和材料清单；材料齐了才开工，他会真的走过去逐块盖"),
		new Capability("交给他一个大目标",
			List.of("帮我准备一个矿井前哨站", "准备一个哨塔"),
			"他会拆成备料 → 场地准备 → 掘进 → 施工 → 点灯 → 验收六步；模板、材料和进度都在面板「工程」页"),
		new Capability("找结构和生物群系",
			List.of("最近的远古城市在哪", "帮我找一下要塞", "最近的樱花林在哪",
				"最近的蘑菇岛在哪"),
			"坐标来自世界生成器本身；这附近真的没有时他会说没有，不会编一个给你"),
		new Capability("状态效果、时间与天气",
			List.of("给我加上夜视效果", "来个抗火", "把时间调成白天", "把雨停了"),
			"效果只有一份白名单（夜视/速度/急迫/力量/跳跃/再生/抗性/抗火/水下呼吸）"),
		new Capability("问他情况（不花钱、不等待）",
			List.of("你背包里有什么", "你穿的什么", "我血量多少", "附近有怪吗"),
			"这几个答案服务端本来就知道，不走大模型，问完立刻回"),
		new Capability("指哪打哪",
			List.of("打那只苦力怕", "帮我清掉附近的怪", "杀掉那只僵尸"),
			"他永远不会攻击玩家；牛羊这类和平生物只有你点名了种类才会动手"),
		new Capability("用弓还是用剑",
			List.of("自动选武器", "用弓打", "近战就行"),
			"默认按怪的种类挑：骷髅/苦力怕/恶魂用弓，僵尸/蜘蛛/末影人用剑。"
				+ "自动档下你亲手拖进主手的武器谁都不许换（面板会显示「打法：手动」），"
				+ "点一下那个按钮就交回自动；选了「只用弓」「只近战」则以那一档为准，"
				+ "手上是什么都按它打。守卫职业到 Lv.4 才学会用弓，在那之前面板写"
				+ "「只用弓（未解锁）」，他老实用近战"),
		new Capability("给他起名字",
			List.of("你叫什么名字"),
			"两条路：拿改过名的命名牌右键他，或者在面板「随从」页最下面那一行填"
				+ "（同样花一个命名牌）。改完他自己记得住，"
				+ "而且聊天里必须叫这个名字他才理你"),
		new Capability("给他背一个背包（装了背包模组才有）",
			List.of("你背包里有什么"),
			"面板「物品」页装备列最下面那一格就是背包位（不占胸甲）。背上之后主背包的"
				+ "36 格装满会自动往背包里溢出，清点和取料也都算上背包里的东西"),
		new Capability("记住地点",
			List.of("这里是基地", "基地在哪"), null),
		new Capability("小动作",
			List.of("挥手", "跳一下", "看着我"), null));

	private CapabilityGuide() {
	}

	public static List<Capability> all() {
		return CAPABILITIES;
	}

	/**
	 * 完整清单，回应「你能做什么」。
	 *
	 * <p>先讲面板再讲说话：绝大多数操作现在点按钮就能完成，打字只是兜底。
	 * 把说法清单摆在最前面等于把玩家又推回聊天栏。</p>
	 */
	public static String helpText() {
		return helpText(null);
	}

	/**
	 * @param name 他当前的名字；有名字时例句里直接写出来，玩家照抄就能用
	 */
	public static String helpText(String name) {
		String called = name == null || name.isBlank() ? "侍从" : name;
		StringBuilder text = new StringBuilder(
			"[Squire] §a按 K（或右键我）打开面板§r，日常操作都在里面：");
		text.append("\n   §7物品页§r 背包与装备，直接拖拽")
			.append("\n   §7指令页§r 我现在真的会做的那几件事，下面是你自己攒的快捷")
			.append("\n   §7权限页§r 逐项开关")
			.append("\n   §7行为页§r 自主档位/跟随距离/打法，最下面一行还能给我改名")
			.append("\n   §7职业页§r 训练、转职、晋升，以及这条线通向哪儿")
			.append("\n§7指令页的「自动装备最佳护甲」只会整理侍从已经穿着或自己背包里的装备。§r")
			// 点名规则必须写在说法清单<b>前面</b>：清单里每一句都得带名字才生效，
			// 玩家照着说却没反应是最伤人的一种失败。
			.append("\n\n也可以直接对我说话——聊天框里请把我的名字带上，")
			.append("比如「§e").append(called).append(" 跟着我§r」；")
			.append("面板底下那个输入框不用带：");
		for (Capability capability : CAPABILITIES) {
			text.append("\n§e").append(capability.title()).append("§r");
			text.append("\n   ").append(String.join(" / ", capability.examples()));
			if (capability.note() != null) {
				text.append("\n   §7").append(capability.note()).append("§r");
			}
		}
		// 职业是长期培养，不是一句话能下达的指令，所以单列在说法清单之后：
		// 玩家先要知道「有这么一条成长线」，再去看它具体怎么走。
		text.append("\n\n§e长期培养（职业）§r")
			.append("\n   §7打开面板「职业」页查看训练、选择成长线、交材料晋升和调整姿态。§r")
			.append("\n   §7选定之后，做这一行的事会攒经验；经验满了再给我晋升材料，")
			.append("我就能学会新的做法（等级只解锁行为，不加战力数值）。§r")
			.append("\n   §7守卫的姿态与补给、工程师的蓝图参数都能直接在对应页查看和调整。§r");
		return text.toString();
	}

	/** 首次召唤时的简短引导——先把面板指出来，别一上来糊玩家一屏说法。 */
	public static String onboardingText() {
		return onboardingText(null);
	}

	public static String onboardingText(String name) {
		String called = name == null || name.isBlank() ? "侍从" : name;
		return "[Squire] 我来了，先跟着你走。"
			+ "\n   §a按 K§r 或 §a右键我§r §7— 打开面板，背包/装备/指挥/权限都在里面§r"
			+ "\n   §e" + called + " 给我32个火把§r §7— 在聊天里说话要带上我的名字我才理§r"
			+ "\n   §7改名：拿命名牌右键我，或在面板「随从」页里填（同样花一个）§r"
			+ "\n   §e你能做什么§r   §7— 看完整清单§r";
	}

	/**
	 * 听不懂时的兜底提示：先按关键词猜一句最接近的正确说法，猜不出再give完整清单入口。
	 *
	 * <p>直接回一句"没听懂"是最差的做法——玩家得不到任何前进的方向。</p>
	 */
	public static String didYouMean(String rawText) {
		String text = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
		String suggestion = null;
		if (contains(text, "附魔", "荆棘", "取消", "去掉", "洗掉", "修")) {
			// 改动已有装备的说法和"要一件新的"很像，但结果天差地别，先猜这一类。
			suggestion = "取消我套装的荆棘附魔";
		} else if (contains(text, "装备", "穿", "戴", "武器", "拿起")) {
			suggestion = "给自己装备下界合金套装";
		} else if (contains(text, "房", "屋", "建", "盖")) {
			suggestion = "帮我盖一个房子";
		} else if (contains(text, "打", "怪", "保护", "护卫", "守")) {
			suggestion = "保护我";
		} else if (contains(text, "血", "治疗", "救", "药")) {
			suggestion = "救我";
		} else if (contains(text, "挖", "砍", "采", "收集", "要", "给")) {
			suggestion = "给我32个火把";
		} else if (contains(text, "过来", "跟", "走", "待", "停")) {
			suggestion = "跟着我";
		}
		if (suggestion == null) {
			return "[Squire] 这句我没听懂。说「你能做什么」可以看我会的所有事。";
		}
		return "[Squire] 这句我没听懂。你是想说「§e" + suggestion
			+ "§r」吗？说「你能做什么」可以看完整清单。";
	}

	private static boolean contains(String text, String... needles) {
		for (String needle : needles) {
			if (text.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}
