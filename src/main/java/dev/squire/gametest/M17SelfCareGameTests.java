package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 残血自救：他自己会吃东西，而且分得清药水种类。
 *
 * <p>这几条测试对应三个真问题：</p>
 * <ul>
 *   <li>「治疗自己」以前只有玩家喊出来才会发生——需要盯着血条、在战斗中腾出手打字，
 *       等于没有这个能力；</li>
 *   <li>普通食物整类不在候选里（判据是「食物效果表里有治疗成分」），背着一组熟牛排
 *       也报 {@code INSUFFICIENT_HEALING_ITEM}；</li>
 *   <li>喷溅药水评分最高，于是自救时他会去「喝」一瓶原版根本喝不了的喷溅药水，
 *       增益药水也和治疗药水混为一谈。</li>
 * </ul>
 */
public final class M17SelfCareGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static void place(net.minecraft.entity.Entity entity, Vec3d feetCenter) {
		entity.refreshPositionAndAngles(feetCenter.x, feetCenter.y, feetCenter.z, 0.0f, 0.0f);
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	/** 召唤一个已经受伤的伙伴，背包里放上指定物品。 */
	private static AvatarEntity hurtCompanion(TestContext context, SquireRuntime rt,
			FakePlayer owner, float health, ItemStack... carried) {
		ServerWorld world = context.getWorld();
		world.spawnEntity(owner);
		TestSupport.clearHostilesNear(world,
			context.getAbsolutePos(new BlockPos(4, 2, 4)), 16);
		AvatarEntity avatar = rt.summonFor(owner);
		place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 5))));
		for (ItemStack stack : carried) {
			avatar.items().insert(stack);
		}
		avatar.setHealth(health);
		return avatar;
	}

	private static void finish(TestContext context, SquireRuntime rt,
			AvatarEntity avatar, FakePlayer owner) {
		rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
		context.complete();
	}

	// ---------------------------------------------------------- 自己吃普通食物

	/**
	 * 残血 + 背包里有熟牛排 = 他自己吃，不用任何人开口。
	 *
	 * <p>普通食物对生物走香草进食路径是<b>一点血都不回</b>的（没有饥饿条），所以这里
	 * 按营养折算成一段再生。断言看的是「牛排少了」+「真的在回血」，而不是执行器的
	 * 自我记账。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-selfcare")
	public void aHurtCompanionEatsItsOwnFoodWithoutBeingTold(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "selfcare-food");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AtomicBoolean checked = new AtomicBoolean(false);
		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> ref[0] = hurtCompanion(context, rt, owner, 6.0f,
			new ItemStack(Items.COOKED_BEEF, 4)));

		context.runAtTick(90, () -> {
			AvatarEntity avatar = ref[0];
			int left = avatar.items().countOf(new Identifier("minecraft:cooked_beef"));
			context.assertTrue(left < 4,
				"他应该自己吃掉了至少一块牛排，剩余=" + left);
			boolean healing = avatar.hasStatusEffect(StatusEffects.REGENERATION)
				|| avatar.getHealth() > 6.0f;
			context.assertTrue(healing,
				"普通食物必须真的让他回血，hp=" + avatar.getHealth());
			checked.set(true);
			finish(context, rt, avatar, owner);
		});
		context.runAtTick(380, () -> {
			if (!checked.get()) {
				context.throwGameTestException("companion never fed itself");
			}
		});
	}

	/** 血是满的就别乱吃——自救是反射，不是浪费。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-selfcare")
	public void aHealthyCompanionLeavesItsFoodAlone(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "selfcare-healthy");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> {
			ref[0] = hurtCompanion(context, rt, owner, 20.0f,
				new ItemStack(Items.COOKED_BEEF, 4));
			ref[0].setHealth(ref[0].getMaxHealth());
		});
		context.runAtTick(120, () -> {
			AvatarEntity avatar = ref[0];
			context.assertTrue(
				avatar.items().countOf(new Identifier("minecraft:cooked_beef")) == 4,
				"满血时一块都不该动");
			finish(context, rt, avatar, owner);
		});
	}

	// ------------------------------------------------------------ 分得清药水

	/**
	 * 背包里只有<b>喷溅</b>治疗药水时，自救就是没有办法——绝不能假装喝下去。
	 *
	 * <p>原版没有「喝一瓶喷溅药水」这个动作。以前的评分表给喷溅药水最高分，自救复用
	 * 同一张表，于是他会把它灌进嘴里。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-selfcare")
	public void heNeverDrinksASplashPotion(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "selfcare-splash");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> ref[0] = hurtCompanion(context, rt, owner, 6.0f,
			PotionUtil.setPotion(new ItemStack(Items.SPLASH_POTION, 2), Potions.HEALING)));

		context.runAtTick(120, () -> {
			AvatarEntity avatar = ref[0];
			context.assertTrue(
				avatar.items().countOf(new Identifier("minecraft:splash_potion")) == 2,
				"喷溅药水一瓶都不该被喝掉");
			finish(context, rt, avatar, owner);
		});
	}

	/** 增益不是治疗：残血时抓着一瓶速度药水解决不了任何问题，该吃的是食物。 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-selfcare")
	public void heTellsHealingPotionsApartFromBuffs(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "selfcare-buff");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> ref[0] = hurtCompanion(context, rt, owner, 6.0f,
			PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS),
			new ItemStack(Items.COOKED_BEEF, 2)));

		context.runAtTick(120, () -> {
			AvatarEntity avatar = ref[0];
			context.assertTrue(
				avatar.items().countOf(new Identifier("minecraft:potion")) == 1,
				"速度药水不是治疗物品，不该被喝掉");
			context.assertTrue(
				avatar.items().countOf(new Identifier("minecraft:cooked_beef")) < 2,
				"该吃的是食物");
			finish(context, rt, avatar, owner);
		});
	}

	// ---------------------------------------------------- 手里拿的说了算

	/**
	 * 玩家亲手插进主手的剑，绝不能在战斗中被自动换成弓。
	 *
	 * <p>这是整轮改动最重要的一条回归守卫。之前 {@code CombatStyle} 的判据是
	 * 「背包里有没有弓和箭」，于是每个战斗 tick 都会把弓拽到主手、把玩家刚放的剑
	 * 挤回包里，全程没有任何痕迹。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-selfcare")
	public void aSwordPutInHisHandIsNeverSwappedForABow(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "hand-rules");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> {
			AvatarEntity avatar = hurtCompanion(context, rt, owner, 20.0f,
				new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 64));
			avatar.setHealth(avatar.getMaxHealth());
			// 玩家把剑放进主手（和面板拖拽走的是同一条路：直接写装备槽）
			avatar.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND,
				new ItemStack(Items.NETHERITE_SWORD));
			rt.startGuard(owner, 16, true);
			ref[0] = avatar;
		});
		// 让护卫运行时跑足够多拍：以前一拍就够把剑换掉了。
		context.runAtTick(90, () -> {
			AvatarEntity avatar = ref[0];
			context.assertTrue(avatar.getEquippedStack(
					net.minecraft.entity.EquipmentSlot.MAINHAND)
				.isOf(Items.NETHERITE_SWORD),
				"玩家亲手给的剑必须留在手上，手上=" + avatar.getEquippedStack(
					net.minecraft.entity.EquipmentSlot.MAINHAND));
			rt.stopGuard(owner);
			finish(context, rt, avatar, owner);
		});
	}

	/**
	 * 选武器对照表里的每一条都必须是真实存在的注册名。
	 *
	 * <p>写错一个字（{@code piglin_brute} 打成 {@code piglin_bruit}）不会报错，
	 * 只会让那一条<b>永远匹配不上</b>——表面上表里有，实际上从来没生效过。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 60, batchId = "squire-selfcare")
	public void everyEntryInTheWeaponTableIsARealEntityType(TestContext context) {
		java.util.List<String> unknown = new java.util.ArrayList<>();
		for (Identifier id : dev.squire.server.combat.TargetWeapon.registeredIds()) {
			if (!net.minecraft.registry.Registries.ENTITY_TYPE.containsId(id)) {
				unknown.add(id.toString());
			}
		}
		context.assertTrue(unknown.isEmpty(),
			"选武器表里有认不出的生物 id（写错就永远匹配不上）：" + unknown);
		context.complete();
	}

	/** 拿着弓但箭用光了：这是唯一允许把弓收回去的情况。 */
	@GameTest(templateName = FLOOR, tickLimit = 300, batchId = "squire-selfcare")
	public void anEmptyQuiverIsTheOneCaseThatSwapsTheBowAway(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "empty-quiver");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> {
			AvatarEntity avatar = hurtCompanion(context, rt, owner, 20.0f,
				new ItemStack(Items.NETHERITE_SWORD));   // 包里有剑，一支箭都没有
			avatar.setHealth(avatar.getMaxHealth());
			avatar.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND,
				new ItemStack(Items.BOW));
			rt.startGuard(owner, 16, true);
			ref[0] = avatar;
		});
		context.runAtTick(90, () -> {
			AvatarEntity avatar = ref[0];
			context.assertTrue(!avatar.getEquippedStack(
					net.minecraft.entity.EquipmentSlot.MAINHAND).isOf(Items.BOW),
				"没箭的弓应该被换成近战武器，而不是举着空弓站着");
			context.assertTrue(!avatar.isDrawingBow(), "更不该停在拉弓状态");
			rt.stopGuard(owner);
			finish(context, rt, avatar, owner);
		});
	}

	/** 有害的东西一口都不能碰，哪怕它也算「食物」。 */
	@GameTest(templateName = FLOOR, tickLimit = 400, batchId = "squire-selfcare")
	public void heNeverEatsSomethingHarmful(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "selfcare-rotten");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		AvatarEntity[] ref = new AvatarEntity[1];
		context.runAtTick(5, () -> ref[0] = hurtCompanion(context, rt, owner, 6.0f,
			new ItemStack(Items.ROTTEN_FLESH, 3),
			PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.HARMING)));

		context.runAtTick(120, () -> {
			AvatarEntity avatar = ref[0];
			context.assertTrue(
				avatar.items().countOf(new Identifier("minecraft:rotten_flesh")) == 3,
				"腐肉带饥饿效果，宁可饿着也不吃");
			context.assertTrue(
				avatar.items().countOf(new Identifier("minecraft:potion")) == 1,
				"伤害药水绝不能当治疗用");
			context.assertTrue(!avatar.hasStatusEffect(StatusEffects.INSTANT_DAMAGE),
				"更不该把自己喝伤");
			finish(context, rt, avatar, owner);
		});
	}
}
