package dev.squire.client.render;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;

/**
 * 把侍从背着的那个背包画到他背上。
 *
 * <p>「精妙背包」自己的渲染层是<b>写给玩家实体</b>的（挂在 PlayerEntityRenderer 上，
 * 还要走 Trinkets 的槽位），套不到我们这具自定义身体上。所以这里不去借它的层，
 * 直接把那件物品按它自己的模型画出来——背包在物品形态下就是一个完整的 3D 模型，
 * 连染色和升级贴图都跟着物品走，看上去就是他背上的那个包。</p>
 *
 * <p>位置绑在身体那一节上（{@code body.rotate}），所以他弯腰、游泳、被击退时背包
 * 会跟着身体走，而不是浮在空中。</p>
 */
public class BackpackFeatureRenderer
		extends FeatureRenderer<AvatarEntity, AvatarPlayerModel> {

	/** 往背后挪多远。正 z 是背面。 */
	private static final float BACK_OFFSET = 0.19f;
	/** 沿身体上下微调，让包挂在肩胛而不是屁股上。 */
	private static final float HEIGHT_OFFSET = 0.28f;
	/** 背包整体缩放。物品模型是 1×1×1 的方块尺度，直接画会比人还大。 */
	private static final float SCALE = 0.85f;

	private final ItemRenderer itemRenderer;

	public BackpackFeatureRenderer(
			FeatureRendererContext<AvatarEntity, AvatarPlayerModel> context,
			ItemRenderer itemRenderer) {
		super(context);
		this.itemRenderer = itemRenderer;
	}

	@Override
	public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
			int light, AvatarEntity entity, float limbAngle, float limbDistance,
			float tickDelta, float animationProgress, float headYaw, float headPitch) {
		ItemStack backpack = entity.syncedBackpack();
		if (backpack.isEmpty()) {
			return;
		}
		matrices.push();
		// 跟着躯干走：这一步之后坐标系原点在身体中心、y 轴朝下（原版模型的惯例）。
		getContextModel().body.rotate(matrices);
		matrices.translate(0.0f, HEIGHT_OFFSET, BACK_OFFSET);
		// 模型是朝观察者的，转过去背对我们；y/z 取负是把模型坐标系翻回世界朝向。
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
		matrices.scale(SCALE, -SCALE, -SCALE);
		itemRenderer.renderItem(backpack, ModelTransformationMode.NONE, light,
			OverlayTexture.DEFAULT_UV, matrices, vertexConsumers, entity.getWorld(),
			entity.getId());
		matrices.pop();
	}
}
