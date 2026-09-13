package dev.squire.server.profession;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

/**
 * 一只随从的职业进度：职业、等级、这一级的经验、溢出、战斗姿态，以及两条防刷台账。
 *
 * <p>挂在 {@link dev.squire.server.profile.SquireProfile} 下面，写法遵守同一条
 * additive-optional 约定：读一律 {@code contains} 保护，写只在非默认值时写。
 * 旧存档读进来就是「没有职业」，一件已有的东西都不会少。</p>
 *
 * <h2>为什么这里存的都不是派生值</h2>
 * <p>{@code promotionReady}、下一级还差多少、解锁了哪些能力，全都能从
 * (职业, 等级, 经验) 加配置算出来，所以一个都不存——存下来的派生值迟早会和来源漂开。
 * 真正必须存的只有<b>算不出来的历史</b>：打过几次同类 Boss、最近盖过哪些同款工程。</p>
 *
 * <h2>死亡不扣</h2>
 * <p>设计文档 §6：死亡、倒地、装备损失都不影响职业进度。随从是长期培养对象，
 * 一次翻车不该把几小时的养成回滚掉。所以这个类里<b>没有</b>任何扣经验的入口——
 * 唯一会往回走的是 {@link #forgetProfession()}，那是玩家自己改行。</p>
 */
public final class ProfessionData {

	/** Boss 击杀台账最多记这么多种。够覆盖原版全部 Boss 加一批模组 Boss。 */
	public static final int MAX_BOSS_ENTRIES = 64;
	/** 同类工程台账的长度。工程是低频事件，这个深度足够覆盖一整个窗口。 */
	public static final int MAX_PROJECT_ENTRIES = 32;

	/** 职业 id；null = 还没选职业。 */
	public String professionId;
	/** 当前等级，1..10。 */
	/** 0 while untrained, then 1..10 after choosing a profession. */
	public int level;
	/** 当前这一级的经验条。 */
	public int xp;
	/** 经验条满了但还没晋升时攒下的那一部分。 */
	public int overflowXp;
	/** 玩家指定的战斗姿态（Lv.8 起可改）。 */
	public String stance = CombatStance.BALANCED.id();
	/**
	 * 已完成的新手训练项目 id。转职门槛看它，不看别的。
	 *
	 * <p>存<b>做过哪几项</b>而不是只存一个总分：训练经验是派生值（把已完成项的
	 * 分数加起来），而面板要逐项打勾。只存总分就没法回答「我还差哪一项」，
	 * 那正是玩家在 Lv.0 唯一想知道的事。</p>
	 */
	public final Set<String> trainingDone = new LinkedHashSet<>();
	/** 实体 id → 这只随从一共打倒过几次。Boss 重复衰减用，终身累计。 */
	public final Map<String, Integer> bossKills = new LinkedHashMap<>();
	/** 最近完成的同类工程签名，用于短窗口内的重复衰减。 */
	public final Deque<ProjectRecord> recentProjects = new ArrayDeque<>();

	/** 一次已完成工程的签名与时间。签名<b>不含材料颜色</b>，见 {@link EngineerXp}。 */
	public record ProjectRecord(String signature, long tick) { }

	// ------------------------------------------------------------------ 派生

	public SquireProfession profession() {
		return SquireProfession.byId(professionId);
	}

	public boolean hasProfession() {
		return profession() != null;
	}

	public CombatStance combatStance() {
		return CombatStance.byIdOrDefault(stance);
	}

	// ------------------------------------------------------------------ 训练（Lv.0）

	/** 已经攒到的训练经验。派生自已完成项，所以永远和那份清单一致。 */
	public int trainingXp() {
		int total = 0;
		for (TrainingMilestone milestone : TrainingMilestone.required()) {
			if (trainingDone.contains(milestone.id())) {
				total += milestone.xp();
			}
		}
		return total;
	}

	public boolean hasTrained(TrainingMilestone milestone) {
		return milestone != null && trainingDone.contains(milestone.id());
	}

	/**
	 * 记一项训练完成。
	 *
	 * @return 这一次真的是新完成的（用来决定要不要发提示）
	 */
	public boolean completeTraining(TrainingMilestone milestone) {
		return milestone != null && trainingDone.add(milestone.id());
	}

