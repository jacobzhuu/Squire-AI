package dev.squire.server.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.squire.server.task.executors.BaseLightsExecutor;

/**
 * 结构化 DSL → 已验证的 {@link AutomationGraph}（方案 G1）。
 *
 * <p>模型能表达的只有"模板 + 参数"，不是任意图。Trigger、Condition、节点类型和
 * 节点可以调用的 Tool 全部走白名单——没有任何入口能塞进一个 raw command 或任意
 * Java 类。</p>
 *
 * <p>编译总是先产出一份 {@link Compiled}，里面既有 Graph 也有给玩家看的 preview：
 * 触发条件、动作、位置、有效期和需要的权限。写世界的模板必须先确认。</p>
 */
public final class AutomationCompiler {

	/** 允许的触发器类型（白名单）。 */
	public static final Set<AutomationTrigger.Kind> ALLOWED_TRIGGERS = Set.of(
		AutomationTrigger.Kind.TIME, AutomationTrigger.Kind.INTERVAL,
		AutomationTrigger.Kind.OWNER_ONLINE, AutomationTrigger.Kind.MANUAL);

	/** 允许的节点类型（白名单）。CREATE_TASK 走既有的任务编译器，不接受任意类型。 */
	public static final Set<AutomationNode.Kind> ALLOWED_NODES = Set.of(
		AutomationNode.Kind.TOOL_CALL, AutomationNode.Kind.CREATE_TASK,
		AutomationNode.Kind.BRANCH, AutomationNode.Kind.WAIT,
		AutomationNode.Kind.NOTIFY);

	/**
	 * 自动化节点可以调用的 Tool 白名单。高风险的世界编辑、命令类工具和 CBP 一律
	 * 不在其中——长期自动化不是绕过确认流程的后门。
	 */
	public static final Set<String> ALLOWED_TOOLS = Set.of(
		"base.lights.set", "query.status", "query.inventory", "query.equipment",
		"container.inspect", "memory.recall_location", "inventory.pickup_nearby");

	/** 最短触发间隔：防止玩家（或模型）造出每 tick 跑一次的图。 */
	public static final long MIN_INTERVAL_TICKS = 200L;
	/** 单张图的最大节点数（与引擎的上限一致）。 */
	public static final int MAX_NODES = AutomationEngine.MAX_NODES;

	/** 内置模板。模型只能点名其中之一。 */
	public enum Template {
		/** 夜晚开灯 / 清晨关灯，两条 recurring 分支。 */
		NIGHT_LIGHTS;

