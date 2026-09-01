package dev.squire.server.runtime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;

import dev.squire.common.protocol.ToolCall;
import dev.squire.common.protocol.ToolResult;

/**
 * Small, process-local conversation/task state keyed by Squire UUID.
 *
 * <p>This is deliberately not long-term memory and not a chat transcript. It keeps
 * only the most recent referents and proposed action long enough for phrases such as
 * "传送过去" or "攻击刚才那个" to have a deterministic meaning. All world mutation
 * still goes through the normal tool gateway.</p>
 */
public final class ShortTermConversationStateStore {
	public static final long DEFAULT_PENDING_TTL_TICKS = 600L;
	public static final long DEFAULT_REFERENCE_TTL_TICKS = 1200L;
	private static final int MAX_TOOL_RESULT_PROMPT_CHARS = 2048;
	private static final Gson GSON = new Gson();

	private static final Pattern DESTINATION_AFTER_ACTION = Pattern.compile(
		"(?:到|去|回到|回)\\s*([\\p{L}\\p{N}_:#-]+)");
	private static final Pattern COORDINATES = Pattern.compile(
		"-?\\d+\\s*[,， ]\\s*-?\\d+\\s*[,， ]\\s*-?\\d+");

	public enum TargetKind { LOCATION, ENTITY }
	public enum PendingKind { TELEPORT_TO, NAVIGATE_TO, TRAVEL_CHOICE, ATTACK }
	public enum ResolutionKind { NONE, EXECUTE, ASK }

	public record TargetReference(TargetKind kind, String id, String label,
			long observedTick) { }

	public record LocationReference(String label, String dimension, int x, int y, int z,
			long observedTick) { }

	public record EntityReference(String entityId, String label, long observedTick) { }

	public record ToolResultSnapshot(String toolName, ToolResult.Status status,
			Map<String, Object> data, long observedTick) {
		public ToolResultSnapshot {
			data = data == null ? Map.of() : Map.copyOf(data);
		}
	}

	public record PendingAction(PendingKind kind, LocationReference location,
			EntityReference entity, long createdTick, long expiresTick) {
		boolean expired(long tick) { return tick >= expiresTick; }
	}

	public record Snapshot(TargetReference lastTarget, LocationReference lastLocation,
			EntityReference lastEntity, ToolResultSnapshot lastToolResult,
			PendingAction pendingAction) { }

	/** Result of inspecting one player utterance before ordinary FastPath/LLM routing. */
	public record Resolution(ResolutionKind kind, String toolName,
			Map<String, Object> arguments, LocationReference location, String prompt) {
		public Resolution {
			arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
		}

		static Resolution none() {
			return new Resolution(ResolutionKind.NONE, null, Map.of(), null, null);
		}

		static Resolution ask(String prompt) {
			return new Resolution(ResolutionKind.ASK, null, Map.of(), null, prompt);
		}

		static Resolution execute(String toolName, Map<String, Object> arguments,
				LocationReference location) {
			return new Resolution(ResolutionKind.EXECUTE, toolName, arguments, location, null);
		}
	}

	private static final class MutableState {
		TargetReference lastTarget;
		LocationReference lastLocation;
		EntityReference lastEntity;
		ToolResultSnapshot lastToolResult;
		PendingAction pendingAction;
	}

	private enum RequestedAction { NONE, TELEPORT, NAVIGATE, DEFAULT_TRAVEL, ATTACK }

	private final Map<UUID, MutableState> byAgent = new LinkedHashMap<>();
	private final long pendingTtlTicks;
	private final long referenceTtlTicks;

	public ShortTermConversationStateStore() {
		this(DEFAULT_PENDING_TTL_TICKS, DEFAULT_REFERENCE_TTL_TICKS);
	}

	ShortTermConversationStateStore(long pendingTtlTicks, long referenceTtlTicks) {
		if (pendingTtlTicks <= 0 || referenceTtlTicks < pendingTtlTicks) {
			throw new IllegalArgumentException(
				"reference TTL must be >= a positive pending TTL");
		}
		this.pendingTtlTicks = pendingTtlTicks;
		this.referenceTtlTicks = referenceTtlTicks;
	}

