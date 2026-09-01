package dev.squire.server.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 性格特质。首次召唤时随机 1~2 个，也可由玩家支付材料后整组洗练。
 *
 * <p><b>只影响机制，不影响说话风格。</b>把性格塞进 {@code systemPrompt} 会和
 * {@code FunctionCallingParser} 的严格 JSON 契约打架，而且完全不可测——「他今天
 * 是不是更谨慎了」没有断言可写。改成阈值和速率之后，每一条特质都能被一个数字验证。</p>
 *
 * <p>特质是<b>横向</b>的：谨慎的建筑师和莽撞的建筑师一样能盖房子，只是撤退线不同。
 * 它不是隐藏的战力等级，所以一对相反的特质（CAUTIOUS/RECKLESS）不会同时出现。</p>
 */
public enum Trait {

	/** 撤退线更高：血量掉到 40% 就退，而不是 25%。 */
	CAUTIOUS("cautious", "谨慎", "生命低于 40% 时撤退", 1),
	/** 撤退线更低，而且会主动扑向护卫半径边缘的目标。 */
	RECKLESS("reckless", "莽撞", "警戒更远、追击更久，生命低于 15% 时撤退", 3),
	/** 干活更快、工作半径更大。 */
	DILIGENT("diligent", "勤勉", "施工每 tick +8，掘进每 tick +4", 2),
	/** 干完活主动把余料存进最近的箱子。 */
	HOARDER("hoarder", "囤积", "标准自主档也会在收工后存放余料", 1),
	/** 移动更快。 */
	SWIFT("swift", "轻捷", "移动速度 +15%", 1),
	/** 更耐打。 */
	STURDY("sturdy", "皮实", "最大生命 +2", 1);

	/** Shared effect values: behaviour and UI translations are tested against these. */
	public static final float BASE_RETREAT_FRACTION = 0.25f;
	public static final float CAUTIOUS_RETREAT_FRACTION = 0.40f;
	public static final float RECKLESS_RETREAT_FRACTION = 0.15f;
	public static final double RECKLESS_AGGRO_RANGE_BONUS = 4.0;
	public static final double RECKLESS_CHASE_FACTOR = 1.25;
	public static final int DILIGENT_BUILD_BONUS = 8;
	public static final int DILIGENT_EXCAVATE_BONUS = 4;
	public static final double SWIFT_SPEED_FACTOR = 1.15;
	public static final double STURDY_MAX_HEALTH_BONUS = 2.0;

	private final String id;
	private final String displayName;
	private final String summary;
	private final int effectLineCount;

	Trait(String id, String displayName, String summary, int effectLineCount) {
		this.id = id;
		this.displayName = displayName;
		this.summary = summary;
		this.effectLineCount = effectLineCount;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public String summary() {
		return summary;
	}

	public String nameKey() {
		return "squire.gui.trait." + id;
	}

	public String descriptionKey() {
		return nameKey() + ".description";
	}

	public String effectKey(int index) {
		if (index < 0 || index >= effectLineCount) {
			throw new IndexOutOfBoundsException(index);
		}
		return nameKey() + ".effect." + (index + 1);
	}

	public int effectLineCount() {
		return effectLineCount;
	}

	public static Trait byId(String raw) {
		if (raw == null) {
			return null;
		}
		String needle = raw.trim().toLowerCase(Locale.ROOT);
		for (Trait trait : values()) {
			if (trait.id.equals(needle)) {
				return trait;
			}
		}
		return null;
	}

	/** 互斥的另一半；没有就返回 null。 */
	public Trait opposite() {
		return switch (this) {
			case CAUTIOUS -> RECKLESS;
			case RECKLESS -> CAUTIOUS;
			default -> null;
		};
	}

	/**
	 * 首次召唤时抽 1~2 个，绝不抽到互相矛盾的一对。
	 *
	 * <p>随机源由调用方给，所以测试可以用固定种子把这件事钉死——「随机」不该
	 * 意味着「测不了」。</p>
	 */
	public static List<Trait> roll(RandomGenerator random) {
		return roll(random, 1 + random.nextInt(2));
	}

	/** Roll an exact count, used by rerolling so a two-trait personality stays two. */
	public static List<Trait> roll(RandomGenerator random, int requestedCount) {
		List<List<Trait>> choices = validCombinations(requestedCount);
		return choices.get(random.nextInt(choices.size()));
	}

	/**
	 * Replace a personality without producing the exact same set again. Ordering is
	 * cosmetic, so equality is set-based. Duplicate and opposite pairs never enter the
	 * candidate table in the first place.
	 */
	public static List<Trait> reroll(RandomGenerator random, int requestedCount,
			Collection<Trait> previous) {
		Set<Trait> old = previous == null ? Set.of()
			: new LinkedHashSet<>(previous);
		List<List<Trait>> choices = new ArrayList<>(validCombinations(requestedCount));
		choices.removeIf(choice -> old.size() == choice.size()
			&& old.containsAll(choice));
		if (choices.isEmpty()) {
			choices = validCombinations(requestedCount);
		}
		return choices.get(random.nextInt(choices.size()));
	}

	private static List<List<Trait>> validCombinations(int requestedCount) {
		int count = Math.max(1, Math.min(2, requestedCount));
		List<List<Trait>> choices = new ArrayList<>();
		Trait[] all = values();
		if (count == 1) {
			for (Trait trait : all) {
				choices.add(List.of(trait));
			}
			return List.copyOf(choices);
		}
		for (int first = 0; first < all.length; first++) {
			for (int second = first + 1; second < all.length; second++) {
				if (all[first].opposite() != all[second]) {
					choices.add(List.of(all[first], all[second]));
				}
			}
		}
		return List.copyOf(choices);
	}
}
