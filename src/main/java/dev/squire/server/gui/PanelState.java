package dev.squire.server.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.squire.server.profile.Ability;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profile.Role;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Track;
import net.minecraft.network.PacketByteBuf;

/**
 * 面板要显示的<b>全部服务端事实</b>，一个带版本号的结构。
 *
 * <p>在此之前状态包是五个裸 varint（syncId / mode / permissionMask / health /
 * maxHealth），两端各自按顺序读写。第 2 期要往里塞职业、等级、槽位、熟练度、
 * 特质和自主档位，第 3/4 期还要再加工程与小队——每加一次就要在两个文件里对齐一次
 * 字段顺序，而对不齐时不会报错，只会显示成别的数字。</p>
 *
 * <p>所以现在读写都只有这一处，并且带 {@link #VERSION}：版本对不上就整包丢弃、
 * 退回默认值，而不是把后面的字节按错位解释出来。客户端和服务端在同一个 jar 里，
 * 版本不匹配只可能出现在「玩家没换客户端就连了新服」这种情形——那时候显示成
 * 「未知」远好过显示成一个错的等级。</p>
 */
public record PanelState(int mode, int permissions, int health, int maxHealth,
		String roleId, int level, int slots, long trackTotal, long trackRemaining,
		List<String> equipped, List<String> traits, String autonomy,
		String projectName, String projectState, List<String> stages,
		String blockedReason, int followTeleport, List<String> shortcuts,
		int combatMode, String blockerCode, String placementName,
		String placementState, String placementBlueprintId, List<String> materialLines,
		List<String> materialChoices, List<String> siteIssues, boolean siteExecutable,
		List<String> shortcutSpecs, String agentName,
		int backpackSlots, int backpackPage, int amethystShards,
		ProfessionView profession, List<String> roster) {

	/**
	 * Material-choice fields travel inside one wire string so adding the material editor
	 * did not add another five parallel lists to this already-wide record.  Keep the
	 * separator and parser here: spelling the six-character text {@code \\u001f} at
	 * one end and the real U+001F character at the other silently makes every row
	 * unparsable.
	 */
	private static final String MATERIAL_CHOICE_SEPARATOR = "\u001f";
	private static final String ROSTER_SEPARATOR = "\u001f";

	/** One decoded row in the construction material editor. */
	public record MaterialChoice(String slotId, String slotName, String familyId,
			String familyName, String representativeItemId) { }
	public record RosterEntry(String agentId, String name, String professionId,
			boolean active, boolean primary, boolean current) { }

	/** Source-compatible constructor used by older tests and call sites. */
	public PanelState(int mode, int permissions, int health, int maxHealth,
			String roleId, int level, int slots, long trackTotal, long trackRemaining,
			List<String> equipped, List<String> traits, String autonomy,
			String projectName, String projectState, List<String> stages,
			String blockedReason, int followTeleport, List<String> shortcuts,
			int combatMode, String blockerCode, String placementName,
			String placementState, String placementBlueprintId, List<String> materialLines,
			List<String> materialChoices, List<String> siteIssues, boolean siteExecutable,
			List<String> shortcutSpecs, String agentName, int backpackSlots,
			int backpackPage, int amethystShards, ProfessionView profession) {
		this(mode, permissions, health, maxHealth, roleId, level, slots, trackTotal,
			trackRemaining, equipped, traits, autonomy, projectName, projectState, stages,
			blockedReason, followTeleport, shortcuts, combatMode, blockerCode,
			placementName, placementState, placementBlueprintId, materialLines,
			materialChoices, siteIssues, siteExecutable, shortcutSpecs, agentName,
			backpackSlots, backpackPage, amethystShards, profession, List.of());
	}

	public static String encodeRosterEntry(String agentId, String name,
			String professionId, boolean active, boolean primary, boolean current) {
		return cleanRoster(agentId) + ROSTER_SEPARATOR + cleanRoster(name)
			+ ROSTER_SEPARATOR + cleanRoster(professionId) + ROSTER_SEPARATOR
			+ active + ROSTER_SEPARATOR + primary + ROSTER_SEPARATOR + current;
	}

	public static Optional<RosterEntry> decodeRosterEntry(String encoded) {
		if (encoded == null) return Optional.empty();
		String[] fields = encoded.split(Pattern.quote(ROSTER_SEPARATOR), -1);
		if (fields.length != 6 || fields[0].isBlank()) return Optional.empty();
		return Optional.of(new RosterEntry(fields[0], fields[1], fields[2],
			Boolean.parseBoolean(fields[3]), Boolean.parseBoolean(fields[4]),
			Boolean.parseBoolean(fields[5])));
	}

	private static String cleanRoster(String value) {
		return value == null ? "" : value.replace(ROSTER_SEPARATOR, " ");
	}

	public static String encodeMaterialChoice(String slotId, String slotName,
			String familyId, String familyName, String representativeItemId) {
		return cleanMaterialField(slotId) + MATERIAL_CHOICE_SEPARATOR
			+ cleanMaterialField(slotName) + MATERIAL_CHOICE_SEPARATOR
			+ cleanMaterialField(familyId) + MATERIAL_CHOICE_SEPARATOR
			+ cleanMaterialField(familyName) + MATERIAL_CHOICE_SEPARATOR
			+ cleanMaterialField(representativeItemId);
	}

	public static Optional<MaterialChoice> decodeMaterialChoice(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return Optional.empty();
		}
		String[] fields = encoded.split(Pattern.quote(MATERIAL_CHOICE_SEPARATOR), -1);
		if (fields.length != 5 || fields[0].isEmpty() || fields[2].isEmpty()
				|| fields[4].isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new MaterialChoice(fields[0], fields[1], fields[2],
			fields[3], fields[4]));
	}

	private static String cleanMaterialField(String value) {
		return value == null ? "" : value.replace(MATERIAL_CHOICE_SEPARATOR, " ");
	}

	/** 状态包版本。字段一变就 +1。 */
	public static final int VERSION = 23;

	/** 一次也没同步到的面板显示这个，而不是显示一堆 0。 */
	public static final PanelState EMPTY = new PanelState(0, 0, 0, 20, "", 0,
		Role.BASE_SLOTS, 0L, 0L, List.of(), List.of(),
		AutonomyLevel.STANDARD.id(), "", "", List.of(), "",
		dev.squire.server.body.avatar.AvatarEntity.FOLLOW_TELEPORT_DEFAULT,
		List.of(), 0, "", "", "", "", List.of(), List.of(), List.of(), false,
		List.of(), "", 0, 0, 0, ProfessionView.EMPTY);

	public PanelState {
		roleId = roleId == null ? "" : roleId;
		autonomy = autonomy == null ? AutonomyLevel.STANDARD.id() : autonomy;
		equipped = List.copyOf(equipped);
		traits = List.copyOf(traits);
		stages = List.copyOf(stages);
		shortcuts = shortcuts == null ? List.of() : List.copyOf(shortcuts);
		shortcutSpecs = shortcutSpecs == null ? List.of()
			: List.copyOf(shortcutSpecs);
		projectName = projectName == null ? "" : projectName;
		projectState = projectState == null ? "" : projectState;
		blockedReason = blockedReason == null ? "" : blockedReason;
		blockerCode = blockerCode == null ? "" : blockerCode;
		placementName = placementName == null ? "" : placementName;
		placementState = placementState == null ? "" : placementState;
		placementBlueprintId = placementBlueprintId == null ? "" : placementBlueprintId;
		materialLines = materialLines == null ? List.of() : List.copyOf(materialLines);
		materialChoices = materialChoices == null ? List.of() : List.copyOf(materialChoices);
		siteIssues = siteIssues == null ? List.of() : List.copyOf(siteIssues);
		agentName = agentName == null ? "" : agentName;
		maxHealth = Math.max(1, maxHealth);
		profession = profession == null ? ProfessionView.EMPTY : profession;
		roster = roster == null ? List.of() : List.copyOf(roster);
	}

	/** 有职业吗。没有就是「通用随从」，面板据此换一套说明文字。 */
	public boolean hasRole() {
		return !roleId.isEmpty() && Role.byId(roleId) != null;
	}

	public Role role() {
		return Role.byId(roleId);
	}

	public AutonomyLevel autonomyLevel() {
		return AutonomyLevel.byIdOrDefault(autonomy);
	}

	public List<Ability> equippedAbilities() {
		List<Ability> out = new ArrayList<>();
		for (String id : equipped) {
			Ability ability = Ability.byId(id);
			if (ability != null) {
				out.add(ability);
			}
		}
		return List.copyOf(out);
	}

	/** 有在做的工程吗。 */
	public boolean hasProject() {
		return !projectName.isEmpty();
	}

	public boolean hasPlacement() {
		return !placementName.isEmpty();
	}

	/** 已完成的阶段数（面板画进度用）。 */
	public int stagesDone() {
		return (int) stages.stream().filter(entry -> entry.endsWith(":DONE")
			|| entry.endsWith(":SKIPPED")).count();
	}

	/**
	 * 第 {@code index} 条快捷指令那一行打包数据：{@code entryId ␟ arg ␟ phrase}。
	 *
	 * <p>打包成一个字段而不是往这个 record 上再加两个 list——这个构造器已经是
	 * 三十多个位置参数，而位置参数错一个不会报错，只会显示成别的东西。</p>
	 */
	private String[] shortcutSpec(int index) {
		String raw = index < 0 || index >= shortcutSpecs.size() ? ""
			: shortcutSpecs.get(index);
		String[] parts = raw.split(
			dev.squire.server.shortcut.ShortcutStore.SPEC_SEPARATOR, -1);
		return parts.length == 3 ? parts : new String[] {"", "", raw};
	}

	/** 第 {@code index} 条绑的动作 id；没绑（老快捷）或越界返回空串。 */
	public String shortcutEntryId(int index) {
		return shortcutSpec(index)[0];
	}

	/** 第 {@code index} 条的档位参数；越界返回空串。 */
	public String shortcutArg(int index) {
		return shortcutSpec(index)[1];
	}

	/** 第 {@code index} 条老快捷的那句原话；绑定式的返回空串。 */
	public String shortcutPhrase(int index) {
		return shortcutSpec(index)[2];
	}

	/** 这一格存的是老式的自然语言快捷吗（面板要提示玩家重新绑一次）。 */
	public boolean shortcutIsLegacy(int index) {
		return shortcutEntryId(index).isEmpty() && !shortcutPhrase(index).isEmpty();
	}

	/** 把工程那一半接上去；没工程时原样返回。 */
	public PanelState withProject(String name, String state, List<String> stageLines,
			String reason) {
		return new PanelState(mode, permissions, health, maxHealth, roleId, level, slots,
			trackTotal, trackRemaining, equipped, traits, autonomy, name, state,
			stageLines, reason, followTeleport, shortcuts, combatMode, blockerCode,
			placementName, placementState, placementBlueprintId, materialLines,
			materialChoices, siteIssues, siteExecutable, shortcutSpecs, agentName,
			backpackSlots, backpackPage, amethystShards, profession, roster);
	}

	/** 背囊的格数与当前页；面板据此决定要不要画翻页按钮、写第几页。 */
	public PanelState withBackpack(int slots, int page) {
		return new PanelState(mode, permissions, health, maxHealth, roleId, level, this.slots,
			trackTotal, trackRemaining, equipped, traits, autonomy, projectName,
			projectState, stages, blockedReason, followTeleport, shortcuts, combatMode,
			blockerCode, placementName, placementState, placementBlueprintId, materialLines,
			materialChoices, siteIssues, siteExecutable, shortcutSpecs, agentName,
			Math.max(0, slots), Math.max(0, page), amethystShards, profession, roster);
	}

	public PanelState withConstruction(String nextBlockerCode, String nextPlacementName,
			String nextPlacementState, String nextBlueprintId,
			List<String> nextMaterialLines, List<String> nextSiteIssues,
			boolean nextSiteExecutable) {
		return withConstruction(nextBlockerCode, nextPlacementName,
			nextPlacementState, nextBlueprintId, nextMaterialLines, materialChoices,
			nextSiteIssues, nextSiteExecutable);
	}

	public PanelState withConstruction(String nextBlockerCode, String nextPlacementName,
			String nextPlacementState, String nextBlueprintId,
			List<String> nextMaterialLines, List<String> nextMaterialChoices,
			List<String> nextSiteIssues, boolean nextSiteExecutable) {
		return new PanelState(mode, permissions, health, maxHealth, roleId, level, slots,
			trackTotal, trackRemaining, equipped, traits, autonomy, projectName,
			projectState, stages, blockedReason, followTeleport, shortcuts, combatMode,
			nextBlockerCode, nextPlacementName, nextPlacementState, nextBlueprintId,
			nextMaterialLines, nextMaterialChoices, nextSiteIssues, nextSiteExecutable,
			shortcutSpecs, agentName, backpackSlots, backpackPage, amethystShards,
			profession, roster);
	}

	public PanelState withRoster(List<String> entries) {
		return new PanelState(mode, permissions, health, maxHealth, roleId, level, slots,
			trackTotal, trackRemaining, equipped, traits, autonomy, projectName,
			projectState, stages, blockedReason, followTeleport, shortcuts, combatMode,
			blockerCode, placementName, placementState, placementBlueprintId, materialLines,
			materialChoices, siteIssues, siteExecutable, shortcutSpecs, agentName,
			backpackSlots, backpackPage, amethystShards, profession, entries);
	}

	/** 服务端侧：从真实档案取一份快照。 */
	public static PanelState of(int mode, int permissions, int health, int maxHealth,
			SquireProfile profile) {
		return of(mode, permissions, health, maxHealth, profile,
			dev.squire.server.body.avatar.AvatarEntity.FOLLOW_TELEPORT_DEFAULT,
			List.of(), 0, List.of(), "");
	}

	public static PanelState of(int mode, int permissions, int health, int maxHealth,
			SquireProfile profile, int followTeleport, List<String> shortcuts,
			int combatMode, List<String> shortcutSpecs, String agentName) {
		return of(mode, permissions, health, maxHealth, profile, followTeleport,
			shortcuts, combatMode, shortcutSpecs, agentName,
			profile == null ? ProfessionView.EMPTY
				: ProfessionView.of(profile.profession,
					dev.squire.server.runtime.SquireRuntime.professionBalance(),
					itemId -> 0));
	}

	/**
	 * 完整快照。
	 *
	 * @param profession 职业页那一整块，由调用方装好——它要数玩家背包里的晋升材料，
	 *                   而档案本身看不见玩家的背包
	 */
	public static PanelState of(int mode, int permissions, int health, int maxHealth,
			SquireProfile profile, int followTeleport, List<String> shortcuts,
			int combatMode, List<String> shortcutSpecs, String agentName,
			ProfessionView profession) {
		return of(mode, permissions, health, maxHealth, profile, followTeleport,
			shortcuts, combatMode, shortcutSpecs, agentName, 0, profession);
	}

	public static PanelState of(int mode, int permissions, int health, int maxHealth,
			SquireProfile profile, int followTeleport, List<String> shortcuts,
			int combatMode, List<String> shortcutSpecs, String agentName,
			int amethystShards, ProfessionView profession) {
		if (profile == null) {
			return new PanelState(mode, permissions, health, maxHealth, "", 0,
				Role.BASE_SLOTS, 0L, 0L, List.of(), List.of(),
				AutonomyLevel.STANDARD.id(), "", "", List.of(), "", followTeleport,
				shortcuts, combatMode, "", "", "", "", List.of(), List.of(), List.of(),
				false, shortcutSpecs, agentName, 0, 0, amethystShards, profession);
		}
		Role role = profile.role();
		Track track = role == null ? null : role.track();
		long total = track == null ? 0L : profile.proficiencyOf(track);
		// The profession progression is the only level shown to players.  Keep the
		// legacy Role/Track fields on the wire for save and ability compatibility,
		// but never let that independent track leak out as a second "Lv" value.
		int visibleLevel = profession == null ? 0 : profession.level();
		return new PanelState(mode, permissions, health, maxHealth,
			role == null ? "" : role.id(), visibleLevel, profile.slots(), total,
			Track.remainingToNextMilestone(total),
			List.copyOf(profile.equippedAbilities), List.copyOf(profile.traits),
			profile.autonomy, "", "", List.of(), "", followTeleport, shortcuts,
			combatMode, "", "", "", "", List.of(), List.of(), List.of(), false,
			shortcutSpecs, agentName, 0, 0, amethystShards, profession);
	}

	// ------------------------------------------------------------------ wire

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(VERSION);
		buf.writeVarInt(mode);
		buf.writeVarInt(permissions);
		buf.writeVarInt(health);
		buf.writeVarInt(maxHealth);
		buf.writeString(roleId, 32);
		buf.writeVarInt(level);
		buf.writeVarInt(slots);
		buf.writeVarLong(trackTotal);
		buf.writeVarLong(trackRemaining);
		writeStrings(buf, equipped);
		writeStrings(buf, traits);
		buf.writeString(autonomy, 32);
		buf.writeString(projectName, MAX_ENTRY_LENGTH);
		buf.writeString(projectState, 32);
		writeStrings(buf, stages);
		buf.writeString(blockedReason, MAX_REASON_LENGTH);
		buf.writeVarInt(followTeleport);
		writeStrings(buf, shortcuts);
		buf.writeVarInt(combatMode);
		buf.writeString(blockerCode, 48);
		buf.writeString(placementName, MAX_ENTRY_LENGTH);
		buf.writeString(placementState, 32);
		buf.writeString(placementBlueprintId, 128);
		writeMaterialLines(buf, materialLines);
		writeLongStrings(buf, materialChoices);
		writeSiteIssues(buf, siteIssues);
		buf.writeBoolean(siteExecutable);
		// 快捷指令的绑定和名字一一对应。面板要能<b>就地编辑</b>已存的那一条，
		// 也要判得出它现在是不是锁着的——只发名字的话两件事都做不到。
		writePhrases(buf, shortcutSpecs);
		// 名字也走状态包：面板标题是开界面那一刻定下的，改完名不重推就还是旧名字。
		buf.writeString(agentName, 48);
		// 背囊：格数决定要不要画翻页，页号决定标题上写第几页。
		buf.writeVarInt(backpackSlots);
		buf.writeVarInt(backpackPage);
		buf.writeVarInt(Math.max(0, amethystShards));
		// 职业页整块。顺序只在 ProfessionView 里出现一次，肉眼对得齐。
		profession.write(buf);
		writeLongStrings(buf, roster);
	}

	/** 版本不匹配时返回 {@link #EMPTY}，绝不把后面的字节按错位解释出来。 */
	public static PanelState read(PacketByteBuf buf) {
		int version = buf.readVarInt();
		if (version != VERSION) {
			return EMPTY;
		}
		return new PanelState(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readString(32), buf.readVarInt(), buf.readVarInt(),
			buf.readVarLong(), buf.readVarLong(), readStrings(buf), readStrings(buf),
			buf.readString(32), buf.readString(MAX_ENTRY_LENGTH), buf.readString(32),
			readStrings(buf), buf.readString(MAX_REASON_LENGTH), buf.readVarInt(),
			readStrings(buf), buf.readVarInt(), buf.readString(48),
			buf.readString(MAX_ENTRY_LENGTH), buf.readString(32), buf.readString(128),
			readMaterialLines(buf), readLongStrings(buf), readSiteIssues(buf), buf.readBoolean(),
			readPhrases(buf), buf.readString(48), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), ProfessionView.read(buf), readLongStrings(buf));
	}

	/** 单个字段最多这么长，整段最多这么多条——网络输入永远有上界。 */
	private static final int MAX_ENTRIES = 16;
	private static final int MAX_ENTRY_LENGTH = 48;
	/** 阻塞原因是一段人话（可能列着缺料），比其它字段长得多。 */
	private static final int MAX_REASON_LENGTH = 512;

	// Site diagnostics are prose, not short IDs. Bound display text at the wire
	// boundary so a longer diagnostic cannot crash the server during player ticks.
	private static void writeSiteIssues(PacketByteBuf buf, List<String> values) {
		int count = Math.min(values.size(), MAX_ENTRIES);
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			String value = values.get(i);
			if (value.length() > MAX_REASON_LENGTH) {
				int end = MAX_REASON_LENGTH - 1;
				if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
				value = value.substring(0, end) + "\u2026";
			}
			buf.writeString(value, MAX_REASON_LENGTH);
		}
	}

	private static List<String> readSiteIssues(PacketByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_ENTRIES) {
			throw new IllegalArgumentException("invalid site issue count");
		}
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) out.add(buf.readString(MAX_REASON_LENGTH));
		return List.copyOf(out);
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

	private static final int MAX_LONG_ENTRY_LENGTH = 192;
	private static void writeMaterialLines(PacketByteBuf buf, List<String> values) {
		int count = Math.min(values.size(), 256); buf.writeVarInt(count);
		for (int i = 0; i < count; i++) buf.writeString(values.get(i), 128);
	}
	private static List<String> readMaterialLines(PacketByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > 256) throw new IllegalArgumentException("invalid material count");
		List<String> out = new ArrayList<>();
		for (int i = 0; i < count; i++) out.add(buf.readString(128));
		return List.copyOf(out);
	}

	private static void writeLongStrings(PacketByteBuf buf, List<String> values) {
		int count = Math.min(values.size(), MAX_ENTRIES);
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			buf.writeString(values.get(i), MAX_LONG_ENTRY_LENGTH);
		}
	}

	private static List<String> readLongStrings(PacketByteBuf buf) {
		int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) out.add(buf.readString(MAX_LONG_ENTRY_LENGTH));
		return List.copyOf(out);
	}

	/**
	 * 一条打包的快捷指令有多长：一句原话 + 动作 id + 参数 + 两个分隔符。
	 * 网络输入永远有上界。
	 */
	private static final int MAX_PHRASE_LENGTH =
		dev.squire.server.shortcut.ShortcutStore.MAX_PHRASE_LENGTH
			+ dev.squire.server.shortcut.ShortcutStore.MAX_ENTRY_ID_LENGTH
			+ dev.squire.server.shortcut.ShortcutStore.MAX_ARG_LENGTH + 2;

	private static void writePhrases(PacketByteBuf buf, List<String> values) {
		int count = Math.min(values.size(), MAX_ENTRIES);
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			String value = values.get(i);
			buf.writeString(value.length() > MAX_PHRASE_LENGTH
				? value.substring(0, MAX_PHRASE_LENGTH) : value, MAX_PHRASE_LENGTH);
		}
	}

	private static List<String> readPhrases(PacketByteBuf buf) {
		int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			out.add(buf.readString(MAX_PHRASE_LENGTH));
		}
		return List.copyOf(out);
	}
}
