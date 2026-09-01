package dev.squire.server.mcp;

/**
 * One tool advertised by an MCP server (spec section 54). Everything here is
 * UNTRUSTED remote input: sizes are capped and shapes sanitized before anything
 * reaches the registry.
 */
public record McpToolInfo(String name, String description, String schemaJson,
		boolean readOnlyHint, boolean destructiveHint) {

	public McpToolInfo {
		name = name == null ? "" : name;
		description = description == null ? "" : description;
		schemaJson = schemaJson == null ? "{}" : schemaJson;
	}
}
