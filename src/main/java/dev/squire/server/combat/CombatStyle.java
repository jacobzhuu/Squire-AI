package dev.squire.server.combat;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;

/**
 * 这一拍该用近战还是远程，以及该把哪件武器拿在手上。
 *
 * <p>抽成一处，是因为选武器这件事原来<b>只</b>写在 {@code GuardRuntime} 里，而且只看
 * {@code GENERIC_ATTACK_DAMAGE}——弓的伤害来自箭的初速，身上根本没有这个属性，于是
 * 一把满配弓的得分是 0，永远赢不过一把木剑。玩家看到的就是「他只会拿最强的近战武器
 * 冲上去」，哪怕你刚给他装备了弓。</p>
 */
public final class CombatStyle {

	/** 打法。 */
	public enum Style {
		/** 自己看着办：够得着就近战，够不着且有弓有箭就射。 */
		AUTO,
		/** 只用近战。 */
		MELEE,
		/** 优先用弓；没弓没箭时仍然会近战自保，而不是站着挨打。 */
		RANGED
	}

	/** 空手时才用得上：拉开这个距离（平方）就去拿弓，否则拿近战。 */
	private static final double MELEE_SWITCH_DISTANCE_SQ = 25.0;

	/** 主手那件东西在战斗里算什么。 */
	public enum HandKind { NOTHING_USABLE, MELEE_WEAPON, BOW }

	/**
	 * 面板上显示的打法。比 {@link Style} 多两档，两档都不是能直接选的选项：
	 *
	 * <ul>
	 *   <li>{@code MANUAL}——「你亲手插了武器」之后的状态，点一下按钮就回到自动；</li>
	 *   <li>{@code BOW_LOCKED}——档位是「只用弓」，可他的职业等级还没解锁弓箭
	 *       （守卫 Lv.4）。这一档存在的唯一理由是<b>面板不许说谎</b>：档位记下了，
	 *       但他现在打出来的是近战，那就得写在按钮上。</li>
	 * </ul>
	 */
	public enum Mode { AUTO, BOW, MELEE, MANUAL, BOW_LOCKED }

	/** 当前该在面板上显示哪一档。不带闸门的版本按「什么都会」算。 */
	public static Mode modeOf(AvatarEntity avatar) {
		return modeOf(avatar, Gates.EVERYTHING);
	}

	/**
	 * 当前该在面板上显示哪一档，把职业闸门算进去。
	 *
	 * <p>武器锁只在 AUTO 档才是一档独立状态——选了「只用弓」「只近战」之后，
	 * 决定他怎么打的是档位，不是手上那件东西（见 {@link #prepare}）。</p>
	 */
	public static Mode modeOf(AvatarEntity avatar, Gates gates) {
		return avatar == null ? Mode.AUTO
			: displayMode(avatar.combatStyle(), avatar.weaponChosenByPlayer(), gates);
	}

	/**
	 * 纯函数版：给定档位、有没有玩家亲手插的武器、以及职业闸门，面板该显示哪一档。
	 *
	 * <p>单独抽出来是为了能被普通单测盖住——{@code AvatarEntity} 要一整套已经引导起来
	 * 的注册表才建得出来，而「面板显示哪一档」本身只是三个值的组合。</p>
	 */
	public static Mode displayMode(Style style, boolean chosenByPlayer, Gates gates) {
		Gates open = gates == null ? Gates.EVERYTHING : gates;
		return switch (style == null ? Style.AUTO : style) {
			case RANGED -> open.bow() ? Mode.BOW : Mode.BOW_LOCKED;
			case MELEE -> Mode.MELEE;
			case AUTO -> chosenByPlayer ? Mode.MANUAL : Mode.AUTO;
		};
	}

	/**
	 * 面板按钮点一下换下一档：自动 → 用弓 → 近战 → 自动。
	 *
	 * <p>从「手动」点出去一律回到自动——玩家点这个按钮就是想把选择权交回去，
	 * 没有必要让他再多点两下。</p>
	 */
	public static void cycleMode(AvatarEntity avatar) {
		cycleMode(avatar, Gates.EVERYTHING);
	}

	/**
	 * 带职业闸门的版本，返回这一下点出来的实际结果，好让调用方给一句说得清的回执。
	 *
	 * <p>「只用弓（未解锁）」和「只用弓」在循环里是同一档：点下去都到近战。他没学会
	 * 用弓不该让玩家多点一下才能走完一圈。</p>
	 */
	public static CommandOutcome cycleMode(AvatarEntity avatar, Gates gates) {
		Style next = nextStyle(modeOf(avatar, gates));
		avatar.setCombatStyle(next);
		return equipForCommand(avatar, next, gates);
	}

