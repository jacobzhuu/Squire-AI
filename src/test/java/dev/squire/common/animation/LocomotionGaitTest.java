package dev.squire.common.animation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocomotionGaitTest {
    private static LocomotionGait ready() {
        var gait = new LocomotionGait();
        for (int i = 0; i < 4; i++) gait.tick(0, 0, 0, true, 0);
        return gait;
    }
    private static LocomotionGait.Frame step(double x, double z, float yaw) {
        var gait = ready(); gait.tick(x, 0, z, true, 0); return gait.frame(1, yaw);
    }

    @Test void forwardAndBackwardHaveOppositePitchNotAnIdenticalForwardCycle() {
        var forward = step(0, .1, 0); var back = step(0, -.1, 0);
        assertEquals(1, forward.forward(), 1e-9); assertEquals(-1, back.forward(), 1e-9);
        assertEquals(-forward.pitch(), back.pitch(), 1e-6);
        assertEquals(0, forward.roll(), 1e-6);
    }
    @Test void strafingUsesRollInsteadOfForwardPitch() {
        var right = step(-.1, 0, 0); var left = step(.1, 0, 0);
        assertEquals(1, right.right(), 1e-9); assertEquals(-1, left.right(), 1e-9);
        assertEquals(0, right.pitch(), 1e-6);
        assertTrue(Math.abs(right.roll()) > .01);
        assertEquals(-right.roll(), left.roll(), 1e-6);
    }
    @Test void bodyHeadingNotWorldAxesDefinesForwardIncludingYawWrap() {
        assertEquals(1, step(-.1, 0, 90).forward(), 1e-9);
        assertEquals(1, step(0, -.1, 180).forward(), 1e-9);
        assertEquals(1, step(.1, 0, -90).forward(), 1e-9);
        assertEquals(step(0, .1, -179).pitch(), step(0, .1, 181).pitch(), 1e-6);
    }
    @Test void diagonalBlendDoesNotMultiplyLegAmplitude() {
        var frame = step(.1 / Math.sqrt(2), .1 / Math.sqrt(2), 0);
        assertEquals(1, frame.forward() * frame.forward() + frame.right() * frame.right(), 1e-9);
        assertEquals(Math.abs(step(0, .1, 0).pitch()), Math.hypot(frame.pitch(), frame.roll()), 1e-6);
    }
    @Test void cadenceAndStrideRespondToActualDisplacement() {
        var slow = step(0, .05, 0); var fast = step(0, .2, 0);
        assertTrue(fast.phase() > slow.phase()); assertTrue(fast.amplitude() > slow.amplitude());
        assertEquals(Math.PI * 2 * .05 / (.75 + .05 * 8), slow.phase(), 1e-9);
        assertEquals(Math.PI * 2 * .2 / (.75 + .2 * 8), fast.phase(), 1e-9);
    }
    @Test void blockedWorkerDoesNotAccumulateStepsAndSettlesToRest() {
        var gait = ready(); gait.tick(0, 0, .1, true, 0);
        double phase = gait.frame(1, 0).phase();
        for (int i = 0; i < 20; i++) gait.tick(0, 0, .1, true, 0);
        assertEquals(phase, gait.frame(1, 0).phase()); assertEquals(0, gait.frame(1, 0).amplitude());
    }
    @Test void renderingAtDifferentFrameRatesDoesNotAdvanceAnimation() {
        var gait = ready(); gait.tick(0, 0, .1, true, 0);
        var expected = gait.frame(1, 0);
        for (int fps : new int[]{30, 60, 144, 240}) for (int i = 0; i < fps; i++) gait.frame((float) i / fps, i);
        assertEquals(expected, gait.frame(1, 0));
    }
    @Test void phaseWrapInterpolatesThroughTheEndOfTheCycle() {
        var gait = ready();
        for (int i = 1; i <= 100; i++) {
            gait.tick(0, 0, i * .2, true, 0);
            double delta = gait.frame(1, 0).phase() - gait.frame(0, 0).phase();
            assertEquals(Math.PI * 2 * .2 / 2.35, delta, 1e-9);
        }
    }
    @Test void markedTinyCorrectionAndFollowingNetworkInterpolationAreNotWalking() {
        var gait = ready(); gait.tick(0, 0, .1, true, 0);
        for (int i = 0; i < 4; i++) {
            gait.tick(0, 0, .2 + .1 * i, true, 1);
            assertEquals(0, gait.frame(1, 0).amplitude());
        }
        gait.tick(0, 0, .6, true, 1);
        assertTrue(gait.frame(1, 0).amplitude() > 0);
    }
    @Test void largeUnmarkedTeleportResetsRatherThanSprintAnimating() {
        var gait = ready(); gait.tick(0, 0, .1, true, 0); gait.tick(100, 5, 100, true, 0);
        assertEquals(0, gait.frame(1, 0).amplitude()); assertEquals(0, gait.frame(1, 0).phase());
    }
    @Test void airborneSwimmingAndClimbingDoNotUseGroundPhase() {
        var gait = ready(); gait.tick(0, 0, .1, true, 0);
        double phase = gait.frame(1, 0).phase();
        gait.tick(0, .4, .3, false, 0);
        assertEquals(0, gait.frame(1, 0).amplitude()); assertEquals(phase, gait.frame(1, 0).phase());
    }
    @Test void companionsKeepIndependentState() {
        var moving = ready(); var idle = ready(); moving.tick(0, 0, .1, true, 0);
        assertTrue(moving.frame(1, 0).amplitude() > 0); assertEquals(0, idle.frame(1, 0).amplitude());
    }
    @Test void reversalBlendsInsteadOfFlippingLegsInOneFrame() {
        var gait = ready(); gait.tick(0, 0, .1, true, 0); gait.tick(0, 0, 0, true, 0);
        assertEquals(1, gait.frame(0, 0).forward()); assertEquals(0, gait.frame(.5f, 0).forward());
        assertEquals(-1, gait.frame(1, 0).forward());
    }
}
