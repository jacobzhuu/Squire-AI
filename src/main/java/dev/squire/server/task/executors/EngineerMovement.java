package dev.squire.server.task.executors;

import dev.squire.api.body.MoveOptions;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profession.EngineerProgression;
import dev.squire.server.profession.SquireProfession;

/** Shared travel speed for construction and excavation; animation uses actual displacement. */
final class EngineerMovement {
    private EngineerMovement() { }
    static double speed(SquireProfile profile) {
        return profile != null && profile.profession.profession() == SquireProfession.ENGINEER
            ? EngineerProgression.current().movement(profile.profession.level) : 1.0;
    }
    static MoveOptions options(SquireProfile profile) {
        return new MoveOptions(speed(profile), MoveOptions.WALK.arriveWithin());
    }

    /** Match navigation's level/attribute multipliers without removing precise-edge braking. */
    static double directSpeed(double remaining, double movementMultiplier, double attributeSpeed) {
        double scaled = .1 * Math.max(0, attributeSpeed) / .35;
        double limit = movementMultiplier <= .5 ? Math.min(.1, scaled) : Math.min(.2, scaled * movementMultiplier);
        return Math.min(Math.max(0, remaining), limit);
    }

    static void directStep(dev.squire.server.body.avatar.AvatarEntity avatar, double dx, double dz,
            double movementMultiplier) {
        double length = Math.hypot(dx, dz);
        // Cancel stale forward input from MoveControl before injecting a reviewed edge.
        avatar.getMoveControl().moveTo(avatar.getX(), avatar.getY(), avatar.getZ(), 0);
        avatar.setForwardSpeed(0);
        avatar.setSidewaysSpeed(0);
        double step = directSpeed(length, movementMultiplier,
            avatar.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED));
        if (length < .001) { avatar.setVelocity(0, avatar.getVelocity().y, 0); return; }
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float yaw = net.minecraft.util.math.MathHelper.stepUnwrappedAngleTowards(avatar.getYaw(), targetYaw, 18);
        avatar.setYaw(yaw);
        avatar.setBodyYaw(yaw);
        avatar.getLookControl().lookAt(avatar.getX() + dx / length * 2, avatar.getEyeY(),
            avatar.getZ() + dz / length * 2, 18, 15);
        avatar.setVelocity(dx / length * step, avatar.getVelocity().y, dz / length * step);
    }
}
