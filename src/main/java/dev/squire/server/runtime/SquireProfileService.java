package dev.squire.server.runtime;

import java.util.List;
import java.util.Optional;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profile.Ability;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Track;
import dev.squire.server.profile.Trait;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * 职业 / 能力槽 / 自主档位的玩家意图层。
 *
 * <p>三条贯穿这里的规则：</p>
 * <ul>
 *   <li><b>每一条拒绝都要有出路。</b>没解锁就说还差多少熟练度，槽满就说摘哪一个，
 *       冷却中就说还剩几秒——绝不留一句「不行」。</li>
 *   <li><b>换职业要在家门口。</b>在野外临时改行会让「专精」变成一个随时可切的菜单，
 *       取舍就不存在了。没设过家的玩家例外放行，并如实说明。</li>
 *   <li><b>未落地的东西如实说未开放</b>，绝不假装存在（农夫、完全自动、
 *       以及第 2 期后半段才接上的几项能力）。</li>
 * </ul>
 */
final class SquireProfileService {

	/** 换职业必须离家这么近。 */
	private static final double HOME_RADIUS = 24.0;

	private final SquireRuntime runtime;

	SquireProfileService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	// ------------------------------------------------------------------ 查看

	public SquireRuntime.ExecutionResult status(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireProfile profile = bound.profile();
		Role role = profile.role();
		StringBuilder text = new StringBuilder("[Squire] 档案：");
		if (role == null) {
			text.append("通用随从（还没有职业）\n")
				.append("基础能力都会：护卫、按蓝图施工、掘进、搬运、送货、记点。\n")
				.append("可在侍从面板选择职业，开始积累熟练度并解锁进阶能力。");
		} else {
			int level = profile.level();
			long total = role.track() == null ? 0 : profile.proficiencyOf(role.track());
			long next = Track.remainingToNextMilestone(total);
			text.append(role.displayName()).append(" Lv").append(level)
				.append("（").append(role.track() == null ? "-" : role.track().id())
				.append(" ").append(total).append("）");
			text.append(next > 0 ? "，距下一档还差 " + next : "，已满档");
			text.append("\n能力槽 ").append(profile.equippedAbilities.size())
				.append("/").append(profile.slots()).append("：");
			if (profile.equippedAbilities.isEmpty()) {
				text.append("（空）");
			}
			for (Ability ability : profile.equippedList()) {
				text.append("\n  · ").append(ability.displayName()).append(" —— ")
					.append(ability.summary());
			}
		}
		List<Trait> traits = profile.traitList();
		if (!traits.isEmpty()) {
			text.append("\n性格：");
			for (Trait trait : traits) {
				text.append(trait.displayName()).append("（").append(trait.summary())
					.append("）");
			}
		}
		text.append("\n自主程度：").append(profile.autonomyLevel().displayName())
			.append("（").append(profile.autonomyLevel().summary()).append("）");
		text.append("\n能力可在侍从面板的档案页查看和装配。");
		return SquireRuntime.ExecutionResult.ok("feedback.profile_status",
			text.toString());
	}

	public SquireRuntime.ExecutionResult listRoles(ServerPlayerEntity sender) {
		StringBuilder text = new StringBuilder("[Squire] 职业：");
		for (Role role : Role.values()) {
			text.append("\n  · ").append(role.id()).append(" —— ")
				.append(role.displayName());
			if (!role.available()) {
				text.append("（未开放）");
				continue;
			}
			text.append("：");
			for (Ability ability : Ability.of(role)) {
				text.append("\n      ").append(ability.basic() ? "基础" : "Lv"
					+ ability.unlockLevel()).append(" ")
					.append(ability.displayName())
					.append(ability.available() ? "" : "（未开放）");
			}
		}
		text.append("\n可在侍从面板的档案页选择。");
		return SquireRuntime.ExecutionResult.ok("feedback.role_list", text.toString());
	}

