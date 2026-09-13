package dev.squire.server.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Loads trusted server-side protection integrations and requires every adapter to allow a write. */
public final class ProtectionAdapterRegistry {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProtectionAdapterRegistry.class);

	private ProtectionAdapterRegistry() { }

	public static ProtectionAdapter load(MinecraftServer server) {
		List<ProtectionAdapter> adapters = new ArrayList<>();
		adapters.add(new VanillaSpawnProtection(server));
		var containers = net.fabricmc.loader.api.FabricLoader.getInstance()
			.getEntrypointContainers("squire_protection", ProtectionAdapterProvider.class);
		for (var container : containers) {
			String modId = container.getProvider().getMetadata().getId();
			try {
				ProtectionAdapter adapter = container.getEntrypoint().create(server);
				if (adapter == null) throw new IllegalStateException("provider returned null");
				adapters.add(adapter);
				LOG.info("[protection] loaded adapter from {}", modId);
			} catch (RuntimeException failure) {
				LOG.error("[protection] adapter from {} failed to initialize; world mutations are fail-closed", modId, failure);
				adapters.add(denyAll("protection adapter " + modId + " failed"));
			}
		}
		return combine(adapters);
	}

	public static ProtectionAdapter combine(List<ProtectionAdapter> adapters) {
		List<ProtectionAdapter> all = List.copyOf(adapters);
		return new ProtectionAdapter() {
			private PermissionDecision check(java.util.function.Function<ProtectionAdapter, PermissionDecision> query) {
				for (ProtectionAdapter adapter : all) {
					try {
						PermissionDecision result = query.apply(adapter);
						if (result == null) return PermissionDecision.deny("protection adapter returned no decision");
						if (!result.allowed()) return result;
					} catch (RuntimeException failure) {
						LOG.error("[protection] adapter failed during permission check; denying world mutation", failure);
						return PermissionDecision.deny("protection adapter failed during permission check");
					}
				}
				return PermissionDecision.allow();
			}
			@Override public PermissionDecision canBreak(ServerWorld world, BlockPos pos, UUID actor) {
				return check(a -> a.canBreak(world, pos, actor));
			}
			@Override public PermissionDecision canPlace(ServerWorld world, BlockPos pos, UUID actor) {
				return check(a -> a.canPlace(world, pos, actor));
			}
			@Override public PermissionDecision canInteract(ServerWorld world, BlockPos pos, UUID actor) {
				return check(a -> a.canInteract(world, pos, actor));
			}
			@Override public PermissionDecision canEditRegion(ServerWorld world, BoundedRegion region, UUID actor) {
				return check(a -> a.canEditRegion(world, region, actor));
			}
		};
	}

	private static ProtectionAdapter denyAll(String reason) {
		return new ProtectionAdapter() {
			private PermissionDecision denied() { return PermissionDecision.deny(reason); }
			@Override public PermissionDecision canBreak(ServerWorld w, BlockPos p, UUID a) { return denied(); }
			@Override public PermissionDecision canPlace(ServerWorld w, BlockPos p, UUID a) { return denied(); }
			@Override public PermissionDecision canInteract(ServerWorld w, BlockPos p, UUID a) { return denied(); }
			@Override public PermissionDecision canEditRegion(ServerWorld w, BoundedRegion r, UUID a) { return denied(); }
		};
	}
}
