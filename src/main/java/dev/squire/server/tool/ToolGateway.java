package dev.squire.server.tool;

import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;

import dev.squire.common.errors.ErrorCode;
import dev.squire.common.errors.ErrorPayload;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;

/**
 * The ONLY path from model/planner/admin to a tool handler (spec section 27).
 * Pipeline order is fixed; any stage refusing means the tool does not execute.
 * All native handlers run synchronously on the server thread by contract.
 */
public final class ToolGateway {

	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(ToolGateway.class);

	/** Per-agent capability source, resolved by the runtime (spec section 30). */
	public interface CapabilityChecker {
		boolean has(AgentPermission permission);
	}

	private final ToolRegistry registry;
	private final LoopProtector loopProtector = new LoopProtector();
	private final AuditLog audit = new AuditLog();

	/** Optional extra policy hook (kill-switch / world rules plug in here). */
	private PolicyGate policyGate = (def, caller, ctx) -> null;

	public interface PolicyGate {
		/** @return error payload to reject with, or null to allow. */
		ErrorPayload check(ToolDefinition definition, CallerIdentity caller,
				ToolExecutionContext context);
	}

	/** Resolves the requesting owner's permission nodes (spec section 61). */
	public interface NodeChecker {
		boolean has(UUID playerId, String node);
	}

	private NodeChecker nodeChecker = (playerId, node) -> true;
	private dev.squire.server.security.QuotaLedger quotas;
	private dev.squire.server.security.CapabilityStore capabilityStore;
	/** §75 observability; null until the runtime wires it (metrics stay optional). */
	private volatile dev.squire.server.metrics.SquireMetrics metrics;

	/** Owner-confirmed HIGH-risk calls skip the BLOCKED stage (server-verified). */
	public interface ConfirmationAuthorizer {
		boolean isConfirmed(UUID playerId, String toolName, String argumentsFingerprint);
	}

	private ConfirmationAuthorizer confirmationAuthorizer = (p, t, f) -> false;

	public void setConfirmationAuthorizer(ConfirmationAuthorizer authorizer) {
		this.confirmationAuthorizer = authorizer == null ? (p, t, f) -> false : authorizer;
	}

	/**
	 * Defense-in-depth for §82 "Non-owner instruction": a MODEL call that mutates the
	 * world/inventory must act for an agent whose OWNER is the sender (or an admin).
	 */
	public interface OwnerVerifier {
		boolean isOwnerOrAdmin(UUID senderId, UUID agentId);
	}

	private OwnerVerifier ownerVerifier = (s, a) -> true;

	public void setOwnerVerifier(OwnerVerifier verifier) {
		this.ownerVerifier = verifier == null ? (s, a) -> true : verifier;
	}

	/** AgentPermission classes that mutate persistent state. */
	private static boolean isWriteClass(AgentPermission permission) {
		return switch (permission) {
			case WORLD_BREAK, WORLD_PLACE, WORLD_EDIT, INVENTORY_WRITE,
				 COMBAT, COMMAND -> true;
			default -> false;
		};
	}

	public ToolGateway(ToolRegistry registry) {
		this.registry = Objects.requireNonNull(registry);
	}

	public void setPolicyGate(PolicyGate gate) {
		this.policyGate = gate == null ? (d, c, x) -> null : gate;
	}

	public void setNodeChecker(NodeChecker checker) {
		this.nodeChecker = checker == null ? (p, n) -> true : checker;
	}

	public void setQuotas(dev.squire.server.security.QuotaLedger quotas) {
		this.quotas = quotas;
	}

	/**
	 * agentId → 职业进度。第二道闸靠它判断「这只随从会不会这件事」。
	 *
	 * <p>注入而不是直接引用运行时：网关在最小/测试运行时也要能跑，而那里没有存档。
	 * 没接线时（{@code null}）第二道闸不生效——那正是单测里只想验权限节点的场景。</p>
	 */
	public void setProfessionLookup(
			java.util.function.Function<java.util.UUID,
				dev.squire.server.profession.ProfessionData> lookup) {
		this.professionLookup = lookup;
	}