	/** Capture the latest structured tool outcome; older outcomes are never retained. */
	public synchronized void observeToolResult(UUID agentId, ToolCall call,
			ToolResult result, long tick) {
		if (agentId == null || call == null || result == null) return;
		MutableState state = byAgent.computeIfAbsent(agentId, ignored -> new MutableState());
		state.lastToolResult = new ToolResultSnapshot(call.toolName(), result.status(),
			result.data(), tick);

		if (call.toolName().startsWith("world.locate_")) {
			// A new locate query supersedes the old destination even when it finds nothing.
			state.lastLocation = null;
			if (state.lastTarget != null && state.lastTarget.kind() == TargetKind.LOCATION) {
				state.lastTarget = null;
			}
			state.pendingAction = null;
			if (result.status() == ToolResult.Status.SUCCESS
					&& Boolean.TRUE.equals(result.data().get("found"))) {
				LocationReference location = locationFrom(call, result, tick);
				if (location != null) {
					state.lastLocation = location;
					state.lastTarget = new TargetReference(TargetKind.LOCATION,
						location.label(), location.label(), tick);
				}
			}
		}

		if ((result.status() == ToolResult.Status.SUCCESS
				|| result.status() == ToolResult.Status.RUNNING)) {
			String entityId = string(call.arguments().get("entityId"));
			if (entityId == null) entityId = string(result.data().get("entityId"));
			if (entityId != null) {
				EntityReference entity = new EntityReference(entityId, entityId, tick);
				state.lastEntity = entity;
				state.lastTarget = new TargetReference(TargetKind.ENTITY, entityId,
					entity.label(), tick);
			}
		}
	}

	/** Convert an assistant's concrete offer into a short-lived pending action. */
	public synchronized void observeAssistantMessage(UUID agentId, String message,
			long tick) {
		if (agentId == null || message == null || message.isBlank()) return;
		MutableState state = byAgent.get(agentId);
		if (state == null) return;
		prune(state, tick);
		String text = normalize(message);
		boolean prospective = containsAny(text, "要不要", "可以", "是否", "想不想",
			"需要我", "我带你", "我领你", "would you like", "want me to", "shall i");
		if (!prospective) return;

		if (state.lastLocation != null && referenceIsFresh(state.lastLocation, tick)) {
			boolean teleport = containsAny(text, "传送", "送你", "tp", "teleport");
			boolean navigate = containsAny(text, "带你过去", "带你去", "领你", "带路",
				"走过去", "lead you", "take you there", "walk there");
			PendingKind kind = teleport && navigate ? PendingKind.TRAVEL_CHOICE
				: teleport ? PendingKind.TELEPORT_TO
				: navigate ? PendingKind.NAVIGATE_TO : null;
			if (kind != null) {
				state.pendingAction = new PendingAction(kind, state.lastLocation, null,
					tick, tick + pendingTtlTicks);
			}
		}

		if (state.lastEntity != null && entityIsFresh(state.lastEntity, tick)
				&& containsAny(text, "要不要我打", "要我攻击", "帮你打", "attack it")) {
			state.pendingAction = new PendingAction(PendingKind.ATTACK, null,
				state.lastEntity, tick, tick + pendingTtlTicks);
		}
	}

	/**
	 * Resolve only unambiguous short references. Explicit new targets pass through to
	 * the ordinary FastPath/LLM parser after invalidating the stale referent.
	 */
	public synchronized Resolution resolve(UUID agentId, String rawText, long tick) {
		if (agentId == null || rawText == null || rawText.isBlank()) {
			return Resolution.none();
		}
		MutableState state = byAgent.get(agentId);
		String text = normalize(rawText);

		if (hasExplicitReplacement(text)) {
			if (state != null) clearReferents(state);
			return Resolution.none();
		}

		RequestedAction requested = requestedAction(text);
		if (requested == RequestedAction.NONE) return Resolution.none();
		if (state == null) return askFor(requested);
		prune(state, tick);

		return switch (requested) {
			case TELEPORT -> locationForContinuation(state, tick)
				.map(location -> teleport(location, text)).orElseGet(() -> askFor(requested));
			case NAVIGATE -> locationForContinuation(state, tick)
				.map(ShortTermConversationStateStore::navigate)
				.orElseGet(() -> askFor(requested));
			case DEFAULT_TRAVEL -> resolveDefaultTravel(state, tick);
			case ATTACK -> entityForContinuation(state, tick)
				.map(ShortTermConversationStateStore::attack)
				.orElseGet(() -> askFor(requested));
			case NONE -> Resolution.none();
		};
	}