		public static Template parse(String raw) {
			if (raw == null) {
				return null;
			}
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/** 编译结果：图 + 玩家可读的 preview + 是否写世界。 */
	public record Compiled(List<AutomationGraph> graphs, String preview,
			boolean writesWorld, String rejection) {

		public boolean ok() {
			return rejection == null;
		}

		static Compiled reject(String reason) {
			return new Compiled(List.of(), null, false, reason);
		}
	}

	/** 夜间开灯的默认时刻：黄昏点灯、黎明熄灯。 */
	public static final long DUSK_TICK = 13000L;
	public static final long DAWN_TICK = 23000L;
	/** 触发窗口：足够宽以容忍卡顿，又不会跨到白天。 */
	public static final long WINDOW_TICKS = 600L;

	private AutomationCompiler() {
	}

	/**
	 * 把一个模板请求编译成图。{@code base} 必须来自可信位置记忆或玩家显式坐标——
	 * 这里不做解析，只验证调用方已经给出了一个具体位置。
	 */
	public static Compiled compileNightLights(UUID ownerId, UUID agentId, String baseName,
			String dimensionId, net.minecraft.util.math.BlockPos base, int radius) {
		if (base == null) {
			return Compiled.reject("NO_BASE_MEMORY: 先站在基地说“这里是基地”，"
				+ "或者给出明确坐标。");
		}
		int bounded = Math.min(BaseLightsExecutor.MAX_RADIUS, Math.max(1, radius));

		AutomationGraph on = lightsGraph(ownerId, agentId,
			"night-lights-on:" + baseName, DUSK_TICK, true, dimensionId, base, bounded);
		AutomationGraph off = lightsGraph(ownerId, agentId,
			"night-lights-off:" + baseName, DAWN_TICK, false, dimensionId, base, bounded);

		String preview = "自动化模板：夜间基地灯光\n"
			+ "触发 1：每天 " + DUSK_TICK + " tick（黄昏）→ 开灯\n"
			+ "触发 2：每天 " + DAWN_TICK + " tick（黎明）→ 关灯\n"
			+ "位置：" + dimensionId + " (" + base.getX() + ", " + base.getY() + ", "
			+ base.getZ() + ")，扫描半径 " + bounded + " 格\n"
			+ "动作：翻动该范围内的真实拉杆（世界里不会新增任何命令方块）\n"
			+ "有效期：无限期，直到你 pause 或 remove\n"
			+ "需要权限：squire.automation；每次写入仍过 Protection 检查";
		return new Compiled(List.of(on, off), preview, true, null);
	}

	private static AutomationGraph lightsGraph(UUID ownerId, UUID agentId, String name,
			long atTick, boolean on, String dimensionId,
			net.minecraft.util.math.BlockPos base, int radius) {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put(BaseLightsExecutor.PARAM_ON, on);
		args.put(BaseLightsExecutor.PARAM_X, base.getX());
		args.put(BaseLightsExecutor.PARAM_Y, base.getY());
		args.put(BaseLightsExecutor.PARAM_Z, base.getZ());
		args.put(BaseLightsExecutor.PARAM_RADIUS, radius);

		AutomationGraph.Builder builder = AutomationGraph.builder(ownerId, agentId, name)
			.trigger(AutomationTrigger.atTimeOfDay(atTick, WINDOW_TICKS))
			.ttlTicks(AutomationGraph.NO_EXPIRY); // 方案 G3：recurring 默认无限期
		builder.node(AutomationNode.toolCall("base.lights.set", args));
		return builder.build();
	}

	/**
	 * 通用校验，任何来源（模板、命令、模型）的图在注册前都要过一遍。
	 *
	 * @param toolExists 判定一个 Tool 名是否真的注册过
	 * @return null 表示通过，否则是拒绝原因
	 */
	public static String validate(AutomationGraph graph,
			java.util.function.Predicate<String> toolExists) {
		if (graph == null) {
			return "INVALID_ARGUMENT: no graph";
		}
		if (!ALLOWED_TRIGGERS.contains(graph.trigger().kind())) {
			return "POLICY_DENIED: trigger " + graph.trigger().kind()
				+ " is not allowed for player-created automations";
		}
		if (graph.trigger().kind() == AutomationTrigger.Kind.INTERVAL
				&& graph.trigger().intervalTicks() < MIN_INTERVAL_TICKS) {
			return "POLICY_DENIED: interval below the " + MIN_INTERVAL_TICKS
				+ "-tick minimum";
		}
		List<AutomationNode> nodes = graph.nodeList();
		if (nodes.isEmpty()) {
			return "INVALID_ARGUMENT: automation has no nodes";
		}
		if (nodes.size() > MAX_NODES) {
			return "POLICY_DENIED: " + nodes.size() + " nodes exceeds the "
				+ MAX_NODES + " cap";
		}
		for (AutomationNode node : nodes) {
			if (!ALLOWED_NODES.contains(node.kind())) {
				return "POLICY_DENIED: node kind " + node.kind() + " is not allowed";
			}
			if (node.kind() == AutomationNode.Kind.TOOL_CALL) {
				if (!ALLOWED_TOOLS.contains(node.text())) {
					return "POLICY_DENIED: tool '" + node.text()
						+ "' may not be driven by an automation";
				}
				if (toolExists != null && !toolExists.test(node.text())) {
					return "TOOL_NOT_FOUND: '" + node.text() + "' is not registered";
				}
			}
		}
		String cycle = findUncontrolledCycle(graph);
		if (cycle != null) {
			return cycle;
		}
		return null;
	}

	/**
	 * 无环检查。允许的"受控循环"只有一种：环上至少有一个 WAIT 节点，否则一次触发
	 * 就能把节点预算烧光。
	 */
	private static String findUncontrolledCycle(AutomationGraph graph) {
		Map<UUID, List<UUID>> outgoing = new LinkedHashMap<>();
		for (AutomationGraph.Edge edge : graph.edgeList()) {
			outgoing.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge.to);
		}
		Set<UUID> visiting = new java.util.LinkedHashSet<>();
		Set<UUID> done = new java.util.HashSet<>();
		for (AutomationNode node : graph.nodeList()) {
			String problem = walk(graph, node.id(), outgoing, visiting, done);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	private static String walk(AutomationGraph graph, UUID nodeId,
			Map<UUID, List<UUID>> outgoing, Set<UUID> visiting, Set<UUID> done) {
		if (done.contains(nodeId)) {
			return null;
		}
		if (!visiting.add(nodeId)) {
			boolean hasWait = visiting.stream()
				.map(graph::node)
				.anyMatch(n -> n != null && n.kind() == AutomationNode.Kind.WAIT);
			return hasWait ? null
				: "POLICY_DENIED: automation contains a cycle with no WAIT node";
		}
		for (UUID next : outgoing.getOrDefault(nodeId, List.of())) {
			String problem = walk(graph, next, outgoing, visiting, done);
			if (problem != null) {
				return problem;
			}
		}
		visiting.remove(nodeId);
		done.add(nodeId);
		return null;
	}
}
