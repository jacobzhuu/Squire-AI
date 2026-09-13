package dev.squire.server.gui;

import java.util.ArrayList;
import java.util.List;

import dev.squire.server.profession.CombatStance;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.ProfessionData;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profession.TrainingMilestone;
import dev.squire.server.blueprint.Blueprint;
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
		List<String> templateLocks, List<BlueprintEntry> catalog, long catalogVersion, String constructionGrowth) {
	public ProfessionView(String professionId, int level, int xp, int xpNeeded, int overflowXp,
			boolean promotionReady, String stanceId, int trainingXp, int trainingRequired,
			List<String> trainingDone, List<String> promotionCost, List<String> unlockedAbilities,
			List<String> nextUnlocks, int guardMaxHealth, int food, int potions, int arrows,
			boolean bow, boolean shield, int backupWeapons, int maxFootprint,
			List<String> templates, List<String> templateLocks, List<BlueprintEntry> catalog, long catalogVersion) {
		this(professionId, level, xp, xpNeeded, overflowXp, promotionReady, stanceId, trainingXp,
			trainingRequired, trainingDone, promotionCost, unlockedAbilities, nextUnlocks, guardMaxHealth,
			food, potions, arrows, bow, shield, backupWeapons, maxFootprint, templates, templateLocks, catalog, catalogVersion, "");
	}
	public ProfessionView(String professionId, int level, int xp, int xpNeeded, int overflowXp,
			boolean promotionReady, String stanceId, int trainingXp, int trainingRequired,
			List<String> trainingDone, List<String> promotionCost, List<String> unlockedAbilities,
			List<String> nextUnlocks, int guardMaxHealth, int food, int potions, int arrows,
			boolean bow, boolean shield, int backupWeapons, int maxFootprint,
			List<String> templates, List<String> templateLocks) {
		this(professionId, level, xp, xpNeeded, overflowXp, promotionReady, stanceId,
			trainingXp, trainingRequired, trainingDone, promotionCost, unlockedAbilities,
			nextUnlocks, guardMaxHealth, food, potions, arrows, bow, shield, backupWeapons,
			maxFootprint, templates, templateLocks, List.of(), 0);
	}

	/** 一次也没同步到时用它，而不是显示一堆 0。 */
	public static final ProfessionView EMPTY = new ProfessionView("", 0, 0, 0, 0, false,
		CombatStance.BALANCED.id(), 0, TrainingMilestone.totalXp(), List.of(), List.of(),
		List.of(), List.of(), 20, 0, 0, 0, false, false, 0, 0, List.of(), List.of());

	/** 单条上限，和 {@code PanelState} 的约定一致：网络输入永远有上界。 */
	private static final int MAX_ENTRIES = 64;
	private static final int MAX_ENTRY_LENGTH = 512;

	public ProfessionView {
		constructionGrowth = constructionGrowth == null ? "" : constructionGrowth;
		catalog = catalog == null ? List.of() : List.copyOf(catalog);
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

	public record BlueprintEntry(String id, String displayName, String category,
			int minLevel, int width, int height, int depth, String author, String style,
			String source, String license, String kind, boolean allowed, String reason, int tier) {
		public BlueprintEntry(String id, String displayName, String category, int minLevel, int width, int height, int depth,
				String author, String style, String source, String license, String kind, boolean allowed, String reason) {
			this(id, displayName, category, minLevel, width, height, depth, author, style, source, license, kind, allowed, reason, 0);
		}
		public boolean unlockedAt(int level) { return allowed && level >= minLevel; }
	}
	public List<BlueprintEntry> blueprintLibrary() { return catalog; }
	/** One lightweight family page or one family's variants; never flattens the full library. */
	public ProfessionView withBuildingCatalog(dev.squire.server.blueprint.BuildingCatalog buildings,
			String familyId, long revision) {
		List<BlueprintEntry> entries = new ArrayList<>();
		var family = buildings.family(familyId).orElse(null);
		if (family == null) {
			for (var f : buildings.families()) {
				var v = f.variants().get(0);
				long available = f.variants().stream().filter(x -> x.allowed(level)).count();
				entries.add(new BlueprintEntry(f.id(), f.name(), buildings.categoryName(f.category()), f.minLevel(), 0, 0, 0,
					v.author(), v.style(), v.source(), v.license(), "family", available > 0,
					available + "/" + f.variants().size() + " 个可施工版本；点击查看 Tier、平台与组件"));
			}
		} else {
			entries.add(new BlueprintEntry("catalog:root", "← 返回建筑家族", buildings.categoryName(family.category()), 1, 0, 0, 0,
				"", "", "", "", "family", true, ""));
			for (var v : family.variants()) {
				String reason = !v.buildable() ? v.reason() : level < v.requiredEngineerLevel()
					? "需要工程师 Lv" + v.requiredEngineerLevel() : "";
				entries.add(new BlueprintEntry(v.id(), v.displayName(), buildings.categoryName(v.category()), v.requiredEngineerLevel(),
					v.width(), v.height(), v.depth(), v.author(), v.style(), v.source(), v.license(),
					v.buildable() ? "fixed" : "unsupported", profession() == SquireProfession.ENGINEER && v.allowed(level), reason, v.tier()));
			}
		}
		var growth = dev.squire.server.profession.EngineerProgression.current();
		long available = buildings.variants().stream().filter(v -> v.allowed(level)).count();
		long nextCount = buildings.variants().stream().filter(v -> v.buildable() && v.requiredEngineerLevel() == level + 1).count();
		var retiredAbilities = dev.squire.server.blueprint.BuildingContentPolicy.current().retiredAbilities();
		return new ProfessionView(professionId, level, xp, xpNeeded, overflowXp, promotionReady,
			stanceId, trainingXp, trainingRequired, trainingDone, promotionCost, unlockedAbilities.stream().filter(id -> !retiredAbilities.contains(id)).toList(),
			nextUnlocks.stream().filter(id -> !retiredAbilities.contains(id)).toList(), guardMaxHealth, food, potions, arrows, bow, shield, backupWeapons,
			maxFootprint, List.of(), List.of(), entries, revision * 31 + Integer.toUnsignedLong(entries.hashCode()),
			"工程师 Lv" + level + " · 可建 " + available + " 变体 · 放置间隔 " + growth.placementInterval(level)
				+ " tick · 新工程" + growth.materialAdjustmentLabel(level)
				+ (level < 10 ? " · 下级新增 " + nextCount + " 变体；Lv10 全材料分区" : " · 建筑大师：完整已验证目录与材料主题"));
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
			if (row.startsWith("b|")) continue;
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

	public ProfessionView withBlueprintCatalog(List<Blueprint> blueprints) {
		return withBlueprintCatalog(blueprints, ProfessionConfig.defaults());
	}
	public ProfessionView withBlueprintCatalog(List<Blueprint> blueprints, ProfessionConfig config) {
		return withBlueprintCatalog(blueprints, config, 0);
	}
	public ProfessionView withBlueprintCatalog(List<Blueprint> blueprints, ProfessionConfig config, long revision) {
		var data = new ProfessionData(); data.professionId = professionId; data.level = level;
		List<BlueprintEntry> entries = new ArrayList<>();
		for (Blueprint b : blueprints) entries.add(catalogEntry(b, data, config, "fixed", b.id()));
		for (var template : dev.squire.server.blueprint.ProjectSpec.Template.values()) {
			var b = dev.squire.server.blueprint.ProjectBlueprintFactory.compile(template.defaults());
			entries.add(catalogEntry(b, data, config, "parametric", "template:" + template.id()));
		}
		entries.sort(java.util.Comparator.comparing(BlueprintEntry::category)
			.thenComparingInt(BlueprintEntry::minLevel).thenComparing(BlueprintEntry::id));
		return new ProfessionView(professionId, level, xp, xpNeeded, overflowXp, promotionReady,
			stanceId, trainingXp, trainingRequired, trainingDone, promotionCost, unlockedAbilities,
			nextUnlocks, guardMaxHealth, food, potions, arrows, bow, shield, backupWeapons,
			maxFootprint, templates, templateLocks, entries.stream().limit(512).toList(), revision * 31 + Integer.toUnsignedLong(entries.hashCode()));
	}
	private static BlueprintEntry catalogEntry(Blueprint b, ProfessionData data, ProfessionConfig config, String kind, String id) {
		var policy = dev.squire.server.runtime.EngineerBuildPolicy.evaluate(b, data, config);
		var size = dev.squire.server.runtime.EngineerBuildPolicy.dimensions(b);
		return new BlueprintEntry(id, b.displayName(), b.category().name(), policy.minLevel(),
			size.width(), size.height(), size.depth(), b.metadata().author(), b.metadata().style(),
			b.metadata().source(), b.metadata().license(), kind, policy.allowed(), policy.reason());
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
			maxFootprint, templates, templateLocks, catalog, catalogVersion);
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
		buf.writeLong(catalogVersion);
		buf.writeVarInt(catalog.size());
		for (var e : catalog) {
			buf.writeString(e.id(), 512); buf.writeString(e.displayName(), 256); buf.writeString(e.category(), 32);
			buf.writeVarInt(e.minLevel()); buf.writeVarInt(e.width()); buf.writeVarInt(e.height()); buf.writeVarInt(e.depth());
			buf.writeString(e.author(), 256); buf.writeString(e.style(), 256); buf.writeString(e.source(), 2048);
			buf.writeString(e.license(), 128); buf.writeString(e.kind(), 32); buf.writeBoolean(e.allowed()); buf.writeString(e.reason(), 256);
			buf.writeVarInt(e.tier());
		}
		buf.writeString(constructionGrowth, 512);
	}

	public static ProfessionView read(PacketByteBuf buf) {
		return new ProfessionView(buf.readString(32), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readString(32),
			buf.readVarInt(), buf.readVarInt(), readStrings(buf), readStrings(buf),
			readStrings(buf), readStrings(buf), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(), readStrings(buf), readStrings(buf)).readCatalog(buf);
	}
	private ProfessionView readCatalog(PacketByteBuf buf) {
		long version = buf.readLong(); int size = buf.readVarInt();
		if (size < 0 || size > 512) throw new IllegalArgumentException("invalid catalog size");
		List<BlueprintEntry> entries = new ArrayList<>();
		for (int i = 0; i < size; i++) entries.add(new BlueprintEntry(buf.readString(512), buf.readString(256), buf.readString(32),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readString(256), buf.readString(256),
				buf.readString(2048), buf.readString(128), buf.readString(32), buf.readBoolean(), buf.readString(256), buf.readVarInt()));
		return new ProfessionView(professionId, level, xp, xpNeeded, overflowXp, promotionReady,
			stanceId, trainingXp, trainingRequired, trainingDone, promotionCost, unlockedAbilities,
			nextUnlocks, guardMaxHealth, food, potions, arrows, bow, shield, backupWeapons,
			maxFootprint, templates, templateLocks, entries, version, buf.readString(512));
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
