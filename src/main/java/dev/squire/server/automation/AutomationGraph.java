package dev.squire.server.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One long-term automation owned by a player and executed for their agent
 * (spec section 49): trigger → condition guards → a small node graph walked at
 * ≤1 step/tick. ToolCall nodes dispatch through the Tool Gateway; nothing here
 * ever places command blocks (ADR-009).
 *
 * <p>Serialization is hand-rolled over Gson's tree model so restart recovery is
 * deterministic across refactors.</p>
 */
public final class AutomationGraph {
	public enum State { ACTIVE, PAUSED, EXPIRED, ERROR }

	/** ttlTicks value meaning "runs until the player pauses or removes it". */
	public static final long NO_EXPIRY = 0L;
	/** The pre-G3 implicit default, recognised so old files can be migrated once. */
	public static final long LEGACY_DEFAULT_TTL_TICKS = 14L * 24000;

	/** Directed edge; {@code branchValue} null for unconditional edges. */
	public static final class Edge {
		public final UUID from;
		public final UUID to;
		public final Boolean branchValue;

		public Edge(UUID from, UUID to, Boolean branchValue) {
			this.from = from;
			this.to = to;
			this.branchValue = branchValue;
		}
	}

	private final UUID id;
	private final UUID ownerId;
	private final UUID agentId;
	private final String name;
	private final AutomationTrigger trigger;
	private final List<AutomationCondition> conditions;
	private final LinkedHashMap<UUID, AutomationNode> nodes;
	private final List<Edge> edges;
	private long ttlTicks;
	/** Captured at registration for audit; enforcement always uses CURRENT policy. */
	private String policySnapshot;

	private State state = State.ACTIVE;
	/** Registration tick; the engine stamps it — TTL counts from here. */
	private long createdAtTick;
	// ---- runtime cursor (persisted for restart recovery) ----
	private UUID currentNodeId;
	private long waitUntilTick;
	private long lastFiredTick = Long.MIN_VALUE;
	private long lastFiredDay = Long.MIN_VALUE;
	private boolean ownerWasOnline;
	private String errorCode;
	private final java.util.Set<UUID> seenPlayers = new java.util.HashSet<>();
	private final java.util.ArrayDeque<UUID> recentTaskEvents = new java.util.ArrayDeque<>();
	private int stepsThisFiring;

	private AutomationGraph(UUID id, UUID ownerId, UUID agentId, String name,
			AutomationTrigger trigger, List<AutomationCondition> conditions,
			LinkedHashMap<UUID, AutomationNode> nodes, List<Edge> edges,
			long ttlTicks, String policySnapshot) {
		this.id = id;
		this.ownerId = ownerId;
		this.agentId = agentId;
		this.name = name;
		this.trigger = trigger;
		this.conditions = List.copyOf(conditions);
		this.nodes = nodes;
		this.edges = List.copyOf(edges);
		this.ttlTicks = ttlTicks;
		this.policySnapshot = policySnapshot;
	}

	// ------------------------------------------------------------------ builder

	public static Builder builder(UUID ownerId, UUID agentId, String name) {
		return new Builder(ownerId, agentId, name);
	}

	public static final class Builder {
		private final UUID ownerId;
		private final UUID agentId;
		private final String name;
		private AutomationTrigger trigger;
		private final List<AutomationCondition> conditions = new ArrayList<>();
		private final LinkedHashMap<UUID, AutomationNode> nodes = new LinkedHashMap<>();
		private final List<Edge> edges = new ArrayList<>();
		/**
		 * 方案 G3：recurring 自动化默认无限期。旧版默认两周后静默过期，玩家说的
		 * "每天晚上开灯"会在没有任何提示的情况下停掉——那不是有效期，那是 bug。
		 * 一次性自动化仍可显式设置 TTL。
		 */
		private long ttlTicks = NO_EXPIRY;

		private Builder(UUID ownerId, UUID agentId, String name) {
			if (ownerId == null || agentId == null || name == null || name.isBlank()) {
				throw new IllegalArgumentException("owner/agent/name required");
			}
			this.ownerId = ownerId;
			this.agentId = agentId;
			this.name = name.trim();
		}

