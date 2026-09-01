package dev.squire.server.gui;

import java.util.List;

import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/**
 * 把伙伴的 6 个原版装备槽包装成一个 {@link Inventory}，供右键面板的槽位使用。
 *
 * <p>为什么必须是"委托到实体"而不是"复制一份"：面板里拖进去的东西必须<b>就是</b>
 * 他身上穿的那件，否则会出现界面里有、身上没有。同时，ScreenHandler 的同步机制
 * 是靠逐槽读 {@code getStack()} 再发包，所以这里读到的必须是实时的实体装备。</p>
 *
 * <p>客户端没有实体引用，用 {@link net.minecraft.inventory.SimpleInventory} 顶上即可
 * ——同步包会把内容写进去。</p>
 */
public final class AvatarEquipmentInventory implements Inventory {

	private final AvatarEntity avatar;
	private final List<EquipmentSlot> order;

	public AvatarEquipmentInventory(AvatarEntity avatar, List<EquipmentSlot> order) {
		this.avatar = avatar;
		this.order = order;
	}

	@Override
	public int size() {
		return order.size();
	}

	@Override
	public boolean isEmpty() {
		for (EquipmentSlot slot : order) {
			if (!avatar.getEquippedStack(slot).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getStack(int index) {
		return valid(index) ? avatar.getEquippedStack(order.get(index)) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeStack(int index, int amount) {
		if (!valid(index)) {
			return ItemStack.EMPTY;
		}
		ItemStack current = avatar.getEquippedStack(order.get(index));
		if (current.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack taken = current.split(amount);
		// split 之后 current 可能空了；无论如何都要把剩余量写回装备槽。
		avatar.equipStack(order.get(index), current.isEmpty() ? ItemStack.EMPTY : current);
		return taken;
	}

	@Override
	public ItemStack removeStack(int index) {
		if (!valid(index)) {
			return ItemStack.EMPTY;
		}
		ItemStack current = avatar.getEquippedStack(order.get(index));
		avatar.equipStack(order.get(index), ItemStack.EMPTY);
		noteManualWeaponChange(index, ItemStack.EMPTY);
		return current;
	}

	@Override
	public void setStack(int index, ItemStack stack) {
		if (valid(index)) {
			ItemStack placed = stack == null ? ItemStack.EMPTY : stack;
			avatar.equipStack(order.get(index), placed);
			noteManualWeaponChange(index, placed);
		}
	}

	/**
	 * 从面板动过主手 = 玩家亲手选了武器，战斗层不许再自动换掉它。
	 *
	 * <p>把手掏空则相反：那是把选择权交还给他自己（AUTO 会按对面是什么怪来挑）。</p>
	 */
	private void noteManualWeaponChange(int index, ItemStack placed) {
		if (order.get(index) != EquipmentSlot.MAINHAND) {
			return;
		}
		if (placed.isEmpty()) {
			avatar.releaseWeaponChoice();
		} else {
			avatar.markWeaponChosenByPlayer();
		}
	}

	@Override
	public void markDirty() {
		// 装备变化会被 AgentRecord 快照，这里不需要额外落盘。
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return avatar.isAlive() && avatar.squaredDistanceTo(player) < 64.0;
	}

	@Override
	public void clear() {
		for (EquipmentSlot slot : order) {
			avatar.equipStack(slot, ItemStack.EMPTY);
		}
	}

	private boolean valid(int index) {
		return index >= 0 && index < order.size();
	}
}
