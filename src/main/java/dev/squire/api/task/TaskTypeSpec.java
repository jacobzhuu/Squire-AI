package dev.squire.api.task;

import java.util.List;
import java.util.Objects;

/**
 * Descriptor for one externally-contributed task type. The core instantiates it as
 * a regular scheduler task; the provider's {@link ExternalTaskExecutor} runs when
 * the task starts (server thread) and its result decides completion.
 */
public record TaskTypeSpec(String typeId, List<dev.squire.api.tool.ArgSpec> params,
		long defaultTimeoutTicks, String description) {

	public TaskTypeSpec {
		Objects.requireNonNull(typeId, "typeId");
		if (!typeId.matches("[a-z0-9_][a-z0-9_.-]*:[a-z0-9_][a-z0-9_-]*")) {
			throw new IllegalArgumentException(
				"task type id must be namespaced 'modid:name': " + typeId);
		}
		String namespace = typeId.substring(0, typeId.indexOf(':'));
		if (namespace.equals("minecraft") || namespace.equals("squire")) {
			throw new IllegalArgumentException("namespace is reserved: " + namespace);
		}
		params = params == null ? List.of() : List.copyOf(params);
		if (defaultTimeoutTicks < 20) {
			throw new IllegalArgumentException("defaultTimeoutTicks must be >= 20");
		}
	}
}
