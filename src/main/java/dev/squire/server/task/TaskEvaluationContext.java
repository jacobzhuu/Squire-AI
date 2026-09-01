package dev.squire.server.task;

import java.util.UUID;

import dev.squire.api.body.AgentBody;

/**
 * Read access to whatever a {@link TaskCondition} may check: the agent's body interface
 * plus the tick. Deliberately narrow so conditions cannot mutate the world.
 */
public interface TaskEvaluationContext {
	long tick();

	UUID agentId();

	AgentBody body();
}
