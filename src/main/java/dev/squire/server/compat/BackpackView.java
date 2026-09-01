package dev.squire.server.compat;

import net.minecraft.item.ItemStack;

/**
 * 一个「能装东西的物品」的只读+读写视图：侍从背着的那个背包。
 *
 * <p>抽成接口不是为了将来换实现，而是为了<b>能测</b>：真实实现要靠
 * Sophisticated Backpacks 注册的物品存储，而那个模组不在我们的开发环境里，
 * 任何自动化测试都碰不到它。溢出、取料、清点这些逻辑写在
 * {@link dev.squire.server.body.avatar.AvatarInventory} 里，只依赖这个接口，
 * 于是可以拿一个假背包在 GameTest 里跑完整条路径。</p>
 *
 * <p>所有方法都以 {@link ItemStack} 收发，不暴露 Fabric 的传输 API——
 * 事务的开启与提交是 {@link BackpackCompat} 的事。</p>
 */
public interface BackpackView {

	/** 背包有多少格（27～120 不等，看材质和升级）。 */
	int slotCount();

	/** 第 slot 格的内容；空格返回 {@link ItemStack#EMPTY}。返回的是副本，改它没用。 */
	ItemStack stackAt(int slot);

	/**
	 * 尽量塞进去。
	 *
	 * @return 装不下的余量（全部装下时为 EMPTY）
	 */
	ItemStack insert(ItemStack stack);

	/** 不真的搬，只算这一叠能吃下多少个。 */
	int roomFor(ItemStack stack);

	/**
	 * 从第 slot 格取走最多 amount 个。
	 *
	 * @return 真的取到的那一份（含原 NBT）；一个都没取到时为 EMPTY
	 */
	ItemStack extract(int slot, int amount);

	/**
	 * 把第 slot 格<b>整格换成</b>这一叠（面板里拖放用）。
	 *
	 * <p>一个事务里先掏空再放入：放不进（背包有过滤/虚空升级，或者格子不收这件东西）
	 * 就整体回滚，那一格保持原样。绝不允许出现「旧的没了、新的没进去」。</p>
	 *
	 * @return 换成功了才返回 true
	 */
	boolean setStack(int slot, ItemStack stack);

	/** 第 slot 格收不收这件东西（面板里判断能不能放进去）。 */
	boolean accepts(int slot, ItemStack stack);
}
