package dev.squire.server.ext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.api.sensor.SquireSensorProvider;
import dev.squire.api.task.ExternalTaskInvocation;
import dev.squire.api.task.ExternalTaskExecutor;
import dev.squire.api.task.SquireTaskProvider;
import dev.squire.api.task.TaskTypeSpec;
import dev.squire.api.tool.ArgSpec;
import dev.squire.api.tool.ExternalTool;
import dev.squire.api.tool.ExternalToolResult;
import dev.squire.api.tool.SquireToolProvider;
import dev.squire.api.tool.ToolRegistrar;
import dev.squire.api.tool.ToolInvocation;
import dev.squire.common.errors.ErrorCode;
import dev.squire.common.errors.ErrorPayload;
import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;
import dev.squire.server.security.PermissionNodes;
import dev.squire.server.security.ToolTrust;
import dev.squire.server.tool.AgentPermission;
import dev.squire.server.tool.ArgDefinition;
import dev.squire.server.tool.RiskLevel;
import dev.squire.server.tool.ToolDefinition;
import dev.squire.server.tool.ToolExposure;
import dev.squire.server.tool.ToolRegistry;

/**
 * M4 extension platform core (spec sections 26.4/53-59): adapts third-party
 * {@code squire-api} contributions onto native definitions WITHOUT touching the
 * gateway pipeline. Every import passes the same caps and the same fail-closed
 * classification; refusals are REPORTED, never thrown, so one bad mod cannot break
 * server startup.
 */
public final class ExtensionManager {
	private static final Logger LOG = LoggerFactory.getLogger(ExtensionManager.class);

	public static final int MAX_TOOL_ARGS = 32;
	public static final int MAX_DESCRIPTION_CHARS = 2000;
	public static final int MAX_TOOLS_PER_PROVIDER = 64;
	public static final int MAX_TASK_TYPES_PER_PROVIDER = 32;
	public static final int MAX_TASK_PARAMS = 16;

	/** One import outcome, accepted or refused, kept for the inspector. */
	public record ImportReport(String itemId, String kind, String origin,
			boolean accepted, String reason) {
	}

	private final ToolRegistry tools;
	private final dev.squire.server.task.TaskScheduler scheduler;
	private final SensorRegistry sensors = new SensorRegistry();
	private final List<ImportReport> reports = new ArrayList<>();
	private final Map<String, TaskTypeSpec> taskSpecs = new ConcurrentHashMap<>();
	private final Map<String, ExternalTaskExecutor> taskExecutors =
		new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> providerToolCounts = new ConcurrentHashMap<>();
	private final Map<UUID, PendingCompletion> pendingCompletions =
		new ConcurrentHashMap<>();
	/**
	 * Bridge-owned results, NOT task.executionState(): the scheduler reuses that slot
	 * to remember when verification began (TaskScheduler.advanceRunning), which would
	 * clobber anything we store there before WORK_DONE.
	 */
	private final Map<UUID, ExternalToolResult> outcomes = new ConcurrentHashMap<>();

	/** Result the bridge last recorded for a task (verifier-facing, ADR-013 safe). */
	public Optional<ExternalToolResult> outcomeOf(UUID taskId) {
		return Optional.ofNullable(outcomes.get(taskId));
	}

	public ExtensionManager(ToolRegistry tools,
			dev.squire.server.task.TaskScheduler scheduler) {
		this.tools = tools;
		this.scheduler = scheduler;
	}

	public SensorRegistry sensors() {
		return sensors;
	}

	public List<ImportReport> reports() {
		synchronized (reports) {
			return List.copyOf(reports);
		}
	}

	// ------------------------------------------------------------------ tool import

