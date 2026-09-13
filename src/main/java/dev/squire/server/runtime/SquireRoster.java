package dev.squire.server.runtime;

import java.util.UUID;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 伙伴身体的物化与位置：召唤、恢复家当、找落脚点、受控传送、按档案重放模式。
 *
 * <p>从 {@link SquireRuntime} 里原样搬出来的，行为一个字没改。这里是「一个玩家有几只
 * 随从」这个问题唯一需要动的地方——多随从上线时，把
 * {@code agents().resolveForOwner} 换成按当前选中项解析，改动就收敛在这个类里，
 * 而不是散在 Runtime 的三十多处调用点上。</p>
 */
final class SquireRoster {

	private final SquireRuntime runtime;

	SquireRoster(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	// ------------------------------------------------------------------ summon

	/**
	 * Summon an avatar owned by {@code owner}（方案 A1/A2：同一 owner 的 agentId 与
	 * 名字永久复用；再次 summon 恢复档案里的背包/装备/血量/家/模式；首次召唤默认
	 * FOLLOW）。
	 */
	AvatarEntity summonFor(ServerPlayerEntity owner) {
		return summonAt(owner, findSpawnColumn((ServerWorld) owner.getWorld(), owner));
	}

	AvatarEntity summonAt(ServerPlayerEntity owner, BlockPos spawnColumn) {
		dev.squire.server.agent.SquireAgentStateStore store = runtime.agentStore();
		var existingRecord = store.recordOfOwner(owner.getUuid());
		boolean firstSummon = existingRecord.isEmpty();
		var record = existingRecord.orElseGet(() ->
			store.createRecord(owner.getUuid(), "Squire"));
		return materializeAt(owner, spawnColumn, record, firstSummon);
	}

	/** 创建第二份永久档案；现有身体不会被替换。数量/职业门槛由仪式层校验。 */
	AvatarEntity summonNewAt(ServerPlayerEntity owner, BlockPos spawnColumn) {
		var records = runtime.agentStore().recordsOfOwner(owner.getUuid());
		var record = runtime.agentStore().createRecord(owner.getUuid(),
			"Squire-" + (records.size() + 1));
		return materializeAt(owner, spawnColumn, record, true);
	}

	/** 召回铃按永久 id 物化指定侍从。 */
	AvatarEntity summonAgentAt(ServerPlayerEntity owner, BlockPos spawnColumn,
			UUID agentId) {
		var record = runtime.agentStore().recordOfAgent(agentId).orElse(null);
		if (record == null || !record.ownerId.equals(owner.getUuid())) return null;
		return materializeAt(owner, spawnColumn, record, false);
	}

	private AvatarEntity materializeAt(ServerPlayerEntity owner, BlockPos spawnColumn,
			dev.squire.server.agent.SquireAgentStateStore.AgentRecord record,
			boolean firstSummon) {
		if (record.oathDeadline > 0) return null;
        // 只替换同一个永久身份的旧身体，绝不碰同主人的另一名侍从。
		runtime.agents().resolveByAgentId(record.agentId).ifPresent(existing -> {
			runtime.persistSnapshot(existing);
			runtime.agents().unregister(existing.getUuid());
			existing.discard();
		});
		dev.squire.server.agent.SquireAgentStateStore store = runtime.agentStore();

		ServerWorld world = (ServerWorld) owner.getWorld(); // same dimension as the owner
		AvatarEntity avatar = dev.squire.server.registry.SquireEntities.AVATAR.create(world);
		if (avatar == null) {
			throw new IllegalStateException("avatar entity type failed to create");
		}
		avatar.refreshPositionAndAngles(spawnColumn.getX() + 0.5, spawnColumn.getY(),
			spawnColumn.getZ() + 0.5, owner.getYaw(), 0.0f);
		avatar.assignAgentId(record.agentId); // 方案 A1：身份跨实体实例复用
		avatar.setOwner(owner.getUuid());
		// setBaseName 而不是 setCustomName：忙碌状态会往名牌上拼后缀，闲下来要能还原本名。
		avatar.setBaseName(Text.literal("[Squire] " + record.displayName));
		SquireRuntime.MATERIALIZING.set(true);
		try {
			world.spawnEntity(avatar);
			runtime.agents().register(avatar);
		} finally {
			SquireRuntime.MATERIALIZING.set(false);
		}

		if (firstSummon) { // 首次召唤默认 FOLLOW，无历史状态可恢复
			avatar.setFollowMode(owner.getUuid());
			record.movementMode = "FOLLOW";
		} else if (record.profile.traits.isEmpty()) {
			// 旧存档里已经存在、但还没有性格的随从：补一次。
			rollTraits(record, world.getRandom());
		}
		// 守卫的生命上限是等级<b>派生</b>的属性，不存在实体身上，每次物化都要按等级
		// 重新设一遍。必须在 restoreBelongings <b>之前</b>：血量是从档案里写回来的，
		// 上限还停在 20 的时候写回 25 会被香草当场夹掉，Lv10 守卫每召回一次掉五点血。
		runtime.applyProfessionLevelEffects(avatar);
		if (firstSummon) {
			rollTraits(record, world.getRandom());
		} else {
			restoreBelongings(avatar, record); // 背包/装备/血量/家/模式
		}
		// 档案跟着实体走：Goal 要读能力、待命半径和巡逻点。
		avatar.attachProfile(() -> record.profile);
		// 任务接管期间移动 Goal 让位，但玩家的长期命令不丢。
		avatar.attachBusyCheck(() -> runtime.scheduler()
			.current(avatar.agentId()).isPresent());
		avatar.attachExplicitSelfCareCheck(() -> runtime.scheduler()
			.current(avatar.agentId())
			.map(task -> dev.squire.server.task.executors.HealTaskExecutor.TYPE
				.equals(task.type()))
			.orElse(false));
		// 移速与生命等实体属性型性格，每次物化都要幂等地重新写上去。
		avatar.applyTraits(record.profile.traits);
		store.snapshotFromEntity(avatar);
		// 首次召唤是唯一一次玩家一定会看的时刻，把"接下来能做什么"讲清楚；
		// 之后只报一句回归，不再刷屏。
		runtime.feedback(owner, firstSummon
			? dev.squire.server.help.CapabilityGuide.onboardingText(record.displayName)
			: "[Squire] " + record.displayName + " 回来了。在聊天里叫我"
				+ "「" + record.displayName + "」我才理你；说「你能做什么」看我会的事。");
		return avatar;
	}

	/**
	 * 首次召唤时抽 1~2 个性格特质。
	 *
	 * <p>特质<b>只影响机制，不影响说话风格</b>：把性格塞进提示词会和
	 * 解析器的严格 JSON 契约打架，而且完全不可测——「他今天是不是更谨慎了」
	 * 没有断言可写。改成阈值和速率之后，每一条都能被一个数字验证。</p>
	 */
	static void rollTraits(dev.squire.server.agent.SquireAgentStateStore.AgentRecord record,
			net.minecraft.util.math.random.Random random) {
		if (record == null || !record.profile.traits.isEmpty()) {
			return;
		}
		java.util.random.RandomGenerator source = new java.util.random.RandomGenerator() {
			@Override
			public long nextLong() {
				return random.nextLong();
			}

			@Override
			public int nextInt(int bound) {
				return random.nextInt(bound);
			}
		};
		for (var trait : dev.squire.server.profile.Trait.roll(source)) {
			record.profile.traits.add(trait.id());
		}
	}

	/** 方案 A2：把档案中的家当与长期状态写回新物化的实体。 */
	static void restoreBelongings(AvatarEntity avatar,
			dev.squire.server.agent.SquireAgentStateStore.AgentRecord record) {
		net.minecraft.inventory.Inventory inventory = avatar.backingInventory();
		for (int i = 0; i < inventory.size() && i < record.inventory.size(); i++) {
			net.minecraft.item.ItemStack saved = record.inventory.get(i);
			inventory.setStack(i,
				saved == null || saved.isEmpty()
					? net.minecraft.item.ItemStack.EMPTY : saved.copy());
		}
		if (!record.inventory.isEmpty()) {
			inventory.markDirty();
		}
		avatar.setBackpackStack(record.backpack.copy());
		avatar.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, record.mainHand.copy());
		avatar.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, record.offHand.copy());
		for (int i = 0; i < 4; i++) {
			avatar.equipStack(
				net.minecraft.entity.EquipmentSlot.fromTypeIndex(
					net.minecraft.entity.EquipmentSlot.Type.ARMOR, i),
				record.armor[i].copy());
		}
		if (record.health > 0.0f && !Float.isNaN(record.health)) {
			avatar.setHealth(Math.min(Math.max(1.0f, record.health),
				avatar.getMaxHealth()));
		}
		if (record.homeGlobalPos != null) {
			avatar.setHomeGlobalPos(record.homeGlobalPos);
		}
		AvatarEntity.MovementMode mode;
		try {
			mode = AvatarEntity.MovementMode.valueOf(record.movementMode);
		} catch (IllegalArgumentException badName) {
			mode = AvatarEntity.MovementMode.IDLE;
		}
		avatar.applyMovementState(mode, record.ownerId, record.stayGlobalPos);
	}

	/**
	 * Safe standing column ~2 blocks in front of the owner. Uses ONLY the
	 * horizontal facing (the old rotation-vector offset included pitch, so
	 * summoning while looking down buried the body in the ground), then snaps to
	 * the nearest standable column; falls back to the owner's own tile — by
	 * definition survivable, since the player is standing on it.
	 */
	static BlockPos findSpawnColumn(ServerWorld world, ServerPlayerEntity owner) {
		double yawRad = Math.toRadians(owner.getYaw());
		net.minecraft.util.math.Vec3d flat = new net.minecraft.util.math.Vec3d(
			-Math.sin(yawRad), 0.0, Math.cos(yawRad));
		BlockPos requested = BlockPos.ofFloored(
			owner.getPos().add(flat.multiply(2)));
		for (int dy = 1; dy >= -3; dy--) { // prefer at/above the owner's feet level
			BlockPos candidate = requested.add(0, dy, 0);
			if (isStandable(world, candidate)) {
				return candidate;
			}
		}
		return owner.getBlockPos();
	}

	static boolean isStandable(ServerWorld world, BlockPos feet) {
		return world.getBlockState(feet).isAir()
			&& world.getBlockState(feet.up()).isAir()
			&& world.getBlockState(feet.down()).isSolidBlock(world, feet.down());
	}


	/**
	 * 方案 A2：把档案里的长期事实写回实体（家、STAY 锚点、模式）。
	 * 背包/装备/血量由实体自身 NBT 持久化，无需在此重放。
	 */
	static void restoreRecordOnto(AvatarEntity avatar,
			dev.squire.server.agent.SquireAgentStateStore.AgentRecord record) {
		if (record.homeGlobalPos != null && avatar.homeGlobalPos().isEmpty()) {
			avatar.setHomeGlobalPos(record.homeGlobalPos);
		}
		AvatarEntity.MovementMode mode;
		try {
			mode = AvatarEntity.MovementMode.valueOf(record.movementMode);
		} catch (IllegalArgumentException badName) {
			mode = AvatarEntity.MovementMode.IDLE;
		}
		avatar.applyMovementState(mode, record.ownerId, record.stayGlobalPos);
	}

	/**
	 * 受控传送（方案 A2）：同维度直接落位；跨维度走 {@link Entity#teleport}。
	 * 跨维度会换出新的实体实例（vanilla 走 NBT 复制），模式在原实例上是运行时字段，
	 * 因此传送后按档案重放 FOLLOW/STAY，并返回当前生效的实体。
	 */
	AvatarEntity controlledTeleport(AvatarEntity avatar, ServerWorld target,
			net.minecraft.util.math.GlobalPos home) {
		if (avatar.oathActive()) return null;
        BlockPos pos = home.getPos();
		AvatarEntity.MovementMode mode = avatar.mode();
		net.minecraft.util.math.GlobalPos anchor =
			avatar.stayGlobalPos().orElse(null);
		UUID ownerId = avatar.ownerId();
		avatar.stopMoving();
		if (target == avatar.getWorld()) {
			avatar.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(),
				pos.getZ() + 0.5, avatar.getYaw(), 0.0f);
			return avatar;
		}
		boolean moved = avatar.teleport(target, pos.getX() + 0.5, pos.getY(),
			pos.getZ() + 0.5,
			java.util.Set.of(),
			avatar.getYaw(), 0.0f);
		if (!moved) {
			return null; // UNREACHABLE — caller reports failure
		}
		AvatarEntity arrived = runtime.agents().resolveByAgentId(avatar.agentId()).orElse(null);
		if (arrived == null) {
			dev.squire.SquireMod.LOGGER.error(
				"[Squire] cross-dimension teleport lost agent {}", avatar.agentId());
			return null;
		}
		// 运行时模式不进实体 NBT：跨实例后重放。跨维度时旧锚点属于旧维度，
		// applyMovementState 会因维度不符退回 IDLE——所以这里先把它换成落点 home。
		net.minecraft.util.math.GlobalPos replayAnchor =
			anchor != null && anchor.getDimension().equals(target.getRegistryKey())
				? anchor : home;
		arrived.applyMovementState(mode, ownerId, replayAnchor);
		return arrived;
	}
}
