package dev.squire.client.render;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;

/**
 * 玩家模型、物品使用姿势和按实际位移驱动的方向性地面步态。
 *
 * <p>{@code PlayerEntityModel} 自己<b>不</b>设 {@code rightArmPose} —— 玩家的姿势是
 * {@code PlayerEntityRenderer} 从 {@code AbstractClientPlayerEntity} 上读出来的，
 * 而那个方法是 private 且只吃玩家实体，伙伴走不了那条路。原版骷髅走的是这一条：
 * 在 {@code animateModel} 里按「手上拿什么 + 是不是正在使用它」决定姿势。</p>
 *
 * <p>姿势必须在 {@code setAngles} <b>之前</b>定下来，因为
 * {@code BipedEntityModel.positionRightArm} 是在 setAngles 里读它的。
 * {@code animateModel} 是原版保证的那个时机，所以钩子放在这里而不是渲染器的
 * {@code render()} 里——后者只是"碰巧"也在之前，而且还得记得在 else 分支里复位，
 * 忘了就会永远卡在拉弓姿势（正是这次要修的那类毛病）。</p>
 */
public class AvatarPlayerModel extends PlayerEntityModel<AvatarEntity> {
	private float gaitTickDelta;

	public AvatarPlayerModel(ModelPart root, boolean slim) {
		super(root, slim);
	}

	@Override
	public void animateModel(AvatarEntity entity, float limbAngle, float limbDistance,
			float tickDelta) {
		gaitTickDelta = tickDelta;
		// 每帧先复位，再按当前状态赋值——省掉"忘了复位"这一整类 bug。
		this.rightArmPose = BipedEntityModel.ArmPose.EMPTY;
		this.leftArmPose = BipedEntityModel.ArmPose.EMPTY;
		BipedEntityModel.ArmPose mainPose = armPose(entity, Hand.MAIN_HAND);
		BipedEntityModel.ArmPose offPose = armPose(entity, Hand.OFF_HAND);
		if (entity.getMainArm() == Arm.RIGHT) {
			this.rightArmPose = mainPose;
			this.leftArmPose = offPose;
		} else {
			this.leftArmPose = mainPose;
			this.rightArmPose = offPose;
		}
		super.animateModel(entity, limbAngle, limbDistance, tickDelta);
	}

	@Override
	public void setAngles(AvatarEntity entity, float limbAngle, float limbDistance,
			float animationProgress, float headYaw, float headPitch) {
		boolean ground = entity.usesGroundGait();
		sneaking = entity.isInSneakingPose() || ground && entity.isSneaking();
		// Keep vanilla swimming/riding/flight and all item/attack poses. On the
		// ground replace only the vanilla forward-only locomotion contribution.
		super.setAngles(entity, ground ? 0 : limbAngle, ground ? 0 : limbDistance,
			animationProgress, headYaw, headPitch);
		if (!ground) return;
		float bodyYaw = net.minecraft.util.math.MathHelper.lerpAngleDegrees(
			gaitTickDelta, entity.prevBodyYaw, entity.bodyYaw);
		var gait = entity.locomotionGait().frame(gaitTickDelta, bodyYaw);
		applyGroundGait(gait, !entity.isUsingItem() && handSwingProgress <= 0);
	}

	/** Also used by the bone-level regression tests; equipment overlays follow the same pose. */
	void applyGroundGait(dev.squire.common.animation.LocomotionGait.Frame gait, boolean swingArms) {
		rightLeg.pitch += gait.pitch();
		leftLeg.pitch -= gait.pitch();
		rightLeg.roll += gait.roll();
		leftLeg.roll -= gait.roll();
		if (swingArms) {
			if (rightArmPose == ArmPose.EMPTY || rightArmPose == ArmPose.ITEM) {
				rightArm.pitch -= gait.pitch() * (rightArmPose == ArmPose.ITEM ? .35f : .65f);
				rightArm.roll -= gait.roll() * .25f;
			}
			if (leftArmPose == ArmPose.EMPTY || leftArmPose == ArmPose.ITEM) {
				leftArm.pitch += gait.pitch() * (leftArmPose == ArmPose.ITEM ? .35f : .65f);
				leftArm.roll += gait.roll() * .25f;
			}
		}
		leftPants.copyTransform(leftLeg);
		rightPants.copyTransform(rightLeg);
		leftSleeve.copyTransform(leftArm);
		rightSleeve.copyTransform(rightArm);
	}

	/**
	 * {@code PlayerEntityRenderer.getArmPose} 的删减版，只保留伙伴用得到的几种。
	 *
	 * <p>{@code isUsingItem} / {@code getActiveHand} / {@code getItemUseTimeLeft} 都走
	 * {@code LIVING_FLAGS}，是原版自动同步的，客户端不需要任何自定义封包就知道他
	 * 正在拉弓。</p>
	 */
	private static BipedEntityModel.ArmPose armPose(AvatarEntity entity, Hand hand) {
		ItemStack stack = entity.getStackInHand(hand);
		if (stack.isEmpty()) {
			return BipedEntityModel.ArmPose.EMPTY;
		}
		if (entity.getActiveHand() == hand && entity.getItemUseTimeLeft() > 0) {
			return switch (stack.getUseAction()) {
				case BOW -> BipedEntityModel.ArmPose.BOW_AND_ARROW;
				case BLOCK -> BipedEntityModel.ArmPose.BLOCK;
				case SPEAR -> BipedEntityModel.ArmPose.THROW_SPEAR;
				case CROSSBOW -> BipedEntityModel.ArmPose.CROSSBOW_CHARGE;
				default -> BipedEntityModel.ArmPose.ITEM;
			};
		}
		return BipedEntityModel.ArmPose.ITEM;
	}
}
