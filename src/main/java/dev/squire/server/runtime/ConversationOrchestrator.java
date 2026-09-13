package dev.squire.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.squire.api.provider.LlmProvider;
import dev.squire.api.tool.ExternalToolResult;
import dev.squire.common.errors.ErrorCode;
import dev.squire.common.errors.ErrorPayload;
import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.cognition.FunctionCallingParser;
import dev.squire.server.perception.PerceptionService;
import dev.squire.server.provider.ProviderRegistry;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskState;
import dev.squire.server.threading.AsyncBridge;
import dev.squire.server.tool.CallerIdentity;
import dev.squire.server.tool.ToolExecutionContext;
import dev.squire.server.tool.ToolGateway;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Durable, bounded conversational state machine:
 * UNDERSTAND -> DISPATCH -> WAIT_TOOL -> OBSERVE ->
 * COMPLETE/REPLAN/CLARIFYING/ASK_USER/FAILED.
 * LLM calls are asynchronous and high-level only; movement, combat and all per-tick
 * execution remain in Java runtimes.
 */
public final class ConversationOrchestrator {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ConversationOrchestrator.class);
	private static final Gson GSON = new Gson();
	private static final int MAX_REPLANS = 2;
	private static final int MAX_TOOL_CALLS = 32;
	private static final int MAX_ESTIMATED_TOKENS = 16_384;
	private static final int MAX_ACTIVE_TURNS_PER_PLAYER = 3;
	private static final int MAX_ACTIVE_TURNS_SERVER = 64;
	private static final int MAX_TERMINAL_HISTORY = 512;
	private static final int MAX_PROVIDER_CONCURRENCY = 5;
	private static final int MAX_QUEUED_PROVIDER_REQUESTS = 16;
	private static final int MAX_HIGH_RISK_CALLS = 1;
	private static final long TURN_TIMEOUT_TICKS = 1200L;
	private static final long CLARIFICATION_TIMEOUT_TICKS = 12000L;
	private static final long TERMINAL_RETENTION_TICKS = 1_728_000L;
	private static final int MAX_OBSERVATION_PROMPT_CHARS = 16_384;

	private final MinecraftServer server;
	private final SquireRuntime runtime;
	private final ToolGateway gateway;
	private final TurnStateStore store;
	private final ShortTermConversationStateStore shortTerm =
		new ShortTermConversationStateStore();
	private final Map<UUID, TurnRecord> turns = new LinkedHashMap<>();
	/**
	 * Provider identity is captured once per turn. A config reload may replace the
	 * process-wide default for new turns, but an in-flight turn must never jump between
	 * endpoints, credentials or model implementations halfway through a replan.
	 */
	private final Map<UUID, LlmProvider> providersByTurn = new LinkedHashMap<>();
	/** Accessed by provider completion threads and the server thread. */
	private final Set<UUID> providerInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.concurrent.ConcurrentMap<UUID, java.util.concurrent.CompletableFuture<AgentResponse>> providerFutures =
		new java.util.concurrent.ConcurrentHashMap<>();
	/** Server-thread FIFO; bounded independently of the persisted turn history. */
	private final Map<UUID, LlmProvider> queuedProviders = new LinkedHashMap<>();
	private final ProviderRequestLimiter requestLimiter = new ProviderRequestLimiter();
	private final dev.squire.server.metrics.SquireMetrics metrics =
		new dev.squire.server.metrics.SquireMetrics();

	public ConversationOrchestrator(MinecraftServer server, SquireRuntime runtime,
			ToolGateway gateway, TurnStateStore store) {
		this.server = server;
		this.runtime = runtime;
		this.gateway = gateway;
		this.store = store;
	}

	public void beginTurn(ServerPlayerEntity sender, String rawText) {
		UUID agentId = runtime.agents().resolveForOwner(sender.getUuid())
			.map(AvatarEntity::agentId).orElse(null);
		TurnRecord clarifying = latestClarifying(sender.getUuid(), agentId);
		if (clarifying != null) {
			if (isCancelUtterance(rawText)) {
				cancelTurn(clarifying, true);
				notify(clarifying, "已取消刚才的任务。");
				return;
			}
			long tick = now();
			clarifying.addObservation(GSON.toJson(Map.of(
				"kind", "clarification_answer",
				"answer", TurnStateStore.structuredSummary(rawText))), tick);
			clarifying.resumeClarification(tick + TURN_TIMEOUT_TICKS, tick);
			save();
			notify(clarifying, "明白，按你的补充继续规划…");
			requestCurrentProviderOrFail(clarifying);
			return;
		}
		var providerOpt = ProviderRegistry.current();
		if (providerOpt.isEmpty()) {
			// 没有 LLM 时这句话是玩家唯一的反馈，绝不能只说"没配置"就完事——
			// 那等于告诉他"这句不行"却不告诉他什么行。直接给出可用说法。
			runtime.notifier().send(sender.getUuid(),
				dev.squire.server.help.CapabilityGuide.didYouMean(rawText));
			return;
		}
		long activeForPlayer = turns.values().stream()
			.filter(turn -> !turn.isTerminal() && turn.ownerId().equals(sender.getUuid())).count();
		if (activeForPlayer >= MAX_ACTIVE_TURNS_PER_PLAYER) {
			runtime.notifier().send(sender.getUuid(), "[Squire] 你有太多未完成的对话，请先等待或取消当前任务。");
			return;
		}
		if (activeTurnCount() >= MAX_ACTIVE_TURNS_SERVER) {
			runtime.notifier().send(sender.getUuid(), "[Squire] 当前服务器对话较多，请稍后再试。");
			return;
		}
		long tick = now();
		TurnRecord record = new TurnRecord(UUID.randomUUID(), sender.getUuid(), agentId,
			rawText, providerOpt.get().id(), tick, tick, tick + TURN_TIMEOUT_TICKS,
			TurnRecord.State.UNDERSTAND, 0, 0, 0, 0, null,
			List.of(), List.of(), List.of(), false);
		turns.put(record.turnId(), record);
		providersByTurn.put(record.turnId(), providerOpt.get());
		trimTerminalHistory();
		save();
		LOG.info("[turn] begin id={} player={} agent={} provider={}", shortId(record.turnId()),
			shortId(sender.getUuid()), agentId, providerOpt.get().id());
		// 立刻回执。规划可能要跑满 60 秒，期间玩家必须知道"他听见了、正在想"，
		// 否则和死机没有任何区别。聊天回执给出"听见了"，头顶粒子给出"还在想"。
		runtime.notifier().send(sender.getUuid(), "[Squire] 收到："
			+ cap(TurnStateStore.structuredSummary(rawText), 120) + "。正在规划…");
		runtime.setActivity(agentId,
			dev.squire.server.body.avatar.AvatarEntity.ActivityState.THINKING);
		recordReplay("turn_start", o -> {
			o.addProperty("turnId", record.turnId().toString());
			o.addProperty("player", sender.getUuid().toString());
			o.addProperty("agent", String.valueOf(agentId));
			o.addProperty("input", rawText);
		});
		requestProvider(record, providerOpt.get());
	}

	/** Advance WAIT_TOOL/call correlation and resume persisted provider phases. */
	public void tick(long tick) {
		for (TurnRecord turn : List.copyOf(turns.values())) {
			if (turn.isTerminal()) continue;
			if (tick > turn.deadlineTick()) {
				fail(turn, "TURN_TIMEOUT", "规划等待超过 60 秒，已安全停止。");
				continue;
			}
			if (turn.state() == TurnRecord.State.WAIT_TOOL) {
				observeWaiting(turn, tick);
				continue;
			}
			if ((turn.state() == TurnRecord.State.UNDERSTAND
					|| turn.state() == TurnRecord.State.OBSERVE
					|| turn.state() == TurnRecord.State.REPLAN)
					&& !providerInFlight.contains(turn.turnId())) {
				requestCurrentProviderOrFail(turn);
			}
		}
		drainProviderQueue();
		boolean removed = turns.values().removeIf(turn -> turn.isTerminal()
			&& tick - turn.updatedTick() > TERMINAL_RETENTION_TICKS);
		removed |= trimTerminalHistory();
		if (removed) save();
	}

	public int load() {
		long tick = now();
		for (var entry : providerFutures.entrySet()) {
			TurnRecord old = turns.get(entry.getKey());
			if (old != null && !old.isTerminal()) old.fail("ORCHESTRATOR_RELOADED", tick);
			entry.getValue().cancel(true);
		}
		providerFutures.clear();
		providerInFlight.clear();
		queuedProviders.clear();
		turns.clear();
		providersByTurn.clear();
		for (TurnRecord turn : store.load()) {
			// A server cannot recover an in-process provider/MCP future. Task-backed
			// waits remain correlated to restored task ids; call-only waits become a
			// bounded replan. A crash during DISPATCH is never replayed because the
			// original write may already have happened.
			if (turn.state() == TurnRecord.State.DISPATCH) {
				turn.fail("SERVER_RESTARTED_DURING_DISPATCH", tick);
			} else if (turn.state() == TurnRecord.State.WAIT_TOOL
					&& !turn.waitingCallIds().isEmpty()) {
				turn.addObservation(errorObservation(
					"SERVER_RESTARTED: in-flight call outcome unavailable"), tick);
				if (!turn.waitingTaskIds().isEmpty()) {
					turn.waitFor(turn.waitingTaskIds(), List.of(), true, tick);
				} else if (turn.replans() < MAX_REPLANS) {
					turn.clearWaiting(tick);
					turn.beginReplan("SERVER_RESTARTED", tick);
				} else {
					turn.fail("SERVER_RESTARTED", tick);
				}
			}
			if (!turn.isTerminal() && (activeTurnCount() >= MAX_ACTIVE_TURNS_SERVER
					|| activeTurnCountFor(turn.ownerId()) >= MAX_ACTIVE_TURNS_PER_PLAYER)) {
				turn.fail("SERVER_RESTARTED_LIMIT", tick);
			}
			turns.put(turn.turnId(), turn);
		}
		trimTerminalHistory();
		save();
		return turns.size();
	}

	public void save() { store.save(turns.values()); }

	public List<TurnRecord> records() { return List.copyOf(turns.values()); }

	public dev.squire.server.metrics.SquireMetrics metrics() { return metrics; }

	/** Current provider I/O slots, exposed for server metrics and GameTest assertions. */
	public int activeProviderRequestCount() { return providerInFlight.size(); }

	/** Waiting provider requests; always bounded by {@value #MAX_QUEUED_PROVIDER_REQUESTS}. */
	public int queuedProviderRequestCount() { return queuedProviders.size(); }

	private long activeTurnCount() {
		return turns.values().stream().filter(turn -> !turn.isTerminal()).count();
	}

	private long activeTurnCountFor(UUID ownerId) {
		return turns.values().stream().filter(turn -> !turn.isTerminal()
			&& turn.ownerId().equals(ownerId)).count();
	}

	/** Keep the full persisted dialogue set bounded, even on legacy saves. */
	private boolean trimTerminalHistory() {
		List<TurnRecord> terminal = turns.values().stream().filter(TurnRecord::isTerminal)
			.sorted(java.util.Comparator.comparingLong(TurnRecord::updatedTick)).toList();
		int excess = terminal.size() - MAX_TERMINAL_HISTORY;
		if (excess <= 0) return false;
		for (int i = 0; i < excess; i++) turns.remove(terminal.get(i).turnId());
		return true;
	}

	public ShortTermConversationStateStore shortTermState() { return shortTerm; }

    /** Explicit structured commands use the same validated gateway, without an LLM. */
    public SquireRuntime.ExecutionResult executeStructured(ServerPlayerEntity sender, String tool,
            Map<String,Object> arguments) {
        UUID agentId=runtime.agents().resolveForOwner(sender.getUuid()).map(AvatarEntity::agentId).orElse(null);
        if(agentId==null)return SquireRuntime.ExecutionResult.refused("The selected companion is unavailable.");
        var definition=runtime.toolRegistry().lookup(tool).orElse(null);
        if(definition==null || !definition.exposure().visibleToModel())
            return SquireRuntime.ExecutionResult.refused("This tool is not exposed to companion commands.");
        String name=runtime.agents().resolveByAgentId(agentId).map(runtime::displayNameOf).orElse("Squire");
        gateway.newTurn();
        var call=ToolCall.of(tool,arguments);
        var result=dispatch(agentId,sender.getUuid(),call,CallerIdentity.model(sender.getUuid(),agentId));
        shortTerm.observeToolResult(agentId,call,result,now());
        String message=describe(call,result);
        if(!result.data().isEmpty() && result.status()!=ToolResult.Status.BLOCKED) message+="\n"+GSON.toJson(result.data());
        boolean success=result.status()==ToolResult.Status.SUCCESS || result.status()==ToolResult.Status.RUNNING;
        return new SquireRuntime.ExecutionResult(success,"feedback.structured_command",result.errorOrNull().map(e -> e.code().wire()).orElse(null),
            "["+name+"] "+message);
    }

	private void requestProvider(TurnRecord turn, LlmProvider provider) {
		if (turn.isTerminal() || providerInFlight.contains(turn.turnId())
				|| queuedProviders.containsKey(turn.turnId())) return;
		boolean queueNeeded = providerInFlight.size() >= MAX_PROVIDER_CONCURRENCY;
		if (queueNeeded && queuedProviders.size() >= MAX_QUEUED_PROVIDER_REQUESTS) {
			fail(turn, "PROVIDER_BUSY", "当前模型请求较多，请稍后重新发送。");
			return;
		}
		if (!requestLimiter.tryAcquire(turn.ownerId(), now())) {
			fail(turn, "PROVIDER_RATE_LIMIT", "模型请求已达到每分钟限额，请稍后再试。");
			return;
		}
		if (queueNeeded) {
			queuedProviders.put(turn.turnId(), provider);
			return;
		}
		launchProvider(turn, provider);
	}

	private void drainProviderQueue() {
		while (providerInFlight.size() < MAX_PROVIDER_CONCURRENCY && !queuedProviders.isEmpty()) {
			var next = queuedProviders.entrySet().iterator().next();
			UUID turnId = next.getKey();
			LlmProvider provider = next.getValue();
			queuedProviders.remove(turnId);
			TurnRecord turn = turns.get(turnId);
			if (turn == null || turn.isTerminal()) continue;
			if (now() > turn.deadlineTick()) {
				fail(turn, "TURN_TIMEOUT", "规划等待超过 60 秒，已安全停止。");
				continue;
			}
			launchProvider(turn, provider);
		}
	}

	private void launchProvider(TurnRecord turn, LlmProvider provider) {
		if (turn.isTerminal() || !providerInFlight.add(turn.turnId())) return;
		String message = providerMessage(turn, perception(turn),
			shortTerm.promptContext(turn.agentId(), now()));
		if (!turn.consumeEstimatedTokens(Math.max(1, message.length() / 4),
				MAX_ESTIMATED_TOKENS, now())) {
			providerInFlight.remove(turn.turnId());
			fail(turn, "TOKEN_BUDGET_EXCEEDED", "规划上下文已超过本轮 Token 预算，已安全停止。");
			drainProviderQueue();
			return;
		}
		save();
		AgentRequest request = new AgentRequest(UUID.randomUUID(), turn.ownerId(),
			turn.agentId(), message, agentName(turn));
		metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_REQUESTS);
		long startedNanos = System.nanoTime();
		java.util.concurrent.CompletableFuture<AgentResponse> future;
		try {
			future = provider.generate(request);
			if (future == null) throw new IllegalStateException("provider returned a null future");
		} catch (RuntimeException providerFailure) {
			providerInFlight.remove(turn.turnId());
			handleProviderFailure(turn, providerFailure.getMessage() == null
				? providerFailure.getClass().getSimpleName() : providerFailure.getMessage());
			drainProviderQueue();
			return;
		}
		providerFutures.put(turn.turnId(), future);
		future.whenComplete((response, error) -> {
			metrics.record(dev.squire.server.metrics.SquireMetrics.Timer.LLM_LATENCY,
				System.nanoTime() - startedNanos);
			if (error != null) {
				metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_FAILURES);
			} else if (response != null) {
				long tokens = response.usage().totalTokens() > 0
					? response.usage().totalTokens()
					: Math.max(1, response.text().length() / 4);
				metrics.add(dev.squire.server.metrics.SquireMetrics.Key.LLM_TOKENS, tokens);
			}
			AsyncBridge.runOnServer(server, () -> {
				// Keep the turn marked in-flight until its response is actually applied on
				// the server thread. Otherwise a tick between completion and application
				// can submit a duplicate provider request.
				providerInFlight.remove(turn.turnId());
				providerFutures.remove(turn.turnId(), future);
				try {
					if (!turn.isTerminal() && response != null && !turn.consumeEstimatedTokens(
							Math.max(1, response.text().length() / 4),
							MAX_ESTIMATED_TOKENS, now())) {
						fail(turn, "TOKEN_BUDGET_EXCEEDED",
							"模型输出超过本轮 Token 预算，已安全停止。");
					} else if (!turn.isTerminal()) {
						processResponse(turn.turnId(), response, error);
					}
				} finally {
					drainProviderQueue();
				}
			});
		});
	}

	/**
	 * 玩家给他起的名字，交给提示词当身份用。
	 *
	 * <p>还没起名时返回空串而不是默认的 "Squire"——那样提示词会写出一句
	 * 「玩家给你起名叫 Squire」的假话。</p>
	 */
	private String agentName(TurnRecord turn) {
		if (turn.agentId() == null) {
			return "";
		}
		String name = runtime.agents().resolveByAgentId(turn.agentId())
			.map(runtime::displayNameOf).orElse("");
		return "Squire".equals(name) ? "" : name;
	}

	private String perception(TurnRecord turn) {
		AvatarEntity avatar = turn.agentId() == null ? null
			: runtime.agents().resolveByAgentId(turn.agentId()).orElse(null);
		String context = avatar == null ? "[context] no avatar summoned"
			: PerceptionService.render(avatar, PerceptionService.Level.NORMAL);
		// 玩家自己的状态也要进上下文：不给他，他就只能凭空编一个血量出来。
		context += "\n" + PerceptionService.renderOwner(
			server.getPlayerManager().getPlayer(turn.ownerId()));
		if (turn.agentId() != null) {
			for (String line : runtime.extensions().sensors().observe(turn.agentId(),
					turn.ownerId(), now())) context += "\n" + line;
		}
		return context;
	}

	private static String providerMessage(TurnRecord turn, String perception,
			String shortTermContext) {
		// The user's words and recent task referents come before volatile positions.
		// Otherwise "there" is easily rebound to the current owner/Squire coordinates.
		StringBuilder out = new StringBuilder("[player] ").append(turn.originalInput());
		if (shortTermContext != null && !shortTermContext.isBlank()) {
			out.append("\n[short_term_task_state] ").append(shortTermContext)
				.append("\nResolve short references such as there/过去/刚才那个 from this ")
				.append("state before considering current positions. Current positions say ")
				.append("where entities are now; they are not the conversational destination.");
		}
		out.append("\n[current_world_observations]\n").append(perception);
		if (!turn.planSteps().isEmpty()) {
			out.append("\n[active_plan] ").append(GSON.toJson(turn.planSteps()))
				.append("\nContinue the remaining goal; do not repeat completed steps.");
		}
		if (!turn.observations().isEmpty()) {
			out.append("\n[turn_state] ").append(turn.state())
				.append("; replan ").append(turn.replans()).append('/').append(MAX_REPLANS)
				.append("\n[structured_observations]\n");
			int used = 0;
			for (int i = turn.observations().size() - 1; i >= 0; i--) {
				String observation = turn.observations().get(i);
				if (used + observation.length() > MAX_OBSERVATION_PROMPT_CHARS) break;
				out.append(observation).append('\n');
				used += observation.length();
			}
			out.append("Use these results as data. Answer the player if the goal is done; ")
				.append("otherwise choose only a high-level tool plan. Do not perform tick-level ")
				.append("movement or combat. Never repeat an equivalent failed call.");
		}
		return out.toString();
	}

	private void processResponse(UUID turnId, AgentResponse response, Throwable error) {
		TurnRecord turn = turns.get(turnId);
		if (turn == null || turn.isTerminal()) return;
		if (error != null || response == null) {
			handleProviderFailure(turn, "PROVIDER_ERROR: " + rootMessage(error));
			return;
		}
		if (response.outcome() == AgentResponse.Outcome.EMPTY_OUTPUT) {
			metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_EMPTY_OUTPUTS);
			handleProviderFailure(turn, "EMPTY_OUTPUT");
			return;
		}
		if (response.outcome() == AgentResponse.Outcome.TRUNCATED) {
			metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_TRUNCATED);
			handleProviderFailure(turn, "TRUNCATED_OUTPUT: " + response.finishReason());
			return;
		}
		if (response.outcome() == AgentResponse.Outcome.CONTENT_FILTERED) {
			fail(turn, "CONTENT_FILTERED", "模型拒绝处理这项请求；请换一种安全、明确的说法。");
			return;
		}
		turn.clearProviderRetries(now());
		recordReplay("llm_response", o -> {
			o.addProperty("turnId", turnId.toString());
			o.addProperty("text", response.text());
		});
		FunctionCallingParser.ModelTurn modelTurn = FunctionCallingParser.parse(response.text());
		if (modelTurn.malformed()) {
			gateway.audit().record(-1L, "MODEL", "?", false, "malformed_model_output");
			attemptReplan(turn, "MALFORMED_MODEL_OUTPUT: " + modelTurn.parseError());
			return;
		}
		if (!modelTurn.plan().isEmpty()) turn.setPlan(modelTurn.plan(), now());
		if (!modelTurn.ask().isBlank()) {
			metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_CLARIFICATIONS);
			shortTerm.observeAssistantMessage(turn.agentId(), modelTurn.ask(), now());
			turn.awaitClarification(modelTurn.ask(), now() + CLARIFICATION_TIMEOUT_TICKS,
				now());
			save();
			setActivity(turn, AvatarEntity.ActivityState.IDLE);
			notify(turn, planPrefix(turn) + modelTurn.ask());
			return;
		}
		if (!modelTurn.hasWork()) {
			shortTerm.observeAssistantMessage(turn.agentId(), modelTurn.say(), now());
			turn.state(TurnRecord.State.COMPLETE, now());
			providersByTurn.remove(turn.turnId());
			save();
			setActivity(turn, AvatarEntity.ActivityState.IDLE);
			// 模型什么都没说、也没要调用工具时，以前这里直接 return —— 回合"成功"结束，
			// 玩家一个字都收不到。空回复本身就是需要如实报告的结果（多半是被
			// maxTokens 截断了），不能装作无事发生。
			notify(turn, modelTurn.say());
			return;
		}
		if (turn.agentId() == null) {
			fail(turn, "ENTITY_NOT_FOUND", "尚未召唤 Squire，无法执行该计划。");
			return;
		}
		for (ToolCall call : modelTurn.calls()) {
			List<String> missing = missingRequiredArguments(call);
			if (!missing.isEmpty()) {
				metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_CLARIFICATIONS);
				long tick = now();
				turn.addObservation(GSON.toJson(Map.of(
					"kind", "proposed_tool_call",
					"tool", call.toolName(),
					"arguments", call.arguments(),
					"missing", missing)), tick);
				String question = "执行“" + friendlyName(call.toolName()) + "”还需要："
					+ String.join("、", missing) + "。请补充一下。";
				turn.awaitClarification(question, tick + CLARIFICATION_TIMEOUT_TICKS, tick);
				save();
				setActivity(turn, AvatarEntity.ActivityState.IDLE);
				notify(turn, planPrefix(turn) + question);
				return;
			}
		}
		List<ToolCall> executableCalls = new ArrayList<>();
		List<String> duplicateLines = new ArrayList<>();
		for (ToolCall call : modelTurn.calls()) {
			if (alreadySucceeded(turn, call)) {
				duplicateLines.add("已完成过相同操作，本轮不再重复执行：" + call.toolName());
			} else {
				executableCalls.add(call);
			}
		}
		if (executableCalls.isEmpty()) {
			shortTerm.observeAssistantMessage(turn.agentId(), modelTurn.say(), now());
			turn.state(TurnRecord.State.COMPLETE, now());
			providersByTurn.remove(turn.turnId());
			save();
			setActivity(turn, AvatarEntity.ActivityState.IDLE);
			notify(turn, progressText(modelTurn.say(), duplicateLines));
			return;
		}
		if (turn.toolCalls() + executableCalls.size() > MAX_TOOL_CALLS) {
			fail(turn, "TOOL_BUDGET_EXCEEDED", "该规划调用工具过多，已安全停止。");
			return;
		}
		int highRisk = 0;
		for (ToolCall call : executableCalls) {
			if (runtime.toolRegistry().lookup(call.toolName())
					.map(definition -> definition.risk()
						== dev.squire.server.tool.RiskLevel.HIGH).orElse(false)) {
				highRisk++;
			}
		}
		if (!turn.consumeHighRiskCalls(highRisk, MAX_HIGH_RISK_CALLS, now())) {
			fail(turn, "RISK_BUDGET_EXCEEDED",
				"同一规划轮最多允许一个高风险操作；请拆分请求并分别确认。");
			return;
		}

		long tick = now();
		turn.state(TurnRecord.State.DISPATCH, tick);
		turn.addToolCalls(executableCalls.size(), tick);
		// Persist the unsafe edge before invoking any handler. If the server dies after
		// a write but before its observation is saved, recovery sees DISPATCH and fails
		// closed instead of replaying a possibly completed operation.
		save();
		gateway.newTurn();
		List<UUID> taskIds = new ArrayList<>();
		List<UUID> callIds = new ArrayList<>();
		List<String> playerLines = new ArrayList<>(duplicateLines);
		boolean failed = false;
		for (ToolCall call : executableCalls) {
			ToolResult result = dispatch(turn.agentId(), turn.ownerId(), call);
			shortTerm.observeToolResult(turn.agentId(), call, result, tick);
			turn.addObservation(toolObservation(call, result), tick);
			playerLines.add(describe(call, result));
			if (result.status() == ToolResult.Status.RUNNING) {
				List<UUID> extracted = taskIds(result.data());
				taskIds.addAll(extracted);
				if (extracted.isEmpty()) callIds.add(call.callId());
			} else if (result.status() == ToolResult.Status.BLOCKED
					&& result.errorOrNull().map(e -> e.code()
						== ErrorCode.CONFIRMATION_REQUIRED).orElse(false)) {
				turn.state(TurnRecord.State.ASK_USER, tick);
				save();
				// 等玩家确认不是"忙"，别让粒子一直转下去。
				setActivity(turn, AvatarEntity.ActivityState.IDLE);
				notify(turn, progressText(modelTurn.say(), playerLines));
				return;
			} else if (result.status() == ToolResult.Status.FAILED
					|| result.status() == ToolResult.Status.CANCELLED) {
				failed = true;
			}
		}
		// One embodied/world-changing action at a time. Read-only calls can be grouped;
		// later mutations remain in the structured journal for the next observation turn.
		List<ToolCall> serialised = new ArrayList<>();
		List<String> deferred = new ArrayList<>();
		boolean mutationSeen = false;
		for (ToolCall call : executableCalls) {
			boolean readOnly = runtime.toolRegistry().lookup(call.toolName())
				.map(dev.squire.server.tool.ToolDefinition::readOnly).orElse(false);
			if (!readOnly && mutationSeen) {
				deferred.add(call.toolName());
				continue;
			}
			serialised.add(call);
			if (!readOnly) mutationSeen = true;
		}
		if (!deferred.isEmpty()) {
			turn.addObservation(GSON.toJson(Map.of("kind", "deferred_plan_steps",
				"tools", deferred, "reason", "one_mutation_at_a_time")), now());
		}
		executableCalls = serialised;
		shortTerm.observeAssistantMessage(turn.agentId(), modelTurn.say(), tick);
		if (!modelTurn.say().isBlank() || !playerLines.isEmpty())
			notify(turn, planPrefix(turn) + progressText(modelTurn.say(), playerLines));
		if (!taskIds.isEmpty() || !callIds.isEmpty()) {
			turn.waitFor(List.copyOf(new LinkedHashSet<>(taskIds)),
				List.copyOf(new LinkedHashSet<>(callIds)), failed, tick);
			save();
			return;
		}
		if (failed) {
			attemptReplan(turn, "one or more tool calls failed");
			return;
		}
		turn.state(TurnRecord.State.OBSERVE, tick);
		save();
		requestCurrentProviderOrFail(turn);
	}

	private void observeWaiting(TurnRecord turn, long tick) {
		boolean failed = turn.waitingFailed();
		List<UUID> remainingTasks = new ArrayList<>();
		List<UUID> remainingCalls = new ArrayList<>();
		for (UUID taskId : turn.waitingTaskIds()) {
			Task task = runtime.scheduler().findTask(taskId).orElse(null);
			if (task == null) {
				turn.addObservation(taskObservation(taskId, null), tick);
				failed = true;
			} else if (!task.state().isTerminal()) {
				remainingTasks.add(taskId);
			} else {
				String observation = taskObservation(taskId, task);
				turn.addObservation(observation, tick);
				notify(turn, "任务结果：" + cap(observation, 2000));
				if (task.state() != TaskState.COMPLETED) failed = true;
			}
		}
		for (UUID callId : turn.waitingCallIds()) {
			ExternalToolResult outcome = runtime.mcp().takeOutcome(callId).orElse(null);
			if (outcome == null) {
				remainingCalls.add(callId);
			} else {
				String observation = externalObservation(outcome);
				turn.addObservation(observation, tick);
				// 方案 I1：玩家看到的是可读摘要，模型仍然拿到完整的结构化数据
				notify(turn, renderForPlayer(callId, outcome));
				if (!outcome.isSuccess()) failed = true;
			}
		}
		if (!remainingTasks.isEmpty() || !remainingCalls.isEmpty()) {
			if (remainingTasks.equals(turn.waitingTaskIds())
					&& remainingCalls.equals(turn.waitingCallIds())
					&& failed == turn.waitingFailed()) {
				return; // no change: avoid rewriting the entire turns file every waiting tick
			}
			turn.waitFor(remainingTasks, remainingCalls, failed, tick);
			save();
			return;
		}
		turn.clearWaiting(tick);
		if (failed) {
			attemptReplan(turn, "asynchronous tool/task failed");
			return;
		}
		turn.state(TurnRecord.State.OBSERVE, tick);
		save();
		requestCurrentProviderOrFail(turn);
	}

	private void attemptReplan(TurnRecord turn, String error) {
		if (turn.replans() >= MAX_REPLANS) {
			fail(turn, "REPLAN_LIMIT", "尝试两次替代方案后仍未成功：" + error);
			return;
		}
		long tick = now();
		turn.addObservation(errorObservation(error), tick);
		turn.beginReplan(error, tick);
		save();
		// 静默重试最多两次，加上首次规划就是三轮 LLM 往返。不吭声的话玩家会以为卡死了。
		notify(turn, "上一步没走通（" + cap(error, 120) + "），换个思路再试…");
		requestCurrentProviderOrFail(turn);
	}

	private void requestCurrentProviderOrFail(TurnRecord turn) {
		providerFor(turn).ifPresentOrElse(provider -> requestProvider(turn, provider),
			() -> fail(turn, "PROVIDER_UNAVAILABLE", "LLM 在规划期间不可用，已安全停止。"));
	}

	private void handleProviderFailure(TurnRecord turn, String reason) {
		long tick = now();
		if (turn.providerRetries() < 1) {
			turn.incrementProviderRetry(tick);
			turn.addObservation(errorObservation(reason), tick);
			save();
			notify(turn, "模型没有给出完整答复，正在重试一次…");
			requestCurrentProviderOrFail(turn);
			return;
		}
		java.util.Optional<LlmProvider> failover =
			ProviderRegistry.failoverAfter(turn.providerId());
		if (failover.isPresent()) {
			LlmProvider next = failover.get();
			turn.switchProvider(next.id(), tick);
			turn.addObservation(errorObservation("provider failover after " + reason), tick);
			providersByTurn.put(turn.turnId(), next);
			metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.LLM_FAILOVERS);
			save();
			notify(turn, "当前模型不可用，已切换到管理员允许的备用模型…");
			requestProvider(turn, next);
			return;
		}
		fail(turn, "PROVIDER_UNAVAILABLE",
			"模型重试后仍未返回有效内容。确定性快捷命令仍可使用，请稍后再试。原因："
				+ cap(reason, 100));
	}

	private java.util.Optional<LlmProvider> providerFor(TurnRecord turn) {
		LlmProvider captured = providersByTurn.get(turn.turnId());
		if (captured != null) {
			return java.util.Optional.of(captured);
		}
		// A persisted turn can only resume through a provider with the same public id.
		// New in-process turns always use the exact object captured in beginTurn above.
		return ProviderRegistry.configured().stream()
			.map(ProviderRegistry.Registered::provider)
			.filter(provider -> provider.id().equals(turn.providerId()))
			.findFirst().map(provider -> {
				providersByTurn.put(turn.turnId(), provider);
				return provider;
			});
	}

	public record ConversationView(UUID turnId, TurnRecord.State state, String goal,
			String pendingQuestion, List<String> plan, String lastError) { }

	public java.util.Optional<ConversationView> statusFor(UUID ownerId, UUID agentId) {
		return turns.values().stream()
			.filter(turn -> turn.ownerId().equals(ownerId)
				&& (agentId == null || sameAgent(turn.agentId(), agentId)))
			.max(java.util.Comparator.comparingLong(TurnRecord::updatedTick))
			.map(turn -> new ConversationView(turn.turnId(), turn.state(),
				TurnStateStore.structuredSummary(turn.originalInput()),
				turn.pendingQuestion(), turn.planSteps(), turn.lastError()));
	}

	/** Cancel active dialogue and only the tasks correlated to those dialogue turns. */
	public int cancelFor(UUID ownerId, UUID agentId) {
		int count = 0;
		for (TurnRecord turn : List.copyOf(turns.values())) {
			if (!turn.isTerminal() && turn.ownerId().equals(ownerId)
					&& (agentId == null || sameAgent(turn.agentId(), agentId))) {
				cancelTurn(turn, true);
				count++;
			}
		}
		return count;
	}

	/** Forget completed/failed dialogue summaries; active physical work is untouched. */
	public int forgetFor(UUID ownerId, UUID agentId) {
		int before = turns.size();
		turns.values().removeIf(turn -> turn.ownerId().equals(ownerId)
			&& (agentId == null || sameAgent(turn.agentId(), agentId)) && turn.isTerminal());
		int removed = before - turns.size();
		if (removed > 0) save();
		return removed;
	}

	private void cancelTurn(TurnRecord turn, boolean cancelTasks) {
		if (cancelTasks) runtime.scheduler().cancelTasks(turn.waitingTaskIds(),
			"CONVERSATION_CANCELLED");
		turn.fail("PLAYER_CANCELLED", now());
		providersByTurn.remove(turn.turnId());
		queuedProviders.remove(turn.turnId());
		providerInFlight.remove(turn.turnId());
		java.util.concurrent.CompletableFuture<AgentResponse> future = providerFutures.remove(turn.turnId());
		if (future != null) future.cancel(true);
		save();
		setActivity(turn, AvatarEntity.ActivityState.IDLE);
		drainProviderQueue();
	}

	private TurnRecord latestClarifying(UUID ownerId, UUID agentId) {
		return turns.values().stream()
			.filter(turn -> turn.state() == TurnRecord.State.CLARIFYING
				&& turn.ownerId().equals(ownerId) && sameAgent(turn.agentId(), agentId))
			.max(java.util.Comparator.comparingLong(TurnRecord::updatedTick)).orElse(null);
	}

	private static boolean sameAgent(UUID left, UUID right) {
		return java.util.Objects.equals(left, right);
	}

	private static boolean isCancelUtterance(String text) {
		if (text == null) return false;
		String value = text.trim().toLowerCase(java.util.Locale.ROOT);
		return value.equals("算了") || value.equals("取消") || value.equals("别做了")
			|| value.equals("不用了") || value.equals("cancel") || value.equals("never mind");
	}

	private void fail(TurnRecord turn, String code, String message) {
		turn.fail(code, now());
		providersByTurn.remove(turn.turnId());
		queuedProviders.remove(turn.turnId());
		providerInFlight.remove(turn.turnId());
		java.util.concurrent.CompletableFuture<AgentResponse> future = providerFutures.remove(turn.turnId());
		if (future != null) future.cancel(true);
		save();
		setActivity(turn, AvatarEntity.ActivityState.FAILED);
		notify(turn, message);
		drainProviderQueue();
	}

	/** 回合状态 → 伙伴头顶的可见状态。实体不在场时静默跳过。 */
	private void setActivity(TurnRecord turn, AvatarEntity.ActivityState state) {
		runtime.setActivity(turn.agentId(), state);
	}

	/** 这个伙伴身上是否还压着一个没结束的对话回合（用于每 tick 归位忙碌状态）。 */
	public boolean hasActiveTurn(UUID agentId) {
		if (agentId == null) {
			return false;
		}
		for (TurnRecord turn : turns.values()) {
			if (!turn.isTerminal() && agentId.equals(turn.agentId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Handle a short, unambiguous continuation without another LLM round-trip.
	 * The resolver only supplies canonical arguments; execution still crosses the
	 * ToolGateway, including schema, capability, profession, permission-node,
	 * ownership, policy, quota and loop checks.
	 */
	public java.util.Optional<SquireRuntime.ExecutionResult> tryHandleContinuation(
			ServerPlayerEntity sender, String rawText) {
		UUID agentId = runtime.agents().resolveForOwner(sender.getUuid())
			.map(AvatarEntity::agentId).orElse(null);
		ShortTermConversationStateStore.Resolution resolution =
			shortTerm.resolve(agentId, rawText, now());
		if (resolution.kind()
				== ShortTermConversationStateStore.ResolutionKind.NONE) {
			return java.util.Optional.empty();
		}
		if (resolution.kind()
				== ShortTermConversationStateStore.ResolutionKind.ASK) {
			return java.util.Optional.of(new SquireRuntime.ExecutionResult(true,
				"feedback.missing_short_term_target", null,
				"[Squire] " + resolution.prompt()));
		}

		AvatarEntity avatar = runtime.agents().resolveByAgentId(agentId).orElse(null);
		if (avatar == null) {
			return java.util.Optional.of(SquireRuntime.ExecutionResult.refused(
				"[Squire] 侍从不在场，无法继续刚才的动作。"));
		}
		if ("navigation.move_to".equals(resolution.toolName())
				&& resolution.location() != null
				&& resolution.location().dimension() != null
				&& !resolution.location().dimension().equals(
					avatar.getWorld().getRegistryKey().getValue().toString())) {
			return java.util.Optional.of(new SquireRuntime.ExecutionResult(true,
				"feedback.cross_dimension_route", null,
				"[Squire] 那个地方在另一个维度，没法直接带路。要改成传送吗？"));
		}

		ToolCall call = ToolCall.of(resolution.toolName(), resolution.arguments());
		gateway.newTurn();
		ToolResult result = dispatch(agentId, sender.getUuid(), call,
			CallerIdentity.fastPath(sender.getUuid(), agentId));
		shortTerm.observeToolResult(agentId, call, result, now());
		if (result.status() == ToolResult.Status.SUCCESS
				|| result.status() == ToolResult.Status.RUNNING) {
			shortTerm.completePending(agentId);
		}
		Object report = result.data().get("report");
		String message = report instanceof String text && !text.isBlank() ? text
			: "[Squire] " + describe(call, result);
		boolean success = result.status() == ToolResult.Status.SUCCESS
			|| result.status() == ToolResult.Status.RUNNING
			|| result.status() == ToolResult.Status.PARTIAL;
		return java.util.Optional.of(new SquireRuntime.ExecutionResult(success,
			"feedback.short_term_continuation", result.errorOrNull()
				.map(error -> error.code().wire()).orElse(null), message));
	}

	/** Apply an explicit correction to a waiting task instead of starting a new chat. */
	public java.util.Optional<SquireRuntime.ExecutionResult> tryHandleCorrection(
			ServerPlayerEntity sender, String rawText) {
		UUID agentId = runtime.agents().resolveForOwner(sender.getUuid())
			.map(AvatarEntity::agentId).orElse(null);
		TurnRecord turn = turns.values().stream()
			.filter(candidate -> candidate.ownerId().equals(sender.getUuid())
				&& sameAgent(candidate.agentId(), agentId)
				&& candidate.state() == TurnRecord.State.WAIT_TOOL)
			.max(java.util.Comparator.comparingLong(TurnRecord::updatedTick)).orElse(null);
		if (turn == null) return java.util.Optional.empty();
		int cancelled = runtime.scheduler().cancelTasks(turn.waitingTaskIds(),
			"PLAYER_CORRECTION");
		long tick = now();
		turn.clearWaiting(tick);
		turn.addObservation(GSON.toJson(Map.of("kind", "player_correction",
			"value", TurnStateStore.structuredSummary(rawText),
			"cancelledSteps", cancelled)), tick);
		turn.resumeClarification(tick + TURN_TIMEOUT_TICKS, tick);
		save();
		requestCurrentProviderOrFail(turn);
		return java.util.Optional.of(new SquireRuntime.ExecutionResult(true,
			"feedback.conversation_corrected", null,
			"[Squire] 已收到更正，停下未完成步骤并重新规划。"
				+ (cancelled > 0 ? "已发生的世界改动不会自动回滚。" : "")));
	}

	private ToolResult dispatch(UUID agentId, UUID senderId, ToolCall call) {
		return dispatch(agentId, senderId, call,
			CallerIdentity.model(senderId, agentId));
	}

	private ToolResult dispatch(UUID agentId, UUID senderId, ToolCall call,
			CallerIdentity caller) {
		AvatarEntity avatar = runtime.agents().resolveByAgentId(agentId).orElse(null);
		if (avatar == null) return ToolResult.failed(call.callId(),
			ErrorPayload.of(ErrorCode.ENTITY_NOT_FOUND, "agent body vanished"));
		ToolExecutionContext ctx = new ToolExecutionContext() {
			@Override public dev.squire.api.body.AgentBody body() { return avatar; }
			@Override public AvatarEntity avatar() { return avatar; }
			@Override public MinecraftServer server() { return server; }
			@Override public UUID requesterId() { return senderId; }
			@Override public long tick() { return now(); }
		};
		ToolResult result = runtime.agents().withTarget(senderId, agentId, () -> gateway.dispatch(call, caller,
			ctx, runtime.capabilitiesOf(agentId)));
		if (result.status() == ToolResult.Status.BLOCKED
				&& result.errorOrNull().map(e -> e.code()
					== ErrorCode.CONFIRMATION_REQUIRED).orElse(false)) {
			// 方案 F3：登记成持久 PendingOperation 并附上真实 preview。确认后由服务器
			// 重放这份 canonical 参数，玩家不必重说一遍，模型也改不动它。
			var operation = runtime.previewBlockedCall(senderId, agentId,
				call.toolName(), call.arguments());
			result = new ToolResult(result.callId(), result.status(),
				Map.of("confirmId", operation.confirmId().toString(),
					"preview", operation.preview()),
				result.error());
		}
		return result;
	}

	private static List<UUID> taskIds(Map<String, Object> data) {
		List<UUID> ids = new ArrayList<>();
		readUuid(data.get("taskId"), ids);
		Object many = data.get("taskIds");
		if (many instanceof Iterable<?> iterable)
			for (Object value : iterable) readUuid(value, ids);
		else readUuid(many, ids);
		return ids;
	}

	private static void readUuid(Object value, List<UUID> out) {
		if (value == null) return;
		try { out.add(value instanceof UUID id ? id : UUID.fromString(String.valueOf(value))); }
		catch (IllegalArgumentException ignored) { }
	}

	private String taskObservation(UUID id, Task task) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "task_result");
		value.put("taskId", id.toString());
		if (task == null) {
			value.put("status", "FAILED");
			value.put("error", "TASK_NOT_FOUND");
		} else {
			value.put("type", task.type());
			value.put("status", task.state().name());
			task.lastErrorCode().ifPresent(error -> value.put("error", error));
			runtime.extensions().outcomeOf(id).ifPresent(outcome ->
				value.put("data", outcome.data()));
		}
		return GSON.toJson(value);
	}

	private static String toolObservation(ToolCall call, ToolResult result) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "tool_result");
		value.put("callId", call.callId().toString());
		value.put("tool", call.toolName());
		value.put("argumentsFingerprint", ToolGateway.fingerprint(call.arguments()));
		value.put("status", result.status().name());
		value.put("data", result.data());
		result.errorOrNull().ifPresent(error -> value.put("error", Map.of(
			"code", error.code().wire(), "message", error.message())));
		return GSON.toJson(value);
	}

	/**
	 * Synchronous command tools are not necessarily idempotent (notably /give).
	 * A provider may accidentally emit the same call again after seeing its success
	 * observation, so successful tool+argument pairs are deduplicated for the whole
	 * durable turn. The fingerprint lives in the observation journal and therefore
	 * survives a normal save/reload as well.
	 */
	private static boolean alreadySucceeded(TurnRecord turn, ToolCall call) {
		String expectedTool = call.toolName();
		String expectedArguments = ToolGateway.fingerprint(call.arguments());
		for (String observation : turn.observations()) {
			try {
				JsonObject value = JsonParser.parseString(observation).getAsJsonObject();
				if (value.has("kind") && "tool_result".equals(value.get("kind").getAsString())
						&& value.has("tool")
						&& expectedTool.equals(value.get("tool").getAsString())
						&& value.has("argumentsFingerprint")
						&& expectedArguments.equals(value.get("argumentsFingerprint").getAsString())
						&& value.has("status")
						&& ToolResult.Status.SUCCESS.name().equals(value.get("status").getAsString())) {
					return true;
				}
			} catch (RuntimeException ignored) {
				// Observations may come from tasks, MCP, or an older schema.
			}
		}
		return false;
	}

	/**
	 * 方案 I1：玩家可见的一句话。原始结构化数据只进 observation（下一轮模型上下文）
	 * 和受限日志，不会整坨糊到聊天里。
	 */
	private String renderForPlayer(UUID callId, ExternalToolResult result) {
		String toolName = runtime.mcp().pendingCalls().get(callId)
			.map(call -> call.server() + ":" + call.toolName())
			.orElse("外部工具");
		if (result.isSuccess()) {
			return dev.squire.server.ext.ResultRenderer.renderSuccess(toolName,
				dev.squire.server.ext.ExternalResultSanitizer.sanitize(result.data()));
		}
		return dev.squire.server.ext.ResultRenderer.renderFailure(toolName,
			result.errorCode(), result.message());
	}

	private static String externalObservation(ExternalToolResult result) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("kind", "call_result");
		value.put("callId", result.callId().toString());
		value.put("status", result.status().name());
		// 消毒后才进入模型上下文：体积、深度和敏感字段都在这里被限制
		value.put("data", dev.squire.server.ext.ExternalResultSanitizer
			.sanitize(result.data()));
		if (result.errorCode() != null) value.put("error", Map.of(
			"code", result.errorCode(), "message", String.valueOf(result.message())));
		return GSON.toJson(value);
	}

	private static String errorObservation(String error) {
		return GSON.toJson(Map.of("kind", "planning_error", "status", "FAILED",
			"error", String.valueOf(error)));
	}

	/**
	 * 给玩家看的一行结果。<b>绝不要把 result.data() 的原始 JSON 拼进聊天</b>——
	 * 以前会出现 "给你一组橡木。；minecraft.command.give：{...}" 这种东西，
	 * 对玩家毫无意义。详细数据留在 observation 里给模型和审计看。
	 */
	private static String describe(ToolCall call, ToolResult result) {
		return switch (result.status()) {
			case SUCCESS -> "已完成：" + friendlyName(call.toolName());
			case RUNNING -> "正在执行：" + friendlyName(call.toolName());
			case BLOCKED -> {
				Object confirmId = result.data().get("confirmId");
				Object preview = result.data().get("preview");
				yield friendlyName(call.toolName()) + "：等待确认（世界还没有任何改变）"
					+ (preview == null ? "" : "\n" + preview)
					+ (confirmId == null ? ""
						: "\n确认执行：/squire confirm " + confirmId
							+ "\n取消：/squire deny " + confirmId);
			}
			case FAILED -> friendlyName(call.toolName()) + "：失败（"
				+ result.errorOrNull().map(e -> e.code().wire() + "，" + e.message())
					.orElse("unknown") + "）";
			case CANCELLED -> friendlyName(call.toolName()) + "：已取消";
			case PARTIAL -> friendlyName(call.toolName()) + "：部分完成";
		};
	}

	/** 工具名 → 人话。认不出的就原样返回，至少不会更糟。 */
	private static String friendlyName(String toolName) {
		return switch (toolName) {
			case "minecraft.command.give", "items.fulfill" -> "把物品交给你";
			case "items.edit" -> "改动你已有的装备";
			case "inventory.acquire_self" -> "给自己取装备";
			case "minecraft.command.fill", "minecraft.command.setblock" -> "改动方块";
			case "minecraft.command.effect" -> "给你上状态效果";
			case "minecraft.command.teleport" -> "移动位置";
			case "minecraft.command.summon_safe" -> "召唤生物";
			case "guard.start" -> "开始护卫";
			case "guard.stop" -> "解除护卫";
			case "heal.now" -> "治疗自己";
			case "aid.owner" -> "救助你";
			case "container.inspect" -> "查看容器";
			case "query.status", "query.inventory", "query.equipment" -> "查看自身状态";
			case "query.player" -> "看看你的状态";
			case "entity.scan_nearby" -> "扫一眼附近";
			case "crafting.recipe_of" -> "查配方";
			case "combat.attack_target" -> "去打目标";
			case "combat.set_style" -> "换打法";
			case "world.set_time" -> "调整时间";
			case "world.set_weather" -> "调整天气";
			case "world.locate_structure", "world.locate_biome" -> "查找位置";
			case "project.start" -> "开始一个工程";
			case "blueprint.place", "blueprint.build" -> "按蓝图施工";
			case "inventory.give" -> "把背包里的东西给你";
			case "navigation.move_to" -> "走到指定位置";
			case "navigation.come_to_owner" -> "到你身边来";
			case "player.teleport" -> "把你传送过去";
			default -> toolName;
		};
	}

	private static String progressText(String say, List<String> lines) {
		List<String> out = new ArrayList<>();
		if (say != null && !say.isBlank()) out.add(say);
		out.addAll(lines);
		return String.join("；", out);
	}

	private List<String> missingRequiredArguments(ToolCall call) {
		return runtime.toolRegistry().lookup(call.toolName()).map(definition -> {
			List<String> missing = new ArrayList<>();
			for (dev.squire.server.tool.ArgDefinition argument : definition.args()) {
				if (argument.required() && (!call.arguments().containsKey(argument.name())
						|| call.arguments().get(argument.name()) == null
						|| String.valueOf(call.arguments().get(argument.name())).isBlank())) {
					missing.add(argument.description() == null
						|| argument.description().isBlank() ? argument.name()
						: argument.description());
				}
			}
			return List.copyOf(missing);
		}).orElse(List.of());
	}

	private static String planPrefix(TurnRecord turn) {
		if (turn.planSteps().isEmpty()) return "";
		return "计划：" + String.join(" → ", turn.planSteps()) + "\n";
	}

	private void notify(TurnRecord turn, String message) {
		if (message != null && !message.isBlank())
			runtime.notifier().send(turn.ownerId(), "[" + agentName(turn) + "] " + message);
	}

	private long now() { return server.getOverworld().getTime(); }

	private static String cap(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	private static String shortId(UUID id) { return id.toString().substring(0, 8); }

	private static String rootMessage(Throwable error) {
		if (error == null) return "null response";
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return String.valueOf(root.getMessage());
	}

	private void recordReplay(String type,
			java.util.function.Consumer<com.google.gson.JsonObject> fill) {
		try {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			fill.accept(o);
			runtime.replay().record(type, o);
		} catch (RuntimeException e) {
			LOG.warn("[turn] replay record failed: {}", e.toString());
		}
	}
}