		public Builder trigger(AutomationTrigger value) {
			this.trigger = value;
			return this;
		}

		public Builder condition(AutomationCondition value) {
			this.conditions.add(value);
			return this;
		}

		/** Adds a node; returns the node id for edge wiring. */
		public UUID node(AutomationNode value) {
			nodes.put(value.id(), value);
			return value.id();
		}

		public Builder edge(UUID from, UUID to) {
			edges.add(new Edge(from, to, null));
			return this;
		}

		public Builder edgeBranchTrue(UUID from, UUID to) {
			edges.add(new Edge(from, to, Boolean.TRUE));
			return this;
		}

		public Builder edgeBranchFalse(UUID from, UUID to) {
			edges.add(new Edge(from, to, Boolean.FALSE));
			return this;
		}

		public Builder ttlTicks(long value) {
			this.ttlTicks = value;
			return this;
		}

		public AutomationGraph build() {
			if (trigger == null) {
				throw new IllegalStateException("automation needs a trigger");
			}
			if (nodes.isEmpty()) {
				throw new IllegalStateException("automation needs at least one node");
			}
			for (Edge edge : edges) {
				if (!nodes.containsKey(edge.from) || !nodes.containsKey(edge.to)) {
					throw new IllegalStateException("edge references unknown node");
				}
			}
			for (AutomationNode node : nodes.values()) {
				if (node.kind() != AutomationNode.Kind.BRANCH) {
					continue;
				}
				boolean hasTrue = false;
				boolean hasFalse = false;
				for (Edge edge : edges) {
					if (!edge.from.equals(node.id())) {
						continue;
					}
					hasTrue |= Boolean.TRUE.equals(edge.branchValue);
					hasFalse |= Boolean.FALSE.equals(edge.branchValue);
				}
				if (!hasTrue || !hasFalse) {
					throw new IllegalStateException(
						"branch node needs both true and false edges");
				}
				if (node.conditionIndex() >= conditions.size()) {
					throw new IllegalStateException("branch references unknown condition");
				}
			}
			return new AutomationGraph(UUID.randomUUID(), ownerId, agentId, name,
				trigger, conditions, nodes, edges, ttlTicks, "");
		}
	}

	// ------------------------------------------------------------------ queries

	public UUID id() {
		return id;
	}

	public UUID ownerId() {
		return ownerId;
	}

	public UUID agentId() {
		return agentId;
	}

	public String name() {
		return name;
	}

	public AutomationTrigger trigger() {
		return trigger;
	}

	public List<AutomationCondition> conditions() {
		return conditions;
	}

	public List<AutomationNode> nodeList() {
		return new ArrayList<>(nodes.values());
	}

	public AutomationNode node(UUID nodeId) {
		return nodes.get(nodeId);
	}

	public AutomationNode entryNode() {
		return nodes.values().iterator().next();
	}

	public List<Edge> edgeList() {
		return edges;
	}

	public State state() {
		return state;
	}

	void setState(State value) {
		this.state = value;
	}

	public long ttlTicks() {
		return ttlTicks;
	}

	/** True when this graph keeps running until the owner stops it (方案 G3). */
	public boolean runsIndefinitely() {
		return ttlTicks <= NO_EXPIRY;
	}

	/** True for triggers that are meant to repeat forever rather than fire once. */
	public boolean isRecurring() {
		return trigger.kind() == AutomationTrigger.Kind.TIME
			|| trigger.kind() == AutomationTrigger.Kind.INTERVAL
			|| trigger.kind() == AutomationTrigger.Kind.OWNER_ONLINE
			|| trigger.kind() == AutomationTrigger.Kind.PLAYER_ENTER_REGION;
	}

	/** One-time migration hook: drop a legacy implicit TTL (方案 13.2). */
	void clearLegacyTtl() {
		this.ttlTicks = NO_EXPIRY;
	}

	public String policySnapshot() {
		return policySnapshot;
	}

	void setPolicySnapshot(String value) {
		this.policySnapshot = value;
	}

	public long createdAtTick() {
		return createdAtTick;
	}

	void setCreatedAtTick(long value) {
		this.createdAtTick = value;
	}

