package dev.squire.server.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 容器物流的唯一世界侧入口（方案 C3）。
 *
 * <p>职责：解析并验证目标 BlockEntity（类型、距离、区块加载、权限），提供容量预检、
 * 两阶段事务式搬运和稳定排序。所有 Executor 只经过这里改容器，因此保护判定、
 * NBT 保留和回滚语义只有一份实现。</p>
 */
public final class ContainerAccess {

	/** 与玩家一致的容器交互距离（6 格）。 */
	public static final double MAX_INTERACT_DISTANCE_SQ = 36.0;

	private ContainerAccess() {
	}

	/** 解析结果：成功时携带可写 {@link Inventory}；失败时携带结构化错误码。 */
	public record Resolved(Inventory inventory, BlockPos pos, String typeId,
			String errorCode, String detail) {

		public boolean ok() {
			return inventory != null;
		}

		static Resolved fail(String code, String detail) {
			return new Resolved(null, null, null, code, detail);
		}
	}

	/**
	 * 解析一个容器方块。位置必须来自可见目标、玩家明确坐标或可信位置记忆——本方法
	 * 不做来源判断，但每一项世界侧前置条件都在这里 fail-closed。
	 *
	 * @param actorPos    伙伴当前位置（距离校验）
	 * @param actorOwner  用于 Protection 判定的 owner
	 * @param write       true 表示本次操作会修改容器内容
	 */
	public static Resolved resolve(ServerWorld world, BlockPos pos,
			net.minecraft.util.math.Vec3d actorPos, UUID actorOwner,
			ProtectionAdapter protection, boolean write) {
		if (world == null || pos == null) {
			return Resolved.fail("INVALID_ARGUMENT", "no world or position");
		}
		if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return Resolved.fail("WORLD_CHANGED", "target chunk is not loaded");
		}
		if (actorPos != null && actorPos.squaredDistanceTo(pos.getX() + 0.5,
				pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_INTERACT_DISTANCE_SQ) {
			return Resolved.fail("UNREACHABLE", "container out of interaction range");
		}
		BlockEntity entity = world.getBlockEntity(pos);
		if (!(entity instanceof Inventory)) {
			return Resolved.fail("NOT_A_CONTAINER",
				"block at " + pos.toShortString() + " is not a container");
		}
		var decision = protection.canInteract(world, pos, actorOwner);
		if (!decision.allowed()) {
			return Resolved.fail("PROTECTED_REGION",
				(write ? "write to container denied: " : "read of container denied: ")
					+ decision.reason());
		}
		BlockState state = world.getBlockState(pos);
		Inventory inventory = (Inventory) entity;
		// 双箱：必须拿到合并后的视图，否则只写到一半
		if (state.getBlock() instanceof ChestBlock chest) {
			Inventory merged = ChestBlock.getInventory(chest, state, world, pos, true);
			if (merged != null) {
				inventory = merged;
			}
		}
		if (inventory instanceof net.minecraft.inventory.DoubleInventory doubleInventory) {
			for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
				BlockEntity other = world.getBlockEntity(pos.offset(direction));
				if (other instanceof Inventory half && half != entity && doubleInventory.isPart(half)) {
					var otherDecision = protection.canInteract(world, pos.offset(direction), actorOwner);
					if (!otherDecision.allowed()) {
						return Resolved.fail("PROTECTED_REGION", (write ? "write to container denied: "
							: "read of container denied: ") + otherDecision.reason());
					}
				}
			}
		}
		String typeId = Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()) == null
			? entity.getType().toString()
			: Registries.BLOCK_ENTITY_TYPE.getId(entity.getType()).toString();
		return new Resolved(inventory, pos.toImmutable(), typeId, null, null);
	}

	/** True for chest-like containers where any slot accepts any item (sort is safe). */
	public static boolean isSortable(ServerWorld world, BlockPos pos) {
		BlockEntity entity = world.getBlockEntity(pos);
		return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity
			|| entity instanceof ShulkerBoxBlockEntity;
	}

	// ------------------------------------------------------------------ 容量与搬运

	/** 这一叠里目标容器还能收下多少个（不改任何状态）。 */
	public static int insertableAmount(Inventory inventory, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		int max = Math.min(stack.getMaxCount(), inventory.getMaxCountPerStack());
		int room = 0;
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack existing = inventory.getStack(slot);
			if (existing.isEmpty()) {
				if (inventory.isValid(slot, stack)) {
					room += max;
				}
			} else if (existing.isStackable() && ItemStack.canCombine(existing, stack)) {
				room += Math.max(0, Math.min(max, existing.getMaxCount())
					- existing.getCount());
			}
			if (room >= stack.getCount()) {
				return stack.getCount();
			}
		}
		return Math.min(room, stack.getCount());
	}

	/**
	 * 把一叠放进容器（先合并再占空槽），返回放不下的余量。传入的 stack 不被修改。
	 */
	public static ItemStack insert(Inventory inventory, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack remaining = stack.copy();
		int max = Math.min(stack.getMaxCount(), inventory.getMaxCountPerStack());
		for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
			ItemStack existing = inventory.getStack(slot);
			if (!existing.isEmpty() && existing.isStackable()
					&& ItemStack.canCombine(existing, remaining)) {
				int moved = Math.min(remaining.getCount(), max - existing.getCount());
				if (moved > 0) {
					existing.increment(moved);
					remaining.decrement(moved);
					inventory.markDirty();
				}
			}
		}
		for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
			if (inventory.getStack(slot).isEmpty() && inventory.isValid(slot, remaining)) {
				ItemStack placed = remaining.split(Math.min(remaining.getCount(), max));
				inventory.setStack(slot, placed);
				inventory.markDirty();
			}
		}
		return remaining;
	}

	/** 从容器取出最多 count 个匹配物品，保留原 stack 的 NBT。 */
	public static List<ItemStack> extract(Inventory inventory,
			Predicate<ItemStack> filter, int count) {
		List<ItemStack> taken = new ArrayList<>();
		int left = count;
		for (int slot = 0; slot < inventory.size() && left > 0; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isEmpty() && filter.test(stack)) {
				int amount = Math.min(left, stack.getCount());
				taken.add(inventory.removeStack(slot, amount));
				left -= amount;
			}
		}
		if (left < count) {
			inventory.markDirty();
		}
		return taken;
	}

	public static int count(Inventory inventory, Predicate<ItemStack> filter) {
		int total = 0;
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isEmpty() && filter.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** item id -> 总数，用于 inspect 和事务后校验。 */
	public static Map<String, Integer> contents(Inventory inventory) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isEmpty()) {
				counts.merge(Registries.ITEM.getId(stack.getItem()).toString(),
					stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	public static int freeSlots(Inventory inventory) {
		int free = 0;
		for (int slot = 0; slot < inventory.size(); slot++) {
			if (inventory.getStack(slot).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	// ------------------------------------------------------------------ 事务

	/** 容器全量深拷贝，用于两阶段提交失败时回滚。 */
	public static List<ItemStack> snapshot(Inventory inventory) {
		List<ItemStack> copy = new ArrayList<>(inventory.size());
		for (int slot = 0; slot < inventory.size(); slot++) {
			copy.add(inventory.getStack(slot).copy());
		}
		return copy;
	}

	public static void restore(Inventory inventory, List<ItemStack> snapshot) {
		if (snapshot == null) {
			return;
		}
		for (int slot = 0; slot < inventory.size() && slot < snapshot.size(); slot++) {
			inventory.setStack(slot, snapshot.get(slot).copy());
		}
		inventory.markDirty();
	}

	// ------------------------------------------------------------------ 排序

	/**
	 * 稳定分类顺序（方案 C3：可配置、保留 NBT、不删除未知物品）。未匹配任何类别的
	 * 物品落到 MISC，仍然保留在容器里。
	 */
	public enum Category {
		TOOL, WEAPON, ARMOR, FOOD, BLOCK, ORE_AND_INGOT, REDSTONE, MISC
	}

	public static Category categoryOf(ItemStack stack) {
		var item = stack.getItem();
		String id = Registries.ITEM.getId(item).getPath();
		if (item instanceof net.minecraft.item.ArmorItem) {
			return Category.ARMOR;
		}
		if (item instanceof net.minecraft.item.SwordItem
				|| item instanceof net.minecraft.item.RangedWeaponItem
				|| item instanceof net.minecraft.item.TridentItem) {
			return Category.WEAPON;
		}
		if (item instanceof net.minecraft.item.ToolItem
				|| item instanceof net.minecraft.item.ShearsItem
				|| item instanceof net.minecraft.item.FlintAndSteelItem) {
			return Category.TOOL;
		}
		if (item.isFood()) {
			return Category.FOOD;
		}
		if (id.endsWith("_ore") || id.startsWith("raw_") || id.endsWith("_ingot")
				|| id.endsWith("_nugget") || id.equals("diamond") || id.equals("emerald")
				|| id.equals("coal") || id.equals("charcoal")) {
			return Category.ORE_AND_INGOT;
		}
		if (id.contains("redstone") || id.contains("repeater")
				|| id.contains("comparator") || id.contains("piston")
				|| id.contains("observer") || id.contains("hopper")) {
			return Category.REDSTONE;
		}
		if (item instanceof net.minecraft.item.BlockItem) {
			return Category.BLOCK;
		}
		return Category.MISC;
	}

	/** 排序键：类别 → item id → NBT 串。相同键的堆叠会被合并。 */
	private static String sortKey(ItemStack stack) {
		String nbt = stack.getNbt() == null ? "" : stack.getNbt().toString();
		return categoryOf(stack).ordinal() + "|"
			+ Registries.ITEM.getId(stack.getItem()) + "|" + nbt;
	}

	/**
	 * 就地整理一个容器：合并同类同 NBT 的堆叠，按稳定顺序重排。任何一步失败都整体
	 * 回滚，且总量必须守恒——校验不过就还原原状并报错。
	 *
	 * @return null 表示成功，否则是结构化错误码
	 */
	public static String sort(Inventory inventory) {
		List<ItemStack> before = snapshot(inventory);
		Map<String, Integer> expected = contents(inventory);

		List<ItemStack> flattened = new ArrayList<>();
		for (ItemStack stack : before) {
			if (!stack.isEmpty()) {
				flattened.add(stack.copy());
			}
		}
		// 合并（只合并 item + NBT 都相同的，附魔/命名物品因此不会被吞掉）
		List<ItemStack> merged = new ArrayList<>();
		for (ItemStack stack : flattened) {
			boolean absorbed = false;
			for (ItemStack target : merged) {
				if (target.isStackable() && ItemStack.canCombine(target, stack)) {
					int room = Math.min(target.getMaxCount(),
						inventory.getMaxCountPerStack()) - target.getCount();
					int moved = Math.min(room, stack.getCount());
					if (moved > 0) {
						target.increment(moved);
						stack.decrement(moved);
					}
					if (stack.isEmpty()) {
						absorbed = true;
						break;
					}
				}
			}
			if (!absorbed && !stack.isEmpty()) {
				merged.add(stack);
			}
		}
		merged.sort(Comparator.comparing(ContainerAccess::sortKey));

		if (merged.size() > inventory.size()) {
			return "INVENTORY_FULL"; // 理论上不可能：合并只会减少堆叠数
		}
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack value = slot < merged.size() ? merged.get(slot) : ItemStack.EMPTY;
			if (!value.isEmpty() && !inventory.isValid(slot, value)) {
				restore(inventory, before);
				return "PRECONDITION_FAILED";
			}
			inventory.setStack(slot, value);
		}
		inventory.markDirty();
		if (!contents(inventory).equals(expected)) {
			restore(inventory, before);
			return "INTERNAL_ERROR"; // 守恒校验失败：宁可什么都不做
		}
		return null;
	}
}