	/** 训练够了吗——也就是能不能转职。 */
	public boolean trainingComplete(ProfessionConfig config) {
		return trainingXp() >= config.trainingXpRequired;
	}

	/** 还差哪几项。面板 Lv.0 页直接列它。 */
	public java.util.List<TrainingMilestone> remainingTraining() {
		java.util.List<TrainingMilestone> out = new java.util.ArrayList<>();
		for (TrainingMilestone milestone : TrainingMilestone.required()) {
			if (!hasTrained(milestone)) {
				out.add(milestone);
			}
		}
		return java.util.List.copyOf(out);
	}

	public boolean isMaxLevel() {
		return level >= SquireProfession.MAX_LEVEL;
	}

	/** 这一级的经验条上限；满级返回 0。 */
	public int xpNeeded(ProfessionConfig config) {
		return isMaxLevel() ? 0 : config.xpToNext(level);
	}

	/**
	 * 经验够了吗。<b>够了也不会自动升级</b>——设计文档 §4.1 明确禁止自动晋升：
	 * 玩家必须交材料并亲手点一下。
	 */
	public boolean xpFull(ProfessionConfig config) {
		return !isMaxLevel() && xp >= xpNeeded(config);
	}

	/** 会这项能力吗。职业不对、没到等级、或者还没落地，都不算会。 */
	public boolean can(ProfessionAbility ability) {
		return ability != null && ability.available()
			&& ability.profession() == profession()
			&& ability.unlockLevel() <= level;
	}

	/** 会这项能力吗（按 id）。 */
	public boolean can(String abilityId) {
		return can(ProfessionAbility.byId(abilityId));
	}

	// ------------------------------------------------------------------ 变更

	/**
	 * 选定一个职业。等级和经验从头开始，但<b>台账和训练记录保留</b>——同一只随从
	 * 改行以后再回来，不该靠改行把 Boss 重复衰减洗掉；训练更是玩家已经做过的事，
	 * 没有任何理由让他再做一遍。
	 */
	public void setProfession(SquireProfession profession) {
		this.professionId = profession == null ? null : profession.id();
		this.level = profession == null ? 0 : SquireProfession.MIN_LEVEL;
		this.xp = 0;
		this.overflowXp = 0;
	}

	/** 卸掉职业，回到「通用随从」。 */
	public void forgetProfession() {
		setProfession(null);
	}

	/** 记一次 Boss 击杀，返回<b>这是第几次</b>（从 1 开始）。 */
	public int noteBossKill(String entityId) {
		if (entityId == null || entityId.isBlank()) {
			return 1;
		}
		int next = bossKills.merge(entityId, 1, Integer::sum);
		if (bossKills.size() > MAX_BOSS_ENTRIES) {
			// LinkedHashMap 是插入序：最早见过的那种 Boss 先淘汰。淘汰只影响衰减，
			// 代价是「很久以前打过一次」被当成新的，比让台账无限长安全。
			var iterator = bossKills.keySet().iterator();
			iterator.next();
			iterator.remove();
		}
		return next;
	}

	/** 这种 Boss 之前已经打过几次（不含正在结算的这一次）。 */
	public int bossKillsOf(String entityId) {
		return entityId == null ? 0 : bossKills.getOrDefault(entityId, 0);
	}

	/**
	 * 窗口内已经完成过几次同款工程（不含正在结算的这一次）。
	 *
	 * <p>顺手清掉过期条目：台账只需要覆盖一个窗口，留着旧的既占地方又会误判。</p>
	 */
	public int recentProjectCount(String signature, long now, long windowTicks) {
		recentProjects.removeIf(entry -> now - entry.tick() > windowTicks
			|| entry.tick() > now);
		if (signature == null) {
			return 0;
		}
		int count = 0;
		for (ProjectRecord entry : recentProjects) {
			if (signature.equals(entry.signature())) {
				count++;
			}
		}
		return count;
	}