	/** 从当前显示的这一档点一下，会到哪一档。纯函数，理由同 {@link #displayMode}。 */
	public static Style nextStyle(Mode current) {
		return switch (current == null ? Mode.AUTO : current) {
			case AUTO -> Style.RANGED;
			case BOW, BOW_LOCKED -> Style.MELEE;
			case MELEE, MANUAL -> Style.AUTO;
		};
	}

	private CombatStyle() {
	}

	/** 他手上拿的是什么。这是整个打法判断<b>唯一</b>的输入。 */
	public static HandKind classifyHand(AvatarEntity avatar) {
		var held = avatar.items().equipped(EquipmentSlot.MAINHAND);
		if (held.isEmpty()) {
			return HandKind.NOTHING_USABLE;
		}
		if (held.getItem() instanceof net.minecraft.item.BowItem) {
			return HandKind.BOW;
		}
		// 有攻击力加成的都算武器：剑、斧、三叉戟，以及任何模组武器。镐和铲的加成
		// 很低但确实大于 0 —— 玩家把镐塞他手里就是让他用镐打，随他去。
		return AvatarInventory.attackDamageOf(held) > 0
			? HandKind.MELEE_WEAPON : HandKind.NOTHING_USABLE;
	}

	/**
	 * 决定这一拍怎么打，并且<b>只在允许时</b>换手。返回 true 表示射箭。
	 *
	 * <p>判断的优先级从高到低一共四层，顺序本身就是规则：</p>
	 *
	 * <ol>
	 *   <li><b>职业闸门</b>——还没学会用弓的守卫（Lv.1–3）不用弓，手上被塞了一把
	 *       也要收起来；</li>
	 *   <li><b>物理现实</b>——弓没箭就是射不出去；</li>
	 *   <li><b>玩家选的档位</b>（「只用弓」「只近战」）——面板上写着什么就打什么；</li>
	 *   <li><b>AUTO 档</b>才轮到「手上拿着什么就用什么打」：玩家亲手把剑塞进他手里
	 *       之后再把弓翻出来是「不听话」，不是「聪明」。</li>
	 * </ol>
	 *
	 * <p>第 3 层和第 4 层原来是反的：武器锁排在档位前面，而那条分支<b>从头到尾没读过
	 * {@code style}</b>。于是「只近战」的他举着弓照射（背包里没有近战武器 →
	 * 换手失败 → 弓还在手上 → 锁住），「只用弓」的他攥着剑不撒手（没弓／没箭 →
	 * 换手失败 → 剑还在手上 → 锁住），面板上的字和他做的事正好相反。</p>
	 */
	public static boolean prepare(AvatarEntity avatar, LivingEntity target,
			Style preference) {
		return prepare(avatar, target, preference, Gates.EVERYTHING);
	}

	/**
	 * 职业等级对选武器这件事开了哪几道闸。
	 *
	 * <p>{@link #EVERYTHING} 是<b>没有职业</b>时的取值，也就是职业系统出现之前的
	 * 行为——没选职业的随从今天会做的事，明天一件也不会少。守卫职业反过来是从
	 * Lv.1 的「只会拿着东西上去砍」开始，逐级把这些闸打开。</p>
	 *
	 * @param bow        会不会用弓（守卫 Lv.4）。关掉时<b>谁也打不开</b>：玩家亲手插的
	 *                   弓会被收回背包，面板上的「只用弓」显示成「未解锁」并按近战打
	 * @param autoSwitch 允许按「对面是什么」换武器（守卫 Lv.5）；关掉就退回纯距离判断
	 */
	public record Gates(boolean bow, boolean autoSwitch) {

		/** 没有职业时的取值：闸全开。 */
		public static final Gates EVERYTHING = new Gates(true, true);
		/** 守卫 Lv.1–3：只会近战。 */
		public static final Gates MELEE_ONLY = new Gates(false, false);

		public static Gates of(boolean bow, boolean autoSwitch) {
			return new Gates(bow, bow && autoSwitch);
		}
	}

