package dev.squire.server.automation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.squire.common.protocol.ToolResult;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.TaskScheduler;
import dev.squire.server.tool.AuditLog;
import dev.squire.server.world.BoundedRegion;

/**
 * Owns every {@link AutomationGraph} and drives them on the server thread
 * (spec section 49). Long-term conditional logic lives HERE — never as hidden
 * command blocks (ADR-009).
 *
 * <p>Safety properties:</p>
 * <ul>
 *   <li>{@code enabled} ships OFF (§94); while off no trigger evaluates and no
 *       graph steps.</li>
 *   <li>TOOL_CALL steps dispatch through the gateway via {@link NodeDispatcher} —
 *       same pipeline, permissions, quotas and audit as model calls.</li>
 *   <li>One node step per tick, TTL expiry and a runaway guard bound execution.</li>
 *   <li>State persists to JSON: restart recovery keeps PAUSED paused and resumes
 *       ACTIVE graphs with cursor/wait state intact.</li>
 * </ul>
 */
public final class AutomationEngine {
	private static final Logger LOG = LoggerFactory.getLogger(AutomationEngine.class);

	public static final int MAX_PER_OWNER = 16;
	public static final int MAX_NODES = 32;
	/**
	 * v2 (方案 G3/13.2): recurring graphs no longer carry an implicit two-week TTL,
	 * and the engine's enabled flag is persisted.
	 */
	public static final int SCHEMA_VERSION = 2;
	/** Hard ceiling on node steps within one firing before the graph errors out. */
	private static final int RUNAWAY_LIMIT = 10_000;
	private static final int TASK_DEDUPE_CAPACITY = 64;

	/** Gateway seam: TOOL_CALL steps land here (production wires ToolGateway). */
	public interface NodeDispatcher {
		ToolResult dispatch(String toolName, Map<String, Object> arguments,
				UUID ownerId, UUID agentId);
	}

	/** Player-facing notification seam (chat when online, log otherwise). */
	public interface Notifier {
		void send(UUID ownerId, String message);
	}

	/** World facts probed on the server thread — never trusted from data files. */
	public interface WorldProbe {
		boolean ownerOnline(UUID ownerId);

		Set<UUID> playersInRegion(String dimension, BoundedRegion region);

		long dayTime();
	}

	public record Access(boolean ok, String message) {
		static Access ok(String message) {
			return new Access(true, message);
		}

		static Access deny(String message) {
			return new Access(false, message);
		}
	}

	private final LinkedHashMap<UUID, AutomationGraph> graphs = new LinkedHashMap<>();
	private final NodeDispatcher dispatcher;
	private final Notifier notifier;
	private final WorldProbe probe;
	private final TaskScheduler scheduler;
	private final AuditLog audit;
	private final Supplier<Path> fileSupplier;
	private volatile boolean enabled = false;
	/**
	 * True once {@link #load} has run. Persisting the enabled flag must never write an
	 * EMPTY graph list over a file we have not read yet — toggling the switch before
	 * recovery would otherwise delete every automation on disk.
	 */
	private volatile boolean loaded = false;

	public AutomationEngine(NodeDispatcher dispatcher, Notifier notifier,
			WorldProbe probe, TaskScheduler scheduler, AuditLog audit,
			Supplier<Path> fileSupplier) {
		this.dispatcher = dispatcher;
		this.notifier = notifier;
		this.probe = probe;
		this.scheduler = scheduler;
		this.audit = audit;
		this.fileSupplier = fileSupplier;
	}

