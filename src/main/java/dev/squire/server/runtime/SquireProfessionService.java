package dev.squire.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.CombatStance;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.ProfessionService;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profile.SquireProfile;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 职业系统的玩家意图层：看进度、选职业、晋升、改战斗姿态、清点补给。
 *
 * <p>三条贯穿这里的规则，和 {@link SquireProfileService} 是同一套：</p>
 * <ul>
 *   <li><b>每一条拒绝都要有出路。</b>经验不够就说还差多少，材料不够就说缺哪几件、
 *       缺几个，满级了就说到顶了——绝不留一句「不行」。</li>
 *   <li><b>晋升材料只从玩家身上扣</b>，而且<b>先查齐再扣</b>：不能出现扣了铁锭却
 *       因为缺钻石而失败，那是玩家永远无法原谅的一类 bug。</li>
 *   <li><b>禁止自动晋升。</b>经验满了只发一句提示，升不升是玩家的事。</li>
 * </ul>
 */
public final class SquireProfessionService {

	private final SquireRuntime runtime;

	SquireProfessionService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	private ProfessionService service() {
		return runtime.professions();
	}

	private ProfessionConfig config() {
		return runtime.professionConfig();
	}

	// ------------------------------------------------------------------ 查看

	public SquireRuntime.ExecutionResult status(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProfessionData data = bound.data();
		SquireProfession profession = data.profession();
		StringBuilder text = new StringBuilder("[Squire] 职业：");
		if (profession == null) {
			text.append("还没有选职业。\n")
				.append("选一个之后才会积累职业经验、按等级解锁新的做法：\n")
				.append("  守卫 —— 处理世界里的实体威胁\n")
				.append("  工程师 —— 处理模板建筑与蓝图\n")
				.append("在侍从面板的「职业」页可以查看并选择。");
			return SquireRuntime.ExecutionResult.ok("feedback.profession_status",
				text.toString());
		}
		int needed = service().xpNeeded(data);
		text.append(profession.displayName()).append(" Lv").append(data.level);
		if (data.isMaxLevel()) {
			text.append("（已满级）");
		} else {
			text.append("\n经验 ").append(service().barText(data)).append(" ")
				.append(data.xp).append(" / ").append(needed);
			if (data.overflowXp > 0) {
				text.append("（溢出 ").append(data.overflowXp).append("，晋升后转入下一级）");
			}
		}

		text.append("\n已经会的：");
		for (ProfessionAbility ability : ProfessionAbility.of(profession)) {
			if (data.can(ability)) {
				text.append("\n  ✓ ").append(ability.displayName()).append(" —— ")
					.append(ability.summary());
			}
		}
		ProfessionAbility next = ProfessionAbility.nextAfter(profession, data.level);
		if (next != null) {
			text.append("\n下一个（Lv").append(next.unlockLevel()).append("）：")
				.append(next.displayName()).append(" —— ").append(next.summary());
		}

		if (data.can(ProfessionAbility.GUARD_COMBAT_STANCE)) {
			text.append("\n战斗姿态：").append(data.combatStance().displayName())
				.append("（可在职业页调整）");
		}

		ProfessionService.PromotionCheck check = service().checkPromotion(data);
		if (check.ok()) {
			text.append("\n\n★ 可以晋升到 Lv").append(check.targetLevel()).append(" 了。")
				.append("\n需要：").append(ProfessionService.describeCost(check.cost()))
				.append("\n把材料带在身上，然后在职业页点「晋升」。");
		} else if (!data.isMaxLevel()) {
			text.append("\n晋升到 Lv").append(check.targetLevel()).append(" 需要：")
				.append(ProfessionService.describeCost(check.cost()))
				.append("，以及练满这一条经验。");
		}
		return SquireRuntime.ExecutionResult.ok("feedback.profession_status",
			text.toString());
	}

	public SquireRuntime.ExecutionResult list(ServerPlayerEntity sender) {
		StringBuilder text = new StringBuilder("[Squire] 职业与能学到的东西：");
		for (SquireProfession profession : SquireProfession.values()) {
			text.append("\n\n").append(profession.id()).append(" —— ")
				.append(profession.displayName()).append("：");
			for (ProfessionAbility ability : ProfessionAbility.of(profession)) {
				text.append("\n  Lv").append(ability.unlockLevel()).append(" ")
					.append(ability.displayName())
					.append(ability.available() ? "" : "（未开放）")
					.append(" —— ").append(ability.summary());
			}
		}
		text.append("\n\n可在侍从面板的「职业」页选择。");
		return SquireRuntime.ExecutionResult.ok("feedback.profession_list",
			text.toString());
	}

