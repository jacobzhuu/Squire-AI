package dev.squire.server.tool;

/**
 * Coarse capability classes checked at the Gateway (spec section 30 preamble).
 * Fine-grained permission NODES and policies arrive with M3; these constants give
 * tool definitions their stable identity already.
 */
public enum AgentPermission {
	MOVE,
	INVENTORY_READ,
	INVENTORY_WRITE,
	WORLD_BREAK,
	WORLD_PLACE,
	WORLD_EDIT,
	CRAFT,
	COMBAT,
	HEAL,
	TASK_CONTROL,
	COMMAND,
	QUERY
}