	public SquireRuntime.ExecutionResult listAbilities(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireProfile profile = bound.profile();
		StringBuilder text = new StringBuilder("[Squire] 能力（槽位 ")
			.append(profile.equippedAbilities.size()).append("/")
			.append(profile.slots()).append("）：");
		for (Ability ability : Ability.values()) {
			if (ability.basic()) {
				continue; // 基础能力人人都有，列出来只会淹没真正要选的东西
			}
			String state;
			if (!ability.available()) {
				state = "未开放";
			} else if (profile.isEquipped(ability)) {
				state = "已装备";
			} else if (profile.isUnlocked(ability)) {
				state = "已解锁";
			} else {
				state = ability.role().displayName() + " Lv" + ability.unlockLevel()
					+ " 解锁";
			}
			text.append("\n  · ").append(ability.id()).append(" ")
				.append(ability.displayName()).append("（").append(state).append("）—— ")
				.append(ability.summary());
		}
		text.append("\n可在侍从面板的档案页装上或摘掉能力。");
		return SquireRuntime.ExecutionResult.ok("feedback.ability_list", text.toString());
	}

	// ------------------------------------------------------------------ 选职业

	public SquireRuntime.ExecutionResult setRole(ServerPlayerEntity sender,
			String roleId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		Role role = Role.byId(roleId);
		if (role == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.role_unknown",
				"[Squire] 没有叫「" + roleId + "」的职业。可在档案页查看已有选择。");
		}
		if (!role.available()) {
			return SquireRuntime.ExecutionResult.fail("feedback.role_locked",
				"[Squire] " + role.displayName()
					+ "还没开放。耕作要从零起一个子系统，而且它本质是野外采集——"
					+ "正是我不做的那个方向。");
		}
		SquireProfile profile = bound.profile();
		if (role == profile.role()) {
			return SquireRuntime.ExecutionResult.ok("feedback.role_unchanged",
				"[Squire] 我已经是" + role.displayName() + "了。");
		}
		SquireRuntime.ExecutionResult blocked = checkRespec(sender, bound);
		if (blocked != null) {
			return blocked;
		}
		profile.roleId = role.id();
		profile.lastRespecTick = runtime.currentTick();
		List<Ability> unlocked = profile.refreshUnlocks();
		List<Ability> dropped = profile.pruneEquipped();
		runtime.persistSnapshot(bound.avatar());

