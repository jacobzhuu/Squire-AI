package dev.squire.server.gui;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.compat.BackpackView;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/**
 * 把「他背着的那个背包里的一页」当成一个 36 格容器暴露给面板。
 *
 * <p>为什么要分页：背包从 27 格到 120 格不等，而 {@code Slot} 的坐标是 final 的，
 * 没法在运行时给一个界面换一套格子。所以面板固定复用伙伴背包那块 9×4 的网格，
 * 由这个适配器把 {@code 页号 × 36 + 下标} 映射到背包真实的格子上——翻页只是换一个
 * 偏移量，界面一格都不用动。</p>
 *
 * <p>写入全部走 {@link BackpackView}，也就是那个模组自己的存储：过滤升级、虚空升级、
 * 堆叠上限都由它说了算，我们不绕过去。放不进就整体回滚，那一格保持原样
 * （客户端下一次同步会自己纠正回来）。</p>
 */
public final class AvatarBackpackContentsInventory implements Inventory {

	/** 面板一页显示多少格：正好是伙伴背包那块网格的大小。 */
	public static final int PAGE_SIZE = 36;

	private final AvatarEntity avatar;
	private int page;

	public AvatarBackpackContentsInventory(AvatarEntity avatar) {
		this.avatar = avatar;
	}

	private BackpackView view() {
		return avatar == null ? null : avatar.items().backpack();
	}

	/** 背包一共多少格；没背包就是 0。 */
	public int backpackSize() {
		BackpackView view = view();
		return view == null ? 0 : view.slotCount();
	}

	public int page() {
		return page;
	}

	public int pageCount() {
		int size = backpackSize();
		return size <= 0 ? 1 : (size + PAGE_SIZE - 1) / PAGE_SIZE;
	}

	/** 翻页。越界一律夹回来：面板上的按钮永远点得动，但不会翻到不存在的页。 */
	public void setPage(int next) {
		this.page = Math.max(0, Math.min(next, pageCount() - 1));
	}

	/** 这一页的第 index 格对应背包里的第几格。 */
	private int backpackSlot(int index) {
		return page * PAGE_SIZE + index;
	}

	@Override
	public int size() {
		return PAGE_SIZE;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < PAGE_SIZE; i++) {
			if (!getStack(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		BackpackView view = view();
		if (view == null || slot < 0 || slot >= PAGE_SIZE) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = view.stackAt(backpackSlot(slot));
		// 装了「堆叠升级」的背包一格可以放几百个，而原版的槽位同步和点击逻辑
		// 都是按单叠上限设计的。这里如实显示上限之内的那部分，多的先不暴露，
		// 免得点一下就出现对不上的数字。
		if (stack.getCount() > stack.getMaxCount()) {
			ItemStack shown = stack.copy();
			shown.setCount(stack.getMaxCount());
			return shown;
		}
		return stack;
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		BackpackView view = view();
		if (view == null || slot < 0 || slot >= PAGE_SIZE || amount <= 0) {
			return ItemStack.EMPTY;
		}
		return view.extract(backpackSlot(slot), amount);
	}

	@Override
	public ItemStack removeStack(int slot) {
		ItemStack shown = getStack(slot);
		return removeStack(slot, shown.isEmpty() ? 0 : shown.getMaxCount());
	}

	/** Exact backing count for lossless server-side bulk transfers. */
	public ItemStack exactStack(int slot) {
		BackpackView view = view();
		return view == null || slot < 0 || slot >= PAGE_SIZE
			? ItemStack.EMPTY : view.stackAt(backpackSlot(slot));
	}

	/** Extract only after the destination accepted that exact amount. */
	public ItemStack extractExact(int slot, int amount) {
		return removeStack(slot, amount);
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		BackpackView view = view();
		if (view == null || slot < 0 || slot >= PAGE_SIZE) {
			return;
		}
		ItemStack existing = view.stackAt(backpackSlot(slot));
		if (existing.getCount() > existing.getMaxCount()) {
			// Vanilla screen slots can only represent one ordinary stack. Never let
			// their truncated display copy replace or clear an expanded backing slot.
			return;
		}
		view.setStack(backpackSlot(slot), stack);
	}

	/** 这一格收不收这件东西——过滤/虚空升级说了算。 */
	public boolean accepts(int slot, ItemStack stack) {
		BackpackView view = view();
		return view != null && slot >= 0 && slot < PAGE_SIZE
			&& view.accepts(backpackSlot(slot), stack);
	}

	@Override
	public void markDirty() {
		// 写入是即时的（事务已经提交），没有额外的落盘动作。
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return true; // 真正的把关在 SquireScreenHandler.canUse
	}

	@Override
	public void clear() {
		BackpackView view = view();
		if (view == null) return;
		for (int i = 0; i < PAGE_SIZE && backpackSlot(i) < view.slotCount(); i++)
			view.setStack(backpackSlot(i), ItemStack.EMPTY);
	}
}
