package dev.squire.server.project;

import com.google.gson.JsonObject;
import dev.squire.server.blueprint.*;
import dev.squire.server.goal.*;
import dev.squire.server.task.*;
import dev.squire.server.task.executors.RuntimeServices;
import dev.squire.server.world.ProtectionAdapter;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectForceCancelTest {
    @TempDir Path dir;
    private final TaskScheduler scheduler = new TaskScheduler();
    private final RuntimeServices services = new RuntimeServices() {
        public net.minecraft.server.MinecraftServer server() { return null; }
        public dev.squire.server.body.avatar.AvatarEntity avatar(UUID id) { return null; }
        public net.minecraft.server.network.ServerPlayerEntity requester(UUID id) { return null; }
        public long currentTick() { return 100; }
    };

    private ProjectCoordinator coordinator(ProjectStore store) {
        var blueprints = new BlueprintManager(new BlueprintPlacementStore(() -> dir.resolve("placements.json")), () -> null);
        var goals = new GoalCoordinator(null, scheduler, services, null,
            new GoalStateStore(() -> dir.resolve("goals.json")));
        return new ProjectCoordinator(services, scheduler, goals, blueprints, null, store,
            id -> null, id -> null, ProtectionAdapter.ALLOW_ALL);
    }

    private Project broken() {
        var project = new Project(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Broken build", "test", UUID.randomUUID(), "minecraft:overworld", 0,
            ProjectCoordinator.compileStages());
        project.pendingMutation("RECOVERY_GEOMETRY_MISSING");
        project.constructionProgress(new JsonObject());
        project.setState(Project.State.PAUSED);
        project.reserve(Map.of(new Identifier("minecraft:stone"), 12), Map.of());
        return project;
    }

    private Task task(Project project, Map<String,Object> parameters) {
        return new Task(project.agentId(), project.ownerId, "test", TaskPriority.P3_USER_TASK,
            "work", null, null, 100, RetryPolicy.DEFAULT, true, "test", parameters);
    }

    @Test void missingGeometryCanBeAbandonedWithoutRefundAndNeverResurrectsOnReload() {
        var store = new ProjectStore(() -> dir.resolve("projects.json"));
        var coordinator = coordinator(store);
        var project = broken();
        coordinator.put(project);
        var own = task(project, Map.of("projectId", project.projectId.toString()));
        var unrelated = task(project, Map.of("projectId", UUID.randomUUID().toString()));
        scheduler.submit(own, 0);
        scheduler.submit(unrelated, 0);
        assertTrue(coordinator.forceCancel(project));
        assertEquals(TaskState.CANCELLED, own.state());
        assertTrue(unrelated.state().isActive());
        assertTrue(coordinator.activeOf(project.ownerId).isEmpty());
        assertEquals(12, project.reservedCount(new Identifier("minecraft:stone")));
        assertEquals("RECOVERY_GEOMETRY_MISSING", project.pendingMutation());
        coordinator.resume(project);
        coordinator.pause(project);
        assertEquals(Project.State.FORCE_CANCELLED, project.state());

        var restarted = coordinator(new ProjectStore(() -> dir.resolve("projects.json")));
        restarted.load();
        restarted.tick(100);
        var archived = restarted.project(project.projectId).orElseThrow();
        assertEquals(Project.State.FORCE_CANCELLED, archived.state());
        assertEquals(project.ownerSupply(), archived.ownerSupply());
        assertTrue(archived.forceCancelledTaskIds().contains(own.taskId()));
        assertTrue(restarted.activeOf(project.ownerId).isEmpty());
        assertFalse(restarted.beginMutation(project.projectId, "refund"));
        assertFalse(restarted.completeMutation(project.projectId));
        var next = new Project(UUID.randomUUID(), project.ownerId, project.agentId(), "New build",
            "test", UUID.randomUUID(), "minecraft:overworld", 101, ProjectCoordinator.compileStages());
        restarted.put(next);
        assertEquals(next, restarted.activeOf(project.ownerId).orElseThrow());
        assertTrue(restarted.forceCancel(archived));
        assertEquals(next, restarted.activeOf(project.ownerId).orElseThrow());
    }

    @Test void failedPersistenceDoesNotReportCancellationOrReleaseTheProject() {
        var coordinator = coordinator(new ProjectStore(() -> null));
        var project = broken();
        coordinator.put(project);
        assertFalse(coordinator.forceCancel(project));
        assertEquals(Project.State.PAUSED, project.state());
        assertTrue(coordinator.activeOf(project.ownerId).isPresent());
        assertEquals(12, project.reservedCount(new Identifier("minecraft:stone")));
    }

    @Test void lateRecoveredTasksAreStoppedButOtherProjectsSurvive() {
        var coordinator = coordinator(new ProjectStore(() -> dir.resolve("projects.json")));
        var project = broken();
        coordinator.put(project);
        assertTrue(coordinator.forceCancel(project));
        var late = task(project, Map.of("placementId", project.placementId.toString()));
        var other = task(project, Map.of());
        scheduler.submit(late, 0);
        scheduler.submit(other, 0);
        coordinator.reconcileForceCancelled();
        assertEquals(TaskState.CANCELLED, late.state());
        assertTrue(other.state().isActive());
    }

    @Test void cancellationStopsRunningExecutorAndGoalAndRetainsEmptyEscrowTombstone() {
        var store = new ProjectStore(() -> dir.resolve("projects.json"));
        var coordinator = coordinator(store);
        var project = broken();
        project.clearSupply();
        project.pendingMutation("");
        coordinator.put(project);
        boolean[] stopped = {false};
        scheduler.register(new TaskExecutor() {
            public String type() { return "test"; }
            public void start(Task task) { }
            public StepOutcome tick(Task task, long tick) { return StepOutcome.CONTINUE; }
            public void cancel(Task task) { stopped[0] = true; }
        });
        var work = task(project, Map.of("projectId", project.projectId.toString()));
        scheduler.submit(work, 0);
        scheduler.tick(0, id -> new TaskEvaluationContext() {
            public long tick() { return 0; }
            public UUID agentId() { return id; }
            public dev.squire.api.body.AgentBody body() { return null; }
        });
        assertTrue(scheduler.current(project.agentId()).isPresent());
        assertTrue(coordinator.forceCancel(project));
        assertTrue(stopped[0]);
        assertTrue(scheduler.current(project.agentId()).isEmpty());
        coordinator.remove(project.projectId);
        assertTrue(coordinator.project(project.projectId).isPresent());
        var restarted = coordinator(new ProjectStore(() -> dir.resolve("projects.json")));
        restarted.load();
        assertEquals(Project.State.FORCE_CANCELLED, restarted.project(project.projectId).orElseThrow().state());
        assertTrue(restarted.activeOf(project.ownerId).isEmpty());
    }
}
