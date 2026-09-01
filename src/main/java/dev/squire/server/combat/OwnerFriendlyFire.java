package dev.squire.server.combat;

import dev.squire.server.body.avatar.AvatarEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 最后一道闸：<b>主人绝不会被自己的侍从打伤</b>，哪怕是一支已经飞在半空的箭。
 *
 * <h2>为什么瞄准那一层不够</h2>
 * <p>{@code AvatarEntity.attack} / {@code shoot} 已经拒绝把主人当目标，
 * {@code AvatarEntity#friendlyInLineOfFire} 又拒绝在主人挡着射线时放箭。但这两条
 * 都发生在<b>放箭之前</b>，而箭是一枚真实的香草实体：它离弦之后要飞上零点几秒，
 * 这段时间里主人完全可能自己走进弹道——冲上去补刀、被僵尸击退、或者只是想绕到侧面。
 * 那一箭没有任何前置检查拦得住，因为决策做完的时候它还不存在。</p>
 *
 * <p>所以这里在<b>结算伤害</b>那一刻再判一次：受伤的是玩家、而伤害来源追溯回去是
 * 他自己的侍从，就直接取消。这条闸不关心是箭、是剑、还是以后会有的什么东西——
 * 它只认「谁打了谁」，也因此不会在加新武器时被漏掉。</p>
 *
 * <h2>取消之后箭去哪儿了</h2>
 * <p>香草 {@code PersistentProjectileEntity} 在 {@code damage()} 返回 false 时会让
 * 箭反弹、减速、掉在地上——正好是玩家能理解的画面（「弹开了」），而且箭还能捡回来，
 * 符合这个模组「只搬运、不凭空生成也不凭空消失」的一贯规矩。</p>
 *
 * <p>只挡侍从 → 主人这一个方向。别的玩家、别的生物、以及主人打侍从，一概不碰：
 * 那些是正常的世界规则，不是这条不变量要管的事。</p>
 */
public final class OwnerFriendlyFire {

	private OwnerFriendlyFire() {
	}

	/** 在模组初始化时挂上。挂两次是幂等的（同一个监听器注册两遍只是多跑一次判断）。 */
	public static void register() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!isOwnerHitByOwnSquire(entity, source.getAttacker()));
	}

	/**
	 * 这一下是「侍从打了自己的主人」吗。
	 *
	 * @param victim   正在挨打的实体
	 * @param attacker 伤害来源的<b>发起者</b>。箭矢的 DamageSource 会把射手填在这里，
	 *                 所以近战和远程走的是同一条判断
	 */
	public static boolean isOwnerHitByOwnSquire(Entity victim, Entity attacker) {
		return victim instanceof PlayerEntity player
			&& attacker instanceof AvatarEntity avatar
			&& avatar.isOwner(player);
	}
}
