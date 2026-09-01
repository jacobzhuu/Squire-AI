package dev.squire.server.registry;

import dev.squire.SquireMod;
import dev.squire.server.body.avatar.AvatarEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Registry for Squire entity types.
 */
public final class SquireEntities {
	public static final EntityType<AvatarEntity> AVATAR = Registry.register(
		Registries.ENTITY_TYPE,
		new Identifier(SquireMod.MOD_ID, "avatar"),
		FabricEntityTypeBuilder.createMob()
			.spawnGroup(SpawnGroup.MISC)
			.dimensions(EntityDimensions.fixed(0.6f, 1.8f))
			.trackRangeChunks(8)
			// 玩家外形的伙伴通常就在镜头旁边。Fabric 默认每 3 tick 才发一次位置，
			// 近距离看会有明显的“拖一下、追一下”；逐 tick 同步让客户端插值连续。
			.trackedUpdateRate(1)
			.entityFactory(AvatarEntity::new)
			.build());

	private SquireEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(AVATAR, AvatarEntity.createAttributes());
		SquireMod.LOGGER.info("[Squire] entities registered");
	}
}
