package dev.squire.server.combat;

import java.util.Map;

import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 「打这种怪该用弓还是用剑」——AUTO 档位的选武器依据。
 *
 * <p>之前 AUTO 只看距离：超过 5 格就射，贴脸就砍。那条规则对骷髅和僵尸一视同仁，
 * 可这两种怪的正确打法完全相反——放风筝骷髅是找死（对射你输在血量上），而离远了
 * 用弓耗僵尸则毫无必要。距离是个太粗的代理指标，真正决定打法的是<b>对面是什么</b>。</p>
 *
 * <p>表里没有的（包括所有模组生物）退回距离判断，不做猜测。</p>
 */
public final class TargetWeapon {

	/** 对这种怪该用什么。 */
	public enum Preference { BOW, MELEE }

	private static final Map<Identifier, Preference> TABLE = Map.<Identifier, Preference>ofEntries(
		// —— 近战：贴身缠斗型，或者根本吃不到远程收益的 ——
		entry("zombie", Preference.MELEE),
		entry("zombie_villager", Preference.MELEE),
		entry("husk", Preference.MELEE),
		entry("drowned", Preference.MELEE),
		entry("spider", Preference.MELEE),
		entry("cave_spider", Preference.MELEE),
		entry("slime", Preference.MELEE),
		entry("magma_cube", Preference.MELEE),
		entry("silverfish", Preference.MELEE),
		entry("endermite", Preference.MELEE),
		entry("guardian", Preference.MELEE),
		entry("elder_guardian", Preference.MELEE),
		entry("vex", Preference.MELEE),
		entry("wither_skeleton", Preference.MELEE),
		entry("wither", Preference.MELEE),
		// 末影人不在玩家给的表里，但它对箭免疫——看到箭飞来就瞬移，一支都打不中。
		// 按距离兜底的话 5 格外他会去射，等于站着挨打，所以补上这一条。
		entry("enderman", Preference.MELEE),

		// —— 远程：会放风筝、会爆炸、会飞，或者近身代价太高的 ——
		entry("skeleton", Preference.BOW),
		entry("stray", Preference.BOW),
		entry("creeper", Preference.BOW),
		entry("phantom", Preference.BOW),
		entry("witch", Preference.BOW),
		entry("pillager", Preference.BOW),
		entry("vindicator", Preference.BOW),
		entry("evoker", Preference.BOW),
		entry("ravager", Preference.BOW),
		entry("blaze", Preference.BOW),
		entry("ghast", Preference.BOW),
		entry("hoglin", Preference.BOW),
		entry("piglin_brute", Preference.BOW),
		entry("zoglin", Preference.BOW),
		entry("shulker", Preference.BOW),
		entry("warden", Preference.BOW),
		entry("ender_dragon", Preference.BOW));

	private static Map.Entry<Identifier, Preference> entry(String path, Preference value) {
		return Map.entry(new Identifier("minecraft", path), value);
	}

	private TargetWeapon() {
	}

	/**
	 * 这种怪该用什么打；表里没有就返回 {@code null}，由调用方退回距离判断。
	 *
	 * <p>刻意<b>不</b>给未列出的生物瞎猜一个默认值：模组生物的行为无从预判，
	 * 而猜错的代价是玩家眼睁睁看着他用错武器送命。</p>
	 */
	public static Preference of(LivingEntity target) {
		if (target == null) {
			return null;
		}
		return TABLE.get(Registries.ENTITY_TYPE.getId(target.getType()));
	}

	/** 表里登记的全部生物 id（测试用：核对每一条都是真实存在的注册名）。 */
	public static java.util.Set<Identifier> registeredIds() {
		return TABLE.keySet();
	}
}
