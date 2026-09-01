package dev.squire.server.gui;

import java.util.ArrayList;
import java.util.List;

import dev.squire.server.profession.CombatStance;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profession.TrainingMilestone;
import net.minecraft.network.PacketByteBuf;

/**
 * 职业页要显示的<b>全部服务端事实</b>。
 *
 * <h2>为什么单独一个 record</h2>
 * <p>职业页要十几个字段（训练进度、经验、晋升材料、补给、蓝图权限…）。把它们平铺进
 * {@link PanelState} 会让那个构造器长到四十多个位置参数——而那正是
 * {@code PanelState} 类级文档里写着的、当初要避免的东西：位置一错不会报错，
 * 只会显示成别的数字。装进一个自带 {@link #write}/{@link #read} 的嵌套 record，
 * 顺序就只在这一个文件里出现两次，肉眼能对齐。</p>
 *
 * <p><b>客户端一项也不自己算。</b>「够不够晋升」「缺几个钻石」「这一级能不能旋转蓝图」
 * 全部在服务端算好再发过来。客户端算一遍等于把判据抄两份，迟早和服务端说的不一样，
 * 而玩家看到的是「按钮亮着但点了说不行」。</p>
 */
public record ProfessionView(String professionId, int level, int xp, int xpNeeded,
		int overflowXp, boolean promotionReady, String stanceId,
		int trainingXp, int trainingRequired, List<String> trainingDone,
		List<String> promotionCost, List<String> unlockedAbilities,
		List<String> nextUnlocks, int guardMaxHealth,
		int food, int potions, int arrows, boolean bow, boolean shield,
		int backupWeapons, int maxFootprint, List<String> templates,
		List<String> templateLocks) {

	/** 一次也没同步到时用它，而不是显示一堆 0。 */
	public static final ProfessionView EMPTY = new ProfessionView("", 0, 0, 0, 0, false,
		CombatStance.BALANCED.id(), 0, TrainingMilestone.totalXp(), List.of(), List.of(),
		List.of(), List.of(), 20, 0, 0, 0, false, false, 0, 0, List.of(), List.of());

	/** 单条上限，和 {@code PanelState} 的约定一致：网络输入永远有上界。 */
	private static final int MAX_ENTRIES = 24;
	private static final int MAX_ENTRY_LENGTH = 64;

	public ProfessionView {
		professionId = professionId == null ? "" : professionId;
		stanceId = stanceId == null ? CombatStance.BALANCED.id() : stanceId;
		trainingDone = trainingDone == null ? List.of() : List.copyOf(trainingDone);
		promotionCost = promotionCost == null ? List.of() : List.copyOf(promotionCost);
		unlockedAbilities = unlockedAbilities == null ? List.of()
			: List.copyOf(unlockedAbilities);
		nextUnlocks = nextUnlocks == null ? List.of() : List.copyOf(nextUnlocks);
		templates = templates == null ? List.of() : List.copyOf(templates);
		templateLocks = templateLocks == null ? List.of() : List.copyOf(templateLocks);
	}

	/** 模板库里的一格：模板 id 和它要求的最低等级。 */
	public record TemplateLock(String templateId, int minLevel) {
		/** 这一级看得见它吗。锁着的照样画出来，只是灰着挂一把锁。 */
		public boolean unlockedAt(int level) {
			return level >= minLevel;
		}
	}

	/**
	 * 整个模板库（含还没解锁的），按等级要求排序。
	 *
	 * <p>只发已解锁的那一半是不够的：一个 Lv.2 的工程师看到「模板库：小屋、棚屋」
	 * 会以为这就是全部，也就没有练下去的理由。最低等级是服务端配置
	 * （{@code ProfessionConfig.templateMinLevel}），客户端算不出来，只能发过来。</p>
	 */
	public List<TemplateLock> templateLibrary() {
		List<TemplateLock> out = new ArrayList<>();
		for (String row : templateLocks) {
			int bar = row.indexOf('|');
			if (bar <= 0) {
				continue;
			}
			try {
				out.add(new TemplateLock(row.substring(0, bar),
					Integer.parseInt(row.substring(bar + 1))));
			} catch (NumberFormatException ignored) {
				// 坏掉的一行跳过，不让它毁掉整个库
			}
		}
		return List.copyOf(out);
	}

	// ------------------------------------------------------------------ 派生

	public SquireProfession profession() {
		return SquireProfession.byId(professionId);
	}

	/** 选过职业了吗。没选就是 Lv.0，职业页显示训练清单。 */
	public boolean hasProfession() {
		return profession() != null;
	}

	public CombatStance stance() {
		return CombatStance.byIdOrDefault(stanceId);
	}

	public boolean isMaxLevel() {
		return level >= SquireProfession.MAX_LEVEL;
	}

	/** 训练做完了吗——也就是那两个转职按钮亮不亮。 */
	public boolean trainingComplete() {
		return trainingXp >= trainingRequired;
	}

	public boolean hasTrained(TrainingMilestone milestone) {
		return milestone != null && trainingDone.contains(milestone.id());
	}

	/** 经验条填充比例 0..1。满级永远是 1。 */
	public double bar() {
		return xpNeeded <= 0 ? 1.0 : Math.min(1.0, xp / (double) xpNeeded);
	}

	/** 训练条填充比例 0..1。 */
	public double trainingBar() {
		return trainingRequired <= 0 ? 1.0
			: Math.min(1.0, trainingXp / (double) trainingRequired);
	}

	/** 一条晋升材料：物品 id、需要几个、玩家和侍从身上一共有几个。 */
	public record Material(String itemId, int need, int have) {
		public boolean enough() {
			return have >= need;
		}
	}

	public List<Material> materials() {
		List<Material> out = new ArrayList<>();
		for (String row : promotionCost) {
			String[] parts = row.split("\\|");
			if (parts.length != 3) {
				continue;
			}
			try {
				out.add(new Material(parts[0], Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2])));
			} catch (NumberFormatException ignored) {
				// 坏掉的一行跳过，不让它毁掉整张材料表
			}
		}
		return List.copyOf(out);
	}

	/** 材料齐了吗。晋升按钮亮不亮看它 <b>和</b> {@link #promotionReady}。 */
	public boolean materialsEnough() {
		for (Material material : materials()) {
			if (!material.enough()) {
				return false;
			}
		}
		return true;
	}

	/** 现在能点晋升吗。 */
	public boolean canPromote() {
		return hasProfession() && !isMaxLevel() && promotionReady && materialsEnough();
	}

	public List<ProfessionAbility> unlocked() {
		return parseAbilities(unlockedAbilities);
	}

	public List<ProfessionAbility> next() {
		return parseAbilities(nextUnlocks);
	}

	private static List<ProfessionAbility> parseAbilities(List<String> ids) {
		List<ProfessionAbility> out = new ArrayList<>();
		for (String id : ids) {
			ProfessionAbility ability = ProfessionAbility.byId(id);
			if (ability != null) {
				out.add(ability);
			}
		}
		return List.copyOf(out);
	}

	/** 这一级会这项能力吗。蓝图页的锁图标直接问它。 */
	public boolean can(ProfessionAbility ability) {
		return ability != null && unlockedAbilities.contains(ability.id());
	}

	// ------------------------------------------------------------------ 服务端快照

	/**
	 * 从真实档案取一份快照。
	 *
	 * @param materialCounts 玩家 + 侍从身上各种晋升材料的现有数量，由调用方数好
	 */
	public static ProfessionView of(ProfessionData data, ProfessionConfig config,
			java.util.function.ToIntFunction<String> materialCounts) {
		if (data == null) {
			return EMPTY;
		}
		SquireProfession profession = data.profession();
		int needed = profession == null ? 0 : data.xpNeeded(config);

		List<String> cost = new ArrayList<>();
		if (profession != null && !data.isMaxLevel()) {
			for (var item : config.promotionCost(profession, data.level + 1)) {
				cost.add(item.itemId() + "|" + item.count() + "|"
					+ Math.max(0, materialCounts.applyAsInt(item.itemId())));
			}
		}

		List<String> unlocked = new ArrayList<>();
		List<String> next = new ArrayList<>();
		if (profession != null) {
			for (ProfessionAbility ability : ProfessionAbility.of(profession)) {
				if (data.can(ability)) {
					unlocked.add(ability.id());
				} else if (ability.unlockLevel() == data.level + 1) {
					next.add(ability.id());
				}
			}
		}

		List<String> templates = new ArrayList<>();
		List<String> templateLocks = new ArrayList<>();
		if (profession == SquireProfession.ENGINEER) {
			for (var template
					: dev.squire.server.blueprint.ProjectSpec.Template.values()) {
				int min = config.templateMinLevel(template.id());
				if (min <= data.level) {
					templates.add(template.id());
				}
				// 锁着的也发过去：面板要把整条成长线画出来，而不是只画走过的那一段。
				templateLocks.add(template.id() + "|" + min);
			}
			templateLocks.sort(java.util.Comparator.comparingInt(
				row -> Integer.parseInt(row.substring(row.indexOf('|') + 1))));
		}

		return new ProfessionView(
			profession == null ? "" : profession.id(), data.level, data.xp, needed,
			data.overflowXp, data.xpFull(config), data.stance,
			data.trainingXp(), config.trainingXpRequired,
			List.copyOf(data.trainingDone), cost, unlocked, next,
			config.guardMaxHealth(data.level),
			0, 0, 0, false, false, 0,
			config.maxFootprint(data.level), templates, templateLocks);
	}

	/** 补给概览只有守卫页要，单独接上去，免得每次同步都数一遍背包。 */
	public ProfessionView withSupplies(int nextFood, int nextPotions, int nextArrows,
			boolean nextBow, boolean nextShield, int nextBackupWeapons) {
		return new ProfessionView(professionId, level, xp, xpNeeded, overflowXp,
			promotionReady, stanceId, trainingXp, trainingRequired, trainingDone,
			promotionCost, unlockedAbilities, nextUnlocks, guardMaxHealth,
			nextFood, nextPotions, nextArrows, nextBow, nextShield, nextBackupWeapons,
			maxFootprint, templates, templateLocks);
	}

	// ------------------------------------------------------------------ wire

	public void write(PacketByteBuf buf) {
		buf.writeString(professionId, 32);
		buf.writeVarInt(level);
		buf.writeVarInt(xp);
		buf.writeVarInt(xpNeeded);
		buf.writeVarInt(overflowXp);
		buf.writeBoolean(promotionReady);
		buf.writeString(stanceId, 32);
		buf.writeVarInt(trainingXp);
		buf.writeVarInt(trainingRequired);
		writeStrings(buf, trainingDone);
		writeStrings(buf, promotionCost);
		writeStrings(buf, unlockedAbilities);
		writeStrings(buf, nextUnlocks);
		buf.writeVarInt(guardMaxHealth);
		buf.writeVarInt(food);
		buf.writeVarInt(potions);
		buf.writeVarInt(arrows);
		buf.writeBoolean(bow);
		buf.writeBoolean(shield);
		buf.writeVarInt(backupWeapons);
		buf.writeVarInt(maxFootprint);
		writeStrings(buf, templates);
		writeStrings(buf, templateLocks);
	}

	public static ProfessionView read(PacketByteBuf buf) {
		return new ProfessionView(buf.readString(32), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readString(32),
			buf.readVarInt(), buf.readVarInt(), readStrings(buf), readStrings(buf),
			readStrings(buf), readStrings(buf), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(), readStrings(buf), readStrings(buf));
	}

	private static void writeStrings(PacketByteBuf buf, List<String> values) {
		int count = Math.min(values.size(), MAX_ENTRIES);
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			buf.writeString(values.get(i), MAX_ENTRY_LENGTH);
		}
	}

	private static List<String> readStrings(PacketByteBuf buf) {
		int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			out.add(buf.readString(MAX_ENTRY_LENGTH));
		}
		return List.copyOf(out);
	}
}
