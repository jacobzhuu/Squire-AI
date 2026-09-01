package dev.squire.api.task;

/** Registration callback handed to {@link SquireTaskProvider} implementations. */
public interface TaskTypeRegistrar {

	void register(TaskTypeSpec spec, ExternalTaskExecutor executor);
}
