package dev.squire.server.task;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulerRecoveryTest {

	private static final TaskEvaluationContext CONTEXT = new TaskEvaluationContext() {
		@Override public long tick() { return 0; }
		@Override public UUID agentId() { return new UUID(0, 0); }
		@Override public dev.squire.api.body.AgentBody body() { return null; }
	};

	@Test
	void restartKeepsTaskIdDependenciesAndMachineVerifier() {
		TaskScheduler scheduler = new TaskScheduler();
		scheduler.register(new CompletingExecutor("recoverable"));
		UUID owner = UUID.randomUUID();
		UUID agent = UUID.randomUUID();
		UUID parentId = UUID.randomUUID();
		UUID childId = UUID.randomUUID();

		TaskStateStore.Snapshot parent = snapshot(parentId, agent, owner, List.of());
		TaskStateStore.Snapshot child = snapshot(childId, agent, owner,
			List.of(parentId.toString()));
		int restored = scheduler.restoreFromSnapshots(List.of(parent, child), 100,
			s -> { throw new AssertionError("unexpected unrecoverable " + s.taskId()); });

		assertEquals(2, restored);
		// Parent starts, verifies, then the child is allowed to start under the same IDs.
		scheduler.tick(100, ignored -> CONTEXT);
		assertEquals(parentId, scheduler.current(agent).orElseThrow().taskId());
		scheduler.tick(101, ignored -> CONTEXT);
		scheduler.tick(112, ignored -> CONTEXT);
		scheduler.tick(113, ignored -> CONTEXT);
		assertEquals(childId, scheduler.current(agent).orElseThrow().taskId());
	}

	@Test
	void missingRecoveryConditionFailsClosedAndRejectsDependents() {
		TaskScheduler scheduler = new TaskScheduler();
		scheduler.register(new CompletingExecutor("recoverable"));
		scheduler.register(new TaskExecutor() {
			@Override public String type() { return "ambiguous"; }
			@Override public void start(Task task) { }
			@Override public StepOutcome tick(Task task, long tick) { return StepOutcome.CONTINUE; }
			@Override public void cancel(Task task) { }
		});
		UUID owner = UUID.randomUUID();
		UUID agent = UUID.randomUUID();
		UUID ambiguousId = UUID.randomUUID();
		TaskStateStore.Snapshot ambiguous = new TaskStateStore.Snapshot(
			ambiguousId, agent, owner, "ambiguous", "P3_USER_TASK", "RUNNING", 1,
			"ambiguous", Map.of(), List.of(), 0, 500, null, null,
			3, 20, true, "policy");
		TaskStateStore.Snapshot child = snapshot(UUID.randomUUID(), agent, owner,
			List.of(ambiguousId.toString()));
		java.util.Set<UUID> rejected = new java.util.HashSet<>();

		assertEquals(0, scheduler.restoreFromSnapshots(List.of(ambiguous, child), 10,
			s -> rejected.add(s.taskId())));
		assertEquals(2, rejected.size());
		assertEquals(0, scheduler.liveCount());
	}

	@Test
	void cancellingOneParentFailsEveryBranchAndDescendant() {
		TaskScheduler scheduler = new TaskScheduler();
		scheduler.register(new CompletingExecutor("blocking") {
			@Override public StepOutcome tick(Task task, long tick) {
				return StepOutcome.CONTINUE;
			}
		});
		UUID owner = UUID.randomUUID();
		Task parent = task(UUID.randomUUID(), owner, "blocking");
		Task left = task(UUID.randomUUID(), owner, "blocking");
		Task right = task(UUID.randomUUID(), owner, "blocking");
		Task leaf = task(UUID.randomUUID(), owner, "blocking");
		left.dependsOn(parent);
		right.dependsOn(parent);
		leaf.dependsOn(left);
		scheduler.submit(parent, 0);
		scheduler.submit(left, 0);
		scheduler.submit(right, 0);
		scheduler.submit(leaf, 0);
		scheduler.tick(1, ignored -> CONTEXT);

		assertEquals(TaskState.RUNNING, parent.state());
		assertEquals(1, scheduler.cancelAgent(parent.agentId(), "test"));
		assertEquals(TaskState.CANCELLED, parent.state());
		assertEquals(TaskState.FAILED, left.state());
		assertEquals(TaskState.FAILED, right.state());
		assertEquals(TaskState.FAILED, leaf.state());
		assertFalse(scheduler.finishedTasks().stream()
			.filter(t -> t != parent).anyMatch(t -> !"DEPENDENCY_FAILED".equals(
				t.lastErrorCode().orElse(null))));
		assertEquals(0, scheduler.liveCount());
	}

	private static TaskStateStore.Snapshot snapshot(UUID id, UUID agent, UUID owner,
			List<String> dependencies) {
		return new TaskStateStore.Snapshot(id, agent, owner, "recoverable",
			"P3_USER_TASK", "RUNNING", 1, "restored", Map.of(), dependencies,
			0, 500, null, null, 3, 20, true, "policy");
	}

    @Test
    void retriesPreserveTheActualFailureCause() {
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.register(new CompletingExecutor("fails") {
            @Override public StepOutcome tick(Task task, long tick) {
                task.setLastErrorCode("ACCESS_ROUTE_BLOCKED");
                return StepOutcome.FAILED;
            }
        });
        Task work = task(UUID.randomUUID(), UUID.randomUUID(), "fails");
        scheduler.submit(work, 0);
        for (int tick = 1; tick <= 100; tick++) scheduler.tick(tick, ignored -> CONTEXT);
        assertEquals(TaskState.FAILED, work.state());
        assertEquals("ACCESS_ROUTE_BLOCKED", work.lastErrorCode().orElseThrow());
    }

	private static Task task(UUID agent, UUID owner, String type) {
		return new Task(agent, owner, type, TaskPriority.P3_USER_TASK, type, null,
			TaskCondition.of(ctx -> true, "true"), 500, RetryPolicy.DEFAULT, true,
			"test");
	}

	private static class CompletingExecutor implements TaskExecutor {
		private final String type;
		CompletingExecutor(String type) { this.type = type; }
		@Override public String type() { return type; }
		@Override public void start(Task task) { }
		@Override public StepOutcome tick(Task task, long tick) { return StepOutcome.WORK_DONE; }
		@Override public void cancel(Task task) { }
		@Override public TaskCondition recoverySuccessCondition(
				TaskStateStore.Snapshot snapshot) {
			return TaskCondition.of(ctx -> true, "restored verifier");
		}
	}
}
