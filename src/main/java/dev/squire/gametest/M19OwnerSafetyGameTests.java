package dev.squire.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.combat.OwnerFriendlyFire;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * <b>侍从永远不会攻击自己的主人。</b>
 *
 * <p>这是这个模组里最不能坏的一条。它单独一个文件，是因为它不属于任何一个功能——
 * 战斗、护卫、自主、职业每一条路径都必须服从它，而它们各自的测试只会验各自那条路。</p>
 *
 * <h2>它是怎么坏的</h2>
 * <p>玩家误挥了一下侍从。香草的 {@code LivingEntity.damage()} 把主人写进了
 * {@code getAttacker()}；自主反射把「正在打我的人」当成威胁还了手；而那条反射每 tick
 * 跑一次，于是玩家被自己的伙伴打死。当时排除条件里只有「不是他自己」，没有
 * 「不是主人」。</p>
 *
 * <p>所以这里同时验两层：最底下那道闸（{@code attack}/{@code shoot} 直接拒绝），
 * 以及真实世界里的结果（挨了一下之后主人一滴血都不该掉）。</p>
 *
 * <h2>它的第二种坏法：箭</h2>
 * <p>「不瞄主人」这条闸对箭是不够的。侍从瞄着僵尸、放箭、然后主人走进弹道——
 * 那支箭是一枚真实的香草实体，它不认识谁是主人，而做决定的那一刻它还不存在。
 * 所以这里再验两条：放箭<b>之前</b>主人挡着射线就该收弓，以及一支已经飞出去的箭
 * 打到主人身上必须一滴血都不掉。</p>
 */
public final class M19OwnerSafetyGameTests implements FabricGameTest {

	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static AvatarEntity summon(TestContext context, SquireRuntime rt,
			FakePlayer owner, BlockPos relative) {
		context.getWorld().spawnEntity(owner);
		AvatarEntity avatar = rt.summonFor(owner);
		Vec3d feet = Vec3d.ofBottomCenter(context.getAbsolutePos(relative));
		avatar.refreshPositionAndAngles(feet.x, feet.y, feet.z, 0.0f, 0.0f);
		return avatar;
	}

