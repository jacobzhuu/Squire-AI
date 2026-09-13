package dev.squire.client.render;

import dev.squire.common.animation.LocomotionGait;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AvatarPlayerModelGaitTest {
    private static AvatarPlayerModel model() {
        return new AvatarPlayerModel(PlayerEntityModel.getTexturedModelData(Dilation.NONE, false).getRoot().createPart(64, 64), false);
    }
    @Test void lateralLegPoseReachesPantsAndArmorCopies() {
        var model = model(); model.applyGroundGait(new LocomotionGait.Frame(0, .4, 0, 1), true);
        assertEquals(-.4f, model.rightLeg.roll, 1e-6); assertEquals(.4f, model.leftLeg.roll, 1e-6);
        assertEquals(0, model.rightLeg.pitch);
        assertEquals(model.rightLeg.roll, model.rightPants.roll);
        assertEquals(model.leftLeg.roll, model.leftPants.roll);
        var armor = new BipedEntityModel<dev.squire.server.body.avatar.AvatarEntity>(
            BipedEntityModel.getModelData(Dilation.NONE, 0).getRoot().createPart(64, 32));
        model.copyBipedStateTo(armor);
        assertEquals(model.rightLeg.roll, armor.rightLeg.roll);
        assertEquals(model.leftLeg.roll, armor.leftLeg.roll);
    }
    @Test void activeUseAndAttackArmPoseIsUntouched() {
        var model = model(); model.rightArm.pitch = -1.5f; model.leftArm.yaw = -.4f;
        model.applyGroundGait(new LocomotionGait.Frame(0, .4, 1, 0), false);
        assertEquals(-1.5f, model.rightArm.pitch); assertEquals(-.4f, model.leftArm.yaw);
        assertEquals(model.rightArm.pitch, model.rightSleeve.pitch);
    }
    @Test void bowArmPoseIsNotOverwrittenEvenIfSwingIsRequested() {
        var model = model(); model.rightArmPose = BipedEntityModel.ArmPose.BOW_AND_ARROW;
        model.leftArmPose = BipedEntityModel.ArmPose.BOW_AND_ARROW;
        model.rightArm.pitch = -1.5f; model.leftArm.pitch = -1.3f;
        model.applyGroundGait(new LocomotionGait.Frame(0, .4, 1, 0), true);
        assertEquals(-1.5f, model.rightArm.pitch); assertEquals(-1.3f, model.leftArm.pitch);
    }
    @Test void carryingToolHasSmallerCounterSwingAndBackwardsReversesLegs() {
        var model = model(); model.rightArmPose = BipedEntityModel.ArmPose.ITEM;
        model.applyGroundGait(new LocomotionGait.Frame(0, .4, -1, 0), true);
        assertEquals(-.4f, model.rightLeg.pitch, 1e-6); assertEquals(.4f, model.leftLeg.pitch, 1e-6);
        assertEquals(.14f, model.rightArm.pitch, 1e-6); assertEquals(-.26f, model.leftArm.pitch, 1e-6);
        assertEquals(model.rightArm.pitch, model.rightSleeve.pitch);
    }
}