	/**
	 * Import one external tool under {@code trust}. Refusals (duplicate id, oversized
	 * schema, rejected permission node) are logged + reported, never thrown (§58).
	 */
	public synchronized ImportReport registerTool(ExternalTool tool, ToolTrust trust,
			String origin) {
		String id = tool == null ? "<null>" : tool.id();
		try {
			if (tool == null) {
				return refuse(id, "tool", origin, "null tool");
			}
			if (origin == null || origin.isBlank()) {
				return refuse(id, "tool", origin, "blank origin");
			}
			if (tools.lookup(tool.id()).isPresent()) {
				return refuse(id, "tool", origin, "duplicate tool id");
			}
			if (tool.args().size() > MAX_TOOL_ARGS) {
				return refuse(id, "tool", origin,
					"too many args: " + tool.args().size() + " > " + MAX_TOOL_ARGS);
			}
			AtomicInteger perProvider = providerToolCounts.computeIfAbsent(origin,
				k -> new AtomicInteger());
			if (perProvider.get() >= MAX_TOOLS_PER_PROVIDER) {
				return refuse(id, "tool", origin, "provider exceeded tool cap");
			}
			String node = tool.permissionNode();
			if (node != null && !PermissionNodes.registerCustom(node)) {
				return refuse(id, "tool", origin, "permission node rejected: " + node);
			}
			ToolDefinition def = adapt(tool, trust, origin);
			tools.register(def, adaptHandler(tool));
			perProvider.incrementAndGet();
			LOG.info("[ext] imported tool {} from {} trust={}", tool.id(), origin, trust);
			return accept(tool.id(), "tool", origin);
		} catch (RuntimeException e) {
			return refuse(id, "tool", origin, "rejected: " + e.getMessage());
		}
	}

	private ToolDefinition adapt(ExternalTool tool, ToolTrust trust, String origin) {
		// least privilege (fail-closed, §55): a tool CLAIMING read-only runs in the
		// query class; anything else lands in write-class so owner verification and
		// every other gateway stage apply at full strength.
		AgentPermission permission = tool.readOnlyHint()
			? AgentPermission.QUERY
			: AgentPermission.WORLD_PLACE;
		ToolDefinition.Builder builder = ToolDefinition.builder(tool.id())
			.description(truncate(tool.description()))
			.exposure(mapExposure(tool.exposure()))
			.risk(mapRisk(tool.risk()))
			.permission(permission)
			.sourceTrust(trust)
			.origin(origin);
		if (tool.destructiveHint()) {
			builder.destructive(); // hints only ever make policy stricter
		}
		if (tool.permissionNode() != null) {
			builder.node(tool.permissionNode());
		}
		if (tool.readOnlyHint()) builder.readOnly();
		for (String tag : tool.tags()) builder.tag(tag);
		for (String alias : tool.aliases()) builder.alias(alias);
		for (ArgSpec spec : tool.args()) {
			builder.arg(ArgDefinition.of(spec.name(),
				ArgDefinition.ArgType.valueOf(spec.type().name()),
				spec.required(), spec.minimum(), spec.maximum(), spec.pattern(),
				truncate(spec.description())));
		}
		return builder.build();
	}

	/** Bridges an api handler onto the native handler shape; gateway catches throws. */
	private static dev.squire.server.tool.ToolHandler adaptHandler(ExternalTool tool) {
		return (ToolCall call, dev.squire.server.tool.ToolExecutionContext context) -> {
			ToolInvocation invocation = new ToolInvocation(call.callId(),
				context.body().agentId(), context.requesterId(), call.arguments());
			ExternalToolResult result = tool.handler().execute(invocation);
			return toNative(result, call.callId());
		};
	}

	private static ToolResult toNative(ExternalToolResult result, UUID callId) {
		if (result == null) {
			return ToolResult.failed(callId, ErrorPayload.of(ErrorCode.INTERNAL_ERROR,
				"external tool returned null"));
		}
		Map<String, Object> data = result.data() == null ? Map.of() : result.data();
		return switch (result.status()) {
			case SUCCESS -> ToolResult.success(callId, data);
			case PARTIAL -> new ToolResult(callId, ToolResult.Status.PARTIAL, data, null);
			case RUNNING -> ToolResult.running(callId, data);
			case FAILED -> ToolResult.failed(callId, ErrorPayload.of(
				ErrorCode.INTERNAL_ERROR,
				result.message() == null ? result.errorCode() : result.message()));
		};
	}

