package dev.squire.server.tool;

import java.util.UUID;

import dev.squire.api.body.AgentBody;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * Everything a native handler may touch: resolved on the server thread by the runtime
 * before gateway dispatch. Handlers never receive raw caller-controlled world refs.
 */
public interface ToolExecutionContext {
	/** The agent's body (never null while an agent exists). */
	AgentBody body();

	/** The concrete avatar entity for handlers that need entity-level access. */
	dev.squire.server.body.avatar.AvatarEntity avatar();

	MinecraftServer server();

	default ServerWorld world() {
		return (ServerWorld) avatar().getWorld();
	}

	UUID requesterId();

	long tick();
}
