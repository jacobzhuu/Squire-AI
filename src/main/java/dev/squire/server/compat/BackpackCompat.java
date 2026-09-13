package dev.squire.server.compat;

import net.fabricmc.fabric.api.lookup.v1.item.ItemApiLookup;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/**
 * 「侍从能背背包」的对接层：把一个<b>物品</b>当成容器打开。
 *
 * <p>目标模组是「精妙背包」（Sophisticated Backpacks）。它把背包内容注册在
 * porting_lib 的物品存储查找表 {@code ItemItemStorages.ITEM} 上，而那张表的三个泛型
 * 参数（{@link Storage}、{@link ItemVariant}、{@link ContainerItemContext}）<b>全是
 * Fabric API 自带的类型</b>——所以这里只需要反射读一个静态字段，其余全部是有类型的
 * 正常调用。没有编译期依赖，没装那个模组时 {@link #available()} 直接是 false，
 * 整条路自己关掉。</p>
 *
 * <p>顺带的好处：任何往同一张表上注册过的物品（别的背包/口袋类模组）都能用，
 * 我们并没有把「背包」写死成某一个物品 id。</p>
 *
 * <p><b>为什么必须用「槽位上下文」而不是常量上下文：</b>背包内容最终要落回那一个
 * {@link ItemStack} 的 NBT。用 {@code withConstant} 打开的写入不会回存，
 * 玩家看到的就是「往他背包里放的东西下线就没了」。所以写路径一律用
 * {@link ContainerItemContext#ofSingleSlot}，绑在侍从身上那个真实的一格容器上。</p>
 */
public final class BackpackCompat {

	private BackpackCompat() {
	}

	/** porting_lib 的物品存储查找表；测试可以换成假的。 */
	@FunctionalInterface
	public interface Lookup {
		Storage<ItemVariant> find(ItemStack stack, ContainerItemContext context);
	}

	private static final String LOOKUP_CLASS =
		"io.github.fabricators_of_create.porting_lib.transfer.item.ItemItemStorages";
	private static final String SOPHISTICATED_LOOKUP_INITIALIZER =
		"net.p3pp3rf1y.sophisticatedbackpacks.common.BackpackWrapperLookup";

	/** 解析结果的缓存。null = 还没解析或解析失败（每次调用会再试一次，代价只是一次反射）。 */
	private static Lookup lookup;
	/** 测试装上的假查找表：一旦装上就完全接管，不再碰反射。 */
	private static Lookup override;

	/** 只给测试用：装一个假的背包查找表。传 null 恢复真实实现。 */
	public static void useLookupForTesting(Lookup fake) {
		override = fake;
	}

	/** 这台服务器上到底有没有能当容器用的物品。 */
	public static boolean available() {
		return resolve() != null;
	}

