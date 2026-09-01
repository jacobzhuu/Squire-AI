package dev.squire.api.task;

/**
 * Third-party task contribution (M4): register reusable task types without touching
 * the core task graph. Listed under the {@code "squire"} entrypoint key like tools.
 */
public interface SquireTaskProvider {

	void registerTaskTypes(TaskTypeRegistrar registrar);
}