	private java.util.function.Function<java.util.UUID,
		dev.squire.server.profession.ProfessionData> professionLookup;

	public void setMetrics(dev.squire.server.metrics.SquireMetrics metrics) {
		this.metrics = metrics;
	}

	public void setCapabilityStore(dev.squire.server.security.CapabilityStore store) {
		this.capabilityStore = store;
	}

	/** Begin a new planning turn: loop counters reset (world changed meanwhile). */
	public void newTurn() {
		loopProtector.reset();
	}

	public AuditLog audit() {
		return audit;
	}

	public LoopProtector loopProtector() {
		return loopProtector;
	}

	/** Full pipeline. Never throws — every failure becomes a FAILED/BLOCKED result. */
	public ToolResult dispatch(ToolCall call, CallerIdentity caller,
			ToolExecutionContext context, CapabilityChecker capabilities) {
		long startedNanos = System.nanoTime();
		try {
			ToolResult result = dispatchInternal(call, caller, context, capabilities);
			noteOutcome(result);
			recordReplay(call, result);
			return result;
		} catch (RuntimeException e) {
			audit.record(tickOf(context), caller == null ? "NULL" : caller.kind().name(),
				call == null ? "?" : call.toolName(), false, ErrorCode.INTERNAL_ERROR.wire());
			ToolResult failed = ToolResult.failed(callIdOf(call),
				ErrorPayload.of(ErrorCode.INTERNAL_ERROR, String.valueOf(e.getMessage())));
			noteOutcome(failed);
			recordReplay(call, failed);
			return failed;
		} finally {
			dev.squire.server.metrics.SquireMetrics m = metrics;
			if (m != null) {
				m.inc(dev.squire.server.metrics.SquireMetrics.Key.TOOL_CALLS);
				m.record(dev.squire.server.metrics.SquireMetrics.Timer.TOOL_DURATION,
					System.nanoTime() - startedNanos);
			}
		}
	}

	/** §76 replay hook for gateway decisions + results (redacted inside). */
	private void recordReplay(ToolCall call, ToolResult result) {
		dev.squire.server.replay.ReplayRecorder r =
			replay; // volatile read once
		if (r == null || !r.isEnabled()) {
			return;
		}
		try {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("tool", call == null ? "?" : call.toolName());
			o.addProperty("status", result == null ? "null" : result.status().name());
			if (result != null && result.error() != null) {
				o.addProperty("errorCode",
					String.valueOf(result.error().code()));
			}
			r.record("tool_decision", o);
		} catch (RuntimeException ignored) {
			// replay must never disturb dispatch
		}
	}

	private volatile dev.squire.server.replay.ReplayRecorder replay;

	public void setReplayRecorder(dev.squire.server.replay.ReplayRecorder value) {
		this.replay = value;
	}

	private void noteOutcome(ToolResult result) {
		dev.squire.server.metrics.SquireMetrics m = metrics;
		if (m == null || result == null) {
			return;
		}
		// RUNNING (async MCP) and CANCELLED are neither rejections nor failures;
		// PARTIAL counts as a success-shaped outcome for the rejected counter.
		if (result.status() == ToolResult.Status.BLOCKED
				|| result.status() == ToolResult.Status.FAILED) {
			m.inc(dev.squire.server.metrics.SquireMetrics.Key.TOOL_REJECTED);
		}
	}