	/**
	 * 这只随从的职业给选武器开了哪几道闸。
	 *
	 * <p>放在这里而不是 {@code GuardRuntime} 里，是因为战斗<b>不止护卫那一条路</b>：
	 * 自主反击（{@code AutonomyController}）和「去打那只苦力怕」
	 * （{@code AttackTargetExecutor}）也会选武器，而它们原来调的是不带闸门的重载，
	 * 于是一只 Lv.1 守卫在这两条路上照样会掏弓——等级限制只拦住了三条路里的一条。</p>
	 */
	public static Gates gatesFor(dev.squire.server.profession.ProfessionData profession) {
        if (profession != null && profession.profession()
                == dev.squire.server.profession.SquireProfession.ENGINEER) return Gates.of(false, false);
		if (profession == null || profession.profession()
				!= dev.squire.server.profession.SquireProfession.GUARD) {
			return Gates.EVERYTHING; // 没职业／不是守卫：职业系统出现之前的行为
		}
		return Gates.of(
			profession.can(dev.squire.server.profession.ProfessionAbility
				.GUARD_BOW_PROFICIENCY),
			profession.can(dev.squire.server.profession.ProfessionAbility
				.GUARD_WEAPON_SWITCHING));
	}

	/** 带职业闸门的版本。 */
	public static boolean prepare(AvatarEntity avatar, LivingEntity target,
			Style preference, Gates gates) {
		if (avatar == null || target == null) {
			return false;
		}
		Gates open = gates == null ? Gates.EVERYTHING : gates;
		Style style = preference == null ? Style.AUTO : preference;
		if (GuardSelfDefense.prepare(avatar, target, style, open)) return false;
		double distSq = avatar.squaredDistanceTo(target);
		HandKind hand = classifyHand(avatar);

		// 一、职业闸门。谁都绕不过，玩家亲手插的弓也一样：能力清单上写着守卫 Lv.4
		// 才会用弓，那就必须是真的不会——否则清单是在骗人。
		if (!open.bow() && hand == HandKind.BOW) {
			holsterBow(avatar);
			return false;
		}
		// 二、空弦。举着一把射不出去的弓站着挨打不是「听话」，是坏掉。
		if (hand == HandKind.BOW && !hasAmmoFor(avatar)) {
			degradeToMelee(avatar);
			return false;
		}
		// 三、玩家在面板上点了明确的档位，那就照它打——档位比「手上拿着什么」大。
		if (style == Style.MELEE) {
			if (hand != HandKind.MELEE_WEAPON) {
				// 手上是弓或者空着才去翻一把。已经握着一把近战武器就用那把——
				// 玩家说的是「别用弓」，没说「换一把剑」。
				equipBest(avatar, false);
			}
			avatar.cancelDraw();
			return false;
		}
		if (style == Style.RANGED) {
			if (hand == HandKind.BOW
					|| (hasWorkingBow(avatar) && equipBest(avatar, true))) {
				return distSq <= AvatarEntity.BOW_RANGE_SQ; // 太远先走近，但不换武器
			}
			equipBest(avatar, false); // 没弓／没箭：如实退回近战，而不是站着挨打
			avatar.cancelDraw();
			return false;
		}

		// 四、AUTO 档才轮到武器锁：玩家亲手插的武器谁都不许动，用它打完这一场。
		if (avatar.weaponChosenByPlayer() && hand != HandKind.NOTHING_USABLE) {
			if (hand == HandKind.BOW) {
				return distSq <= AvatarEntity.BOW_RANGE_SQ;
			}
			avatar.cancelDraw();
			return false;
		}

		// 到这里武器都是系统自己挑的，可以按当前这只怪重新决定。
		if (open.bow() && wantsBow(target, distSq, open.autoSwitch())
				&& hasWorkingBow(avatar) && equipBest(avatar, true)) {
			return distSq <= AvatarEntity.BOW_RANGE_SQ;
		}
		equipBest(avatar, false);
		avatar.cancelDraw();
		return false;
	}

	/**
	 * 把手上那把弓收起来：先换成背包里最好的近战武器，一件都没有就<b>空着手</b>上。
	 *
	 * <p>「没有近战武器就继续举着弓」是不行的——那把弓会被当成近战武器抡，而闸门的
	 * 意思是他这个等级<b>不会用弓</b>。空手打不好看，但那是实话。</p>
	 */
	private static void holsterBow(AvatarEntity avatar) {
		avatar.cancelDraw();
		if (equipBest(avatar, false)) {
			return;
		}
		if (avatar.items().unequip(EquipmentSlot.MAINHAND).success()) {
			avatar.noteMainHandSwapped();
		}
	}

	/** 耐久剩不到这个比例就算「快断了」。 */
	public static final float NEARLY_BROKEN_FRACTION = 0.10f;

