package dev.squire.client.render;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * Renders the avatar with the classic player model and its own profession skin.
 * Profession comes from entity tracking, including for offline owners.
 * The name tag shows the explicit AI identity "[Squire] <name>".
 */
public class AvatarRenderer extends MobEntityRenderer<AvatarEntity, AvatarPlayerModel> {
	private static final Identifier COMMON = new Identifier("squire", AvatarSkins.texturePath(""));
	private static final Identifier GUARD = new Identifier("squire", AvatarSkins.texturePath("guard"));
	private static final Identifier ENGINEER = new Identifier("squire", AvatarSkins.texturePath("engineer"));

	public AvatarRenderer(EntityRendererFactory.Context ctx) {
		// AvatarPlayerModel = 玩家模型 + 手臂姿势（拉弓时摆出举弓的样子）。
		// Imported skins use classic four-pixel arms, matching EntityModelLayers.PLAYER.
		super(ctx, new AvatarPlayerModel(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
		// 装备会通过原版实体同步送到客户端，但没有这两个 feature renderer 就一格都
		// 画不出来——伙伴穿了整套下界合金也还是光着。"让他装备套装没反应"里，
		// 有一半是这个原因。
		addFeature(new ArmorFeatureRenderer<>(this,
			new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
			new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
			ctx.getModelManager()));
		addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
		// 背包画在护甲之后：它应该盖在胸甲外面，就像真背在身上一样。
		addFeature(new BackpackFeatureRenderer(this, ctx.getItemRenderer()));
	}

	@Override
	public Identifier getTexture(AvatarEntity entity) {
		return switch (entity.syncedProfession()) {
			case "guard" -> GUARD;
			case "engineer" -> ENGINEER;
			default -> COMMON;
		};
	}

	/**
	 * {@link PlayerEntityModel} 只负责四肢划水，整个人从竖直转到水平是
	 * {@code PlayerEntityRenderer.setupTransforms} 额外做的。这个渲染器继承的是
	 * {@link MobEntityRenderer}，所以必须把玩家渲染器的游泳变换补回来；否则就会出现
	 * “站直身体划水”的怪姿势。
	 */
	@Override
	protected void setupTransforms(AvatarEntity entity, MatrixStack matrices,
			float animationProgress, float bodyYaw, float tickDelta) {
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta);
		float lean = entity.getLeaningPitch(tickDelta);
		if (lean <= 0.0f) {
			return;
		}
		float targetPitch = entity.isTouchingWater()
			? -90.0f - entity.getPitch() : -90.0f;
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(
			MathHelper.lerp(lean, 0.0f, targetPitch)));
		if (entity.isInSwimmingPose()) {
			matrices.translate(0.0f, -1.0f, 0.3f);
		}
	}
}