	private ToolResult dispatchInternal(ToolCall call, CallerIdentity caller,
			ToolExecutionContext context, CapabilityChecker capabilities) {
		// 0. shape of the request itself
		if (call == null || caller == null || isBlank(call.toolName())) {
			audit.record(tickOf(context), kindName(caller), "?", false,
				ErrorCode.MALFORMED_MODEL_OUTPUT.wire());
			return ToolResult.failed(callIdOf(call), ErrorPayload.of(
				ErrorCode.MALFORMED_MODEL_OUTPUT, "tool call missing name or identity"));
		}
		UUID callId = call.callId();

		// 1. lookup
		ToolDefinition def = registry.lookup(call.toolName()).orElse(null);
		if (def == null) {
			return reject(context, caller, call, ErrorCode.TOOL_NOT_FOUND,
				"unknown tool '" + call.toolName() + "'");
		}

		// 2. schema validation (strict: unknown args are rejected too)
		String invalid = validateArguments(def, call.arguments());
		if (invalid != null) {
			return reject(context, caller, call, ErrorCode.INVALID_ARGUMENT, invalid);
		}

		// 3. exposure check against caller kind
		if (!exposureAllows(def.exposure(), caller.kind())) {
			return reject(context, caller, call, ErrorCode.TOOL_NOT_VISIBLE,
				"tool '" + def.name() + "' not exposed to " + caller.kind());
		}

		// 4. sender identity
		if (caller.senderId() == null || caller.agentId() == null) {
			return reject(context, caller, call, ErrorCode.PERMISSION_DENIED,
				"caller identity incomplete");
		}

		// 5. permission via capability set
		if (capabilities == null || !capabilities.has(def.permission())) {
			return reject(context, caller, call, ErrorCode.PERMISSION_DENIED,
				"capability " + def.permission() + " not granted");
		}

		// 5a. profession / level gate (§23) — the SECOND gate.
		//
		// 第一道闸是「模型根本看不见这个工具」（ToolGate.filter，在序列化之前）。
		// 但那道闸挡的是<b>模型</b>，挡不住一个伪造的调用：请求可以从别处来、
		// 目录可以被抢跑、以后也可能有别的调用方。所以执行前再判一次，
		// 而且查的是<b>同一张表</b>——两处判据不同才是真正会出事的地方。
		if (professionLookup != null) {
			ToolGate gate = ToolGate.of(def.name());
			var professionData = professionLookup.apply(caller.agentId());
			if (!gate.allows(professionData)) {
				// 档案查不到和等级不够是两种拒绝，理由要说对（§23.2）。报成
				// 「需要 engineer Lv1」会让模型去劝玩家升级，而真正的问题是
				// 这次调用根本没能对应上一只有档案的随从。
				String why = professionData == null
					? "no profession profile resolved for this squire"
					: "needs " + (gate.profession() == null ? "a profession"
						: gate.profession().id() + " Lv" + gate.unlockLevel());
				return reject(context, caller, call, ErrorCode.PERMISSION_DENIED,
					"tool '" + def.name() + "' " + why);
			}
		}

		// 5b. the requesting owner must hold the tool's permission node (§61)
		if (def.permissionNode() != null && isInteractiveCaller(caller.kind())
				&& !nodeChecker.has(caller.senderId(), def.permissionNode())) {
			return reject(context, caller, call, ErrorCode.PERMISSION_DENIED,
				"player lacks node " + def.permissionNode());
		}

		// 5c. model calls that WRITE must be for the sender's own agent (§82)
		if (isInteractiveCaller(caller.kind()) && isWriteClass(def.permission())
				&& !ownerVerifier.isOwnerOrAdmin(caller.senderId(), caller.agentId())) {
			return reject(context, caller, call, ErrorCode.PERMISSION_DENIED,
				"sender does not own this agent");
		}

		// 6. context validation (via the body abstraction — ADR-002)
		if (context == null || context.body() == null || !context.body().alive()) {
			return reject(context, caller, call, ErrorCode.PRECONDITION_FAILED,
				"execution context unavailable or body dead");
		}

		// 7/8/9. policy + risk confirmation
		ErrorPayload policy = policyGate.check(def, caller, context);
		if (policy != null) {
			return reject(context, caller, call, policy.code(), policy.message());
		}
		boolean ownerConfirmedThisDispatch = false;
		if (def.risk() == RiskLevel.HIGH && caller.kind() == CallerIdentity.CallerKind.MODEL) {
			boolean confirmed = confirmationAuthorizer.isConfirmed(caller.senderId(),
				def.name(), fingerprint(call.arguments()));
			if (!confirmed) {
				return ToolResult.blocked(callId, ErrorPayload.of(
					ErrorCode.CONFIRMATION_REQUIRED,
					"high-risk tool '" + def.name() + "' needs owner confirmation"));
			}
			ownerConfirmedThisDispatch = true;
		}

		// 10. quota / loop budget
		var loop = loopProtector.checkAndCount(def.name(), fingerprint(call.arguments()));
		if (loop.isPresent()) {
			return reject(context, caller, call, ErrorCode.LOOP_DETECTED, loop.get());
		}
		if (quotas != null) {
			var rateLimited = quotas.recordToolCall(context.tick());
			if (rateLimited.isPresent()) {
				return reject(context, caller, call, rateLimited.get(),
					"tool call quota exhausted");
			}
		}

		// 10b. scoped capability for high-risk world edits (§30). A just-confirmed
		// HIGH-risk call may pass without a pre-issued id: the handler then creates a
		// scoped capability and WorldEditor re-validates it EXACTLY before any write.
		if (def.requiresCapability() && !ownerConfirmedThisDispatch) {
			String capabilityError = validateCapability(def, call, context);
			if (capabilityError != null) {
				return reject(context, caller, call,
					capabilityError.startsWith(ErrorCode.CAPABILITY_SCOPE_VIOLATION.wire())
						? ErrorCode.CAPABILITY_SCOPE_VIOLATION : ErrorCode.CAPABILITY_REQUIRED,
					capabilityError);
			}
		}

		// 11. execute on the server thread (gateway is only ever invoked there)
		ToolHandler handler = registry.handlerFor(def.name()).orElse(null);
		if (handler == null) {
			return reject(context, caller, call, ErrorCode.INTERNAL_ERROR,
				"tool registered without handler");
		}
		ToolResult result = handler.execute(call, context);

		// 12. postcondition: result well-formed and echoes the call id
		if (result == null) {
			result = ToolResult.failed(callId, ErrorPayload.of(
				ErrorCode.INTERNAL_ERROR, "handler returned null"));
		} else if (!Objects.equals(result.callId(), callId)) {
			result = ToolResult.failed(callId, ErrorPayload.of(
				ErrorCode.INTERNAL_ERROR, "handler returned mismatched callId"));
		}

		// 13. quota accounting for world impact reported by the handler
		if (quotas != null && result.data() != null
				&& result.data().get("blocksChanged") instanceof Number n && n.longValue() > 0) {
			var overBudget = quotas.recordBlocksChanged(context.tick(), n.longValue());
			if (overBudget.isPresent()) {
				LOG.warn("[gateway] blocksChanged {} pushed quota over budget", n.longValue());
			}
		}

		audit.record(context.tick(), caller.kind().name(), def.name(),
			result.status() != ToolResult.Status.BLOCKED, result.status().name());
		LOG.info("[gateway] {} by {} -> {} {}", def.name(), caller.kind(),
			result.status(),
			result.errorOrNull().map(e -> e.code().wire()).orElse("ok"));
		return result;
	}