	private static ToolExposure mapExposure(String exposure) {
		return switch (exposure) {
			case "PLANNER_INTERNAL" -> ToolExposure.PLANNER_INTERNAL;
			case "ADMIN_ONLY" -> ToolExposure.ADMIN_ONLY;
			default -> ToolExposure.MODEL_PUBLIC;
		};
	}

	private static RiskLevel mapRisk(String risk) {
		return switch (risk) {
			case "LOW" -> RiskLevel.LOW;
			case "HIGH" -> RiskLevel.HIGH;
			default -> RiskLevel.MEDIUM;
		};
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= MAX_DESCRIPTION_CHARS ? text
			: text.substring(0, MAX_DESCRIPTION_CHARS);
	}

	// ------------------------------------------------------------------ task types

	public synchronized ImportReport registerTaskType(TaskTypeSpec spec,
			ExternalTaskExecutor executor, String origin) {
		String id = spec == null ? "<null>" : spec.typeId();
		try {
			if (spec == null || executor == null) {
				return refuse(id, "task", origin, "null spec or executor");
			}
			if (origin == null || origin.isBlank()) {
				return refuse(id, "task", origin, "blank origin");
			}
			if (taskSpecs.containsKey(spec.typeId())) {
				return refuse(id, "task", origin, "duplicate task type");
			}
			long providers = providerToolCounts.size(); // coarse cap across all origins
			if (providers > 1024) {
				return refuse(id, "task", origin, "too many providers");
			}
			taskSpecs.put(spec.typeId(), spec);
			taskExecutors.put(spec.typeId(), executor);
			scheduler.register(new ExternalTaskBridge(spec, executor));
			LOG.info("[ext] imported task type {} from {}", spec.typeId(), origin);
			return accept(spec.typeId(), "task", origin);
		} catch (RuntimeException e) {
			return refuse(id, "task", origin, "rejected: " + e.getMessage());
		}
	}

	public synchronized Optional<TaskTypeSpec> taskType(String typeId) {
		return Optional.ofNullable(taskSpecs.get(typeId));
	}

	public synchronized List<String> taskTypes() {
		return List.copyOf(taskSpecs.keySet());
	}

	/**
	 * Programmatic submission of a registered external task type (tests, admin
	 * surfaces). The bridge executor owns execution; the scheduler's verifier still
	 * owns the COMPLETED transition via the success condition (ADR-013).
	 */
	public synchronized Optional<dev.squire.server.task.Task> submitExternalTask(
			UUID agentId, UUID requesterId, String typeId,
			Map<String, Object> params, long currentTick) {
		TaskTypeSpec spec = taskSpecs.get(typeId);
		if (spec == null || agentId == null) {
			return Optional.empty();
		}
		Map<String, Object> safeParams = params == null ? Map.of()
			: Map.copyOf(params);
		if (safeParams.size() > MAX_TASK_PARAMS) {
			return Optional.empty();
		}
		AtomicReference<dev.squire.server.task.Task> holder = new AtomicReference<>();
		dev.squire.server.task.TaskCondition success =
			dev.squire.server.task.TaskCondition.of(context -> {
				UUID id = holder.get() == null ? null : holder.get().taskId();
				return outcomeOf(id).map(ExternalToolResult::isSuccess).orElse(false);
			}, "external task reported success");
		dev.squire.server.task.Task task = new dev.squire.server.task.Task(agentId,
			requesterId, "ext:" + typeId, dev.squire.server.task.TaskPriority.P3_USER_TASK,
			"external task " + typeId, null, success,
			Math.max(20L, spec.defaultTimeoutTicks()),
			dev.squire.server.task.RetryPolicy.NONE, true, "extension:" + typeId,
			safeParams);
		holder.set(task);
		scheduler.submit(task, currentTick);
		return Optional.of(task);
	}