	public synchronized void completePending(UUID agentId) {
		MutableState state = byAgent.get(agentId);
		if (state != null) state.pendingAction = null;
	}

	public synchronized Optional<Snapshot> snapshot(UUID agentId, long tick) {
		MutableState state = byAgent.get(agentId);
		if (state == null) return Optional.empty();
		prune(state, tick);
		return Optional.of(new Snapshot(state.lastTarget, state.lastLocation,
			state.lastEntity, state.lastToolResult, state.pendingAction));
	}

	/** Compact structured state for the next LLM turn; never includes chat history. */
	public synchronized String promptContext(UUID agentId, long tick) {
		Optional<Snapshot> found = snapshot(agentId, tick);
		if (found.isEmpty()) return "";
		Snapshot snapshot = found.get();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("lastTarget", snapshot.lastTarget());
		out.put("lastLocation", snapshot.lastLocation());
		out.put("lastEntity", snapshot.lastEntity());
		ToolResultSnapshot tool = snapshot.lastToolResult();
		if (tool == null) {
			out.put("lastToolResult", null);
		} else {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("tool", tool.toolName());
			result.put("status", tool.status().name());
			String dataJson = GSON.toJson(tool.data());
			result.put("data", dataJson.length() <= MAX_TOOL_RESULT_PROMPT_CHARS
				? tool.data() : Map.of("truncated", true));
			out.put("lastToolResult", result);
		}
		out.put("pendingAction", snapshot.pendingAction());
		return GSON.toJson(out);
	}

	private Resolution resolveDefaultTravel(MutableState state, long tick) {
		PendingAction pending = state.pendingAction;
		if (pending == null || pending.expired(tick)) {
			return Resolution.ask("你说的是哪个地方？请说出地点名或坐标。");
		}
		return switch (pending.kind()) {
			case TELEPORT_TO -> teleport(pending.location(), "");
			case NAVIGATE_TO -> navigate(pending.location());
			case TRAVEL_CHOICE -> Resolution.ask("你是要我带路，还是直接把你传送过去？");
			case ATTACK -> Resolution.ask("你是要去那个地方，还是攻击刚才的目标？");
		};
	}

	private Optional<LocationReference> locationForContinuation(MutableState state,
			long tick) {
		if (state.pendingAction != null && state.pendingAction.location() != null
				&& !state.pendingAction.expired(tick)) {
			return Optional.of(state.pendingAction.location());
		}
		return state.lastLocation != null
			&& tick < state.lastLocation.observedTick() + pendingTtlTicks
			? Optional.of(state.lastLocation) : Optional.empty();
	}

	private Optional<EntityReference> entityForContinuation(MutableState state,
			long tick) {
		if (state.pendingAction != null && state.pendingAction.entity() != null
				&& !state.pendingAction.expired(tick)) {
			return Optional.of(state.pendingAction.entity());
		}
		return state.lastEntity != null
			&& tick < state.lastEntity.observedTick() + pendingTtlTicks
			? Optional.of(state.lastEntity) : Optional.empty();
	}

	private static Resolution teleport(LocationReference location, String text) {
		Map<String, Object> args = locationArguments(location);
		args.put("bringCompanion", containsAny(text, "我们", "一起", "带上你", "都过去"));
		return Resolution.execute("player.teleport", args, location);
	}

	private static Resolution navigate(LocationReference location) {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("x", location.x());
		args.put("y", location.y());
		args.put("z", location.z());
		return Resolution.execute("navigation.move_to", args, location);
	}

	private static Resolution attack(EntityReference entity) {
		return Resolution.execute("combat.attack_target",
			Map.of("entityId", entity.entityId()), null);
	}

	private static Map<String, Object> locationArguments(LocationReference location) {
		Map<String, Object> args = new LinkedHashMap<>();
		if (location.dimension() != null && !location.dimension().isBlank()) {
			args.put("dimension", location.dimension());
		}
		args.put("x", location.x());
		args.put("y", location.y());
		args.put("z", location.z());
		return args;
	}

	private static Resolution askFor(RequestedAction action) {
		return switch (action) {
			case TELEPORT -> Resolution.ask("你想传到哪里？请说出地点名或坐标。");
			case NAVIGATE, DEFAULT_TRAVEL -> Resolution.ask("你想去哪里？请说出地点名或坐标。");
			case ATTACK -> Resolution.ask("你想攻击哪个目标？");
			case NONE -> Resolution.none();
		};
	}