	// ------------------------------------------------------------------ 选职业

	public SquireRuntime.ExecutionResult choose(ServerPlayerEntity sender,
			String professionId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		SquireProfession profession = SquireProfession.byId(professionId);
		if (profession == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.profession_unknown",
				"[Squire] 没有叫「" + professionId + "」的职业。现在有：guard（守卫）、"
					+ "engineer（工程师）。详情可在职业页查看。");
		}
		ProfessionData data = bound.data();
		if (profession == data.profession()) {
			return SquireRuntime.ExecutionResult.ok("feedback.profession_unchanged",
				"[Squire] 我已经是" + profession.displayName() + " Lv" + data.level
					+ " 了。");
		}
		// 面板按钮和这条命令走<b>同一个</b>判断（训练够不够、是不是已经有职业了）。
		// 两处各写一遍，迟早出现「按钮灰着但命令能过」这种玩家没法理解的状态。
		ProfessionService.ChoiceCheck check = service().checkChoice(data, profession);
		if (!check.ok()) {
			SquireProfession previous = data.profession();
			return SquireRuntime.ExecutionResult.fail(
				previous != null ? "feedback.profession_occupied"
					: "feedback.profession_untrained",
				"[Squire] " + check.reason()
					+ (previous != null
						? "\n真要改行，请在基地附近打开职业页。"
						: "\n这些都能在面板的「职业」页里看到进度。"));
		}
		data.setProfession(profession);
		// 顺手把同源的旧职业也定下来，玩家不必把同一件事说两遍。
		SquireProfile profile = bound.profile();
		if (profile.roleId == null && profession.kindredRole() != null
				&& profession.kindredRole().available()) {
			profile.roleId = profession.kindredRole().id();
			profile.refreshUnlocks();
		}
		bound.avatar().refreshNameplate();
		runtime.persistSnapshot(bound.avatar());