	public UUID currentNodeId() {
		return currentNodeId;
	}

	void setCurrentNodeId(UUID value) {
		this.currentNodeId = value;
		this.stepsThisFiring = value == null ? 0 : this.stepsThisFiring;
	}

	long waitUntilTick() {
		return waitUntilTick;
	}

	void setWaitUntilTick(long value) {
		this.waitUntilTick = value;
	}

	long lastFiredTick() {
		return lastFiredTick;
	}

	void setLastFiredTick(long value) {
		this.lastFiredTick = value;
	}

	public long lastFiredDay() {
		return lastFiredDay;
	}

	void setLastFiredDay(long value) {
		this.lastFiredDay = value;
	}

	boolean ownerWasOnline() {
		return ownerWasOnline;
	}

	void setOwnerWasOnline(boolean value) {
		this.ownerWasOnline = value;
	}

	/** Non-persisted: region dedupe re-primes after a restart (no spurious fires). */
	private boolean regionPrimed;

	public boolean regionPrimed() {
		return regionPrimed;
	}

	void setRegionPrimed(boolean value) {
		this.regionPrimed = value;
	}

	String errorCode() {
		return errorCode;
	}

	void setErrorCode(String value) {
		this.errorCode = value;
	}

	java.util.Set<UUID> seenPlayers() {
		return seenPlayers;
	}

	java.util.ArrayDeque<UUID> recentTaskEvents() {
		return recentTaskEvents;
	}

	int bumpSteps() {
		return ++stepsThisFiring;
	}

