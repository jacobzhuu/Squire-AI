package dev.squire.server.task;

/**
 * A machine-checkable condition over the world (spec sections 18/91).
 * Implemented by the runtime against real world state — never by model output.
 */
public interface TaskCondition {
	/** @return true when the condition currently holds. */
	boolean evaluate(TaskEvaluationContext context);

	/** Human-readable form for logs and clarifying questions. */
	String describe();

	/** Convenience adapter so call sites can use predicate lambdas + a description. */
	static TaskCondition of(java.util.function.Predicate<TaskEvaluationContext> predicate,
			String description) {
		return new TaskCondition() {
			@Override
			public boolean evaluate(TaskEvaluationContext context) {
				return predicate.test(context);
			}

			@Override
			public String describe() {
				return description;
			}
		};
	}
}