		StringBuilder text = new StringBuilder("[Squire] 好，我现在是")
			.append(role.displayName()).append("。");
		text.append("\n基础能力：");
		for (Ability ability : Ability.of(role)) {
			if (ability.basic()) {
				text.append(ability.displayName()).append(" ");
			}
		}
		text.append("\n熟练度记在「").append(role.track().id())
			.append("」上，练到 Lv2 解锁第一个进阶能力。");
		if (!unlocked.isEmpty()) {
			text.append("\n之前练过的还在：");
			unlocked.forEach(a -> text.append(a.displayName()).append(" "));
		}
		if (!dropped.isEmpty()) {
			text.append("\n摘掉了不再合法的：");
			dropped.forEach(a -> text.append(a.displayName()).append(" "));
		}
		text.append("\n可在档案页看看能装什么。");
		return SquireRuntime.ExecutionResult.ok("feedback.role_set", text.toString());
	}

	// ------------------------------------------------------------------ 装备能力

	public SquireRuntime.ExecutionResult equip(ServerPlayerEntity sender,
			String abilityId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		Ability ability = Ability.byId(abilityId);
		if (ability == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.ability_unknown",
				"[Squire] 没有叫「" + abilityId + "」的能力。可在档案页查看能力列表。");
		}
		if (!ability.available()) {
			return SquireRuntime.ExecutionResult.fail("feedback.ability_locked",
				"[Squire] 「" + ability.displayName() + "」还没做完，现在装上去也不会有反应，"
					+ "所以我不让你装。");
		}
		SquireProfile profile = bound.profile();
		if (ability.basic()) {
			return SquireRuntime.ExecutionResult.ok("feedback.ability_basic",
				"[Squire] 「" + ability.displayName() + "」是基础能力，我本来就会，不占槽。");
		}
		if (profile.isEquipped(ability)) {
			return SquireRuntime.ExecutionResult.ok("feedback.ability_equipped",
				"[Squire] 「" + ability.displayName() + "」已经装着了。");
		}
		if (!profile.isUnlocked(ability)) {
			Role need = ability.role();
			long have = need.track() == null ? 0 : profile.proficiencyOf(need.track());
			return SquireRuntime.ExecutionResult.fail("feedback.ability_unearned",
				"[Squire] 「" + ability.displayName() + "」要" + need.displayName()
					+ " Lv" + ability.unlockLevel() + " 才解锁"
					+ (need == profile.role()
						? "，现在是 Lv" + profile.level() + "（" + need.track().id()
							+ " " + have + "，还差 "
							+ Track.remainingToNextMilestone(have) + "）。"
						: "。那是" + need.displayName() + "的能力，要先当过"
							+ need.displayName() + "才练得出来。"));
		}
		int slots = profile.slots();
		if (profile.equippedAbilities.size() >= slots) {
			StringBuilder text = new StringBuilder("[Squire] 槽位满了（")
				.append(slots).append(" 个）。请在档案页先摘一个：");
			for (Ability equipped : profile.equippedList()) {
				text.append("\n  · ").append(equipped.displayName());
			}
			text.append("\n练到 Lv").append(Role.THIRD_SLOT_LEVEL).append(" / Lv")
				.append(Role.FOURTH_SLOT_LEVEL).append(" 会多两个槽。");
			return SquireRuntime.ExecutionResult.fail("feedback.slots_full",
				text.toString());
		}
		int slotIndex = profile.equippedAbilities.size();
		if (Role.slotIsRoleLocked(slotIndex) && ability.role() != profile.role()) {
			return SquireRuntime.ExecutionResult.fail("feedback.slot_role_locked",
				"[Squire] 第 " + (slotIndex + 1) + " 个槽只收本职业的能力，「"
					+ ability.displayName() + "」是" + ability.role().displayName()
					+ "的。跨职业能力只能装进前 " + (Role.BASE_SLOTS + 1) + " 个槽。");
		}
		SquireRuntime.ExecutionResult blocked = checkRespec(sender, bound);
		if (blocked != null) {
			return blocked;
		}
		profile.equippedAbilities.add(ability.id());
		profile.lastRespecTick = runtime.currentTick();
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.ability_equipped",
			"[Squire] 装上「" + ability.displayName() + "」了：" + ability.summary()
				+ "。（" + profile.equippedAbilities.size() + "/" + slots + " 槽）");
	}

	public SquireRuntime.ExecutionResult unequip(ServerPlayerEntity sender,
			String abilityId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		Ability ability = Ability.byId(abilityId);
		SquireProfile profile = bound.profile();
		if (ability == null || !profile.isEquipped(ability)) {
			return SquireRuntime.ExecutionResult.fail("feedback.ability_not_equipped",
				"[Squire] 我没装着「" + abilityId + "」。可在档案页查看当前能力。");
		}
		profile.equippedAbilities.remove(ability.id());
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.ability_removed",
			"[Squire] 摘掉「" + ability.displayName() + "」了。（"
				+ profile.equippedAbilities.size() + "/" + profile.slots() + " 槽）");
	}

	// ------------------------------------------------------------------ 自主档位

	public SquireRuntime.ExecutionResult setAutonomy(ServerPlayerEntity sender,
			String levelId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		AutonomyLevel level = AutonomyLevel.byId(levelId);
		if (level == null) {
			StringBuilder text = new StringBuilder("[Squire] 没有这一档。有这些：");
			for (AutonomyLevel candidate : AutonomyLevel.values()) {
				text.append("\n  · ").append(candidate.id()).append(" ")
					.append(candidate.displayName()).append(" —— ")
					.append(candidate.summary());
			}
			return SquireRuntime.ExecutionResult.fail("feedback.autonomy_unknown",
				text.toString());
		}
		if (!level.available()) {
			return SquireRuntime.ExecutionResult.fail("feedback.autonomy_locked",
				"[Squire] 「" + level.displayName()
					+ "」还没开放。这一档会让我自己消耗你的材料、自己改动世界——"
					+ "在前三档跑稳之前，我不想拿你的存档试。");
		}
		bound.profile().autonomy = level.id();
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.autonomy_set",
			"[Squire] 自主程度改成「" + level.displayName() + "」：" + level.summary()
				+ "。");
	}

	// ------------------------------------------------------------------ 巡逻点

	/**
	 * 把伙伴脚下这一格加进巡逻线。
	 *
	 * <p>取的是<b>他</b>的位置而不是玩家的：玩家要的是「你站到那儿去，把这里记下来」，
	 * 而不是在自己脚下放一个他可能根本过不去的点。</p>
	 */
	public SquireRuntime.ExecutionResult addPatrolPoint(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireProfile profile = bound.profile();
		if (profile.patrolPoints.size() >= SquireProfile.MAX_PATROL_POINTS) {
			return SquireRuntime.ExecutionResult.fail("feedback.patrol_full",
				"[Squire] 巡逻点最多 " + SquireProfile.MAX_PATROL_POINTS
					+ " 个了。可在指挥页点「清空巡逻点」。");
		}
		if (!(bound.avatar().getWorld() instanceof ServerWorld world)) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 他现在不在一个可用的世界里。");
		}
		var point = net.minecraft.util.math.GlobalPos.create(world.getRegistryKey(),
			bound.avatar().getBlockPos().toImmutable());
		profile.patrolPoints.add(point);
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.patrol_added",
			"[Squire] 记下了第 " + profile.patrolPoints.size() + " 个巡逻点（"
				+ point.getPos().toShortString() + "）。"
				+ (profile.patrolPoints.size() == 1
					? "\n再多设几个，然后在指挥页点「巡逻」。"
					: ""));
	}

	public SquireRuntime.ExecutionResult clearPatrolPoints(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		int had = bound.profile().patrolPoints.size();
		bound.profile().patrolPoints.clear();
		bound.profile().patrolCursor = 0;
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.patrol_cleared",
			"[Squire] 清掉了 " + had + " 个巡逻点。现在巡逻会回到「绕着原地转圈」。");
	}

	public SquireRuntime.ExecutionResult listPatrolPoints(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireProfile profile = bound.profile();
		if (profile.patrolPoints.isEmpty()) {
			return SquireRuntime.ExecutionResult.ok("feedback.patrol_none",
				"[Squire] 还没有巡逻点，巡逻就是绕着原地转圈。"
					+ "\n把他带到一处，再在指挥页点「添加巡逻点」。");
		}
		StringBuilder text = new StringBuilder("[Squire] 巡逻线（按顺序走）：");
		for (int i = 0; i < profile.patrolPoints.size(); i++) {
			var point = profile.patrolPoints.get(i);
			text.append("\n").append(i + 1).append(". ")
				.append(point.getPos().toShortString())
				.append(i == profile.patrolCursor ? "  ← 下一个" : "");
		}
		text.append("\n到点会停下来巡检一次");
		text.append(runtime.can(bound.avatar(),
				dev.squire.server.profile.Ability.EXPLORE_WARN)
			? "，发现威胁或暗处会告诉你。"
			: "（要他报告发现，给他装上探险家的「预警」）。");
		return SquireRuntime.ExecutionResult.ok("feedback.patrol_list", text.toString());
	}


	// ------------------------------------------------------------------ helpers

	private record Bound(AvatarEntity avatar, SquireProfile profile,
			SquireRuntime.ExecutionResult failure) { }

	private Bound bind(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return new Bound(null, null, SquireRuntime.ExecutionResult.fail(
				"feedback.no_agent", "[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。"));
		}
		AvatarEntity avatar = found.get();
		SquireProfile profile = runtime.profileOf(avatar);
		if (profile == null) {
			return new Bound(null, null, SquireRuntime.ExecutionResult.fail(
				"feedback.no_profile",
				"[Squire] 读不到他的档案（存档还没就绪？）。稍后再试。"));
		}
		return new Bound(avatar, profile, null);
	}

	/**
	 * 换职业/换能力的两道闸：冷却，以及必须在家附近。
	 *
	 * @return null 表示放行
	 */
	private SquireRuntime.ExecutionResult checkRespec(ServerPlayerEntity sender,
			Bound bound) {
		SquireProfile profile = bound.profile();
		long now = runtime.currentTick();
		long since = now - profile.lastRespecTick;
		if (profile.lastRespecTick != Long.MIN_VALUE
				&& since < SquireProfile.RESPEC_COOLDOWN_TICKS) {
			long secondsLeft = (SquireProfile.RESPEC_COOLDOWN_TICKS - since) / 20L;
			return SquireRuntime.ExecutionResult.fail("feedback.respec_cooldown",
				"[Squire] 刚改过，让我先干一会儿活。还要等 " + secondsLeft + " 秒。");
		}
		// 家门口才能改行。在野外随时切职业，专精就退化成一个下拉菜单。
		var home = bound.avatar().homePos();
		if (home.isEmpty()) {
			return null; // 还没设过家，不拿一条玩家不知道的规则挡他
		}
		double distSq = bound.avatar().squaredDistanceTo(home.get().getX() + 0.5,
			home.get().getY(), home.get().getZ() + 0.5);
		if (distSq > HOME_RADIUS * HOME_RADIUS) {
			return SquireRuntime.ExecutionResult.fail("feedback.respec_away_from_home",
				"[Squire] 改行得回基地弄。可在指挥页让我回家，或者把这里定成新家。");
		}
		return null;
	}
}
