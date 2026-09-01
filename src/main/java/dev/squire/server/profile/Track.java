package dev.squire.server.profile;

import java.util.Locale;

/**
 * 一条熟练度轨道。成长的<b>唯一</b>计量单位。
 *
 * <h2>为什么是里程碑而不是线性经验</h2>
 * <p>线性经验会诱使玩家去刷一个数字；里程碑只回答一个问题：「你到没到下一档」。
 * 而且每一档解锁的永远是<b>能力或蓝图</b>，绝不是战力数值——护卫升级不会给攻击力
 * +2，只会给行为切换。数值成长会让「配置一个随从」退化成「养一个数值」，
 * 那正是这个模组要避开的方向。</p>
 *
 * <h2>防挂机</h2>
 * <p>每 MC 天每条轨道有一个软上限，超出后按 {@link #OVERFLOW_RATE} 折算，
 * 而且只在主人在线时计入。没有这一条，一座刷怪塔或者一段「建 → undo → 建」的
 * 宏就能把任何轨道刷满。计数只在<b>任务终态成功</b>时上报一次，所以重启也不会重复计。</p>
 */
public enum Track {

	/** 击杀有威胁的目标。刷怪塔里的和平怪不算。 */
	COMBAT("combat", 40),
	/** 按蓝图放下去的格数。撤销会把对应的操作扣回来。 */
	BUILD("build", 512),
	/** 按蓝图挖除的格数。 */
	EXCAVATE("excavate", 512),
	/** 搬运/交付的件数。 */
	LOGISTICS("logistics", 1024),
	/** 新地点、新维度、刷新离家最远距离。 */
	EXPLORE("explore", 64);

	/** 五档里程碑。量纲各表各值：BUILD 以格，LOGISTICS 以件，COMBAT 以杀。 */
	public static final int[] MILESTONES = {40, 160, 500, 1400, 3500};

	/** 超过当天软上限之后的折算率。不是归零——加班仍然算数，只是不划算。 */
	public static final double OVERFLOW_RATE = 0.2;

	private final String id;
	private final int dailySoftCap;

	Track(String id, int dailySoftCap) {
		this.id = id;
		this.dailySoftCap = dailySoftCap;
	}

	public String id() {
		return id;
	}

	/** 每 MC 天的软上限，超出部分按 {@link #OVERFLOW_RATE} 折算。 */
	public int dailySoftCap() {
		return dailySoftCap;
	}

	public String nameKey() {
		return "squire.gui.track." + id;
	}

	/** 认不出的 id 一律回 null，读档时坏字段不该把整份档案拖垮。 */
	public static Track byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (Track track : values()) {
			if (track.id.equals(needle)) {
				return track;
			}
		}
		return null;
	}

	/**
	 * 累计值对应的等级：1 起步，每过一档 +1，上限 6（五档全过）。
	 *
	 * <p>等级本身不做任何事，它只决定<b>解锁了哪些能力</b>和<b>有几个能力槽</b>。</p>
	 */
	public static int levelFor(long total) {
		int level = 1;
		for (int milestone : MILESTONES) {
			if (total >= milestone) {
				level++;
			}
		}
		return level;
	}

	/** 下一档还差多少；已经满档返回 0。 */
	public static long remainingToNextMilestone(long total) {
		for (int milestone : MILESTONES) {
			if (total < milestone) {
				return milestone - total;
			}
		}
		return 0L;
	}

	/**
	 * 当天已经赚了 {@code earnedToday} 之后，再赚 {@code raw} 实际记多少。
	 *
	 * <p>软上限之内照原样记，超出的部分打折。刻意不是硬上限：一个真的盖了一整天
	 * 房子的玩家不该在下午突然变成白干。</p>
	 */
	public long applyDailyCap(long earnedToday, long raw) {
		if (raw <= 0) {
			return 0L;
		}
		long room = Math.max(0L, dailySoftCap - Math.max(0L, earnedToday));
		long full = Math.min(room, raw);
		long overflow = raw - full;
		return full + (long) Math.floor(overflow * OVERFLOW_RATE);
	}
}