	/** 记一次已完成的工程。 */
	public void noteProject(String signature, long now) {
		if (signature == null || signature.isBlank()) {
			return;
		}
		recentProjects.addLast(new ProjectRecord(signature, now));
		while (recentProjects.size() > MAX_PROJECT_ENTRIES) {
			recentProjects.removeFirst();
		}
	}

	// ------------------------------------------------------------------ NBT

	/** 整块职业进度在 {@code SquireProfile} 下的键。 */
	public static final String NBT_KEY = "profession";

	/** 没选职业、且台账为空时返回 null——旧存档不会因为读了一遍就长出新字段。 */
	public NbtCompound writeNbt() {
		if (professionId == null && bossKills.isEmpty() && recentProjects.isEmpty()
				&& trainingDone.isEmpty()) {
			return null;
		}
		NbtCompound c = new NbtCompound();
		if (professionId != null) {
			c.putString("id", professionId);
		}
		if (professionId != null && level != SquireProfession.MIN_LEVEL) {
			c.putInt("level", level);
		}
		if (xp != 0) {
			c.putInt("xp", xp);
		}
		if (overflowXp != 0) {
			c.putInt("overflow", overflowXp);
		}
		if (!CombatStance.BALANCED.id().equals(stance)) {
			c.putString("stance", stance);
		}
		if (!trainingDone.isEmpty()) {
			NbtList done = new NbtList();
			for (String id : trainingDone) {
				done.add(net.minecraft.nbt.NbtString.of(id));
			}
			c.put("training", done);
		}
		if (!bossKills.isEmpty()) {
			NbtCompound kills = new NbtCompound();
			bossKills.forEach((id, count) -> {
				if (count != null && count > 0) {
					kills.putInt(id, count);
				}
			});
			if (!kills.isEmpty()) {
				c.put("bossKills", kills);
			}
		}
		if (!recentProjects.isEmpty()) {
			NbtList list = new NbtList();
			for (ProjectRecord entry : recentProjects) {
				NbtCompound record = new NbtCompound();
				record.putString("sig", entry.signature());
				record.putLong("t", entry.tick());
				list.add(record);
			}
			c.put("recentProjects", list);
		}
		return c;
	}

	public void readNbt(NbtCompound c) {
		professionId = null;
		level = 0;
		xp = 0;
		overflowXp = 0;
		stance = CombatStance.BALANCED.id();
		bossKills.clear();
		recentProjects.clear();
		trainingDone.clear();
		if (c == null) {
			return;
		}
		if (c.contains("training")) {
			NbtList done = c.getList("training", NbtElement.STRING_TYPE);
			for (int i = 0; i < done.size(); i++) {
				// 只收认得出的项：以后删掉一项训练，旧存档不该因此卡在一个
				// 永远凑不齐的分数上。
				TrainingMilestone milestone = TrainingMilestone.byId(done.getString(i));
				if (milestone != null) {
					trainingDone.add(milestone.id());
				}
			}
		}
		if (c.contains("id")) {
			SquireProfession parsed = SquireProfession.byId(c.getString("id"));
			professionId = parsed == null ? null : parsed.id();
		}
		level = professionId == null ? 0
			: SquireProfession.clampLevel(c.contains("level") ? c.getInt("level")
				: SquireProfession.MIN_LEVEL);
		xp = Math.max(0, c.contains("xp") ? c.getInt("xp") : 0);
		overflowXp = Math.max(0, c.contains("overflow") ? c.getInt("overflow") : 0);
		if (c.contains("stance")) {
			stance = CombatStance.byIdOrDefault(c.getString("stance")).id();
		}
		if (c.contains("bossKills")) {
			NbtCompound kills = c.getCompound("bossKills");
			for (String id : kills.getKeys()) {
				bossKills.put(id, Math.max(0, kills.getInt(id)));
			}
		}
		if (c.contains("recentProjects")) {
			NbtList list = c.getList("recentProjects", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < list.size() && i < MAX_PROJECT_ENTRIES; i++) {
				NbtCompound record = list.getCompound(i);
				String signature = record.getString("sig");
				if (!signature.isBlank()) {
					recentProjects.addLast(new ProjectRecord(signature,
						record.getLong("t")));
				}
			}
		}
	}
}
