package dev.squire.server.registry;

import dev.squire.SquireMod;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.gui.SquireScreenHandler;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 右键面板的注册与自定义封包。
 *
 * <p>模式/权限按钮走原版的 button-click 通道；状态、聊天、身份字符串与需要
 * 服务端事务确认的洗练使用独立封包。</p>
 */
public final class SquireScreens {

	public static final Identifier STATE_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_panel_state");
	public static final Identifier CHAT_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_panel_chat");
	/** 按键打开现有伙伴的面板；绝不凭空创建或跨维度搬运实体。 */
	public static final Identifier OPEN_PANEL_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_open_panel");
	/** 保存面板第 N 格的快捷指令（槽位 + 名称 + 内容）。 */
	public static final Identifier SHORTCUT_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_panel_shortcut");
	/** Header identity editor request. */
	public static final Identifier RENAME_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_panel_rename");
	/** Confirmed personality reroll request; the server rechecks and consumes the cost. */
	public static final Identifier PERSONALITY_REROLL_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_personality_reroll");

	/** 面板里能发的最长一句话，和聊天栏一致，防止有人塞进一整本书。 */
	public static final int MAX_CHAT_LENGTH = 256;

	/** 名字上限；客户端限制输入，服务端仍会独立校验并拒绝非法值。 */
	public static final int MAX_NAME_LENGTH =
		dev.squire.server.profile.SquireName.MAX_LENGTH;

	public static final ScreenHandlerType<SquireScreenHandler> SQUIRE =
		Registry.register(Registries.SCREEN_HANDLER,
			new Identifier(SquireMod.MOD_ID, "squire_panel"),
			new ExtendedScreenHandlerType<>((syncId, inventory, buf) -> {
				// 服务端写了实体 id，这里必须读掉，否则 buf 读写不对称。
				buf.readVarInt();
				return new SquireScreenHandler(syncId, inventory);
			}));

	private SquireScreens() {
	}

	/** Register all client-to-server panel request receivers. */
	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(CHAT_PACKET,
			(server, player, handler, buf, sender) -> {
				String message = buf.readString(MAX_CHAT_LENGTH);
				server.execute(() -> {
					// 面板里说的话和聊天框里说的话走完全同一条管线，
					// 免得两边行为逐渐分叉。
					if (!message.isBlank()) {
						dev.squire.server.input.InputGateway.acceptChat(player, message);
					}
				});
			});
		ServerPlayNetworking.registerGlobalReceiver(SHORTCUT_PACKET,
			(server, player, handler, buf, sender) -> {
				// 槽位 + 名称 + 动作 id + 档位参数。以前这里收的是一句自然语言，
				// 点一下要重新送回输入网关——也就是可能再走一次模型。现在收的是
				// 一个 CommandCatalog 里的动作 id，执行走服务端动作那条路。
				int slot = buf.readVarInt();
				String name = buf.readString(
					dev.squire.server.shortcut.ShortcutStore.MAX_NAME_LENGTH);
				String entryId = buf.readString(
					dev.squire.server.shortcut.ShortcutStore.MAX_ENTRY_ID_LENGTH);
				String arg = buf.readString(
					dev.squire.server.shortcut.ShortcutStore.MAX_ARG_LENGTH);
				server.execute(() -> {
					var result = dev.squire.server.runtime.SquireRuntime.get()
						.bindShortcutAt(player, slot, name, entryId, arg);
					player.sendMessage(Text.literal(result.message()), false);
					if (player.currentScreenHandler instanceof SquireScreenHandler panel) {
						panel.syncState(player); // 立刻让新按钮出现，别等下一次轮询
					}
				});
			});
		ServerPlayNetworking.registerGlobalReceiver(RENAME_PACKET,
			(server, player, handler, buf, sender) -> {
				int syncId = buf.readVarInt();
				String name = buf.readString(MAX_NAME_LENGTH);
				server.execute(() -> {
					if (!(player.currentScreenHandler instanceof SquireScreenHandler panel)
							|| panel.syncId != syncId || panel.avatarEntity() == null) {
						return;
					}
					var result = dev.squire.server.runtime.SquireRuntime.get()
						.renameAgentFromPanel(player, panel.avatarEntity(), name);
					player.sendMessage(Text.literal(result.message()), false);
					panel.syncState(player);
				});
			});
		ServerPlayNetworking.registerGlobalReceiver(PERSONALITY_REROLL_PACKET,
			(server, player, handler, buf, sender) -> {
				int syncId = buf.readVarInt();
				server.execute(() -> {
					if (!(player.currentScreenHandler instanceof SquireScreenHandler panel)
							|| panel.syncId != syncId || panel.avatarEntity() == null) {
						return;
					}
					var result = dev.squire.server.runtime.SquireRuntime.get()
						.rerollPersonality(player, panel.avatarEntity());
					player.sendMessage(Text.literal(result.message()), false);
					panel.syncState(player);
				});
			});
		ServerPlayNetworking.registerGlobalReceiver(OPEN_PANEL_PACKET,
			(server, player, handler, buf, sender) -> server.execute(() -> {
				var runtime = dev.squire.server.runtime.SquireRuntime.get();
				AvatarEntity avatar = runtime.agents()
					.resolveForOwnerNow(player.getUuid()).orElse(null);
				if (avatar == null) {
					boolean known = runtime.agentStore().recordOfOwner(player.getUuid())
						.isPresent();
					player.sendMessage(Text.translatable(known
						? "squire.summon.use_bell" : "squire.summon.hint"), false);
					return;
				}
				if (avatar.getWorld() != player.getWorld()) {
					player.sendMessage(Text.translatable("squire.summon.use_bell"), false);
					return;
				}
				open(player, avatar);
			}));
		SquireMod.LOGGER.info("[Squire] panel screen registered");
	}

	/**
	 * 打开面板。用 {@link ExtendedScreenHandlerFactory} 而不是普通的
	 * {@link NamedScreenHandlerFactory}：注册的是 ExtendedScreenHandlerType，
	 * 客户端工厂会去读一个 buf，服务端这边必须对称地写。
	 */
	public static void open(ServerPlayerEntity player, AvatarEntity avatar) {
		player.openHandledScreen(new ExtendedScreenHandlerFactory() {
			@Override
			public Text getDisplayName() {
				return avatar.getCustomName() == null
					? Text.literal("Squire") : avatar.getCustomName();
			}

			@Override
			public void writeScreenOpeningData(ServerPlayerEntity target,
					PacketByteBuf buf) {
				buf.writeVarInt(avatar.getId());
			}

			@Override
			public ScreenHandler createMenu(int syncId,
					net.minecraft.entity.player.PlayerInventory inventory,
					net.minecraft.entity.player.PlayerEntity opener) {
				return new SquireScreenHandler(syncId, inventory,
					avatar.items().mainInventory(),
					new dev.squire.server.gui.AvatarEquipmentInventory(avatar,
						SquireScreenHandler.EQUIPMENT_ORDER),
					avatar.backpackSlotInventory(),
					avatar);
			}
		});
		if (player.currentScreenHandler instanceof SquireScreenHandler panel) {
			panel.syncState(player);
		}
	}

	/**
	 * 把面板状态推给单个玩家。字段顺序只存在于 {@link dev.squire.server.gui.PanelState}，
	 * 两端都走那一处，不可能再漂。
	 */
	public static void sendState(ServerPlayerEntity player, int syncId,
		dev.squire.server.gui.PanelState state) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		state.write(buf);
		ServerPlayNetworking.send(player, STATE_PACKET, buf);
	}
}