	private static RequestedAction requestedAction(String text) {
		if (containsAny(text, "攻击刚才那个", "打刚才那个", "攻击那个", "打那个",
			"攻击它", "打它", "attack that", "attack it")) {
			return RequestedAction.ATTACK;
		}
		if (containsAny(text, "传送过去", "传我过去", "把我传过去", "送我过去",
			"传到那里", "传送到那里", "tp过去", "teleport me there")) {
			return RequestedAction.TELEPORT;
		}
		if (containsAny(text, "带我过去", "带我去那里", "领我过去", "走过去",
			"take me there", "lead me there")) {
			return RequestedAction.NAVIGATE;
		}
		if (containsAny(text, "就去那里", "去那里", "去那个地方", "去刚才那个地方",
			"go there")) {
			return RequestedAction.DEFAULT_TRAVEL;
		}
		return RequestedAction.NONE;
	}

	private static boolean hasExplicitReplacement(String text) {
		boolean action = containsAny(text, "传送", "传我", "送我", "带我", "tp",
			"teleport", "攻击", "打", "attack");
		if (!action) return false;
		if (COORDINATES.matcher(text).find()
				|| containsAny(text, "主世界", "下界", "末地", "overworld", "nether", "the end")) {
			return true;
		}
		Matcher matcher = DESTINATION_AFTER_ACTION.matcher(text);
		while (matcher.find()) {
			String destination = matcher.group(1);
			if (!isReferentialDestination(destination)) return true;
		}
		// A named entity after an attack verb is also a replacement target.
		if (containsAny(text, "攻击", "打", "attack")) {
			String residue = text.replaceAll(
				"攻击|打|attack|刚才那个|那个|它|一下|帮我|去|吧|[\\s，。！？,.!?]", "");
			return !residue.isBlank();
		}
		return false;
	}

	private static boolean isReferentialDestination(String value) {
		return containsAny(value, "那里", "那儿", "那边", "那个地方", "刚才", "过去",
			"there", "that place");
	}

	private void prune(MutableState state, long tick) {
		if (state.pendingAction != null && state.pendingAction.expired(tick)) {
			state.pendingAction = null;
		}
		if (state.lastLocation != null
				&& tick >= state.lastLocation.observedTick() + referenceTtlTicks) {
			state.lastLocation = null;
			if (state.lastTarget != null && state.lastTarget.kind() == TargetKind.LOCATION) {
				state.lastTarget = null;
			}
		}
		if (state.lastEntity != null
				&& tick >= state.lastEntity.observedTick() + referenceTtlTicks) {
			state.lastEntity = null;
			if (state.lastTarget != null && state.lastTarget.kind() == TargetKind.ENTITY) {
				state.lastTarget = null;
			}
		}
		if (state.lastToolResult != null
				&& tick >= state.lastToolResult.observedTick() + referenceTtlTicks) {
			state.lastToolResult = null;
		}
	}

	private boolean referenceIsFresh(LocationReference location, long tick) {
		return tick < location.observedTick() + referenceTtlTicks;
	}

	private boolean entityIsFresh(EntityReference entity, long tick) {
		return tick < entity.observedTick() + referenceTtlTicks;
	}

	private static void clearReferents(MutableState state) {
		state.lastTarget = null;
		state.lastLocation = null;
		state.lastEntity = null;
		state.lastToolResult = null;
		state.pendingAction = null;
	}

	private static LocationReference locationFrom(ToolCall call, ToolResult result,
			long tick) {
		Integer x = integer(result.data().get("x"));
		Integer y = integer(result.data().get("y"));
		Integer z = integer(result.data().get("z"));
		if (x == null || y == null || z == null) return null;
		String dimension = string(result.data().get("dimension"));
		String label = string(result.data().get("structure"));
		if (label == null) label = string(result.data().get("biome"));
		if (label == null) label = string(call.arguments().get("structure"));
		if (label == null) label = string(call.arguments().get("biome"));
		if (label == null) label = "最近的位置";
		return new LocationReference(label, dimension, x, y, z, tick);
	}

	private static Integer integer(Object value) {
		return value instanceof Number number ? number.intValue() : null;
	}

	private static String string(Object value) {
		if (value == null) return null;
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT).trim();
	}

	private static boolean containsAny(String text, String... values) {
		if (text == null) return false;
		for (String value : values) if (text.contains(value)) return true;
		return false;
	}
}