		StringBuilder text = new StringBuilder("[Squire] 好，我现在是")
			.append(profession.displayName()).append(" Lv1。");
		text.append("\n现在会的：");
		for (ProfessionAbility ability : ProfessionAbility.of(profession)) {
			if (ability.unlockLevel() == 1) {
				text.append(ability.displayName()).append(" ");
			}
		}
		ProfessionAbility next = ProfessionAbility.nextAfter(profession, 1);
		if (next != null) {
			text.append("\n练到 Lv").append(next.unlockLevel()).append(" 解锁「")
				.append(next.displayName()).append("」：").append(next.summary());
		}
		text.append("\n升级要两样东西：经验（真的去做这一行的事），和晋升材料（")
			.append(ProfessionService.describeCost(config().promotionCost(profession, 2)))
			.append("）。")
			.append("\n进度和晋升入口都在侍从面板的「职业」页。");
		return SquireRuntime.ExecutionResult.ok("feedback.profession_set",
			text.toString());
	}

	/** 卸掉职业。等级和经验清零，Boss 台账保留（否则改行就成了洗衰减的手段）。 */
	public SquireRuntime.ExecutionResult forget(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProfessionData data = bound.data();
		SquireProfession previous = data.profession();
		if (previous == null) {
			return SquireRuntime.ExecutionResult.ok("feedback.profession_none",
				"[Squire] 我本来就没有职业。可以在职业页挑一个。");
		}
		int lostLevel = data.level;
		data.forgetProfession();
		// 生命上限是等级派生的，改行就得跟着回去——否则一个卸任的守卫会一直留着 15 颗心。
		applyLevelEffects(bound.avatar(), data, false);
		bound.avatar().refreshNameplate();
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.profession_forgotten",
			"[Squire] 不当" + previous.displayName() + "了（Lv" + lostLevel
				+ " 的进度已经清零）。基础的事我照样会做。\n"
				+ "可以在职业页挑个新的。");
	}

	// ------------------------------------------------------------------ 晋升

	public SquireRuntime.ExecutionResult promote(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProfessionData data = bound.data();
		ProfessionService.PromotionCheck check = service().checkPromotion(data);
		if (!check.ok()) {
			String hint = data.hasProfession() && !data.isMaxLevel()
				? "\n经验来自真的去做这一行的事：守卫要参与战斗，工程师要完成蓝图施工。"
				: "";
			return SquireRuntime.ExecutionResult.fail("feedback.promotion_blocked",
				"[Squire] 还不能晋升。" + check.reason() + hint);
		}

		List<ProfessionConfig.ItemRequirement> cost = check.cost();
		List<String> missing = missingItems(sender, bound.avatar(), cost);
		if (!missing.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.promotion_materials",
				"[Squire] 经验够了，材料还差：" + String.join("、", missing)
					+ "\n晋升到 Lv" + check.targetLevel() + " 一共要 "
					+ ProfessionService.describeCost(cost)
					+ "，放在你自己的背包里就行，我从你身上取。");
		}
		// 先查齐、再一次性扣。绝不出现「扣了一半才发现不够」。
		consume(sender, bound.avatar(), cost);

		ProfessionService.PromotionOutcome outcome = service().promote(data);
		applyLevelEffects(bound.avatar(), data, true);
		bound.avatar().refreshNameplate();
		runtime.persistSnapshot(bound.avatar());

		StringBuilder text = new StringBuilder("[Squire] 晋升了：")
			.append(data.profession().displayName()).append(" Lv")
			.append(outcome.levelBefore()).append(" → Lv").append(outcome.levelAfter())
			.append("。");
		for (ProfessionAbility ability : outcome.unlocked()) {
			text.append("\n新学会「").append(ability.displayName()).append("」：")
				.append(ability.summary());
		}
		if (outcome.carriedOverflow() > 0) {
			text.append("\n之前攒下的 ").append(outcome.carriedOverflow())
				.append(" 点经验转过来了：").append(outcome.carriedOverflow())
				.append(" / ").append(outcome.newNeeded());
		}
		if (data.profession() == SquireProfession.GUARD) {
			text.append("\n生命上限现在是 ")
				.append(config().guardMaxHealth(data.level)).append("。");
		}
		return SquireRuntime.ExecutionResult.ok("feedback.promoted", text.toString());
	}

	/** 没有守卫职业时的生命上限，和 {@code AvatarEntity.createAttributes} 一致。 */
	static final double BASE_MAX_HEALTH = 20.0;
	/** 没有守卫职业时的空手攻击力，同上。武器的加成叠在它上面。 */
	static final double BASE_ATTACK_DAMAGE = 2.0;

	/**
	 * 把等级效果落到身上。目前只有守卫的生命上限。
	 *
	 * <p>它是<b>按表设定</b>而不是加成叠加，所以重复调用不会越叠越高；不是守卫
	 * （或者刚改行）时回到 20 点，否则一个卸任的守卫会永远留着 15 颗心。</p>
	 *
	 * @param healTheGain 涨出来的那几点直接补满。晋升的那一刻才该这样——每次召回
	 *                    都补一次，等于「重新召唤 = 免费回血」。
	 */
	void applyLevelEffects(AvatarEntity avatar, ProfessionData data,
			boolean healTheGain) {
		if (avatar == null) {
			return;
		}
		boolean guard = data != null && data.profession() == SquireProfession.GUARD;

		// 职业附加的空手伤害。刻意<b>加在基础值上</b>而不是乘一个倍率：
		// 武器的加成叠在同一条属性上，所以一把下界合金剑的伤害不会被职业等级放大。
		var damage = avatar.getAttributeInstance(
			net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
		if (damage != null) {
			damage.setBaseValue(BASE_ATTACK_DAMAGE
				+ (guard ? config().guardBonusDamage(data.level) : 0.0));
		}

		double wanted = guard ? config().guardMaxHealth(data.level) : BASE_MAX_HEALTH;
		var attribute = avatar.getAttributeInstance(
			net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
		if (attribute == null || attribute.getBaseValue() == wanted) {
			return;
		}
		double before = attribute.getBaseValue();
		float health = avatar.getHealth();
		attribute.setBaseValue(wanted);
		float gained = healTheGain ? (float) Math.max(0.0, wanted - before) : 0f;
		avatar.setHealth(Math.min(avatar.getMaxHealth(), health + gained));
	}

	// ------------------------------------------------------------------ 战斗姿态

	public SquireRuntime.ExecutionResult setStance(ServerPlayerEntity sender,
			String stanceId) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProfessionData data = bound.data();
		if (!data.can(ProfessionAbility.GUARD_COMBAT_STANCE)) {
			return SquireRuntime.ExecutionResult.fail("feedback.stance_locked",
				"[Squire] 战斗姿态是守卫 Lv"
					+ ProfessionAbility.GUARD_COMBAT_STANCE.unlockLevel() + " 的能力，"
					+ (data.profession() == SquireProfession.GUARD
						? "我现在才 Lv" + data.level + "。"
						: "我不是守卫。")
					+ "\n在那之前我按均衡打法走：追击不超过护卫半径的 "
					+ config().chaseFactor(CombatStance.BALANCED) + " 倍，"
					+ "血量低于 " + Math.round(config().guardRetreatThreshold * 100)
					+ "% 就撤。");
		}
		CombatStance stance = CombatStance.byId(stanceId);
		if (stance == null) {
			StringBuilder text = new StringBuilder("[Squire] 没有这一档姿态。有这些：");
			for (CombatStance candidate : CombatStance.values()) {
				text.append("\n  · ").append(candidate.id()).append(" ")
					.append(candidate.displayName()).append(" —— 追击上限 ")
					.append(config().chaseFactor(candidate)).append(" 倍护卫半径")
					.append(candidate.pullsAggro() ? "，会主动接战" : "，不主动拉怪");
			}
			return SquireRuntime.ExecutionResult.fail("feedback.stance_unknown",
				text.toString());
		}
		data.stance = stance.id();
		runtime.persistSnapshot(bound.avatar());
		return SquireRuntime.ExecutionResult.ok("feedback.stance_set",
			"[Squire] 换成「" + stance.displayName() + "」了：追击上限 "
				+ config().chaseFactor(stance) + " 倍护卫半径，"
				+ (stance.pullsAggro() ? "会主动接战附近的敌人。" : "只处理真的威胁到你或我的目标。"));
	}

	// ------------------------------------------------------------------ 补给清点

	/** 守卫 Lv.5「补给意识」：如实报一遍自己身上有什么能打、能吃、能喝。 */
	public SquireRuntime.ExecutionResult supplies(ServerPlayerEntity sender) {
		Bound bound = bind(sender);
		if (bound.failure() != null) {
			return bound.failure();
		}
		ProfessionData data = bound.data();
		if (!data.can(ProfessionAbility.GUARD_SUPPLY_AWARENESS)) {
			return SquireRuntime.ExecutionResult.fail("feedback.supply_locked",
				"[Squire] 清点补给是守卫 Lv"
					+ ProfessionAbility.GUARD_SUPPLY_AWARENESS.unlockLevel()
					+ " 的能力，我还不会。你可以直接开面板看我的背包。");
		}
		AvatarEntity avatar = bound.avatar();
		int arrows = 0;
		int food = 0;
		int potions = 0;
		int weapons = 0;
		boolean shield = false;
		boolean bow = false;
		var items = avatar.items();
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack stack = items.getStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (stack.getItem() instanceof net.minecraft.item.ArrowItem) {
				arrows += stack.getCount();
			} else if (stack.getItem() instanceof net.minecraft.item.BowItem) {
				bow = true;
			} else if (stack.getItem() instanceof net.minecraft.item.ShieldItem) {
				shield = true;
			} else if (dev.squire.server.item.Remedies.classify(stack).isPresent()) {
				if (stack.getItem() instanceof net.minecraft.item.PotionItem) {
					potions += stack.getCount();
				} else {
					food += stack.getCount();
				}
			} else if (dev.squire.server.body.avatar.AvatarInventory
					.attackDamageOf(stack) > 0) {
				weapons++;
			}
		}
		var equipped = items.equipped(net.minecraft.entity.EquipmentSlot.MAINHAND);
		if (equipped.getItem() instanceof net.minecraft.item.BowItem) {
			bow = true;
		}
		if (items.equipped(net.minecraft.entity.EquipmentSlot.OFFHAND)
				.getItem() instanceof net.minecraft.item.ShieldItem) {
			shield = true;
		}

		StringBuilder text = new StringBuilder("[Squire] 我身上的补给：");
		text.append("\n  食物 ").append(food).append(" 件");
		text.append("\n  治疗药水 ").append(potions).append(" 瓶");
		text.append("\n  箭 ").append(arrows).append(" 支");
		text.append("\n  备用近战武器 ").append(weapons).append(" 把");
		text.append("\n  弓：").append(bow ? "有" : "没有");
		text.append("\n  盾：").append(shield ? "有" : "没有");
		List<String> shortfalls = new ArrayList<>();
		if (food == 0 && potions == 0) {
			shortfalls.add("没有任何能回血的东西");
		}
		if (bow && arrows == 0) {
			shortfalls.add("有弓但一支箭都没有");
		}
		if (!bow && arrows > 0) {
			shortfalls.add("有箭但没有弓");
		}
		if (!shortfalls.isEmpty()) {
			text.append("\n要提醒你的是：").append(String.join("；", shortfalls))
				.append("。直接丢给我或者从面板放进来就行。");
		}
		return SquireRuntime.ExecutionResult.ok("feedback.supply_report",
			text.toString());
	}

	// ------------------------------------------------------------------ 材料

	/**
	 * 玩家<b>和侍从</b>身上一共有多少件这个物品。
	 *
	 * <p>面板显示的「已有 3 / 8」和真正扣料时数的必须是同一批位置，否则会出现
	 * 「面板说够了，点下去说不够」——那是玩家最没法自己搞明白的一类失败。
	 * 所以这一个方法同时被 {@code ProfessionView} 的快照和下面的扣料用。</p>
	 *
	 * <p>算上侍从的背包，是因为玩家把材料交给他之后再打开面板是很自然的动作，
	 * 那时候材料确实还在，只是换了个口袋。</p>
	 */
	public static int countMaterial(ServerPlayerEntity player, AvatarEntity avatar,
			String itemId) {
		Identifier id = Identifier.tryParse(itemId);
		if (id == null || !Registries.ITEM.containsId(id)) {
			return 0;
		}
		var item = Registries.ITEM.get(id);
		int have = 0;
		if (player != null) {
			for (int slot = 0; slot < player.getInventory().size(); slot++) {
				ItemStack stack = player.getInventory().getStack(slot);
				if (stack.isOf(item)) {
					have += stack.getCount();
				}
			}
		}
		if (avatar != null) {
			var items = avatar.items();
			for (int slot = 0; slot < items.size(); slot++) {
				ItemStack stack = items.getStack(slot);
				if (stack.isOf(item)) {
					have += stack.getCount();
				}
			}
		}
		return have;
	}

	/** 缺哪几件、缺几个。返回人话，直接拿去说。 */
	private List<String> missingItems(ServerPlayerEntity sender, AvatarEntity avatar,
			List<ProfessionConfig.ItemRequirement> cost) {
		List<String> missing = new ArrayList<>();
		for (ProfessionConfig.ItemRequirement requirement : cost) {
			Identifier id = Identifier.tryParse(requirement.itemId());
			if (id == null || !Registries.ITEM.containsId(id)) {
				// 配置写了一个这个版本没有的物品：如实报出来，而不是当它满足了。
				missing.add(requirement.itemId() + "（这个版本里没有这件物品，请检查配置）");
				continue;
			}
			int have = countMaterial(sender, avatar, requirement.itemId());
			if (have < requirement.count()) {
				missing.add(Registries.ITEM.get(id).getName().getString() + " ×"
					+ (requirement.count() - have));
			}
		}
		return missing;
	}

	/**
	 * 扣料。调用前必须已经确认齐全。
	 *
	 * <p>先扣玩家身上的，不够再扣侍从背包——玩家更在意自己包里的东西什么时候少了，
	 * 而侍从背包本来就是交给他保管的。</p>
	 */
	private void consume(ServerPlayerEntity sender, AvatarEntity avatar,
			List<ProfessionConfig.ItemRequirement> cost) {
		for (ProfessionConfig.ItemRequirement requirement : cost) {
			Identifier id = Identifier.tryParse(requirement.itemId());
			if (id == null || !Registries.ITEM.containsId(id)) {
				continue;
			}
			var item = Registries.ITEM.get(id);
			int remaining = requirement.count();
			for (int slot = 0; slot < sender.getInventory().size() && remaining > 0;
					slot++) {
				ItemStack stack = sender.getInventory().getStack(slot);
				if (!stack.isOf(item)) {
					continue;
				}
				int taken = Math.min(remaining, stack.getCount());
				stack.decrement(taken);
				remaining -= taken;
			}
			var items = avatar == null ? null : avatar.items();
			for (int slot = 0; items != null && slot < items.size() && remaining > 0;
					slot++) {
				ItemStack stack = items.getStack(slot);
				if (!stack.isOf(item)) {
					continue;
				}
				int taken = Math.min(remaining, stack.getCount());
				stack.decrement(taken);
				remaining -= taken;
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private record Bound(AvatarEntity avatar, SquireProfile profile, ProfessionData data,
			SquireRuntime.ExecutionResult failure) { }

	private Bound bind(ServerPlayerEntity sender) {
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return new Bound(null, null, null, SquireRuntime.ExecutionResult.fail(
				"feedback.no_agent", "[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。"));
		}
		AvatarEntity avatar = found.get();
		SquireProfile profile = runtime.profileOf(avatar);
		if (profile == null) {
			return new Bound(null, null, null, SquireRuntime.ExecutionResult.fail(
				"feedback.no_profile",
				"[Squire] 读不到他的档案（存档还没就绪？）。稍后再试。"));
		}
		return new Bound(avatar, profile, profile.profession, null);
	}
}