	/** Unconditional successor of {@code nodeId}, or the branch edge for the verdict. */
	UUID successorOf(UUID nodeId, boolean branchVerdict) {
		for (Edge edge : edges) {
			if (!edge.from.equals(nodeId)) {
				continue;
			}
			if (branchVerdict && Boolean.TRUE.equals(edge.branchValue)) {
				return edge.to;
			}
			if (!branchVerdict && Boolean.FALSE.equals(edge.branchValue)) {
				return edge.to;
			}
			if (edge.branchValue == null) {
				return edge.to;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ persistence

	/** Hand-rolled JSON tree (deterministic field order, tolerant reads). */
	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("id", id.toString());
		root.addProperty("owner", ownerId.toString());
		root.addProperty("agent", agentId.toString());
		root.addProperty("name", name);
		root.add("trigger", toJson(trigger));
		JsonArray conds = new JsonArray();
		for (AutomationCondition c : conditions) {
			conds.add(toJson(c));
		}
		root.add("conditions", conds);
		JsonArray nodeArr = new JsonArray();
		for (AutomationNode n : nodes.values()) {
			nodeArr.add(toJson(n));
		}
		root.add("nodes", nodeArr);
		JsonArray edgeArr = new JsonArray();
		for (Edge e : edges) {
			JsonObject eo = new JsonObject();
			eo.addProperty("from", e.from.toString());
			eo.addProperty("to", e.to.toString());
			if (e.branchValue != null) {
				eo.addProperty("branch", e.branchValue);
			}
			edgeArr.add(eo);
		}
		root.add("edges", edgeArr);
		root.addProperty("ttl", ttlTicks);
		root.addProperty("state", state.name());
		root.addProperty("createdAt", createdAtTick);
		root.addProperty("policy", policySnapshot);
		if (currentNodeId != null) {
			root.addProperty("cursor", currentNodeId.toString());
		}
		root.addProperty("waitUntil", waitUntilTick);
		root.addProperty("lastFired", lastFiredTick);
		root.addProperty("lastDay", lastFiredDay);
		root.addProperty("ownerSeen", ownerWasOnline);
		return root;
	}

	private static JsonObject toJson(AutomationTrigger t) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", t.kind().name());
		if (t.kind() == AutomationTrigger.Kind.TIME) {
			o.addProperty("timeOfDay", t.timeOfDayTicks());
			o.addProperty("window", t.windowTicks());
		} else if (t.kind() == AutomationTrigger.Kind.INTERVAL) {
			o.addProperty("interval", t.intervalTicks());
		} else if (t.kind() == AutomationTrigger.Kind.PLAYER_ENTER_REGION) {
			o.addProperty("dimension", t.dimension());
			o.add("region", regionToJson(t.region()));
		} else if (t.kind() == AutomationTrigger.Kind.TASK_EVENT) {
			o.addProperty("taskType", t.taskEventType());
		}
		return o;
	}

	private static JsonObject toJson(AutomationCondition c) {
		JsonObject o = new JsonObject();
		o.addProperty("op", c.op().name());
		if (c.op() == AutomationCondition.Op.TIME_OF_DAY_AFTER
				|| c.op() == AutomationCondition.Op.TIME_OF_DAY_BEFORE) {
			o.addProperty("dayTick", c.dayTick());
		} else if (c.op() == AutomationCondition.Op.PLAYER_IN_REGION) {
			o.addProperty("dimension", c.dimension());
			o.add("region", regionToJson(c.region()));
		}
		return o;
	}

	private static JsonObject toJson(AutomationNode n) {
		JsonObject o = new JsonObject();
		o.addProperty("id", n.id().toString());
		o.addProperty("kind", n.kind().name());
		o.addProperty("text", n.text());
		JsonObject args = new JsonObject();
		n.arguments().forEach((k, v) -> args.add(k, toJsonValue(v)));
		o.add("args", args);
		o.addProperty("condIndex", n.conditionIndex());
		o.addProperty("wait", n.waitTicks());
		return o;
	}

	private static JsonElement toJsonValue(Object v) {
		if (v instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (v instanceof Number num) {
			return new JsonPrimitive(num);
		}
		return new JsonPrimitive(String.valueOf(v));
	}

	private static JsonObject regionToJson(dev.squire.server.world.BoundedRegion r) {
		JsonObject o = new JsonObject();
		o.addProperty("minX", r.min().getX());
		o.addProperty("minY", r.min().getY());
		o.addProperty("minZ", r.min().getZ());
		o.addProperty("maxX", r.max().getX());
		o.addProperty("maxY", r.max().getY());
		o.addProperty("maxZ", r.max().getZ());
		return o;
	}

	/** Rebuilds a graph from JSON; returns null on structural corruption. */
	public static AutomationGraph fromJson(JsonObject root) {
		try {
			UUID id = UUID.fromString(root.get("id").getAsString());
			UUID owner = UUID.fromString(root.get("owner").getAsString());
			UUID agent = UUID.fromString(root.get("agent").getAsString());
			String name = root.get("name").getAsString();
			AutomationTrigger trigger = triggerFromJson(
				root.getAsJsonObject("trigger"));
			List<AutomationCondition> conditions = new ArrayList<>();
			for (JsonElement ce : root.getAsJsonArray("conditions")) {
				conditions.add(conditionFromJson(ce.getAsJsonObject()));
			}
			LinkedHashMap<UUID, AutomationNode> nodes = new LinkedHashMap<>();
			for (JsonElement ne : root.getAsJsonArray("nodes")) {
				AutomationNode n = nodeFromJson(ne.getAsJsonObject());
				nodes.put(n.id(), n);
			}
			List<Edge> edges = new ArrayList<>();
			for (JsonElement ee : root.getAsJsonArray("edges")) {
				JsonObject eo = ee.getAsJsonObject();
				Boolean branch = eo.has("branch") ? eo.get("branch").getAsBoolean() : null;
				edges.add(new Edge(UUID.fromString(eo.get("from").getAsString()),
					UUID.fromString(eo.get("to").getAsString()), branch));
			}
			long ttl = root.get("ttl").getAsLong();
			AutomationGraph g = new AutomationGraph(id, owner, agent, name, trigger,
				conditions, nodes, edges, ttl,
				root.has("policy") ? root.get("policy").getAsString() : "");
			g.state = State.valueOf(root.get("state").getAsString());
			g.createdAtTick = root.get("createdAt").getAsLong();
			g.currentNodeId = root.has("cursor") && !root.get("cursor").isJsonNull()
				? UUID.fromString(root.get("cursor").getAsString()) : null;
			g.waitUntilTick = root.get("waitUntil").getAsLong();
			g.lastFiredTick = root.get("lastFired").getAsLong();
			g.lastFiredDay = root.get("lastDay").getAsLong();
			g.ownerWasOnline = root.get("ownerSeen").getAsBoolean();
			return g;
		} catch (RuntimeException | LinkageError corrupt) {
			return null;
		}
	}

	private static AutomationTrigger triggerFromJson(JsonObject o) {
		return switch (AutomationTrigger.Kind.valueOf(o.get("kind").getAsString())) {
			case MANUAL -> AutomationTrigger.manual();
			case TIME -> AutomationTrigger.atTimeOfDay(o.get("timeOfDay").getAsLong(),
				o.get("window").getAsLong());
			case INTERVAL -> AutomationTrigger.every(o.get("interval").getAsLong());
			case OWNER_ONLINE -> AutomationTrigger.ownerOnline();
			case PLAYER_ENTER_REGION -> AutomationTrigger.playerEntersRegion(
				o.get("dimension").getAsString(), regionFromJson(o.getAsJsonObject("region")));
			case TASK_EVENT -> AutomationTrigger.taskEvent(
				o.get("taskType").getAsString());
		};
	}

	private static AutomationCondition conditionFromJson(JsonObject o) {
		return switch (AutomationCondition.Op.valueOf(o.get("op").getAsString())) {
			case TIME_OF_DAY_AFTER -> AutomationCondition.timeOfDayAfter(
				o.get("dayTick").getAsLong());
			case TIME_OF_DAY_BEFORE -> AutomationCondition.timeOfDayBefore(
				o.get("dayTick").getAsLong());
			case OWNER_ONLINE -> AutomationCondition.ownerOnline();
			case PLAYER_IN_REGION -> AutomationCondition.playerInRegion(
				o.get("dimension").getAsString(), regionFromJson(o.getAsJsonObject("region")));
		};
	}

	private static AutomationNode nodeFromJson(JsonObject o) {
		Map<String, Object> args = new java.util.LinkedHashMap<>();
		JsonObject ao = o.getAsJsonObject("args");
		for (Map.Entry<String, JsonElement> e : ao.entrySet()) {
			args.put(e.getKey(), jsonToValue(e.getValue()));
		}
		UUID id = UUID.fromString(o.get("id").getAsString());
		String text = o.get("text").getAsString();
		int condIndex = o.get("condIndex").getAsInt();
		long wait = o.get("wait").getAsLong();
		return switch (AutomationNode.Kind.valueOf(o.get("kind").getAsString())) {
			case TOOL_CALL -> AutomationNode.restore(id, AutomationNode.Kind.TOOL_CALL,
				text, args, -1, 0);
			case CREATE_TASK -> AutomationNode.restore(id, AutomationNode.Kind.CREATE_TASK,
				text, args, -1, 0);
			case BRANCH -> AutomationNode.restore(id, AutomationNode.Kind.BRANCH,
				"", Map.of(), condIndex, 0);
			case WAIT -> AutomationNode.restore(id, AutomationNode.Kind.WAIT,
				"", Map.of(), -1, wait);
			case NOTIFY -> AutomationNode.restore(id, AutomationNode.Kind.NOTIFY,
				text, Map.of(), -1, 0);
		};
	}

	private static Object jsonToValue(JsonElement element) {
		JsonPrimitive p = element.getAsJsonPrimitive();
		if (p.isBoolean()) {
			return p.getAsBoolean();
		}
		if (p.isNumber()) {
			double d = p.getAsDouble();
			if (d == Math.rint(d) && !p.isString() && Math.abs(d) < 9.0E15) {
				return (long) d;
			}
			return d;
		}
		return p.getAsString();
	}

	static dev.squire.server.world.BoundedRegion regionFromJson(JsonObject o) {
		return new dev.squire.server.world.BoundedRegion(
			new net.minecraft.util.math.BlockPos(o.get("minX").getAsInt(),
				o.get("minY").getAsInt(), o.get("minZ").getAsInt()),
			new net.minecraft.util.math.BlockPos(o.get("maxX").getAsInt(),
				o.get("maxY").getAsInt(), o.get("maxZ").getAsInt()));
	}
}
