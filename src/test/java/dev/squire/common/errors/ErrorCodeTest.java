package dev.squire.common.errors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the unified error-code contract (spec section 29). */
class ErrorCodeTest {
	@Test
	void allSpecSection29CodesExist() {
		// spot-check every group mandated by the spec; removal breaks API consumers
		String[] required = {
			"INVALID_ARGUMENT", "TOOL_NOT_FOUND", "TOOL_NOT_VISIBLE",
			"PERMISSION_DENIED", "POLICY_DENIED", "CAPABILITY_REQUIRED", "CAPABILITY_SCOPE_VIOLATION",
			"CONFIRMATION_REQUIRED", "ENTITY_NOT_FOUND", "BLOCK_NOT_FOUND", "ITEM_NOT_FOUND",
			"TARGET_CHANGED", "WORLD_CHANGED", "PATH_NOT_FOUND", "UNREACHABLE", "AGENT_STUCK",
			"INVENTORY_FULL", "INSUFFICIENT_ITEM", "NO_RECIPE", "RESOURCE_EXHAUSTED", "TIMEOUT",
			"RATE_LIMITED", "BUDGET_EXCEEDED", "LOOP_DETECTED", "PRECONDITION_FAILED",
			"POSTCONDITION_FAILED", "COMMAND_DENIED", "COMMAND_PARSE_FAILED", "COMMAND_FAILED",
			"MCP_UNTRUSTED", "MCP_TIMEOUT", "MCP_PROTOCOL_ERROR", "MCP_RESULT_INVALID",
			"MALFORMED_MODEL_OUTPUT", "INTERNAL_ERROR"
		};
		for (String code : required) {
			assertTrue(hasCode(code), "missing error code: " + code);
		}
	}

	@Test
	void wireFormIsStableLowercaseDotted() {
		assertEquals("permission_denied", ErrorCode.PERMISSION_DENIED.wire());
		assertEquals("malformed_model_output", ErrorCode.MALFORMED_MODEL_OUTPUT.wire());
		assertNotEquals(ErrorCode.PERMISSION_DENIED.wire(), ErrorCode.POLICY_DENIED.wire());
	}

	@Test
	void permissionCodesAreNeverMarkedRetryable() {
		// retrying an authorization refusal must not succeed - security property
		assertFalse(ErrorCode.PERMISSION_DENIED.retryable());
		assertFalse(ErrorCode.POLICY_DENIED.retryable());
		assertFalse(ErrorCode.CAPABILITY_SCOPE_VIOLATION.retryable());
		assertTrue(ErrorCode.PATH_NOT_FOUND.retryable());
	}

	private boolean hasCode(String name) {
		for (ErrorCode code : ErrorCode.values()) {
			if (code.name().equals(name)) {
				return true;
			}
		}
		return false;
	}
}
