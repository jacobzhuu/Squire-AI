package dev.squire;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.squire.server.command.SquireCommands;
import dev.squire.server.input.InputGateway;
import dev.squire.server.registry.SquireEntities;
import dev.squire.server.runtime.SquireRuntime;

/**
 * Squire AI mod entrypoint.
 *
 * <p>Server-authoritative AI companion mod. See docs/IMPLEMENTATION_SPEC.md.
 * Architecture rule: everything under {@code dev.squire.common} and {@code dev.squire.api}
 * is pure Java, everything else may touch Minecraft types (ADR-016).</p>
 */
public final class SquireMod implements ModInitializer {
	public static final String MOD_ID = "squire";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SquireEntities.register();
		dev.squire.server.registry.SquireItems.register();
		dev.squire.server.registry.SquireScreens.register();
		dev.squire.server.summon.SummoningService.register();
		SquireCommands.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> SquireRuntime.init(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (SquireRuntime.isAlive()) {
				SquireRuntime.get().flushAudit(); // final audit persistence (§93)
			}
			SquireRuntime.shutdown();
		});

		// task runtime advances on the server thread only (spec section 7)
		java.util.concurrent.atomic.AtomicLong tickHookCount = new java.util.concurrent.atomic.AtomicLong();
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
			.register(server -> {
				if (server != null && SquireRuntime.isAlive()) {
					long ticks = tickHookCount.incrementAndGet();
					if (ticks == 1L) {
						LOGGER.info("[Squire] server-tick hook active on {}", Thread.currentThread().getName());
					}
					if (ticks % 1200L == 0L) { // audit persistence, ~once a minute
						SquireRuntime.get().flushAudit();
					}
					SquireRuntime.get().tickScheduler();
				}
			});

		// chat capture: sender resolved inside the gateway before any NLP (spec section 12)
		// 公共频道要求先点名（第三个参数）：聊天框里的话不都是对他说的，
		// 不点名就理会等于让旁人一句「跟着我」支使别人的侍从，也会白烧大模型调用。
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params)
			-> InputGateway.acceptChat(sender, message.getContent().getString(), true));

		// 方案 A1/A2：伙伴实体的加载/卸载维护索引并快照长期档案
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD
			.register((entity, world) -> {
				if (entity instanceof dev.squire.server.body.avatar.AvatarEntity avatar) {
					SquireRuntime.onAvatarLoad(avatar);
				}
			});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD
			.register((entity, world) -> {
				if (entity instanceof dev.squire.server.body.avatar.AvatarEntity avatar) {
					SquireRuntime.onAvatarUnload(avatar);
				}
			});

		// 侍从的箭飞在半空时主人走进弹道——瞄准那一层拦不住的最后一种误伤。
		dev.squire.server.combat.OwnerFriendlyFire.register();

		LOGGER.info("[Squire] initialized");
	}
}
