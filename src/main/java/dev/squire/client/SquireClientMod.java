package dev.squire.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.client.render.AvatarRenderer;
import dev.squire.server.registry.SquireEntities;

/**
 * Client-side entrypoint: avatar renderer, GUI/HUD, debug visualization.
 * The client never acts as a permission source of truth (ADR-001).
 */
public final class SquireClientMod implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("squire-client");

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(SquireEntities.AVATAR, AvatarRenderer::new);
        net.minecraft.client.item.ModelPredicateProviderRegistry.register(dev.squire.server.registry.SquireItems.RECALL_BELL,
            new net.minecraft.util.Identifier("squire", "bell_state"), (stack,world,entity,seed) -> {
                var nbt=stack.getNbt();
                return dev.squire.client.render.RecallBellAppearance.state(
                    nbt != null && nbt.containsUuid(dev.squire.server.registry.SquireItems.NBT_AGENT),
                    nbt == null ? "" : nbt.getString("SquireDisplayProfession"));
            });
        net.minecraft.client.item.ModelPredicateProviderRegistry.register(dev.squire.server.registry.SquireItems.RECALL_BELL,
            new net.minecraft.util.Identifier("squire", "bell_quality"), (stack,world,entity,seed) ->
                dev.squire.client.render.RecallBellAppearance.quality(dev.squire.server.registry.SquireItems.tierOf(stack).ordinal()));
		net.minecraft.client.gui.screen.ingame.HandledScreens.register(
			dev.squire.server.registry.SquireScreens.SQUIRE,
			dev.squire.client.gui.SquireScreen::new);
		// 面板里的模式/权限状态由服务端推过来，客户端不自行推断（ADR-001）。
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
			.registerGlobalReceiver(
				dev.squire.server.registry.SquireScreens.STATE_PACKET,
				(client, handler, buf, sender) -> {
					int syncId = buf.readVarInt();
					var state = dev.squire.server.gui.PanelState.read(buf);
                    if(state==dev.squire.server.gui.PanelState.EMPTY)return;
					String terrainReview = buf.readString(128);
                    boolean inventoryAccessible=buf.readBoolean();
                    boolean bodyAvailable=buf.readBoolean();
					client.execute(() -> {
						if (client.player != null && client.player.currentScreenHandler
							instanceof dev.squire.server.gui.SquireScreenHandler panel
							&& panel.syncId == syncId) {
							panel.applyState(state);
							panel.terrainReview = terrainReview;
                            panel.inventoryAccessible=inventoryAccessible;
                            panel.bodyAvailable=bodyAvailable;
						}
					});
				});
		registerPanelKeybind();
		LOGGER.info("[Squire] client init");
	}

	/**
	 * 一个按键直达面板（默认 K）。没有伙伴时服务端会顺手召唤一个。
	 *
	 * <p>这是把交互移出聊天栏的最后一环：在此之前，玩家想见到伙伴必须先记住
	 * 召集铃；而右键面板的前提又是伙伴已经存在。</p>
	 */
	private static void registerPanelKeybind() {
		net.minecraft.client.option.KeyBinding binding =
			net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
				.registerKeyBinding(new net.minecraft.client.option.KeyBinding(
					"key.squire.open_panel",
					org.lwjgl.glfw.GLFW.GLFW_KEY_K,
					"category.squire"));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
			.END_CLIENT_TICK.register(client -> {
				while (binding.wasPressed()) {
					if (client.player != null) {
						net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
							.send(dev.squire.server.registry.SquireScreens
								.OPEN_PANEL_PACKET,
								net.fabricmc.fabric.api.networking.v1.PacketByteBufs
									.create());
					}
				}
			});
	}
}
