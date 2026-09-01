package dev.squire.server.task;

import java.util.Optional;

/**
 * The ONLY component that may take a task VERIFYING → COMPLETED (ADR-013,
 * spec section 19). Success is measured against real world state through
 * {@link TaskCondition}s — model replies can never influence the verdict.
 */
public final class GoalVerifier {

	public enum Verdict { MET, NOT_MET_YET, FAILED }

	public Verdict verify(Task task, TaskEvaluationContext context) {
		if (task.state() != TaskState.VERIFYING) {
			throw new IllegalStateException("verify requires VERIFYING, was " + task.state());
		}
		boolean met;
		try {
			met = context != null && task.successCondition() != null
				&& task.successCondition().evaluate(context);
		} catch (RuntimeException e) {
			return Verdict.FAILED;
		}
		if (met) {
			task.completeVerified();
			return Verdict.MET;
		}
		return Verdict.NOT_MET_YET;
	}
}