	// ------------------------------------------------------------------ lifecycle

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean value) {
		this.enabled = value;
		LOG.info("[automation] {}", value ? "ENABLED" : "disabled");
		if (loaded || !graphs.isEmpty()) {
			save(); // 方案 G3：服主启用一次之后，重启不该又变回关闭
		}
	}

	public int size() {
		return graphs.size();
	}

	/** Registers a builder-built graph; returns null (with a log) when refused. */
	public synchronized AutomationGraph create(AutomationGraph graph, long nowTick) {
		if (graph == null) {
			return null;
		}
		long owned = graphs.values().stream()
			.filter(g -> g.ownerId().equals(graph.ownerId())).count();
		if (owned >= MAX_PER_OWNER) {
			LOG.warn("[automation] refused '{}': owner hit the {} graph cap",
				graph.name(), MAX_PER_OWNER);
			return null;
		}
		if (graph.nodeList().size() > MAX_NODES) {
			LOG.warn("[automation] refused '{}': {} nodes exceeds the cap",
				graph.name(), MAX_NODES);
			return null;
		}
		graph.setCreatedAtTick(nowTick);
		graph.setPolicySnapshot("automation=" + enabled);
		graphs.put(graph.id(), graph);
		audit.record(nowTick, "automation", graph.name() + "/create", true, "OK");
		save();
		return graph;
	}

	public synchronized Optional<AutomationGraph> get(UUID id) {
		return Optional.ofNullable(graphs.get(id));
	}

	public synchronized List<AutomationGraph> ownedBy(UUID ownerId) {
		List<AutomationGraph> mine = new ArrayList<>();
		for (AutomationGraph g : graphs.values()) {
			if (g.ownerId().equals(ownerId)) {
				mine.add(g);
			}
		}
		return mine;
	}

	/** Console/admin listing (§49 list). */
	public synchronized List<AutomationGraph> all() {
		return List.copyOf(graphs.values());
	}

	/** Owner or server-admin may control a graph; everyone else is denied. */
	public synchronized Access pause(UUID id, UUID requesterId, boolean requesterAdmin) {
		AutomationGraph g = controlled(id, requesterId, requesterAdmin);
		if (g == null) {
			return Access.deny("no such automation or not yours");
		}
		if (g.state() != AutomationGraph.State.ACTIVE
				&& g.state() != AutomationGraph.State.ERROR) {
			return Access.deny("automation is " + g.state());
		}
		g.setState(AutomationGraph.State.PAUSED);
		g.setCurrentNodeId(null);
		g.setWaitUntilTick(0);
		audit.record(probe.dayTime(), "automation", g.name() + "/pause", true, "OK");
		save();
		return Access.ok("paused");
	}

	public synchronized Access resume(UUID id, UUID requesterId, boolean requesterAdmin) {
		AutomationGraph g = controlled(id, requesterId, requesterAdmin);
		if (g == null) {
			return Access.deny("no such automation or not yours");
		}
		if (g.state() != AutomationGraph.State.PAUSED
				&& g.state() != AutomationGraph.State.ERROR) {
			return Access.deny("automation is " + g.state());
		}
		g.setState(AutomationGraph.State.ACTIVE);
		g.setErrorCode(null);
		audit.record(probe.dayTime(), "automation", g.name() + "/resume", true, "OK");
		save();
		return Access.ok("resumed");
	}

	public synchronized Access remove(UUID id, UUID requesterId, boolean requesterAdmin) {
		AutomationGraph g = controlled(id, requesterId, requesterAdmin);
		if (g == null) {
			return Access.deny("no such automation or not yours");
		}
		graphs.remove(id);
		audit.record(probe.dayTime(), "automation", g.name() + "/remove", true, "OK");
		save();
		return Access.ok("removed");
	}

	/** MANUAL trigger entry (owner-only through commands). */
	public synchronized Access fire(UUID id, UUID requesterId, long nowTick) {
		AutomationGraph g = graphs.get(id);
		if (g == null || !g.ownerId().equals(requesterId)) {
			return Access.deny("no such automation or not yours");
		}
		if (g.state() != AutomationGraph.State.ACTIVE) {
			return Access.deny("automation is " + g.state());
		}
		startFiring(g, nowTick);
		return Access.ok("fired");
	}

	private AutomationGraph controlled(UUID id, UUID requesterId, boolean requesterAdmin) {
		AutomationGraph g = graphs.get(id);
		if (g == null) {
			return null;
		}
		return g.ownerId().equals(requesterId) || requesterAdmin ? g : null;
	}

	// ------------------------------------------------------------------ ticking

	/**
	 * One server-tick step. Skipped entirely while disabled (§94 default) — the
	 * runtime also stops calling this while the killswitch is active.
	 */
	public void tick(long nowTick) {
		if (!enabled) {
			return;
		}
		for (AutomationGraph g : List.copyOf(graphs.values())) {
			if (g.state() != AutomationGraph.State.ACTIVE) {
				continue;
			}
			// TTL expiry (spec §49): lifetime counted from creation
			if (g.ttlTicks() > 0 && nowTick > g.createdAtTick() + g.ttlTicks()) {
				g.setState(AutomationGraph.State.EXPIRED);
				audit.record(nowTick, "automation", g.name() + "/expire", true, "TTL");
				save();
				continue;
			}
			try {
				if (g.currentNodeId() != null) {
					step(g, nowTick);
				} else {
					evaluateTrigger(g, nowTick);
					if (g.currentNodeId() != null) {
						// the firing's FIRST node runs in the trigger tick itself
						step(g, nowTick);
					}
				}
			} catch (RuntimeException e) {
				LOG.warn("[automation] '{}' threw, graph paused as ERROR: {}",
					g.name(), e.toString());
				g.setState(AutomationGraph.State.ERROR);
				g.setErrorCode("ENGINE_ERROR");
				g.setCurrentNodeId(null);
			}
		}
	}

	private void evaluateTrigger(AutomationGraph g, long nowTick) {
		AutomationTrigger trigger = g.trigger();
		switch (trigger.kind()) {
			case MANUAL -> {
				// fires only through fire()
			}
			case TIME -> {
				long day = probe.dayTime();
				long today = Math.floorDiv(day, 24000L);
				long tod = Math.floorMod(day, 24000L);
				boolean inWindow = tod >= trigger.timeOfDayTicks()
					&& tod < trigger.timeOfDayTicks() + trigger.windowTicks();
				if (inWindow && g.lastFiredDay() < today && conditionsHold(g)) {
					g.setLastFiredDay(today);
					startFiring(g, nowTick);
				}
			}
			case INTERVAL -> {
				long base = g.lastFiredTick() == Long.MIN_VALUE
					? g.createdAtTick() : g.lastFiredTick();
				if (nowTick - base >= trigger.intervalTicks() && conditionsHold(g)) {
					startFiring(g, nowTick);
				}
			}
			case OWNER_ONLINE -> {
				boolean online = probe.ownerOnline(g.ownerId());
				boolean wasOnline = g.ownerWasOnline();
				g.setOwnerWasOnline(online);
				if (online && !wasOnline && conditionsHold(g)) {
					startFiring(g, nowTick);
				}
			}
			case PLAYER_ENTER_REGION -> {
				Set<UUID> inside = probe.playersInRegion(trigger.dimension(),
					trigger.region());
				if (g.regionPrimed()) {
					for (UUID entrant : inside) {
						if (!g.seenPlayers().contains(entrant) && conditionsHold(g)) {
							startFiring(g, nowTick);
							break;
						}
					}
				}
				g.setRegionPrimed(true);
				g.seenPlayers().clear();
				g.seenPlayers().addAll(inside);
			}
			case TASK_EVENT -> {
				for (Task done : scheduler.finishedTasks()) {
					UUID tid = done.taskId();
					if (containsTaskEvent(g, tid)) {
						continue;
					}
					rememberTaskEvent(g, tid);
					String wanted = trigger.taskEventType();
					boolean matches = wanted.isEmpty() || done.type().equals(wanted);
					if (matches && conditionsHold(g)) {
						startFiring(g, nowTick);
						break;
					}
				}
			}
		}
	}

	private static boolean containsTaskEvent(AutomationGraph g, UUID taskId) {
		for (UUID seen : g.recentTaskEvents()) {
			if (seen.equals(taskId)) {
				return true;
			}
		}
		return false;
	}

	private static void rememberTaskEvent(AutomationGraph g, UUID taskId) {
		while (g.recentTaskEvents().size() >= TASK_DEDUPE_CAPACITY) {
			g.recentTaskEvents().pollFirst();
		}
		g.recentTaskEvents().addLast(taskId);
	}

	private boolean conditionsHold(AutomationGraph g) {
		for (AutomationCondition c : g.conditions()) {
			if (!evaluateCondition(c, g)) {
				return false;
			}
		}
		return true;
	}

	private boolean evaluateCondition(AutomationCondition c, AutomationGraph g) {
		return switch (c.op()) {
			case TIME_OF_DAY_AFTER -> Math.floorMod(probe.dayTime(), 24000L) >= c.dayTick();
			case TIME_OF_DAY_BEFORE -> Math.floorMod(probe.dayTime(), 24000L) < c.dayTick();
			case OWNER_ONLINE -> probe.ownerOnline(g.ownerId());
			case PLAYER_IN_REGION -> !probe.playersInRegion(c.dimension(), c.region())
				.isEmpty();
		};
	}

	private void startFiring(AutomationGraph g, long nowTick) {
		g.setLastFiredTick(nowTick);
		g.setCurrentNodeId(g.entryNode().id());
		audit.record(nowTick, "automation",
			g.name() + "/fire:" + g.trigger(), true, "OK");
	}

	/** Executes exactly ONE node of the graph per tick. */
	private void step(AutomationGraph g, long nowTick) {
		if (g.waitUntilTick() > nowTick) {
			return; // parked inside a WAIT node
		}
		if (g.bumpSteps() > RUNAWAY_LIMIT) {
			fail(g, nowTick, "runaway", "RUNAWAY");
			return;
		}
		AutomationNode node = g.node(g.currentNodeId());
		if (node == null) {
			g.setCurrentNodeId(null);
			return;
		}
		switch (node.kind()) {
			case TOOL_CALL -> {
				ToolResult result = dispatcher.dispatch(node.text(), node.arguments(),
					g.ownerId(), g.agentId());
				boolean failed = result.errorOrNull().isPresent();
				audit.record(nowTick, "automation",
					g.name() + "->" + node.text(), !failed,
					failed ? result.errorOrNull().get().code() + ""
						: result.status().name());
				if (failed) {
					fail(g, nowTick, "tool call failed", result.errorOrNull().get()
						.code() + "");
					return;
				}
				g.setCurrentNodeId(g.successorOf(node.id(), false));
			}
			case CREATE_TASK -> {
				String type = node.text();
				if (!scheduler.hasExecutor(type)) {
					fail(g, nowTick, "no executor for task type", "NO_EXECUTOR");
					return;
				}
				Task task = new Task(g.agentId(), g.ownerId(), type,
					TaskPriority.P3_USER_TASK, "automation:" + g.name(), null, null,
					1200L, RetryPolicy.NONE, true, g.policySnapshot(), node.arguments());
				scheduler.submit(task, nowTick);
				audit.record(nowTick, "automation",
					g.name() + "->task:" + type, true, "SUBMITTED");
				g.setCurrentNodeId(g.successorOf(node.id(), false));
			}
			case BRANCH -> {
				int idx = node.conditionIndex();
				boolean verdict = idx >= 0 && idx < g.conditions().size()
					&& evaluateCondition(g.conditions().get(idx), g);
				g.setCurrentNodeId(g.successorOf(node.id(), verdict));
			}
			case WAIT -> {
				g.setWaitUntilTick(nowTick + node.waitTicks());
				g.setCurrentNodeId(g.successorOf(node.id(), false));
			}
			case NOTIFY -> {
				notifier.send(g.ownerId(), "[squire:" + g.name() + "] " + node.text());
				audit.record(nowTick, "automation", g.name() + "/notify", true, "OK");
				g.setCurrentNodeId(g.successorOf(node.id(), false));
			}
		}
	}

	private void fail(AutomationGraph g, long nowTick, String what, String code) {
		LOG.info("[automation] '{}' {}: {}", g.name(), what, code);
		g.setState(AutomationGraph.State.ERROR);
		g.setErrorCode(code);
		g.setCurrentNodeId(null);
		g.setWaitUntilTick(0);
		audit.record(nowTick, "automation", g.name() + "/" + what, false, code);
		save();
	}

	// ------------------------------------------------------------------ persistence

	/** Writes all graphs atomically (tmp file + move). Never throws. */
	public synchronized void save() {
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", SCHEMA_VERSION);
			root.addProperty("enabled", enabled);
			JsonArray arr = new JsonArray();
			for (AutomationGraph g : graphs.values()) {
				arr.add(g.toJson());
			}
			root.add("automations", arr);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			LOG.warn("[automation] save failed (continuing in memory): {}", e.toString());
		}
	}

	/**
	 * Restart recovery (spec §49): loads persisted graphs. PAUSED stays paused;
	 * ACTIVE graphs resume triggering; region/task dedupe state re-primes so a
	 * restart cannot cause spurious firings.
	 *
	 * @return how many graphs were recovered
	 */
	public synchronized int load(long nowTick) {
		graphs.clear();
		this.loaded = true; // from here on, memory is the authoritative copy
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.exists(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			int version = root.has("version") ? root.get("version").getAsInt() : 1;
			if (version > SCHEMA_VERSION) {
				LOG.error("[automation] schema {} is newer than supported {}; "
					+ "starting empty rather than downgrading data", version,
					SCHEMA_VERSION);
				return 0;
			}
			if (root.has("enabled")) {
				// 服主的开关状态跨重启保留；文件里没有这一项时保持默认关闭
				enabled = root.get("enabled").getAsBoolean();
			}
			int migratedTtl = 0;
			for (JsonElement el : root.getAsJsonArray("automations")) {
				try { // per-entry tolerance: one corrupt row cannot discard the rest
					AutomationGraph g = AutomationGraph.fromJson(el.getAsJsonObject());
					if (g == null) {
						LOG.warn("[automation] skipped corrupt entry during recovery");
						continue;
					}
					long created = g.createdAtTick() > 0 ? g.createdAtTick() : nowTick;
					g.setCreatedAtTick(created);
					g.setRegionPrimed(false);
					// 13.2 一次性迁移：旧版 recurring graph 的隐式两周 TTL 变成无限期
					if (version < 2 && g.isRecurring()
							&& g.ttlTicks() == AutomationGraph.LEGACY_DEFAULT_TTL_TICKS) {
						g.clearLegacyTtl();
						migratedTtl++;
					}
					graphs.put(g.id(), g);
				} catch (RuntimeException bad) {
					LOG.warn("[automation] skipped corrupt entry during recovery: {}",
						bad.toString());
				}
			}
			if (migratedTtl > 0) {
				LOG.info("[automation] migrated {} recurring graph(s) from the legacy "
					+ "two-week TTL to run indefinitely", migratedTtl);
				save();
			}
		} catch (Exception e) {
			LOG.warn("[automation] recovery failed (starting empty): {}", e.toString());
		}
		LOG.info("[automation] recovered {} graphs", graphs.size());
		return graphs.size();
	}
}
