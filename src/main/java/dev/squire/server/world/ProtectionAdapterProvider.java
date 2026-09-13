package dev.squire.server.world;

import net.minecraft.server.MinecraftServer;

/** Server mod entrypoint contract for integrating a region/claim protection system. */
@FunctionalInterface
public interface ProtectionAdapterProvider {
	ProtectionAdapter create(MinecraftServer server);
}