	/** Long-running work resolution point for RUNNING external tasks. */
	private ExternalToolResult pollCompletion(UUID taskId) {
		PendingCompletion pending = pendingCompletions.get(taskId);
		return pending == null ? null : pending.poll();
	}

	/** Thread-safe single-shot completion slot handed to providers. */
	private static final class PendingCompletion
			implements ExternalTaskInvocation.Completion {
		private final AtomicReference<ExternalToolResult> resolved =
			new AtomicReference<>();

		@Override
		public void complete(ExternalToolResult result) {
			resolved.compareAndSet(null, result);
		}

		ExternalToolResult poll() {
			return resolved.get();
		}
	}

	/**
	 * Runs one registered external task type inside the normal scheduler lifecycle:
	 * start invokes the provider once; tick translates stored results into step
	 * outcomes. The verifier never sees provider code — only its stored result.
	 */
	private final class ExternalTaskBridge implements dev.squire.server.task.TaskExecutor {
		private final TaskTypeSpec spec;
		private final ExternalTaskExecutor delegate;

		ExternalTaskBridge(TaskTypeSpec spec, ExternalTaskExecutor delegate) {
			this.spec = spec;
			this.delegate = delegate;
		}

		@Override
		public String type() {
			return "ext:" + spec.typeId();
		}

		@Override
		public void start(dev.squire.server.task.Task task) {
			PendingCompletion completion = new PendingCompletion();
			pendingCompletions.put(task.taskId(), completion);
			try {
				ExternalToolResult out = delegate.execute(new ExternalTaskInvocation(
					task.taskId(), task.agentId(), task.requesterId(),
					task.parameters(), completion));
				if (out == null) {
					out = ExternalToolResult.failed(task.taskId(), "NULL_RESULT",
						"provider returned null");
				}
				outcomes.put(task.taskId(), out);
			} catch (RuntimeException e) {
				LOG.warn("[ext] task provider threw on start of {}",
					task.taskId(), e);
				outcomes.put(task.taskId(), ExternalToolResult.failed(task.taskId(),
					e.getClass().getSimpleName(), String.valueOf(e.getMessage())));
			}
		}

		@Override
		public dev.squire.server.task.TaskExecutor.StepOutcome tick(dev.squire.server.task.Task task,
				long tick) {
			ExternalToolResult result = outcomes.get(task.taskId());
			if (result == null) {
				return dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
			}
			if (result.status() == ExternalToolResult.Status.RUNNING) {
				ExternalToolResult later = pollCompletion(task.taskId());
				if (later == null) {
					return dev.squire.server.task.TaskExecutor.StepOutcome.CONTINUE;
				}
				result = later;
				outcomes.put(task.taskId(), later);
			}
			pendingCompletions.remove(task.taskId());
			return switch (result.status()) {
				case SUCCESS, PARTIAL -> dev.squire.server.task.TaskExecutor.StepOutcome.WORK_DONE;
				default -> {
					task.setLastErrorCode(result.errorCode() == null
						? "EXTERNAL_FAILED" : result.errorCode());
					yield dev.squire.server.task.TaskExecutor.StepOutcome.FAILED;
				}
			};
		}

		@Override
		public void cancel(dev.squire.server.task.Task task) {
			pendingCompletions.remove(task.taskId());
		}
	}

	// ------------------------------------------------------------------ entrypoints

	/**
	 * Loads all mods exposing a {@code "squire"} entrypoint (spec §57). Provider
	 * exceptions are contained per mod; entrypoint tools import UNTRUSTED — read-only
	 * ones work immediately, privileged shapes need explicit approval.
	 */
	public void loadEntrypoints() {
		loadToolsFromEntrypoints();
		loadSensorsFromEntrypoints();
		loadTasksFromEntrypoints();
	}

