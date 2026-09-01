package dev.squire.server.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.squire.server.task.CraftPlanner.Step;
import dev.squire.server.task.executors.CraftRecipeExecutor;
import dev.squire.server.task.executors.GatherBlockExecutor;
import dev.squire.server.task.executors.RuntimeServices;
import dev.squire.server.task.executors.SmeltTaskExecutor;

/**
 * Turns a model-expressed goal ("acquire N of X") into a bounded task DAG (spec
 * sections 24/36): plan with {@link CraftPlanner}, materialize one Task per step,
 * chain them with dependencies, submit to the scheduler. The LLM never touches
 * tasks directly and never marks them done — verification stays runtime-owned.
 */
public final class TaskCompiler {

	private final dev.squire.server.task.executors.RuntimeServices services;
	private final TaskScheduler scheduler;

	public TaskCompiler(dev.squire.server.task.executors.RuntimeServices services,
			TaskScheduler scheduler) {
		this.services = services;
		this.scheduler = scheduler;
	}

	/**
	 * Compile "acquire {@code count} × {@code itemId}" into ordered tasks.
	 *
	 * @return the submitted tasks in execution order (empty = already satisfied)
	 */
	public List<Task> compileAcquire(UUID agentId, UUID requesterId, String itemId, int count) {
		dev.squire.server.body.avatar.AvatarEntity avatar = services.avatar(agentId);
		if (avatar == null) {
			throw new IllegalStateException("no live avatar for agent");
		}
		Map<String, Integer> inventory = new HashMap<>();
		for (String id : avatar.inventory().distinctItemIds()) {
			inventory.put(id, avatar.inventory().countOf(id));
		}
		List<Step> steps = CraftPlanner.plan(itemId, count, inventory);

		List<Task> tasks = new ArrayList<>();
		Task previous = null;
		for (Step step : steps) {
			int baseline = inventory.getOrDefault(step.itemId(), 0);
			Task task = switch (step.kind()) {
				case GATHER -> new Task(agentId, requesterId, GatherBlockExecutor.TYPE,
					TaskPriority.P3_USER_TASK,
					"gather " + step.count() + " " + step.itemId(),
					null, GatherBlockExecutor.hasItems(step.itemId(), baseline + step.count()),
					gatherTimeout(step.count()), RetryPolicy.DEFAULT, true, "m2",
					Map.of("blockId", step.blockId(), "itemId", step.itemId(),
						"count", step.count(), "targetCount", baseline + step.count()));
				case CRAFT -> new Task(agentId, requesterId, CraftRecipeExecutor.TYPE,
					TaskPriority.P3_USER_TASK,
					"craft up to " + step.count() + " " + step.itemId(),
					null, CraftRecipeExecutor.hasProduced(step.itemId(), baseline + step.count()),
					200L + 100L * step.count(), RetryPolicy.DEFAULT, true, "m2",
					Map.of("itemId", step.itemId(), "count", step.count(),
						"targetCount", baseline + step.count()));
				case SMELT -> new Task(agentId, requesterId, SmeltTaskExecutor.TYPE,
					TaskPriority.P3_USER_TASK,
					"smelt " + step.count() + " " + step.itemId(),
					null, SmeltTaskExecutor.hasProduced(step.itemId(), baseline + step.count()),
					smeltTimeout(step.count()), RetryPolicy.DEFAULT, true, "m2",
					Map.of("itemId", step.itemId(), "count", step.count(),
						"targetCount", baseline + step.count()));
			};
			if (previous != null) {
				task.dependsOn(previous);
			}
			scheduler.submit(task, services.currentTick());
			tasks.add(task);
			previous = task;
		}
		return tasks;
	}

	private static long gatherTimeout(int count) {
		return 600L + 400L * count; // navigation dominates; generous but bounded
	}

	/**
	 * A REAL furnace cooks one item in 200 ticks (方案 C4), so the old
	 * {@code 200 + 100·count} budget timed a three-ingot smelt out before the first
	 * ingot existed. Budget the true cook time plus walking to the furnace.
	 */
	private static long smeltTimeout(int count) {
		return 600L + 260L * count;
	}
}
