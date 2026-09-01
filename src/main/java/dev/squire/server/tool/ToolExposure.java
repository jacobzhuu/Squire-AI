package dev.squire.server.tool;

/** Who may see and call a tool (spec section 25). */
public enum ToolExposure {
	MODEL_PUBLIC,
	PLANNER_INTERNAL,
	RUNTIME_ONLY,
	ADMIN_ONLY;

	public boolean visibleToModel() {
		return this == MODEL_PUBLIC;
	}
}
