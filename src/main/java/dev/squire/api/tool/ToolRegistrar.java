package dev.squire.api.tool;

/** Registration callback handed to {@link SquireToolProvider} implementations. */
public interface ToolRegistrar {

	/**
	 * Register one tool. The core validates the id/schema and may REFUSE the import
	 * (duplicate, reserved namespace, oversized schema) — refusals are reported to the
	 * server log and surfaced by the Tool Inspector, never thrown into the provider.
	 */
	void register(ExternalTool tool);
}