	private ToolResult reject(ToolExecutionContext context, CallerIdentity caller,
			ToolCall call, ErrorCode code, String message) {
		LOG.info("[gateway] {} by {} -> REJECTED {} ({})", call == null ? "?"
			: call.toolName(), kindName(caller), code.wire(), message);
		audit.record(tickOf(context), kindName(caller),
			call == null ? "?" : call.toolName(), false, code.wire());
		return ToolResult.failed(callIdOf(call), ErrorPayload.of(code, message));
	}

	// --- helpers -----------------------------------------------------------

	private static String kindName(CallerIdentity caller) {
		return caller == null ? "NULL" : caller.kind().name();
	}

	private static UUID callIdOf(ToolCall call) {
		return call == null ? UUID.randomUUID() : call.callId();
	}

	private static long tickOf(ToolExecutionContext context) {
		return context == null ? -1L : context.tick();
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static boolean exposureAllows(ToolExposure exposure, CallerIdentity.CallerKind kind) {
		return switch (kind) {
			case MODEL, FAST_PATH -> exposure.visibleToModel();
			case PLANNER -> exposure != ToolExposure.RUNTIME_ONLY
				&& exposure != ToolExposure.ADMIN_ONLY;
			case ADMIN -> exposure != ToolExposure.RUNTIME_ONLY;
			case RUNTIME -> true;
		};
	}

	private static boolean isInteractiveCaller(CallerIdentity.CallerKind kind) {
		return kind == CallerIdentity.CallerKind.MODEL
			|| kind == CallerIdentity.CallerKind.FAST_PATH;
	}

	/** Strict schema pass: no unknown keys, required present, types/ranges hold. */
	public static String validateArguments(ToolDefinition def, Map<String, Object> arguments) {
		Map<String, Object> args = arguments == null ? Map.of() : arguments;
		for (ArgDefinition arg : def.args()) {
			Object value = args.get(arg.name());
			if (value == null && arg.required()) {
				return arg.validate(null).orElse("missing argument '" + arg.name() + "'");
			}
			if (value != null) {
				var problem = arg.validate(value);
				if (problem.isPresent()) {
					return problem.get();
				}
			}
		}
		for (String key : args.keySet()) {
			boolean known = def.args().stream().anyMatch(a -> a.name().equals(key));
			if (!known) {
				return "unknown argument '" + key + "'";
			}
		}
		return null;
	}

	/**
	 * Coarse capability gate (§30): the call must carry a live, unexpired,
	 * un-consumed capability issued to this agent and allowing this tool, and its
	 * DECLARED region must sit inside the capability's bounds. Exact per-block
	 * validation happens again inside WorldEditor with real numbers.
	 */
	private String validateCapability(ToolDefinition def, ToolCall call,
			ToolExecutionContext context) {
		if (capabilityStore == null) {
			return ErrorCode.CAPABILITY_REQUIRED.wire() + ": no capability store";
		}
		Object rawId = call.arguments().get("capabilityId");
		if (!(rawId instanceof String raw) || raw.isBlank()) {
			return ErrorCode.CAPABILITY_REQUIRED.wire() + ": missing capabilityId argument";
		}
		java.util.UUID capabilityId;
		try {
			capabilityId = java.util.UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return ErrorCode.CAPABILITY_REQUIRED.wire() + ": malformed capabilityId";
		}
		var agentId = context.body().agentId();
		dev.squire.server.world.BoundedRegion declared = declaredRegion(call.arguments());
		int declaredImpact = declared == null ? 1 : (int) Math.min(declared.volume(), Integer.MAX_VALUE);
		var problem = capabilityStore.validateForUse(capabilityId, agentId, def.name(),
			context.avatar().getWorld().getRegistryKey().getValue(), declared,
			declaredImpact, context.tick());
		return problem.orElse(null);
	}

	/** Derives the declared region from standard corner or single-cell arguments. */
	static dev.squire.server.world.BoundedRegion declaredRegion(Map<String, Object> args) {
		Integer x1 = asInt(args.get("x1"));
		Integer y1 = asInt(args.get("y1"));
		Integer z1 = asInt(args.get("z1"));
		Integer x2 = asInt(args.get("x2"));
		Integer y2 = asInt(args.get("y2"));
		Integer z2 = asInt(args.get("z2"));
		if (x1 != null && y1 != null && z1 != null && x2 != null && y2 != null && z2 != null) {
			return dev.squire.server.world.BoundedRegion.ofCorners(x1, y1, z1, x2, y2, z2);
		}
		Integer x = asInt(args.get("x"));
		Integer y = asInt(args.get("y"));
		Integer z = asInt(args.get("z"));
		if (x != null && y != null && z != null) {
			return dev.squire.server.world.BoundedRegion.ofCorners(x, y, z, x, y, z);
		}
		return null;
	}

	private static Integer asInt(Object o) {
		return o instanceof Number n ? Integer.valueOf(n.intValue()) : null;
	}

	/** Canonical argument fingerprint for equivalent-call detection. */
	public static String fingerprint(Map<String, Object> arguments) {
		if (arguments == null || arguments.isEmpty()) {
			return "-";
		}
		StringJoiner joiner = new StringJoiner(";");
		new TreeMap<>(arguments).forEach((k, v) -> joiner.add(k + "=" + v));
		return joiner.toString();
	}
}
