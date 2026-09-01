package dev.squire.api.tool;

import java.util.Map;
import java.util.UUID;

/**
 * Plain-data outcome of one external tool execution (mirrors the wire protocol's
 * ToolResult without leaking protocol types into the api layer).
 */
public record ExternalToolResult(UUID callId, Status status, Map<String, Object> data,
		String errorCode, String message) {

	public enum Status { SUCCESS, PARTIAL, RUNNING, FAILED }

	public ExternalToolResult {
		data = data == null ? Map.of() : Map.copyOf(data);
	}

	public static ExternalToolResult ok(UUID callId) {
		return new ExternalToolResult(callId, Status.SUCCESS, Map.of(), null, null);
	}

	public static ExternalToolResult ok(UUID callId, Map<String, Object> data) {
		return new ExternalToolResult(callId, Status.SUCCESS, data, null, null);
	}

	public static ExternalToolResult partial(UUID callId, Map<String, Object> data,
			String message) {
		return new ExternalToolResult(callId, Status.PARTIAL, data, null, message);
	}

	public static ExternalToolResult running(UUID callId, Map<String, Object> data) {
		return new ExternalToolResult(callId, Status.RUNNING, data, null, null);
	}

	public static ExternalToolResult failed(UUID callId, String errorCode,
			String message) {
		return new ExternalToolResult(callId, Status.FAILED, Map.of(),
			errorCode == null ? "EXTERNAL_TOOL_FAILED" : errorCode, message);
	}

	public boolean isSuccess() {
		return status == Status.SUCCESS || status == Status.PARTIAL;
	}
}
