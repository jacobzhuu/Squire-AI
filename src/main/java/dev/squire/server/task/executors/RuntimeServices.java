package dev.squire.server.task.executors;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.server.MinecraftServer;

/**
 * The world-facing services executors may use, resolved by the runtime at registration
 * time. Executors stay server-thread-only and never see caller-controlled context.
 */
public interface RuntimeServices {
	MinecraftServer server();

	/** Resolved live avatar for the agent (world-scan fallback included, ADR-017). */
	AvatarEntity avatar(java.util.UUID agentId);

	long currentTick();

	/** 玩家可读的中文物品名；最小测试运行时可安全退回注册表路径。 */
	default String itemDisplayName(net.minecraft.util.Identifier itemId) {
		return itemId == null ? "" : itemId.getPath();
	}

	/**
	 * Resolve the PLAYER behind a requester id: player manager first, then any
	 * ServerPlayerEntity living in a loaded world (covers test fake players).
	 *
	 * @return null when the requester is offline / not present
	 */
	net.minecraft.server.network.ServerPlayerEntity requester(java.util.UUID playerId);

	/** The runtime's shared WorldEditor (M3); null in minimal/test runtimes. */
	default dev.squire.server.world.WorldEditor worldEditor() {
		return null;
	}

	/** Scoped capability store backing high-risk edits (§30). */
	default dev.squire.server.security.CapabilityStore capabilities() {
		return null;
	}

	/**
	 * 方案 C5：普通采集、放置、容器修改和自动化写入必须与 WorldEdit/CBP 共用同一个
	 * Protection 判定。最小/测试运行时返回全放行的适配器，而不是 null，这样调用点
	 * 永远只有一条代码路径。
	 */
	default dev.squire.server.world.ProtectionAdapter protection() {
		return dev.squire.server.world.ProtectionAdapter.ALLOW_ALL;
	}

	/**
	 * 蓝图子系统（第 1 期）；最小/测试运行时返回 null。
	 *
	 * <p>施工与掘进执行器只拿得到一个 {@code placementId}，形状每次从这里现算——
	 * 任务参数里绝不塞几百个坐标，重启后才能从断点续建。</p>
	 */
	default dev.squire.server.blueprint.BlueprintManager blueprints() {
		return null;
	}

	/** Spend one real item from a durable project escrow. Non-project runtimes refuse. */
	default boolean consumeProjectMaterial(java.util.UUID projectId,
			net.minecraft.util.Identifier itemId, int count) {
		return false;
	}

	default int projectMaterialCount(java.util.UUID projectId,
			net.minecraft.util.Identifier itemId) {
		return 0;
	}

	/**
	 * 这只随从的档案（第 2 期）；最小/测试运行时返回 null。
	 *
	 * <p>执行器用它回答一个问题：「他会这个吗」。读不到档案时一律按
	 * 「只会基础能力」处理，而不是一律放行或一律拦住。</p>
	 */
	default dev.squire.server.profile.SquireProfile profile(java.util.UUID agentId) {
		return null;
	}

	/**
	 * 这只随从的职业进度（守卫 / 工程师，1–10 级）；档案读不到时返回 null。
	 *
	 * <p>和 {@link #profile} 分开问，是因为两者的<b>缺省语义相反</b>：没有档案时
	 * 基础能力照样会做，但没有职业时职业行为一件也不会——那些行为的前提就是
	 * 玩家真的把他往那个方向培养过。</p>
	 */
	default dev.squire.server.profession.ProfessionData professionData(
			java.util.UUID agentId) {
		dev.squire.server.profile.SquireProfile profile = profile(agentId);
		return profile == null ? null : profile.profession;
	}

	/** 职业系统的平衡表。最小/测试运行时用内置默认表。 */
	default dev.squire.server.profession.ProfessionConfig professionConfig() {
		return dev.squire.server.profession.ProfessionConfig.defaults();
	}

	/** 他到这一级了吗。没有职业的随从一律 false（走原来那条路，不是「全都会」）。 */
	default boolean canProfession(java.util.UUID agentId,
			dev.squire.server.profession.ProfessionAbility ability) {
		dev.squire.server.profession.ProfessionData data = professionData(agentId);
		return data != null && data.can(ability);
	}

	/** 他会这个吗。档案缺失时只放行基础能力。 */
	default boolean can(java.util.UUID agentId, dev.squire.server.profile.Ability ability) {
		if (ability == null) {
			return false;
		}
		dev.squire.server.profile.SquireProfile profile = profile(agentId);
		return profile == null ? ability.basic() && ability.available()
			: profile.can(ability);
	}
}
