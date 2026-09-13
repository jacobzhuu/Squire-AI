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

	public static final Identifier RECALL_PANEL_PACKET = new Identifier(SquireMod.MOD_ID, "squire_panel_recall");
    public static final Identifier SWITCH_PANEL_PACKET = new Identifier(SquireMod.MOD_ID, "squire_switch_panel");

	public static final Identifier STATE_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_panel_state");
	public static final Identifier TERRAIN_ACTION_PACKET = new Identifier(SquireMod.MOD_ID, "squire_terrain_action");
	public static final Identifier CHAT_PACKET =
		new Identifier(SquireMod.MOD_ID, "squire_panel_chat");
	public static final Identifier BLUEPRINT_SELECT_PACKET = new Identifier(SquireMod.MOD_ID, "blueprint_select");
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
        ServerPlayNetworking.registerGlobalReceiver(RECALL_PANEL_PACKET,(server,player,handler,buf,sender) -> {
            int syncId=buf.readVarInt();
            server.execute(() -> {
                if (!(player.currentScreenHandler instanceof SquireScreenHandler panel) || panel.syncId!=syncId || !panel.canUse(player)) return;
                java.util.UUID id=panel.avatarEntity()==null?panel.snapshotAgent:panel.avatarEntity().agentId();
                if(id==null)return;
                var rt=dev.squire.server.runtime.SquireRuntime.get();var result=rt.recallSelectedWithBell(player,id);
                player.sendMessage(Text.literal(result.message()),false);
                if(result.success()) rt.agents().resolveByAgentId(id).ifPresent(a -> open(player,a));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SWITCH_PANEL_PACKET, (server,player,handler,buf,sender) -> {
            int syncId=buf.readVarInt(); java.util.UUID target=buf.readUuid();
            server.execute(() -> {
                if (!(player.currentScreenHandler instanceof SquireScreenHandler panel) || panel.syncId!=syncId || !panel.canUse(player)) return;
                var rt=dev.squire.server.runtime.SquireRuntime.get();
                var record=rt.agentStore().recordOfAgent(target).orElse(null);
                if(record==null || !record.ownerId.equals(player.getUuid())) return;
                var avatar=rt.agents().resolveByAgentId(target).orElse(null);
                if(avatar!=null) open(player,avatar);
                else openSnapshot(player,record);
            });
        });
		ServerPlayNetworking.registerGlobalReceiver(TERRAIN_ACTION_PACKET, (server, player, handler, buf, sender) -> {
			int syncId = buf.readVarInt(), action = buf.readVarInt(); String review = buf.readString(128);
			server.execute(() -> {
				if (player.currentScreenHandler instanceof SquireScreenHandler panel && panel.syncId == syncId && panel.canUse(player))
					panel.terrainAction(player, action, review);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(BLUEPRINT_SELECT_PACKET, (server, player, handler, buf, sender) -> {
			int syncId = buf.readVarInt(); String id = buf.readString(512); long version = buf.readLong();
			server.execute(() -> {
				if (player.currentScreenHandler instanceof SquireScreenHandler panel && panel.syncId == syncId && panel.canUse(player))
					panel.selectBlueprint(player, id, version);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(CHAT_PACKET,
			(server, player, handler, buf, sender) -> {
				int syncId = buf.readVarInt();
                String message = buf.readString(MAX_CHAT_LENGTH);
				server.execute(() -> {
					// 面板里说的话和聊天框里说的话走完全同一条管线，
					// 免得两边行为逐渐分叉。
					if (!message.isBlank() && player.currentScreenHandler instanceof SquireScreenHandler panel
                            && panel.syncId == syncId && panel.canUse(player) && panel.avatarEntity() != null) {
                        dev.squire.server.runtime.SquireRuntime.get().agents().withTarget(player.getUuid(),
                            panel.avatarEntity().agentId(), () -> dev.squire.server.input.InputGateway.acceptChat(player, message));
					}
				});
			});
		ServerPlayNetworking.registerGlobalReceiver(SHORTCUT_PACKET,
			(server, player, handler, buf, sender) -> {
				// 槽位 + 名称 + 动作 id + 档位参数。以前这里收的是一句自然语言，
				// 点一下要重新送回输入网关——也就是可能再走一次模型。现在收的是
				// 一个 CommandCatalog 里的动作 id，执行走服务端动作那条路。
				int syncId = buf.readVarInt();
                int slot = buf.readVarInt();
				String name = buf.readString(
					dev.squire.server.shortcut.ShortcutStore.MAX_NAME_LENGTH);
				String entryId = buf.readString(
					dev.squire.server.shortcut.ShortcutStore.MAX_ENTRY_ID_LENGTH);
				String arg = buf.readString(
					dev.squire.server.shortcut.ShortcutStore.MAX_ARG_LENGTH);
				server.execute(() -> {
					if (!(player.currentScreenHandler instanceof SquireScreenHandler current) || current.syncId != syncId || !current.canUse(player)) return;
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
				AvatarEntity avatar = runtime.agentStore().recordOfOwner(player.getUuid())
					.flatMap(record -> runtime.agents().resolveByAgentId(record.agentId))
					.orElse(null);
				if (avatar == null) {
                    var record = runtime.agentStore().recordOfOwner(player.getUuid());
                    if (record.isPresent()) {openSnapshot(player,record.get());return;}
					boolean known = runtime.agentStore().recordOfOwner(player.getUuid())
						.isPresent();
					player.sendMessage(Text.translatable(known
						? "squire.summon.use_bell" : "squire.summon.hint"), false);
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
        if (!player.getUuid().equals(avatar.ownerId()) && !player.hasPermissionLevel(2)) return;
        dev.squire.server.runtime.SquireRuntime.get().agentStore().setPrimary(avatar.agentId());
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
    public static void openSnapshot(ServerPlayerEntity player, dev.squire.server.agent.SquireAgentStateStore.AgentRecord record) {
        if (!record.ownerId.equals(player.getUuid())) return;
        dev.squire.server.runtime.SquireRuntime.get().agentStore().setPrimary(record.agentId);
        player.openHandledScreen(new ExtendedScreenHandlerFactory() {
            public Text getDisplayName() {return Text.literal(record.displayName);}
            public void writeScreenOpeningData(ServerPlayerEntity p,PacketByteBuf buf) {buf.writeVarInt(-1);}
            public ScreenHandler createMenu(int syncId,net.minecraft.entity.player.PlayerInventory inventory,net.minecraft.entity.player.PlayerEntity p) {
                var panel=new SquireScreenHandler(syncId,inventory); panel.snapshotAgent=record.agentId;return panel;
            }
        });
        if(player.currentScreenHandler instanceof SquireScreenHandler panel) panel.syncState(player);
    }

	public static void sendState(ServerPlayerEntity player, int syncId,
		dev.squire.server.gui.PanelState state) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		state.write(buf);
		var runtime = dev.squire.server.runtime.SquireRuntime.get();
		var placement = runtime.blueprints().activeOf(player.getUuid()).orElse(null);
		buf.writeString(placement == null || dev.squire.server.blueprint.TerrainLeveling.parse(placement.blueprintId).isEmpty()
			? "" : placement.placementId + "/" + placement.terrainReview, 128);
		buf.writeBoolean(player.currentScreenHandler instanceof SquireScreenHandler panel && panel.canExchange(player));
        buf.writeBoolean(player.currentScreenHandler instanceof SquireScreenHandler panel && panel.avatarEntity()!=null && panel.avatarEntity().isAlive());
        ServerPlayNetworking.send(player, STATE_PACKET, buf);
	}
}
