package dev.squire.server.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.squire.common.protocol.ToolDescriptor;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;

/**
 * 一个工具要什么职业、什么等级才<b>看得见</b>（设计文档 §23）。
 *
 * <h2>为什么是一张表而不是几十个 if</h2>
 * <p>「这一级会不会这件事」的判断如果散在工具注册、提示词拼装、网关校验三处，
 * 三处迟早说不到一块去——而对不齐的表现是「模型看得见但点了被拒」，玩家完全无法
 * 理解。所以名字 → 门槛只在这一张表里出现一次，
 * 序列化前的过滤和执行时的第二道闸<b>查的是同一张表</b>。</p>
 *
 * <h2>能力和工具不是一一对应</h2>
 * <p>守卫的威胁判断、拦截、守护协议都是<b>自动行为</b>，玩家从来不会「调用」它们，
 * 所以它们一个工具都不对应。反过来，一个结构变体能力会开出好几个工具。
 * 这张表允许 0 / 1 / 多，不强行凑对。</p>
 *
 * <h2>默认放行，但有一圈围栏</h2>
 * <p>表里没有的一律 {@link #COMMON}——所有随从都能用，包括 Lv.0。这条默认值是
 * 刻意的：职业系统上线<b>不该</b>让一只昨天还会送东西、会护卫、会找村庄的随从
 * 今天忽然少会一半。只有真正属于某条成长线的新能力才写进表里。</p>
 *
 * <p>问题在于「默认放行」对<b>以后新加的</b>工具是错的方向：谁写了一个
 * {@code blueprint.set_pitch} 又忘了加一行，它会安安静静地变成人人可用，
 * 没有任何报错。所以 {@link #OWNED_PREFIXES} 里的命名空间归职业系统管——
 * 那几个前缀下的工具<b>必须</b>在表里有一条，哪怕结论是 COMMON 也要写出来
 * （{@code guard.stop}、{@code combat.set_style} 就是这样的显式 COMMON）。
 * 漏了会被 {@link #unclassified} 抓住，M23 里有一条测试盯着整个注册表。</p>
 */