	private static Lookup resolve() {
		if (override != null) {
			return override;
		}
		if (lookup != null) {
			return lookup;
		}
		try {
			// Sophisticated Backpacks 3.x registers its item storage provider when this
			// lookup class initializes. Squire can be the first mod to query the table.
			try {
				Class.forName(SOPHISTICATED_LOOKUP_INITIALIZER, true,
					BackpackCompat.class.getClassLoader());
			} catch (ClassNotFoundException optionalModAbsent) {
				// Keep the Porting Lib lookup available for other item-storage providers.
			}
			Object table = Class.forName(LOOKUP_CLASS).getField("ITEM").get(null);
			if (table instanceof ItemApiLookup<?, ?> raw) {
				@SuppressWarnings("unchecked")
				ItemApiLookup<Storage<ItemVariant>, ContainerItemContext> typed =
					(ItemApiLookup<Storage<ItemVariant>, ContainerItemContext>) raw;
				lookup = typed::find;
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
			// 没装背包模组是最常见的情况，不值得刷一行日志。
			lookup = null;
		}
		return lookup;
	}

	/**
	 * 这件东西能不能当背包背上？
	 *
	 * <p>判据是「它自己声明了按格子分的物品存储」，而不是某个写死的 id——
	 * 只读探测，用常量上下文即可。</p>
	 */
	public static boolean isBackpack(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		Lookup table = resolve();
		if (table == null) {
			return false;
		}
		try {
			return table.find(stack, ContainerItemContext.withConstant(stack))
				instanceof SlottedStorage;
		} catch (RuntimeException | LinkageError broken) {
			return false;
		}
	}

	/**
	 * 打开侍从背着的那个背包。
	 *
	 * @param slotInventory 侍从身上那个<b>一格</b>的背包槽容器（写入要回存到它里面的 stack）
	 * @return 打不开（没背包 / 没装模组 / 那件东西不是容器）时返回 null
	 */
	public static BackpackView open(Inventory slotInventory) {
		if (slotInventory == null || slotInventory.size() < 1) {
			return null;
		}
		ItemStack stack = slotInventory.getStack(0);
		if (stack.isEmpty()) {
			return null;
		}
		Lookup table = resolve();
		if (table == null) {
			return null;
		}
		try {
			SingleSlotStorage<ItemVariant> slot =
				InventoryStorage.of(slotInventory, null).getSlot(0);
			Storage<ItemVariant> storage =
				table.find(stack, ContainerItemContext.ofSingleSlot(slot));
			return storage instanceof SlottedStorage<ItemVariant> slotted
				? new SlottedView(slotted) : null;
		} catch (RuntimeException | LinkageError broken) {
			return null;
		}
	}

	/** 把 Fabric 的事务式存储包成我们这边用 ItemStack 说话的视图。 */
	private record SlottedView(SlottedStorage<ItemVariant> storage) implements BackpackView {

		@Override
		public int slotCount() {
			return storage.getSlotCount();
		}

		@Override
		public ItemStack stackAt(int slot) {
			if (slot < 0 || slot >= storage.getSlotCount()) {
				return ItemStack.EMPTY;
			}
			SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
			ItemVariant variant = view.getResource();
			long amount = view.getAmount();
			if (variant.isBlank() || amount <= 0) {
				return ItemStack.EMPTY;
			}
			// 一格里可能因为「堆叠升级」放着好几百个，这里只描述它，不搬运，
			// 所以夹到 int 就够；真正搬运走 extract，数量由调用方决定。
			return variant.toStack((int) Math.min(amount, Integer.MAX_VALUE));
		}

		@Override
		public ItemStack insert(ItemStack stack) {
			if (stack == null || stack.isEmpty()) {
				return ItemStack.EMPTY;
			}
			long moved;
			try (Transaction transaction = Transaction.openOuter()) {
				moved = storage.insert(ItemVariant.of(stack), stack.getCount(), transaction);
				transaction.commit();
			}
			if (moved >= stack.getCount()) {
				return ItemStack.EMPTY;
			}
			ItemStack left = stack.copy();
			left.setCount(stack.getCount() - (int) moved);
			return left;
		}

		@Override
		public int roomFor(ItemStack stack) {
			if (stack == null || stack.isEmpty()) {
				return 0;
			}
			// 开一个事务、算完<b>不提交</b>：关闭时自动回滚，这就是 Fabric 的模拟写法。
			try (Transaction probe = Transaction.openOuter()) {
				long room = storage.insert(ItemVariant.of(stack), stack.getCount(), probe);
				return (int) Math.min(room, Integer.MAX_VALUE);
			}
		}

		@Override
		public boolean setStack(int slot, ItemStack stack) {
			if (slot < 0 || slot >= storage.getSlotCount()) {
				return false;
			}
			SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
			try (Transaction transaction = Transaction.openOuter()) {
				ItemVariant current = view.getResource();
				long amount = view.getAmount();
				if (!current.isBlank() && amount > 0
						&& view.extract(current, amount, transaction) != amount) {
					return false; // 掏不干净就当没发生过（关闭时自动回滚）
				}
				if (stack != null && !stack.isEmpty()) {
					long inserted = view.insert(ItemVariant.of(stack),
						stack.getCount(), transaction);
					if (inserted != stack.getCount()) {
						return false; // 放不下整叠：宁可不换，也不留半截
					}
				}
				transaction.commit();
				return true;
			}
		}

		@Override
		public boolean accepts(int slot, ItemStack stack) {
			if (slot < 0 || slot >= storage.getSlotCount()
					|| stack == null || stack.isEmpty()) {
				return false;
			}
			try (Transaction probe = Transaction.openOuter()) {
				return storage.getSlot(slot).insert(ItemVariant.of(stack), 1, probe) > 0;
			}
		}

		@Override
		public ItemStack extract(int slot, int amount) {
			if (slot < 0 || slot >= storage.getSlotCount() || amount <= 0) {
				return ItemStack.EMPTY;
			}
			SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
			ItemVariant variant = view.getResource();
			if (variant.isBlank()) {
				return ItemStack.EMPTY;
			}
			long moved;
			try (Transaction transaction = Transaction.openOuter()) {
				moved = view.extract(variant, amount, transaction);
				transaction.commit();
			}
			return moved <= 0 ? ItemStack.EMPTY : variant.toStack((int) moved);
		}
	}
}
