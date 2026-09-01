package dev.squire.common.errors;

/**
 * Unified error codes (spec section 29).
 *
 * <p>Every rejection / failure surfaced to model, task system or audit uses one of these codes.
 * Natural-language strings are never the sole basis for program branching.</p>
 */
public enum ErrorCode {
	// --- arguments / lookup ---
	INVALID_ARGUMENT,
	TOOL_NOT_FOUND,
	TOOL_NOT_VISIBLE,

	// --- authorization ---
	PERMISSION_DENIED,
	POLICY_DENIED,
	CAPABILITY_REQUIRED,
	CAPABILITY_SCOPE_VIOLATION,
	CONFIRMATION_REQUIRED,

	// --- resolution ---
	ENTITY_NOT_FOUND,
	BLOCK_NOT_FOUND,
	ITEM_NOT_FOUND,
	TARGET_CHANGED,
	WORLD_CHANGED,

	// --- navigation ---
	PATH_NOT_FOUND,
	UNREACHABLE,
	AGENT_STUCK,
	/** 目标点落在伙伴的待命区域之外（第 2 期）。不是路不通，是他不能去。 */
	OUT_OF_STAY_AREA,

	// --- resources ---
	INVENTORY_FULL,
	INSUFFICIENT_ITEM,
	NO_RECIPE,
	RESOURCE_EXHAUSTED,
	/** A protection adapter refused the mutation (spec section 60 / plan C5). */
	PROTECTED_REGION,
	/** A required workstation (crafting table / furnace) was not reachable. */
	NO_WORKSTATION,
	/** The referenced block is not a usable container for this operation. */
	NOT_A_CONTAINER,

	// --- limits ---
	TIMEOUT,
	RATE_LIMITED,
	BUDGET_EXCEEDED,
	LOOP_DETECTED,

	// --- conditions ---
	PRECONDITION_FAILED,
	POSTCONDITION_FAILED,

	// --- commands ---
	COMMAND_DENIED,
	COMMAND_PARSE_FAILED,
	COMMAND_FAILED,

	// --- mcp ---
	MCP_UNTRUSTED,
	MCP_TIMEOUT,
	MCP_PROTOCOL_ERROR,
	MCP_RESULT_INVALID,

	// --- model ---
	MALFORMED_MODEL_OUTPUT,
	INTERNAL_ERROR;

	private final String wireName = name();

	/** Stable lowercase dotted form used in ToolResult error payloads and logs. */
	public String wire() {
		return wireName.toLowerCase(java.util.Locale.ROOT);
	}

	public boolean retryable() {
		return switch (this) {
			case PATH_NOT_FOUND, UNREACHABLE, AGENT_STUCK, TARGET_CHANGED, WORLD_CHANGED,
				 TIMEOUT, RATE_LIMITED, INSUFFICIENT_ITEM -> true;
			default -> false;
		};
	}
}
