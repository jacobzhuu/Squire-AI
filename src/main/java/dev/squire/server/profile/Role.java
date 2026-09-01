package dev.squire.server.profile;

import java.util.Locale;

/**
 * 职业。一只随从只能有一个，能力槽的数量由本职业的熟练度等级决定。
 *
 * <p>职业存在的理由是<b>取舍</b>，不是加成。选了护卫，就不是建筑师；想要一支
 * 能自己运转的营地，你得召第二只——这正是第 4 期小队的动机来源。所以这里没有
 * 任何一条「本职业 +X%」，职业只影响<b>你能装哪些能力、有几个槽</b>。</p>
 *
 * <p>{@link #FARMER} 保留常量但未开放：耕作是「破坏 + 放置 + 作物生长感知 +
 * 跨天时间尺度」，仓库里没有任何相关代码，而且它本质是野外采集的变体——正是
 * ADR-038 挡住的方向。面板上灰显它，好过让玩家找不到而以为坏了。</p>
 */
public enum Role {

	GUARDIAN("guardian", "护卫", Track.COMBAT, true),
	BUILDER("builder", "建筑师", Track.BUILD, true),
	STEWARD("steward", "管家", Track.LOGISTICS, true),
	EXCAVATOR("excavator", "掘进工", Track.EXCAVATE, true),
	SCOUT("scout", "探险家", Track.EXPLORE, true),
	/** 未开放。面板灰显，选它会被如实拒绝并说明原因。 */
	FARMER("farmer", "农夫", null, false);

	/** 没有职业时也有的槽位数。 */
	public static final int BASE_SLOTS = 2;
	/** 到这个等级给第 3 个槽。 */
	public static final int THIRD_SLOT_LEVEL = 3;
	/** 到这个等级给第 4 个槽，而且第 4 槽只能装本职业能力。 */
	public static final int FOURTH_SLOT_LEVEL = 5;

	private final String id;
	private final String displayName;
	private final Track track;
	private final boolean available;

	Role(String id, String displayName, Track track, boolean available) {
		this.id = id;
		this.displayName = displayName;
		this.track = track;
		this.available = available;
	}

	public String id() {
		return id;
	}

	/** 聊天里用的中文名。GUI 用 {@link #nameKey()}；两者由 LangFilesTest 钉在一起。 */
	public String displayName() {
		return displayName;
	}

	/** 决定本职业等级的熟练度轨道；未开放的职业没有。 */
	public Track track() {
		return track;
	}

	public boolean available() {
		return available;
	}

	public String nameKey() {
		return "squire.gui.role." + id;
	}

	public static Role byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (Role role : values()) {
			if (role.id.equals(needle)) {
				return role;
			}
		}
		return null;
	}

	/**
	 * 这个等级有几个能力槽。
	 *
	 * <p>槽位数是<b>派生</b>的，绝不存进档案：存下来的派生值迟早会和来源漂开，
	 * 而这个来源（熟练度累计）本身已经存了。</p>
	 */
	public static int slotsAtLevel(int level) {
		int slots = BASE_SLOTS;
		if (level >= THIRD_SLOT_LEVEL) {
			slots++;
		}
		if (level >= FOURTH_SLOT_LEVEL) {
			slots++;
		}
		return slots;
	}

	/** 第 4 槽（下标 3）只收本职业能力——这是「专精」真正咬人的地方。 */
	public static boolean slotIsRoleLocked(int slotIndex) {
		return slotIndex >= BASE_SLOTS + 1;
	}
}