	private void loadToolsFromEntrypoints() {
		try {
			for (var container : net.fabricmc.loader.api.FabricLoader.getInstance()
					.getEntrypointContainers("squire", SquireToolProvider.class)) {
				String modId = container.getProvider().getMetadata().getId();
				try {
					container.getEntrypoint().registerTools(new ToolRegistrar() {
						private final AtomicInteger count = new AtomicInteger();

						@Override
						public void register(ExternalTool tool) {
							if (count.incrementAndGet() > MAX_TOOLS_PER_PROVIDER) {
								refuse(tool == null ? "<null>" : tool.id(), "tool",
									"mod:" + modId, "provider exceeded tool cap");
								return;
							}
							registerTool(tool, ToolTrust.UNTRUSTED, "mod:" + modId);
						}
					});
				} catch (RuntimeException e) {
					LOG.warn("[ext] tool provider {} threw; skipped", modId, e);
				}
			}
		} catch (RuntimeException e) {
			LOG.warn("[ext] tool entrypoint scan unavailable", e);
		}
	}

	private void loadSensorsFromEntrypoints() {
		try {
			for (var container : net.fabricmc.loader.api.FabricLoader.getInstance()
					.getEntrypointContainers("squire", SquireSensorProvider.class)) {
				String modId = container.getProvider().getMetadata().getId();
				try {
					for (var sensor : container.getEntrypoint().createSensors()) {
						sensors.register(sensor, ToolTrust.UNTRUSTED, "mod:" + modId);
					}
				} catch (RuntimeException e) {
					LOG.warn("[ext] sensor provider {} threw; skipped", modId, e);
				}
			}
		} catch (RuntimeException e) {
			LOG.warn("[ext] sensor entrypoint scan unavailable", e);
		}
	}

	private void loadTasksFromEntrypoints() {
		try {
			for (var container : net.fabricmc.loader.api.FabricLoader.getInstance()
					.getEntrypointContainers("squire", SquireTaskProvider.class)) {
				String modId = container.getProvider().getMetadata().getId();
				try {
					container.getEntrypoint().registerTaskTypes((spec, executor) ->
						registerTaskType(spec, executor, "mod:" + modId));
				} catch (RuntimeException e) {
					LOG.warn("[ext] task provider {} threw; skipped", modId, e);
				}
			}
		} catch (RuntimeException e) {
			LOG.warn("[ext] task entrypoint scan unavailable", e);
		}
	}

	// ------------------------------------------------------------------ inspector

	private ImportReport accept(String id, String kind, String origin) {
		ImportReport report = new ImportReport(id, kind, origin, true, null);
		synchronized (reports) {
			reports.add(report);
		}
		return report;
	}

	private ImportReport refuse(String id, String kind, String origin, String reason) {
		LOG.warn("[ext] refused {} '{}' from {}: {}", kind, id, origin, reason);
		ImportReport report = new ImportReport(id, kind, origin, false, reason);
		synchronized (reports) {
			reports.add(report);
		}
		return report;
	}

	/** One-line summary per known tool for {@code /squire admin tools}. */
	public static List<String> inspectorLines(ToolRegistry registry,
			List<ImportReport> importReports) {
		List<String> lines = new ArrayList<>();
		lines.add("tools:");
		for (ToolDefinition def : registry.allDefinitions()) {
			lines.add(String.format(Locale.ROOT,
				"  %s exp=%s risk=%s perm=%s%s%s trust=%s origin=%s",
				def.name(), def.exposure(), def.risk(), def.permission(),
				def.permissionNode() == null ? "" : " node=" + def.permissionNode(),
				def.requiresCapability() ? " cap=yes" : "",
				def.sourceTrust(), def.origin()));
		}
		lines.add("imports:");
		for (ImportReport report : importReports) {
			lines.add("  " + (report.accepted() ? "+" : "-") + " "
				+ report.kind() + ":" + report.itemId()
				+ (report.reason() == null ? "" : " (" + report.reason() + ")"));
		}
		return lines;
	}
}
