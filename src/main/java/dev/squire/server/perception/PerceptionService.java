package dev.squire.server.perception;

import java.util.List;

import dev.squire.api.body.AgentPhysicalState;
import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Builds the perception context handed to the provider (spec section 16).
 * M2 implements the NORMAL level as bounded plain text: position/health, inventory,
 * owner relation and nearby hostiles — each sensor with a hard item budget so the
 * prompt can never grow unbounded.
 */
public final class PerceptionService {

	public enum Level { MINIMAL, NORMAL, DETAILED }

	private static final int NEARBY_RADIUS = 16;
	private static final int MAX_HOSTILES_LISTED = 5;
	private static final int MAX_ITEMS_LISTED = 12;

	private PerceptionService() {
	}

	/** 持久化档案里的名字；运行时不可用时退回实体名牌。 */
	private static String nameOf(AvatarEntity avatar) {
		try {
			if (dev.squire.server.runtime.SquireRuntime.isAlive()) {
				return dev.squire.server.runtime.SquireRuntime.get().displayNameOf(avatar);
			}
		} catch (RuntimeException e) {
			// 感知渲染绝不能因为取名字失败而整体炸掉
		}
		return avatar.getCustomName() == null ? null : avatar.getCustomName().getString();
	}

	public static String render(AvatarEntity avatar, Level level) {
		AgentPhysicalState s = avatar.snapshotState();
		StringBuilder sb = new StringBuilder("[context] ");
		// 名字放在最前面：玩家用命名牌给他改了名，他自己却答不上来「你叫什么」，
		// 是最直接的「他没有记性」的观感。
		String name = nameOf(avatar);
		if (name != null) {
			sb.append("yourName=\"").append(name).append("\" ");
		}
		sb.append("dim=").append(s.dimension());
		sb.append(" pos=(").append(Math.round(s.x())).append(',').append(Math.round(s.y()))
			.append(',').append(Math.round(s.z())).append(')');
		sb.append(" hp=").append(String.format("%.0f", s.health()))
			.append('/').append(String.format("%.0f", s.maxHealth()));
		if (level == Level.MINIMAL) {
			return sb.toString();
		}
		sb.append(" mode=").append(avatar.mode().name().toLowerCase(java.util.Locale.ROOT));

		List<String> ids = avatar.inventory().distinctItemIds();
		if (!ids.isEmpty()) {
			sb.append(" inventory=");
			int listed = 0;
			for (String id : ids) {
				if (listed++ >= MAX_ITEMS_LISTED) {
					sb.append("+").append(ids.size() - MAX_ITEMS_LISTED).append(" more");
					break;
				}
				sb.append(id).append(':').append(avatar.inventory().countOf(id)).append(' ');
			}
		} else {
			sb.append(" inventory=empty");
		}

		if (level == Level.DETAILED && avatar.getWorld() instanceof ServerWorld world) {
			int hostiles = 0;
			StringBuilder hostileList = new StringBuilder();
			for (var entity : world.iterateEntities()) {
				if (!(entity instanceof HostileEntity hostile) || !hostile.isAlive()
						|| !hostile.isInRange(avatar, NEARBY_RADIUS)) {
					continue;
				}
				hostiles++;
				if (hostiles <= MAX_HOSTILES_LISTED) {
					hostileList.append(hostile.getType().getName().getString()).append(' ');
				}
			}
			if (hostiles > 0) {
				sb.append(" hostiles=").append(hostiles).append('(')
					.append(hostileList.toString().trim())
					.append(hostiles > MAX_HOSTILES_LISTED ? ",…" : "").append(')');
			}
		}
		return sb.toString().trim();
	}

	/**
	 * 周围有什么活物，按种类归并。
	 *
	 * <p>这段扫描一直躺在 {@link Level#DETAILED} 分支里，而对话用的是
	 * {@link Level#NORMAL}——所以在聊天这条路上它从来没有执行过，「附近有怪吗」
	 * 只能靠模型猜。现在它有了自己的工具入口。</p>
	 */
	public static String scanNearby(AvatarEntity avatar, int radius,
			boolean hostileOnly) {
		if (avatar == null || !(avatar.getWorld() instanceof ServerWorld world)) {
			return "no body in world";
		}
		java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
		for (var entity : world.getEntitiesByClass(
				net.minecraft.entity.LivingEntity.class,
				avatar.getBoundingBox().expand(radius),
				e -> e.isAlive() && e != avatar)) {
			if (hostileOnly && !(entity instanceof HostileEntity)) {
				continue;
			}
			String name = entity.getType().getName().getString();
			int distance = (int) Math.round(Math.sqrt(entity.squaredDistanceTo(avatar)));
			int[] slot = counts.computeIfAbsent(name, k -> new int[] {0, Integer.MAX_VALUE});
			slot[0]++;
			slot[1] = Math.min(slot[1], distance);
		}
		if (counts.isEmpty()) {
			return hostileOnly ? "no hostiles within " + radius + " blocks"
				: "nothing alive within " + radius + " blocks";
		}
		StringBuilder sb = new StringBuilder();
		int listed = 0;
		for (var entry : counts.entrySet()) {
			if (listed++ >= MAX_HOSTILES_LISTED * 2) {
				sb.append("+").append(counts.size() - listed + 1).append(" more kinds");
				break;
			}
			sb.append(entry.getKey()).append(" x").append(entry.getValue()[0])
				.append(" (nearest ").append(entry.getValue()[1]).append("m); ");
		}
		return sb.toString().trim();
	}

	/**
	 * 世界与<b>玩家</b>的状态。
	 *
	 * <p>以前的感知块里关于玩家一个字都没有——他知道自己几点血、背包里有什么，却
	 * 完全看不见站在面前的人。于是「我血量多少」「我手上这是什么」这类最像伙伴的
	 * 问题，他一句都答不上来，只能瞎猜。时间和天气同理：不给他，他就会编。</p>
	 */
	public static String renderOwner(net.minecraft.server.network.ServerPlayerEntity owner) {
		if (owner == null) {
			return "[owner] not online";
		}
		StringBuilder sb = new StringBuilder("[owner] name=\"")
			.append(owner.getGameProfile().getName()).append('"');
		sb.append(" pos=(").append(owner.getBlockX()).append(',').append(owner.getBlockY())
			.append(',').append(owner.getBlockZ()).append(')');
		sb.append(" hp=").append(String.format("%.0f", owner.getHealth()))
			.append('/').append(String.format("%.0f", owner.getMaxHealth()));
		sb.append(" hunger=").append(owner.getHungerManager().getFoodLevel()).append("/20");
		var held = owner.getMainHandStack();
		sb.append(" holding=").append(held.isEmpty() ? "nothing"
			: net.minecraft.registry.Registries.ITEM.getId(held.getItem())
				+ "x" + held.getCount());
		if (owner.getWorld() instanceof ServerWorld world) {
			long timeOfDay = world.getTimeOfDay() % 24000L;
			sb.append(" timeOfDay=").append(timeOfDay)
				.append(timeOfDay < 12000 ? "(day)" : "(night)");
			sb.append(" weather=").append(world.isThundering() ? "thunder"
				: world.isRaining() ? "rain" : "clear");
			var biome = world.getBiome(owner.getBlockPos());
			biome.getKey().ifPresent(key ->
				sb.append(" biome=").append(key.getValue()));
		}
		return sb.toString();
	}
}