	/** 这件东西快断了吗。不可损坏的永远不算。 */
	public static boolean nearlyBroken(net.minecraft.item.ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.isDamageable()) {
			return false;
		}
		return nearlyBroken(stack.getDamage(), stack.getMaxDamage());
	}

	/**
	 * 纯算术版：剩余耐久不到一成就算快断了。
	 *
	 * <p>单独抽出来是为了能被普通单测盖住——{@code ItemStack} 的静态初始化需要一整套
	 * 已经引导起来的注册表，而这条规则本身只是两个整数的比较。</p>
	 */
	public static boolean nearlyBroken(int damage, int maxDamage) {
		if (maxDamage <= 0) {
			return false;
		}
		int left = maxDamage - damage;
		return left <= Math.max(1, Math.round(maxDamage * NEARLY_BROKEN_FRACTION));
	}

	/**
	 * 守卫 Lv.2「装备意识」：手上的近战武器快断了就换背包里最好的一把。
	 *
	 * <p>玩家亲手插的武器<b>也换</b>——这不是抢他的选择，是替他接住「那把剑就要碎了」
	 * 这件他多半没在看的事。换完不再上锁，下一场他仍然可以自己插一把。</p>
	 *
	 * <p>手上是弓就只在<b>弓</b>里面找备用的：拿一把快断的弓去换一把剑，等于替
	 * 「只用弓」的他偷偷改了打法，而他自己一句话都没说。</p>
	 *
	 * @return 真的换了才返回 true
	 */
	public static boolean swapOutNearlyBrokenWeapon(AvatarEntity avatar) {
		if (avatar == null) {
			return false;
		}
		AvatarInventory items = avatar.items();
		var held = items.equipped(EquipmentSlot.MAINHAND);
		if (held.isEmpty() || !nearlyBroken(held)) {
			return false;
		}
		AvatarInventory.SlotRef backup =
			held.getItem() instanceof net.minecraft.item.BowItem
				? items.bestBackpackBowSlot() : items.bestBackpackWeaponSlot();
		if (backup == null || nearlyBroken(items.stackAt(backup))) {
			return false; // 备用的一样快断了，换了也没有意义
		}
		if (!items.equipFromMain(backup.mainIndex(), EquipmentSlot.MAINHAND).success()) {
			return false;
		}
		avatar.noteMainHandSwapped();
		return true;
	}

	/**
	 * 守卫 Lv.6「盾牌」：把盾拿到副手。已经拿着就什么都不做。
	 *
	 * @return 副手现在确实是一面盾
	 */
	public static boolean equipShield(AvatarEntity avatar) {
		if (avatar == null) {
			return false;
		}
		AvatarInventory items = avatar.items();
		AvatarInventory.SlotRef shield = items.bestShieldSlot();
		if (shield == null) {
			return false;
		}
		if (shield.isEquipment()) {
			return true;
		}
		return items.equipFromMain(shield.mainIndex(), EquipmentSlot.OFFHAND).success();
	}

	/**
	 * AUTO 档这一拍想用弓吗（还没考虑他有没有弓）。
	 *
	 * <p>只服务 AUTO——「只用弓」「只近战」在 {@link #prepare} 里就判完了，轮不到这里。</p>
	 *
	 * <p>优先查 {@link TargetWeapon} 的对照表——决定打法的是<b>对面是什么</b>，
	 * 而不是离多远。表里没有的怪（含所有模组生物）才退回原来的距离判断。</p>
	 *
	 * <p>{@code autoSwitch} 关掉时（守卫 Lv.4，还没学会自动换武器）跳过对照表，
	 * 只按<b>距离和会不会飞</b>判断——那正是设计文档给 Lv.4 的规则：
	 * 远的用弓、天上的用弓、近的近战。</p>
	 */
	private static boolean wantsBow(LivingEntity target, double distSq,
			boolean autoSwitch) {
		if (distSq > AvatarEntity.BOW_RANGE_SQ) {
			return false; // 超出射程，先走近再说
		}
		if (autoSwitch) {
			TargetWeapon.Preference wanted = TargetWeapon.of(target);
			if (wanted != null) {
				return wanted == TargetWeapon.Preference.BOW;
			}
		} else if (isAirborne(target)) {
			return true; // 够不着的东西，砍是砍不到的
		}
		return distSq > MELEE_SWITCH_DISTANCE_SQ; // 不认识这种怪：老规矩，拉开了才射
	}

	/** 在天上飞着的目标。凋灵、幻翼、恶魂这类近战根本够不着。 */
	private static boolean isAirborne(LivingEntity target) {
		return target != null && !target.isOnGround() && !target.isTouchingWater()
			&& target.getVelocity().y > -0.4;
	}

	/** 拉不动弓时的降级：唯一一个无条件把主手换成近战武器的入口。 */
	public static void degradeToMelee(AvatarEntity avatar) {
		holsterBow(avatar);
	}

	/** 玩家下令换打法之后实际发生了什么。存在的理由是<b>任何输入都要有出路</b>。 */
	public enum CommandOutcome {
		/** 照办了。 */
		OK,
		/** 他还没学会用弓（守卫 Lv.4 解锁）。档位记下了，眼下按近战打。 */
		BOW_NOT_UNLOCKED,
		/** 身上一把弓都没有。 */
		NO_BOW,
		/** 有弓，没箭。 */
		NO_ARROWS
	}

	/** 不带闸门的版本，按「什么都会」算。 */
	public static void equipForCommand(AvatarEntity avatar, Style style) {
		equipForCommand(avatar, style, Gates.EVERYTHING);
	}

	/**
	 * 玩家明确下令时的一次性换手（「用弓打」/「近战就行」/「你自己看着办」）。
	 *
	 * <p>三种都<b>解开</b>武器锁。锁的意思是「别自作聪明换掉我插的这把」，那只在
	 * AUTO 档下才是一句有内容的话；玩家一旦点了明确的档位，「用哪把」就跟着档位走。
	 * 原来这里反过来——RANGED/MELEE 都上锁，而且<b>不看换手成功没有</b>：没弓的时候
	 * 「只用弓」会把手上那把剑锁死，之后你再给他弓也拿不起来了。</p>
	 *
	 * @return 换不成的具体原因，调用方拿去写回执
	 */
	public static CommandOutcome equipForCommand(AvatarEntity avatar, Style style,
			Gates gates) {
		if (avatar == null) {
			return CommandOutcome.OK;
		}
		Gates open = gates == null ? Gates.EVERYTHING : gates;
		avatar.releaseWeaponChoice();
		GuardSelfDefense.reset(avatar);
		switch (style) {
			case RANGED -> {
				if (!open.bow()) {
					degradeToMelee(avatar);
					return CommandOutcome.BOW_NOT_UNLOCKED;
				}
				if (avatar.items().bestBowSlot() == null) {
					degradeToMelee(avatar);
					return CommandOutcome.NO_BOW;
				}
				if (!hasWorkingBow(avatar)) {
					degradeToMelee(avatar);
					return CommandOutcome.NO_ARROWS;
				}
				equipBest(avatar, true);
			}
			case MELEE -> degradeToMelee(avatar);
			case AUTO -> { }
		}
		return CommandOutcome.OK;
	}

	/** 有弓（手上或背包里）而且有箭吗；无限附魔算永远有箭。 */
	public static boolean hasWorkingBow(AvatarEntity avatar) {
		AvatarInventory items = avatar.items();
		AvatarInventory.SlotRef bow = items.bestBowSlot();
		if (bow == null) {
			return false;
		}
		return items.firstArrowSlot() != null
			|| net.minecraft.enchantment.EnchantmentHelper.getLevel(
				net.minecraft.enchantment.Enchantments.INFINITY,
				items.stackAt(bow)) > 0;
	}

	/** 手上这把弓现在射得出去吗。 */
	private static boolean hasAmmoFor(AvatarEntity avatar) {
		return avatar.items().firstArrowSlot() != null
			|| net.minecraft.enchantment.EnchantmentHelper.getLevel(
				net.minecraft.enchantment.Enchantments.INFINITY,
				avatar.items().equipped(EquipmentSlot.MAINHAND)) > 0;
	}

	/**
	 * 真正的搬运。只搬运，绝不复制（方案 C2）。已经拿着对的那把就什么都不做——
	 * 每拍都换手会让他看起来在抽搐。
	 */
	private static boolean equipBest(AvatarEntity avatar, boolean ranged) {
		AvatarInventory items = avatar.items();
		AvatarInventory.SlotRef wanted = ranged
			? items.bestBowSlot() : items.bestWeaponSlot();
		if (wanted == null) {
			return false;
		}
		if (wanted.isEquipment()) {
			return true; // 手上那把已经对了
		}
		boolean swapped = items.equipFromMain(wanted.mainIndex(), EquipmentSlot.MAINHAND)
			.success();
		if (swapped) {
			// 报一声「主手换过了」：拉弓要因此推迟一拍，否则装备变更还没同步到
			// 客户端就进入使用状态，第一箭会没有拉弓动画。
			avatar.noteMainHandSwapped();
		}
		return swapped;
	}
}
