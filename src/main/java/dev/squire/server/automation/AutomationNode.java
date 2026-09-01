package dev.squire.server.automation;

import java.util.Map;
import java.util.UUID;

/**
 * One step inside an {@link AutomationGraph} (spec section 49). TOOL_CALL steps go
 * through the Tool Gateway like every other dispatch — automation is a caller, not
 * a bypass. Branch edges are resolved by the graph's edge list.
 */
public final class AutomationNode {
	public enum Kind { TOOL_CALL, CREATE_TASK, BRANCH, WAIT, NOTIFY }

	private final UUID id;
	private final Kind kind;
	/** TOOL_CALL: registry tool name; CREATE_TASK: task type; NOTIFY: message text. */
	private final String text;
	/** TOOL_CALL / CREATE_TASK arguments. */
	private final Map<String, Object> arguments;
	/** BRANCH: index into the graph's condition list. */
	private final int conditionIndex;
	/** WAIT: ticks to pause the cursor. */
	private final long waitTicks;

	private AutomationNode(UUID id, Kind kind, String text,
			Map<String, Object> arguments, int conditionIndex, long waitTicks) {
		this.id = id;
		this.kind = kind;
		this.text = text;
		this.arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
		this.conditionIndex = conditionIndex;
		this.waitTicks = waitTicks;
	}

	public static AutomationNode toolCall(String toolName, Map<String, Object> args) {
		requireText(toolName);
		return new AutomationNode(UUID.randomUUID(), Kind.TOOL_CALL, toolName, args, -1, 0);
	}

	public static AutomationNode createTask(String taskType, Map<String, Object> params) {
		requireText(taskType);
		return new AutomationNode(UUID.randomUUID(), Kind.CREATE_TASK, taskType, params,
			-1, 0);
	}

	public static AutomationNode branch(int conditionIndex) {
		if (conditionIndex < 0) {
			throw new IllegalArgumentException("branch needs a condition index");
		}
		return new AutomationNode(UUID.randomUUID(), Kind.BRANCH, "", Map.of(),
			conditionIndex, 0);
	}

	public static AutomationNode waitTicks(long ticks) {
		if (ticks < 1 || ticks > 2_400_000) {
			throw new IllegalArgumentException("wait outside 1..2_400_000 ticks");
		}
		return new AutomationNode(UUID.randomUUID(), Kind.WAIT, "", Map.of(), -1, ticks);
	}

	public static AutomationNode notify(String message) {
		requireText(message);
		return new AutomationNode(UUID.randomUUID(), Kind.NOTIFY, message, Map.of(), -1, 0);
	}

	private static void requireText(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("node text required");
		}
	}

	/** Package-private rebuild with a preserved id (serialization round-trip). */
	static AutomationNode restore(UUID id, Kind kind, String text,
			Map<String, Object> arguments, int conditionIndex, long waitTicks) {
		return new AutomationNode(id, kind, text, arguments, conditionIndex, waitTicks);
	}

	public UUID id() {
		return id;
	}

	public Kind kind() {
		return kind;
	}

	public String text() {
		return text;
	}

	public Map<String, Object> arguments() {
		return arguments;
	}

	public int conditionIndex() {
		return conditionIndex;
	}

	public long waitTicks() {
		return waitTicks;
	}
}