	private static void cleanUp(SquireRuntime rt, FakePlayer owner) {
		rt.resolveAvatarFor(owner.getUuid()).ifPresent(
			avatar -> rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE"));
		rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
	}

	/** 底层那道闸：直接命令他打主人，也必须被拒绝。 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-owner-immune")
	public void theOwnerCanNeverBeAttackedEvenWhenOrdered(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "owner-immune");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(2, 2, 2));
			owner.refreshPositionAndAngles(
				Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 3))).x,
				Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 3))).y,
				Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 3))).z,
				0f, 0f);

			var melee = avatar.attack(owner.getUuid());
			context.assertFalse(melee.success(), "近战攻击主人必须被拒绝");
			context.assertTrue("OWNER_IMMUNE".equals(melee.errorCode()),
				"拒绝的理由要说得出来，实际是：" + melee.errorCode());

			var shot = avatar.shoot(owner.getUuid());
			context.assertFalse(shot.success(), "射主人一样必须被拒绝");
			context.assertTrue("OWNER_IMMUNE".equals(shot.errorCode()),
				"实际是：" + shot.errorCode());

			// 连锁定都不许：被自己的伙伴瞄着，本身就是不该出现的状态。
			avatar.setTarget(owner);
			context.assertTrue(avatar.getTarget() == null,
				"主人不该锁得上，实际锁着：" + avatar.getTarget());

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 主人站在射线上时，他<b>不放箭</b>。
	 *
	 * <p>这是误伤链条上最靠前的一环：瞄准闸只挡「把主人当目标」，挡不住
	 * 「瞄着僵尸而主人正好站在中间」。收弓之后的出路和视线被挡是同一条——
	 * 挪个位置，而不是站着不动，也不是换成近战。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-owner-line")
	public void theSquireHoldsFireWhenTheOwnerStandsInTheLine(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "owner-line-of-fire");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(2, 2, 1));
			avatar.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
			avatar.items().insert(new ItemStack(Items.ARROW, 16));

			var zombie = EntityType.ZOMBIE.create(world);
			context.assertTrue(zombie != null, "test zombie");
			Vec3d far = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(2, 2, 7)));
			zombie.refreshPositionAndAngles(far.x, far.y, far.z, 0f, 0f);
			world.spawnEntity(zombie);

			// 主人正好站在两者之间。
			Vec3d between = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(2, 2, 4)));
			owner.refreshPositionAndAngles(between.x, between.y, between.z, 0f, 0f);

			var blocked = avatar.shoot(zombie.getUuid());
			context.assertFalse(blocked.success(), "主人挡着射线时不许放箭");
			context.assertTrue(
				AvatarEntity.FRIENDLY_IN_LINE.equals(blocked.errorCode()),
				"拒绝的理由要说得出来，实际是：" + blocked.errorCode());
			context.assertTrue(world.getEntitiesByClass(ArrowEntity.class,
					owner.getBoundingBox().expand(16.0), a -> true).isEmpty(),
				"被拒的那一拍不该有任何箭飞出去");

			// 反过来也要成立：主人让开之后他必须真的开始拉弓，
			// 否则这条闸就成了「他再也不射箭了」，那是另一种坏掉。
			Vec3d aside = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(6, 2, 1)));
			owner.refreshPositionAndAngles(aside.x, aside.y, aside.z, 0f, 0f);
			var clear = avatar.shoot(zombie.getUuid());
			context.assertFalse(
				AvatarEntity.FRIENDLY_IN_LINE.equals(clear.errorCode()),
				"主人已经让开，却还在报挡路：" + clear.errorCode());

			zombie.discard();
			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 已经飞在半空的那一支箭：打到主人身上，一滴血都不掉。
	 *
	 * <p><b>不能</b>用「让他掉血」来验：Fabric 的 {@code FakePlayer} 把
	 * {@code isInvulnerableTo} 写死成 {@code true}，GameTest 里的主人根本掉不了血——
	 * 那样「主人没掉血」在有 bug 的代码上一样成立，正是这个文件开头警告过的那种假绿。
	 * （不是推测：先按那个写法写了一版，控制组当场就红了。）</p>
	 *
	 * <p>所以直接验真正管事的那条链：{@code ALLOW_DAMAGE} 这个事件的<b>聚合结果</b>。
	 * 它同时证明两件事——判据是对的，而且 {@code OwnerFriendlyFire.register()} 真的
	 * 挂上去了。控制组（同一支箭换成僵尸射的）必须放行，否则这条闸就是无差别拦截，
	 * 那是另一种坏掉。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-owner-arrow")
	public void anArrowFromItsOwnSquireNeverHurtsTheOwner(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "owner-arrow");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(2, 2, 2));
			Vec3d ahead = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(2, 2, 4)));
			owner.refreshPositionAndAngles(ahead.x, ahead.y, ahead.z, 0f, 0f);
			// 判据本身：只认「侍从 → 它自己的主人」这一个方向。
			context.assertTrue(OwnerFriendlyFire.isOwnerHitByOwnSquire(owner, avatar),
				"侍从打自己的主人必须被认出来");
			context.assertFalse(OwnerFriendlyFire.isOwnerHitByOwnSquire(avatar, owner),
				"主人打侍从是正常的世界规则，不该被这条闸碰到");

			ArrowEntity arrow = new ArrowEntity(world, avatar);
			var zombie = EntityType.ZOMBIE.create(world);
			context.assertTrue(zombie != null, "test zombie");
			Vec3d at = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(6, 2, 6)));
			zombie.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
			world.spawnEntity(zombie);

			context.assertFalse(
				ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(owner,
					world.getDamageSources().arrow(arrow, avatar), 4.0f),
				"侍从射出的箭没有被拦下来——主人会被自己的伙伴射伤");
			context.assertTrue(
				ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(owner,
					world.getDamageSources().arrow(arrow, zombie), 4.0f),
				"控制组也被拦了：这条闸成了无差别拦截，主人从此谁都打不动");
			zombie.discard();

			cleanUp(rt, owner);
			context.complete();
		});
	}

	/**
	 * 自主反射的判据本身：挨了主人一下之后，他不会把主人当成要还手的威胁。
	 *
	 * <p>这里<b>不</b>用「跑 60 tick 看主人掉不掉血」那种写法。
	 * {@code AutonomyController.tick()} 用 {@code PlayerManager.getPlayer(ownerId)}
	 * 解析主人，而 GameTest 的 {@code FakePlayer} 从来不在 PlayerManager 里，
	 * 那条循环第一句就 continue 了——于是「主人没掉血」这个断言<b>在有 bug 的代码上
	 * 一样成立</b>。（这不是推测：把修复关掉重跑一遍，那个写法照样是绿的。）
	 * 所以直接验判据。</p>
	 */
	@GameTest(templateName = FLOOR, tickLimit = 200, batchId = "squire-owner-no-revenge")
	public void beingHitByTheOwnerNeverMakesTheOwnerAThreat(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "owner-no-revenge");

		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, rt, owner, new BlockPos(2, 2, 2));
			Vec3d beside = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(2, 2, 3)));
			owner.refreshPositionAndAngles(beside.x, beside.y, beside.z, 0f, 0f);

			// 玩家手滑打了他一下——香草会把主人写进 getAttacker()。
			avatar.damage(world.getDamageSources().playerAttack(owner), 2.0f);
			context.assertTrue(avatar.getAttacker() == owner,
				"这一测试的前提是主人真的被记成了攻击者");

			context.assertFalse(
				dev.squire.server.runtime.AutonomyController
					.wouldRetaliateAgainst(avatar, owner),
				"侍从把主人当成了要还手的目标——这正是玩家被自己伙伴打死的那条路");
			context.assertTrue(avatar.getTarget() != owner, "主人也不该被锁定");

			// 反过来也要成立：修得太狠、连怪都不还手了，是另一种坏掉。
			var zombie = net.minecraft.entity.EntityType.ZOMBIE.create(world);
			context.assertTrue(zombie != null, "test zombie");
			Vec3d at = Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(2, 2, 1)));
			zombie.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
			world.spawnEntity(zombie);
			context.assertTrue(
				dev.squire.server.runtime.AutonomyController
					.wouldRetaliateAgainst(avatar, zombie),
				"咬他的僵尸还是要还手的");
			zombie.discard();

			cleanUp(rt, owner);
			context.complete();
		});
	}
}
