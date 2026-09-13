package dev.squire.server.task.executors;

import java.util.List;
import java.util.UUID;
import dev.squire.gametest.M0SpikeGameTests;
import dev.squire.server.blueprint.ConstructionAccessPlan;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.registry.SquireEntities;
import dev.squire.server.task.*;
import dev.squire.server.world.BoundedRegion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityPose;
import net.minecraft.test.*;
import net.minecraft.util.math.*;

/** Actual entity/controller checks; ground-gait mathematics and model bones also have JUnit coverage. */
public final class EngineerLocomotionGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void directTravelTurnsSmoothlyAndPreservesVerticalMotion(TestContext c) {
        var avatar = SquireEntities.AVATAR.create(c.getWorld());
        avatar.setYaw(0); avatar.setVelocity(0, .15, 0);
        EngineerMovement.directStep(avatar, 2, 0, 1);
        c.assertTrue(Math.abs(avatar.getYaw() + 18) < .01 && avatar.bodyYaw == avatar.getYaw(), "first turn is bounded, body follows");
        c.assertTrue(Math.abs(avatar.getVelocity().x - .1) < .001 && Math.abs(avatar.getVelocity().y - .15) < .001,
            "horizontal step preserves scaffold/jump vertical speed");
        for (int i = 0; i < 5; i++) { EngineerMovement.directStep(avatar, 2, 0, 1); avatar.getLookControl().tick(); }
        c.assertTrue(Math.abs(avatar.getYaw() + 90) < .01, "eventually faces east, not permanent strafing");
        c.assertTrue(Math.abs(MathHelper.wrapDegrees(avatar.headYaw - avatar.getYaw())) < 20, "head follows travel");
        float yaw = avatar.getYaw(); EngineerMovement.directStep(avatar, 0, 0, 1);
        c.assertTrue(avatar.getYaw() == yaw && avatar.getVelocity().horizontalLengthSquared() == 0, "stopped step does not spin or drift");
        avatar.discard(); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 70)
    public void reviewedEdgeWalksAndTurnsWithoutTeleportFallback(TestContext c) {
        AvatarEntity avatar = c.spawnEntity(SquireEntities.AVATAR, new BlockPos(3, 2, 3));
        BlockPos start = c.getAbsolutePos(new BlockPos(3, 2, 3)), target = start.east(2);
        avatar.refreshPositionAndAngles(start.getX() + .5, start.getY(), start.getZ() + .5, 90, 0);
        avatar.setOnGround(true); avatar.attachBusyCheck(() -> true);
        var progress = new AccessBuildExecutor.Progress(UUID.randomUUID(), UUID.randomUUID(), null);
        progress.program = new ConstructionAccessPlan(List.of(),
            new BoundedRegion(start.add(-2, -1, -2), start.add(4, 3, 3)), "", start);
        var task = new Task(UUID.randomUUID(), UUID.randomUUID(), BlueprintBuildExecutor.TYPE, TaskPriority.P3_USER_TASK,
            "locomotion-test", null, null, 1000, RetryPolicy.DEFAULT, true, "test");
        for (int i = 1; i <= 45; i++) {
            final int tick = i;
            c.runAtTick(i, () -> AccessBuildExecutor.move(avatar, task, progress, target, tick));
        }
        c.runAtTick(46, () -> {
            c.assertTrue(avatar.squaredDistanceTo(Vec3d.ofBottomCenter(target)) < .15, "reviewed floor edge really walked to target");
            c.assertTrue(Math.abs(MathHelper.wrapDegrees(avatar.getYaw() + 90)) < 20, "body turns to direction of travel");
            c.assertTrue(avatar.repositionRevision() == 0 && !progress.program.assistance, "did not pass by teleport fallback");
            avatar.discard(); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void tinyWorksiteCorrectionsAreSyncedButStationaryWorkIsNot(TestContext c) {
        var avatar = SquireEntities.AVATAR.create(c.getWorld());
        BlockPos p = c.getAbsolutePos(new BlockPos(3, 2, 3));
        avatar.refreshPositionAndAngles(p.getX() + .5, p.getY(), p.getZ() + .5, 35, 10);
        ConstructionRecovery.teleport(avatar, p);
        c.assertTrue(avatar.repositionRevision() == 0, "no-op work station does not reset animation every tick");
        avatar.repositionForConstruction(avatar.getX() + .05, avatar.getY(), avatar.getZ());
        c.assertTrue(avatar.repositionRevision() == 1, "even a small correction is distinguished from a step");
        ConstructionRecovery.teleport(avatar, p);
        c.assertTrue(avatar.repositionRevision() == 2 && avatar.getYaw() == 35 && avatar.getPitch() == 10,
            "assisted recenter keeps facing and marks animation discontinuity");
        c.assertTrue(avatar.getVelocity().lengthSquared() == 0, "recenter clears velocity");
        avatar.discard(); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR)
    public void nonWalkingPosesRemainOwnedByVanillaAnimation(TestContext c) {
        var avatar = SquireEntities.AVATAR.create(c.getWorld());
        avatar.setOnGround(true); c.assertTrue(avatar.usesGroundGait(), "standing uses directional gait");
        avatar.setOnGround(false); c.assertTrue(!avatar.usesGroundGait(), "airborne uses vanilla");
        avatar.setOnGround(true); avatar.setPose(EntityPose.SWIMMING);
        c.assertTrue(!avatar.usesGroundGait(), "swimming uses vanilla");
        avatar.setPose(EntityPose.SLEEPING); c.assertTrue(!avatar.usesGroundGait(), "sleeping uses vanilla");
        avatar.discard(); c.complete();
    }
}
