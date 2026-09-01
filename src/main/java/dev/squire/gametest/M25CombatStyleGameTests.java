package dev.squire.gametest;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.combat.CombatStyle;
import dev.squire.server.registry.SquireEntities;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * 面板上写的打法，就是他真正打出来的打法。
 *
 * <p>这一组守的是一个玩家<b>一眼就能看见</b>的毛病：行为页选「只用弓」他上去砍，
 * 选「只近战」他站着射。原因不是标签接反了，而是判断的<b>优先级</b>反了——
 * 「玩家亲手插的武器谁都不许动」这条分支排在档位前面，而它从头到尾没读过档位；
 * 换手失败（没弓、没箭、包里没有近战武器）时又照样上锁，于是手上碰巧是什么，
 * 他就一直用什么，跟面板上的字正好相反。</p>
 *
 * <p>另一半是<b>等级权限</b>：选武器这件事原来只有护卫那一条路过闸门，自主反击和
 * 「去打那只怪」调的是不带闸门的重载。守卫 Lv.4 才解锁「弓箭使用」，那就必须在
 * 每一条路上都成立，也包括玩家亲手把弓塞进他手里的时候——否则能力清单是在骗人。</p>
 */
public final class M25CombatStyleGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	/** 站位：五格开外，弓射得到，也够得着近战。 */
	private static final BlockPos AVATAR_POS = new BlockPos(2, 2, 4);
	private static final BlockPos TARGET_POS = new BlockPos(7, 2, 4);

	private static AvatarEntity avatar(TestContext context) {
		AvatarEntity avatar = context.spawnEntity(SquireEntities.AVATAR, AVATAR_POS);
		avatar.releaseWeaponChoice();
		avatar.setCombatStyle(CombatStyle.Style.AUTO);
		return avatar;
	}

	/** 一只不会动的僵尸。目标只是「有个东西在那儿」，它的 AI 只会把测试搅乱。 */
	private static LivingEntity dummy(TestContext context) {
		LivingEntity target = context.spawnEntity(EntityType.ZOMBIE, TARGET_POS);
		if (target instanceof MobEntity mob) {
			mob.setAiDisabled(true);
		}
		return target;
	}

	/** 直接把东西塞进主手，等价于玩家在面板上拖一件武器进去（连锁一起）。 */
	private static void putInHand(AvatarEntity avatar, ItemStack stack) {
		avatar.items().setEquipped(EquipmentSlot.MAINHAND, stack);
		avatar.markWeaponChosenByPlayer();
	}

	private static boolean holdingBow(AvatarEntity avatar) {
		return avatar.items().equipped(EquipmentSlot.MAINHAND).getItem() instanceof BowItem;
	}

	private static boolean holdingSword(AvatarEntity avatar) {
		return avatar.items().equipped(EquipmentSlot.MAINHAND).getItem() instanceof SwordItem;
	}

	// ------------------------------------------------------------ 档位 vs 武器锁

	/**
	 * 「只近战」不许射箭——哪怕他手上正握着一把上好弦的弓。
	 *
	 * <p>这正是玩家报的那一半：包里没有近战武器时换手失败，弓留在手上并且被锁住，
	 * 下一拍那条锁分支看见「手上是弓」就返回射箭。面板写着只近战。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void meleeOnlyNeverShootsTheBowInHisHand(TestContext context) {
		AvatarEntity avatar = avatar(context);
		LivingEntity target = dummy(context);
		avatar.items().insert(new ItemStack(Items.ARROW, 8));
		putInHand(avatar, new ItemStack(Items.BOW));

		context.assertFalse(
			CombatStyle.prepare(avatar, target, CombatStyle.Style.MELEE),
			"「只近战」不该射箭");
		context.assertFalse(avatar.isDrawingBow(), "更不该在拉弓");

		// 包里有剑就该换过去——「只近战」的他手上不该一直是一把弓。
		avatar.items().insert(new ItemStack(Items.IRON_SWORD));
		context.assertFalse(
			CombatStyle.prepare(avatar, target, CombatStyle.Style.MELEE),
			"给了剑更不该射箭");
		context.assertTrue(holdingSword(avatar), "该换成剑，实际手上是 "
			+ avatar.items().equipped(EquipmentSlot.MAINHAND));
		context.complete();
	}

	/**
	 * 「只用弓」就去把弓拿出来——哪怕玩家上一场亲手给他插过一把剑。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void bowOnlyPicksUpTheBowEvenWithASwordInHand(TestContext context) {
		AvatarEntity avatar = avatar(context);
		LivingEntity target = dummy(context);
		avatar.items().insert(new ItemStack(Items.BOW));
		avatar.items().insert(new ItemStack(Items.ARROW, 8));
		putInHand(avatar, new ItemStack(Items.IRON_SWORD));

		context.assertTrue(
			CombatStyle.prepare(avatar, target, CombatStyle.Style.RANGED),
			"「只用弓」该射箭");
		context.assertTrue(holdingBow(avatar), "该换成弓");
		context.complete();
	}

	/**
	 * 空弦降级<b>不许留下后遗症</b>：给了箭之后他要能自己把弓再举起来。
	 *
	 * <p>原来这里是个死结——没箭时降级成近战，可「玩家选了弓」那把锁还在，于是
	 * 之后无论给多少箭，那条锁分支都只看手上的剑，永远返回近战。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void anEmptyQuiverDoesNotStickToMelee(TestContext context) {
		AvatarEntity avatar = avatar(context);
		LivingEntity target = dummy(context);
		avatar.items().insert(new ItemStack(Items.IRON_SWORD));
		putInHand(avatar, new ItemStack(Items.BOW)); // 有弓，一支箭都没有

		context.assertFalse(
			CombatStyle.prepare(avatar, target, CombatStyle.Style.RANGED),
			"没箭当然射不出去");
		context.assertTrue(holdingSword(avatar), "没箭就该退回近战，而不是举着空弓");

		avatar.items().insert(new ItemStack(Items.ARROW, 4));
		context.assertTrue(
			CombatStyle.prepare(avatar, target, CombatStyle.Style.RANGED),
			"给了箭之后必须自己把弓举回来");
		context.assertTrue(holdingBow(avatar), "手上该是弓");
		context.complete();
	}

	/**
	 * 「只用弓」但身上一把弓都没有：如实用近战打，而且<b>不把那把剑锁死</b>。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void bowOnlyWithoutABowFallsBackButKeepsTheOrder(TestContext context) {
		AvatarEntity avatar = avatar(context);
		LivingEntity target = dummy(context);
		avatar.items().insert(new ItemStack(Items.IRON_SWORD));

		context.assertTrue(CombatStyle.CommandOutcome.NO_BOW
				== CombatStyle.equipForCommand(avatar, CombatStyle.Style.RANGED,
					CombatStyle.Gates.EVERYTHING),
			"没有弓就要如实说没有弓");
		context.assertTrue(holdingSword(avatar), "眼下先用近战");
		context.assertFalse(CombatStyle.prepare(avatar, target,
			CombatStyle.Style.RANGED), "没弓射不了");

		avatar.items().insert(new ItemStack(Items.BOW));
		avatar.items().insert(new ItemStack(Items.ARROW, 4));
		context.assertTrue(
			CombatStyle.prepare(avatar, target, CombatStyle.Style.RANGED),
			"给了弓和箭，那句「只用弓」就该自己兑现");
		context.complete();
	}

	// ---------------------------------------------------------------- 等级权限

	/**
	 * 守卫 Lv.1–3 不会用弓——玩家亲手把弓塞进他手里也一样，他会收起来换剑。
	 *
	 * <p>能力清单上写着「Lv.4 弓箭使用」，那就必须是真的不会。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void lowLevelGuardHolstersABowPutInHisHand(TestContext context) {
		AvatarEntity avatar = avatar(context);
		LivingEntity target = dummy(context);
		avatar.items().insert(new ItemStack(Items.ARROW, 8));
		avatar.items().insert(new ItemStack(Items.IRON_SWORD));
		putInHand(avatar, new ItemStack(Items.BOW));

		context.assertFalse(CombatStyle.prepare(avatar, target,
			CombatStyle.Style.RANGED, CombatStyle.Gates.MELEE_ONLY),
			"还没学会用弓的守卫不许射箭");
		context.assertTrue(holdingSword(avatar), "该把弓收起来换剑，实际手上是 "
			+ avatar.items().equipped(EquipmentSlot.MAINHAND));
		context.complete();
	}

	/** 一把近战武器都没有时也一样：空着手上，而不是抡着一把「他不会用」的弓。 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void lowLevelGuardGoesEmptyHandedRatherThanSwingABow(TestContext context) {
		AvatarEntity avatar = avatar(context);
		LivingEntity target = dummy(context);
		avatar.items().insert(new ItemStack(Items.ARROW, 8));
		putInHand(avatar, new ItemStack(Items.BOW));

		context.assertFalse(CombatStyle.prepare(avatar, target,
			CombatStyle.Style.RANGED, CombatStyle.Gates.MELEE_ONLY),
			"还没学会用弓的守卫不许射箭");
		context.assertFalse(holdingBow(avatar), "弓该回背包里去");
		context.assertTrue(avatar.items().countOf(
			net.minecraft.registry.Registries.ITEM.getId(Items.BOW)) > 0,
			"收起来不等于弄丢——弓必须还在背包里");
		context.complete();
	}

	/** 点了「只用弓」但等级还不会：说清楚原因，档位记下，升级之后自己生效。 */
	@GameTest(templateName = FLOOR, tickLimit = 40)
	public void lockedBowSaysSoInsteadOfSilentlyMeleeing(TestContext context) {
		AvatarEntity avatar = avatar(context);
		avatar.items().insert(new ItemStack(Items.BOW));
		avatar.items().insert(new ItemStack(Items.ARROW, 8));
		avatar.items().insert(new ItemStack(Items.IRON_SWORD));

		avatar.setCombatStyle(CombatStyle.Style.RANGED);
		context.assertTrue(CombatStyle.CommandOutcome.BOW_NOT_UNLOCKED
				== CombatStyle.equipForCommand(avatar, CombatStyle.Style.RANGED,
					CombatStyle.Gates.MELEE_ONLY),
			"该说的是「我还没学会用弓」，不是默默去砍");
		context.assertFalse(holdingBow(avatar), "眼下拿的该是近战武器");
		context.assertTrue(CombatStyle.Mode.BOW_LOCKED
				== CombatStyle.modeOf(avatar, CombatStyle.Gates.MELEE_ONLY),
			"面板要照实写「未解锁」");

		// 升到 Lv.4（闸门打开）之后，同一个档位不用玩家再点一次就该兑现。
		LivingEntity target = dummy(context);
		context.assertTrue(CombatStyle.prepare(avatar, target,
			CombatStyle.Style.RANGED, CombatStyle.Gates.of(true, false)),
			"解锁之后那句「只用弓」自己生效");
		context.assertTrue(holdingBow(avatar), "手上该是弓");
		context.complete();
	}
}
