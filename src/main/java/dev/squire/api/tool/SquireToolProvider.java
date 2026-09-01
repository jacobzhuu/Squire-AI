package dev.squire.api.tool;

/**
 * Third-party entrypoint (spec section 58): implement this and list your class in
 * {@code fabric.mod.json} under the {@code "squire"} entrypoint key. The core calls
 * it once per server start — WITHOUT any modification to Squire itself.
 */
public interface SquireToolProvider {

	void registerTools(ToolRegistrar registrar);
}