public record ToolGate(Kind kind, SquireProfession profession,
		ProfessionAbility ability) {

	/**
	 * 一条门槛属于哪一类。
	 *
	 * <p>{@link #TRAINING} 是第三类，专为「Lv.0 也得盖一次东西才能做完新手训练」
	 * 而存在：还没转职的随从<b>用得了</b>基础蓝图，而正式转职之后这条能力归工程师。
	 * 没有它就只有两个选择——要么 Lv.0 拿到全部正式蓝图权限，要么新手训练卡死。</p>
	 */
	public enum Kind { COMMON, PROFESSION, TRAINING }

	/** 谁都能用，包括还没转职的 Lv.0。 */
	public static final ToolGate COMMON = new ToolGate(Kind.COMMON, null, null);

	static ToolGate profession(SquireProfession profession, ProfessionAbility ability) {
		return new ToolGate(Kind.PROFESSION, profession, ability);
	}

	/** 训练期能力：Lv.0 和工程师都能用，守卫不能。 */
	static ToolGate training() {
		return new ToolGate(Kind.TRAINING, SquireProfession.ENGINEER,
			ProfessionAbility.ENGINEER_BASIC_BLUEPRINT);
	}

	/**
	 * 这只随从现在看得见这个工具吗。
	 *
	 * <h2>{@code data == null} 不是「新手」</h2>
	 * <p>这两件事以前被混为一谈，是个真的洞。真正的新手<b>有</b>档案，只是里面写着
	 * 「没有职业、Lv.0」——{@link ProfessionData} 是
	 * {@link dev.squire.server.profile.SquireProfile} 的 {@code final} 字段，
	 * 对象一存在就非 null，早于 NBT 读取。所以 {@code null} 只有一个含义：
	 * <b>这只随从是谁都还没查出来</b>（agentId 为空、运行时没起来、档案里根本没有
	 * 这条记录）。在这种「不知道」的状态下发出职业权限，等于把身份不明的调用
	 * 当成新手招待。所以 §23.2 起两类都 fail-closed：<b>不知道就是不给</b>。</p>
	 */
	public boolean allows(ProfessionData data) {
		return switch (kind) {
			case COMMON -> true;
			// 职业工具的默认答案必须是「不会」，否则一次读档抖动就等于把整条
			// 成长线送出去。
			case PROFESSION -> data != null && data.profession() == profession
				// 职业对了还要看等级。<b>两者都要</b>——只看等级的话，一个 Lv.10 的
				// 工程师会因为「等级 >= 8」而拿到守卫的战斗姿态。
				&& (ability == null || data.can(ability));
			// 只有<b>明确写着</b>「还没转职」的档案才算训练期。查不到档案的一律拒绝：
			// 允许「查不到」降级换到训练期权限，就等于给了一条不用有档案的旁路。
			case TRAINING -> data != null
				&& (!data.hasProfession()
					|| data.profession() == profession && data.can(ability));
		};
	}

	public boolean isCommon() {
		return kind == Kind.COMMON;
	}

	/** 这一条门槛在几级开。展示与测试用；COMMON 返回 0。 */
	public int unlockLevel() {
		return ability == null ? 0 : ability.unlockLevel();
	}

	// ------------------------------------------------------------------ 表

	private static final Map<String, ToolGate> GATES = buildGates();

	private static Map<String, ToolGate> buildGates() {
		Map<String, ToolGate> gates = new LinkedHashMap<>();

		// —— 守卫的<b>主动</b>职业工具。
		//
		// 「让模型主动发起一场战斗」是守卫这条线的本事，不是每只随从的通用能力——
		// 职业系统的意义正是把原来那只万能随从拆开。所以主动接战和长期护卫收进来。
		//
		// 但要分清两件事：这里锁的是<b>模型能不能调这个工具</b>。
		// 实体自己的被动自卫（挨打了还手、吃东西、喝药、穿装备、跟随）在
		// AutonomyController / GuardRuntime 里，和工具权限无关，任何职业都保留。
		// 玩家自己说「保护我」走 FastPath，按面板按钮走动作表，两条路都不经过这道闸。
		gates.put("guard.start",
			profession(SquireProfession.GUARD, ProfessionAbility.GUARD_BASIC_MELEE));
		gates.put("combat.attack_target",
			profession(SquireProfession.GUARD, ProfessionAbility.GUARD_BASIC_MELEE));
		// —— 下面两条是<b>写出来的</b> COMMON，不是漏写。
		//
		// guard.stop：任何时候都必须叫得停一条已经生效的护卫策略，哪怕这只随从后来
		// 改了行。Start / Modify 可以按职业收，Stop / Cancel 应当始终可用——
		// 「任何输入都要有出路」在这里就是这一条。
		gates.put("guard.stop", COMMON);
		// combat.set_style：ranged / melee / auto 是「打的时候怎么打」，不是
		// 「要不要主动打」。挨了咬的工程师照样得还手，还手时想用弓也得允许——
		// 被动自卫不归工具权限管（§23.1）。所以是通用能力，但必须显式声明。
		gates.put("combat.set_style", COMMON);
		gates.put("combat.supplies",
			profession(SquireProfession.GUARD,
				ProfessionAbility.GUARD_SUPPLY_AWARENESS));
		gates.put("combat.set_stance",
			profession(SquireProfession.GUARD,
				ProfessionAbility.GUARD_COMBAT_STANCE));

		// —— 基础施工：照现成模板盖。<b>所有随从都能用</b>，包括 Lv.0 和守卫。
		//
		// 这三条以前是 training() / ENGINEER，也就是「守卫不许盖房子」。但面板上的
		// 「基础施工」按钮只校验主人身份，守卫点一下就盖起来了——同一件事，
		// 按钮能做、说话被拒，玩家完全无法理解。
		//
		// 修法不是把整条工程线放开，而是把线画在<b>蓝图本身</b>上：这三个工具都带
		// blueprintId，服务端在 SquireBlueprintService#place 里按
		// {@link dev.squire.server.blueprint.BuildTier} 分档——固定模板放行，
		// 参数化规格要工程师且要够等级。所以这里是 COMMON 而不是漏写：工具本身
		// 人人可用，被拒的是<b>参数</b>，和「数量超上限」是同一类回执。
		gates.put("blueprint.place", COMMON);
		gates.put("blueprint.build", COMMON);
		gates.put("project.start", COMMON);

		// —— 工程师的参数化设计。这一整段才是转职买来的东西。
		gates.put("blueprint.design",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_BASIC_BLUEPRINT));
		gates.put("blueprint.set_size",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_TEMPLATE_LIBRARY_1));
		gates.put("blueprint.rotate",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_BLUEPRINT_ROTATION));
		gates.put("blueprint.set_material_region",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_MATERIAL_REGIONS));
		gates.put("blueprint.set_variant",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_STRUCTURAL_VARIANTS));
		gates.put("blueprint.set_floors",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_MULTI_FLOOR));
		gates.put("blueprint.mirror",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_BLUEPRINT_MIRROR));
		gates.put("blueprint.add_module",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_OPTIONAL_MODULES));
		gates.put("blueprint.save_preset",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_BLUEPRINT_PRESET_LIBRARY));
		gates.put("blueprint.load_preset",
			profession(SquireProfession.ENGINEER,
				ProfessionAbility.ENGINEER_BLUEPRINT_PRESET_LIBRARY));

		// —— PLANNER_INTERNAL 里唯一需要收的一条（§23.2 审计结论）。
		//
		// build.structure 让调用方直接报宽/深/高，垒一个实心 / 空心 / 只有墙 /
		// 只有地板的盒子。它<b>没有模板</b>——尺寸就是参数，所以它归参数化那一侧，
		// 而不是跟着 blueprint.place 变成 COMMON。守卫能照模板盖房，但不能改尺寸，
		// 这一条正是那句话的落点；Lv.0 仍然够得着，新手训练不受影响。
		//
		// 模型本来就调不到它（PLANNER_INTERNAL），自动化白名单里也没有它，
		// 所以这一条今天挡不下任何真实调用。写它是为了以后：哪天有人把它放开成
		// MODEL_PUBLIC，或者加进自动化白名单，边界已经在那儿了，不用再想起来补。
		gates.put("build.structure", training());

		return Map.copyOf(gates);
	}

	// -------------------------------------------------- 围栏：漏写会被抓住

	/**
	 * 归职业系统管的命名空间。
	 *
	 * <p>这几个前缀下的工具必须在 {@link #GATES} 里有一条<b>明确的</b>结论。
	 * 结论是 {@link #COMMON} 完全可以，但要写出来——因为「作者想过，认为人人可用」
	 * 和「作者忘了」在代码里长得一模一样，而后者会静悄悄地把一条职业能力发给所有人。</p>
	 *
	 * <p>不在这几个前缀里的工具（{@code query.} / {@code navigation.} /
	 * {@code inventory.} / {@code memory.} 等等）继续默认 COMMON，不用登记：
	 * 那些是所有随从的通用本事，硬要每条都登记只会让这张表变成注册表的副本。</p>
	 */
	public static final List<String> OWNED_PREFIXES =
		List.of("guard.", "combat.", "blueprint.", "project.", "build.");

	/**
	 * 传进来的工具名里，哪些落在职业命名空间却没有条目。
	 *
	 * <p>返回非空 = 有人加了工具忘了定门槛。测试拿整个注册表调它。</p>
	 */
	public static List<String> unclassified(java.util.Collection<String> toolNames) {
		if (toolNames == null) {
			return List.of();
		}
		List<String> missing = new java.util.ArrayList<>();
		for (String name : toolNames) {
			if (name == null || GATES.containsKey(name)) {
				continue;
			}
			for (String prefix : OWNED_PREFIXES) {
				if (name.startsWith(prefix)) {
					missing.add(name);
					break;
				}
			}
		}
		return List.copyOf(missing);
	}

	/** 这个工具的门槛。表里没有的一律 {@link #COMMON}。 */
	public static ToolGate of(String toolName) {
		return toolName == null ? COMMON : GATES.getOrDefault(toolName, COMMON);
	}

	/** 有门槛的工具名（诊断与测试用）。 */
	public static Map<String, ToolGate> all() {
		return GATES;
	}

	/**
	 * 给模型看的能力边界摘要——<b>几行字，不是又一份 schema</b>。
	 *
	 * <p>玩家对一个 Lv.2 工程师说「把房子转 90 度」时，模型手上没有
	 * {@code blueprint.rotate}。没有这段摘要，它只能编一个理由，或者硬套一个别的
	 * 工具；有了这段，它能如实说「我要练到 Lv.3」。</p>
	 *
	 * <p>刻意<b>不</b>把锁着的工具 schema 发进去再写「不要用」：那样既更贵，
	 * 模型也更容易照着用。</p>
	 */
	public static String capabilitySummary(ProfessionData data) {
		if (data == null || !data.hasProfession()) {
			return "Squire profession: none yet (Lv.0, still in training). "
				+ "Guard and Engineer growth lines are not open; say so plainly if the "
				+ "player asks for something that needs one.";
		}
		SquireProfession profession = data.profession();
		StringBuilder text = new StringBuilder("Squire profession: ")
			.append(profession.id()).append(" Lv.").append(data.level).append('\n');
		ProfessionAbility next = ProfessionAbility.nextAfter(profession, data.level);
		if (next != null) {
			text.append("Not unlocked yet: ").append(next.displayName())
				.append(" at Lv.").append(next.unlockLevel())
				.append(". If the player asks for that, say which level it needs "
					+ "instead of improvising.");
		} else {
			text.append("Fully trained in this line.");
		}
		return text.toString();
	}

	/**
	 * 按这只随从的职业和等级裁掉看不见的工具。
	 *
	 * <p><b>必须在序列化之前调用。</b>先把四十条 schema 拼出来再告诉模型其中一半
	 * 不能用，既没省下 token 也没减少干扰——那样做等于什么都没做。</p>
	 */
	public static List<ToolDescriptor> filter(List<ToolDescriptor> all,
			ProfessionData data) {
		if (all == null || all.isEmpty()) {
			return List.of();
		}
		List<ToolDescriptor> out = new java.util.ArrayList<>(all.size());
		for (ToolDescriptor tool : all) {
			if (of(tool.name()).allows(data)) {
				out.add(tool);
			}
		}
		return List.copyOf(out);
	}
}
