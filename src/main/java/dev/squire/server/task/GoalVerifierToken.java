package dev.squire.server.task;

/**
 * Capability token proving the holder is the Goal Verifier (ADR-013): the ONLY caller
 * allowed to take {@code VERIFYING → COMPLETED}. Cannot be constructed outside this
 * package, so model output or upper layers can never declare a task done.
 */
public final class GoalVerifierToken {
	private static final GoalVerifierToken INSTANCE = new GoalVerifierToken();

	private GoalVerifierToken() {
	}

	static GoalVerifierToken instance() {
		return INSTANCE;
	}
}
