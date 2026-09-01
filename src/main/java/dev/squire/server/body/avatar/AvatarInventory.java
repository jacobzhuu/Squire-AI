package dev.squire.server.body.avatar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.block.BlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 权威背包模型（方案 C1）：36 格主背包 + main/off hand + 四个护甲槽的唯一写入口。
 *
 * <p>所有 Executor 只能通过这里改物品；{@code InventoryView} 仍然只读。每个操作都
 * 搬运真实 {@link ItemStack} 对象，从不按 item id 重建——NBT、附魔、耐久和自定义
 * 数据因此天然保留（这正是旧 {@code new ItemStack(item)} 路径丢失的东西）。</p>
 *
 * <p>装备槽由 {@code MobEntity} 的 ArmorItems/HandItems NBT 持久化，主背包由
 * {@link AvatarEntity} 自己写入；两者一起被快照进世界级 AgentRecord。</p>
 */
public final class AvatarInventory {

	/** 主背包 36 格（真实玩家规格）。 */
	public static final int MAIN_SIZE = AvatarEntity.MAIN_INVENTORY_SIZE;

	/** 装备槽的固定顺序，用于快照/回滚和遍历。 */
	public static final List<EquipmentSlot> EQUIPMENT_SLOTS = List.of(
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	/**
	 * 一个具体槽位的引用。{@code equipment != null} 表示装备槽，否则是主背包下标。
	 * 破坏方块后的耐久/附魔消耗必须写回同一个引用（方案 C2）。
	 */
	public record SlotRef(EquipmentSlot equipment, int mainIndex) {

		public static SlotRef main(int index) {
			return new SlotRef(null, index);
		}

		public static SlotRef of(EquipmentSlot slot) {
			return new SlotRef(slot, -1);
		}

		public boolean isEquipment() {
			return equipment != null;
		}

		@Override
		public String toString() {
			return isEquipment() ? "equip:" + equipment.getName() : "main:" + mainIndex;
		}
	}

	/**
	 * 事务快照：整背包 + 全部装备的深拷贝，用于两阶段提交失败时回滚。
	 *
	 * <p><b>背包槽里那件物品会被快照，但背包<em>内容</em>不会。</b>背包内容归那个模组
	 * 自己管（可能存在世界级存储里，只用一个 uuid 指过去），复制物品并不会复制内容。
	 * 所以回滚能把「背包被摘掉」这件事撤销，撤销不了「往背包里塞了三块石头」。
	 * 需要真正可回滚的搬运时，别把背包卷进同一个事务。</p>
	 */
	public record Snapshot(List<ItemStack> main, List<ItemStack> equipment,
			ItemStack backpack) {
	}

	private final AvatarEntity avatar;
	private final SimpleInventory main;
	/** 背包槽（一格）。内容不在这里，在那件物品自己身上。 */
	private final SimpleInventory backpackSlot;

	/**
	 * 主背包本体，供右键面板的槽位直接读写。
	 *
	 * <p>返回真实容器而不是副本：面板里搬动的必须就是伙伴身上的那份东西，
	 * 否则会出现"界面里放进去了，身上却没有"。</p>
	 */
	public SimpleInventory mainInventory() {
		return main;
	}

	AvatarInventory(AvatarEntity avatar, SimpleInventory main, SimpleInventory backpackSlot) {
		this.avatar = avatar;
		this.main = main;
		this.backpackSlot = backpackSlot;
	}

	// ------------------------------------------------------------------ 背包（外挂容量）

	/** 缓存的背包视图，键是<b>那一件物品对象本身</b>。 */
	private ItemStack cachedBackpackStack = ItemStack.EMPTY;
	private dev.squire.server.compat.BackpackView cachedBackpack;

	/**
	 * 他背上那个背包的视图；没背、或者这台服务器上没有背包模组时返回 null。
	 *
	 * <p>缓存的键是<b>物品对象的同一性</b>而不是内容：背包被拿走、换成另一个、
	 * 或者被别处改成另一件东西时，槽位里的 {@link ItemStack} 对象会被整个换掉，
	 * 缓存随之作废；而内容变化不需要重取——视图是穿透到真实存储上的，不是快照。</p>
	 *
	 * <p>非要缓存是因为面板：开着背囊页时每 tick 都要读 36 格，每读一格都重新
	 * 打开一次背包的话，那是每秒两千多次 NBT 解析。</p>
	 */
	public dev.squire.server.compat.BackpackView backpack() {
		ItemStack current = backpackSlot.getStack(0);
		if (current.isEmpty()) {
			cachedBackpackStack = ItemStack.EMPTY;
			cachedBackpack = null;
			return null;
		}
		if (cachedBackpack != null && cachedBackpackStack == current) {
			return cachedBackpack;
		}
		cachedBackpack = dev.squire.server.compat.BackpackCompat.open(backpackSlot);
		cachedBackpackStack = current;
		return cachedBackpack;
	}

	public ItemStack backpackStack() {
		return backpackSlot.getStack(0);
	}

	/** 背包一共多少格；没背就是 0。 */
	public int backpackSize() {
		var view = backpack();
		return view == null ? 0 : view.slotCount();
	}

	// ------------------------------------------------------------------ 主背包读写

	public int size() {
		return main.size();
	}

	/** 直接引用（不复制），调用方若要改必须走本类的写方法。 */
	public ItemStack getStack(int slot) {
		return slot < 0 || slot >= main.size() ? ItemStack.EMPTY : main.getStack(slot);
	}

	public void setStack(int slot, ItemStack stack) {
		if (slot < 0 || slot >= main.size()) {
			return;
		}
		main.setStack(slot, stack == null ? ItemStack.EMPTY : stack);
		main.markDirty();
	}

	public ItemStack stackAt(SlotRef ref) {
		if (ref == null) {
			return ItemStack.EMPTY;
		}
		return ref.isEquipment() ? avatar.getEquippedStack(ref.equipment())
			: getStack(ref.mainIndex());
	}

	public void setAt(SlotRef ref, ItemStack stack) {
		if (ref == null) {
			return;
		}
		ItemStack value = stack == null ? ItemStack.EMPTY : stack;
		if (ref.isEquipment()) {
			avatar.equipStack(ref.equipment(), value);
		} else {
			setStack(ref.mainIndex(), value);
		}
	}

	// ------------------------------------------------------------------ 查询

	public int countOf(Identifier id) {
		var item = Registries.ITEM.get(id);
		return countMatching(stack -> stack.getItem() == item);
	}

	/**
	 * 清点：主背包 <b>加上</b>他背着的背包。
	 *
	 * <p>「他身上有多少铁锭」这个问题的答案必须包含背包，否则会出现
	 * 「明明背包里有一组铁，他却说不够」——玩家看得见那些东西。</p>
	 */
	public int countMatching(Predicate<ItemStack> filter) {
		int total = 0;
		for (int i = 0; i < main.size(); i++) {
			ItemStack stack = main.getStack(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				total += stack.getCount();
			}
		}
		var view = backpack();
		if (view != null) {
			for (int i = 0; i < view.slotCount(); i++) {
				ItemStack stack = view.stackAt(i);
				if (!stack.isEmpty() && filter.test(stack)) {
					total += stack.getCount();
				}
			}
		}
		return total;
	}

	public boolean hasAtLeast(Identifier id, int count) {
		return countOf(id) >= count;
	}

	public List<String> distinctItemIds() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < main.size(); i++) {
			addDistinctId(ids, main.getStack(i));
		}
		var view = backpack();
		if (view != null) {
			for (int i = 0; i < view.slotCount(); i++) {
				addDistinctId(ids, view.stackAt(i));
			}
		}
		return ids;
	}

	private static void addDistinctId(List<String> ids, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		if (!ids.contains(id)) {
			ids.add(id);
		}
	}

	/** <b>主背包</b>的占用格数（面板上那 36 格）；背包另算，见 {@link #backpackSize()}。 */
	public int occupiedSlots() {
		int n = 0;
		for (int i = 0; i < main.size(); i++) {
			if (!main.getStack(i).isEmpty()) {
				n++;
			}
		}
		return n;
	}

	public int freeSlots() {
		return main.size() - occupiedSlots();
	}

	/**
	 * 找到持有该物品的第一个<b>主背包</b>槽（用于装备/整理），没有则 -1。
	 *
	 * <p>主背包里没有、但背包里有时，会先把那一叠从背包<b>取回主背包</b>再返回下标。
	 * 不这么做的话会出现「背包里明明背着一把钻石剑，他却说没有」——
	 * 装备/使用这条路只认主背包的槽位下标。</p>
	 */
	public int findSlotOf(Identifier id) {
		var item = Registries.ITEM.get(id);
		for (int i = 0; i < main.size(); i++) {
			ItemStack stack = main.getStack(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				return i;
			}
		}
		return pullFromBackpack(stack -> stack.getItem() == item);
	}

	/**
	 * 从背包里取出第一件符合条件的东西，放进主背包的空格，返回那个格子的下标。
	 *
	 * @return 背包里没有、或主背包没有空格时返回 -1
	 */
	public int pullFromBackpack(Predicate<ItemStack> filter) {
		var view = backpack();
		if (view == null) {
			return -1;
		}
		int free = firstEmptyMainSlot();
		if (free < 0) {
			return -1;
		}
		for (int i = 0; i < view.slotCount(); i++) {
			ItemStack stack = view.stackAt(i);
			if (stack.isEmpty() || !filter.test(stack)) {
				continue;
			}
			ItemStack taken = view.extract(i, Math.min(stack.getCount(), stack.getMaxCount()));
			if (taken.isEmpty()) {
				continue;
			}
			main.setStack(free, taken);
			main.markDirty();
			return free;
		}
		return -1;
	}

	private int firstEmptyMainSlot() {
		for (int i = 0; i < main.size(); i++) {
			if (main.getStack(i).isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ 容量预检

	/**
	 * 容量预检（方案 C1）：在真正搬运前算出这一叠里能装下多少个。
	 * 两阶段容器事务先用它计算可移动量，再统一提交。
	 */
	public int insertableAmount(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		int room = 0;
		int max = stack.getMaxCount();
		for (int i = 0; i < main.size(); i++) {
			ItemStack existing = main.getStack(i);
			if (existing.isEmpty()) {
				room += max;
			} else if (existing.isStackable() && ItemStack.canCombine(existing, stack)) {
				room += Math.max(0, max - existing.getCount());
			}
			if (room >= stack.getCount()) {
				return stack.getCount();
			}
		}
		// 主背包装不下的部分再问背包。背包可能带过滤/虚空升级，所以只能问它自己。
		var view = backpack();
		if (view != null) {
			room += view.roomFor(stack);
		}
		return Math.min(room, stack.getCount());
	}

	// ------------------------------------------------------------------ 插入 / 提取

	/**
	 * 插入一叠物品（先合并同类，再占空槽，<b>主背包满了才进背包</b>）。合并用
	 * {@code canCombine}，即 item + NBT 同时相等才叠——否则附魔书/命名工具会被错误
	 * 合并成普通物品。
	 *
	 * <p>顺序是刻意的：工具、武器、正在用的材料留在主背包（那 36 格是他真正「手边」
	 * 的东西，选工具、选武器只看这里），背包是<b>溢出仓</b>。</p>
	 *
	 * @return 装不下的余量（全部装下时为 EMPTY）
	 */
	public ItemStack insert(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack remaining = stack.copy();
		for (int i = 0; i < main.size() && !remaining.isEmpty(); i++) {
			ItemStack existing = main.getStack(i);
			if (!existing.isEmpty() && existing.isStackable()
					&& ItemStack.canCombine(existing, remaining)) {
				int merged = Math.min(remaining.getCount(),
					existing.getMaxCount() - existing.getCount());
				if (merged > 0) {
					existing.increment(merged);
					remaining.decrement(merged);
					main.markDirty();
				}
			}
		}
		for (int i = 0; i < main.size() && !remaining.isEmpty(); i++) {
			if (main.getStack(i).isEmpty()) {
				int moved = Math.min(remaining.getCount(), remaining.getMaxCount());
				main.setStack(i, remaining.split(moved));
				main.markDirty();
			}
		}
		if (!remaining.isEmpty()) {
			var view = backpack();
			if (view != null) {
				remaining = view.insert(remaining);
			}
		}
		return remaining;
	}

	/**
	 * 按 item id 取出最多 {@code count} 个，返回真实被取走的 stack（保留 NBT/耐久）。
	 */
	public List<ItemStack> extract(Identifier id, int count) {
		var item = Registries.ITEM.get(id);
		return extractMatching(stack -> stack.getItem() == item, count);
	}

	/**
	 * 按谓词取出最多 {@code count} 个，保留原 stack 的 NBT。
	 *
	 * <p>先掏主背包，不够再掏背包——和 {@link #insert} 反过来，
	 * 于是「拿出来用」总是先动手边的，背包保持成一个仓库。</p>
	 */
	public List<ItemStack> extractMatching(Predicate<ItemStack> filter, int count) {
		List<ItemStack> out = new ArrayList<>();
		int left = count;
		for (int i = 0; i < main.size() && left > 0; i++) {
			ItemStack stack = main.getStack(i);
			if (!stack.isEmpty() && filter.test(stack)) {
				int taken = Math.min(left, stack.getCount());
				out.add(stack.split(taken));
				left -= taken;
				if (stack.isEmpty()) {
					main.setStack(i, ItemStack.EMPTY);
				}
			}
		}
		if (left < count) {
			main.markDirty();
		}
		if (left > 0) {
			var view = backpack();
			if (view != null) {
				for (int i = 0; i < view.slotCount() && left > 0; i++) {
					ItemStack stack = view.stackAt(i);
					if (stack.isEmpty() || !filter.test(stack)) {
						continue;
					}
					ItemStack taken = view.extract(i, Math.min(left, stack.getCount()));
					if (!taken.isEmpty()) {
						out.add(taken);
						left -= taken.getCount();
					}
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ 整理

	/**
	 * 整理用的全序：物品 id → 数量多的在前 → NBT 文本兜底。
	 *
	 * <p>三层加起来是<b>全序</b>，所以同一份背包整理两次结果必然一样；
	 * 少了任何一层，两个只差 NBT 的堆叠谁前谁后就成了随机数。</p>
	 */
	private static final java.util.Comparator<ItemStack> SORT_ORDER =
		java.util.Comparator
			.comparing((ItemStack stack) ->
				Registries.ITEM.getId(stack.getItem()).toString())
			.thenComparing(java.util.Comparator.comparingInt(ItemStack::getCount).reversed())
			.thenComparing((ItemStack stack) ->
				stack.getNbt() == null ? "" : stack.getNbt().toString());

	/**
	 * 合并同类、按物品 id 归拢到背包前段，返回空出来的格子数。
	 *
	 * <p>只动 36 格主背包，<b>装备槽一根手指都不碰</b>。这正是不能把这件事交给
	 * 「一键背包整理 Next」的原因：它把伙伴这一侧看成一个大箱子，装备槽也在里面，
	 * 排序会试着往头盔槽里塞随便什么东西，被 {@code canInsert} 拒收后点击序列断在半路
	 * （见 {@code dev.squire.client.gui.IpnCompat}）。</p>
	 *
	 * <p>合并只认 {@code canCombine}，附魔书、命名工具、耐久不同的工具都不会被并成一叠。</p>
	 *
	 * <p><b>槽位下标会变。</b>所以只能在没有跨 tick 持有 {@link SlotRef} 的时候调用；
	 * 现有的采集/挖掘执行器都是同一 tick 内取槽位、写回耐久，因此安全。</p>
	 */
	public int sort() {
		List<ItemStack> merged = new ArrayList<>();
		int occupiedBefore = 0;
		for (int i = 0; i < main.size(); i++) {
			ItemStack stack = main.getStack(i);
			if (stack.isEmpty()) {
				continue;
			}
			occupiedBefore++;
			for (ItemStack target : merged) {
				if (stack.isEmpty()) {
					break;
				}
				if (!target.isStackable() || !ItemStack.canCombine(target, stack)) {
					continue;
				}
				int room = target.getMaxCount() - target.getCount();
				if (room > 0) {
					int moved = Math.min(room, stack.getCount());
					target.increment(moved);
					stack.decrement(moved);
				}
			}
			if (!stack.isEmpty()) {
				merged.add(stack);
			}
		}
		merged.sort(SORT_ORDER);
		for (int i = 0; i < main.size(); i++) {
			main.setStack(i, i < merged.size() ? merged.get(i) : ItemStack.EMPTY);
		}
		main.markDirty();
		return occupiedBefore - merged.size();
	}

	// ------------------------------------------------------------------ 装备

	public ItemStack equipped(EquipmentSlot slot) {
		return avatar.getEquippedStack(slot);
	}

	public void setEquipped(EquipmentSlot slot, ItemStack stack) {
		avatar.equipStack(slot, stack == null ? ItemStack.EMPTY : stack);
	}

	/** 该物品天然属于哪个槽（护甲进对应护甲槽，其它进主手）。 */
	public static EquipmentSlot naturalSlotFor(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return EquipmentSlot.MAINHAND;
		}
		if (stack.getItem() instanceof ArmorItem armor) {
			return armor.getSlotType();
		}
		return LivingEntity.getPreferredEquipmentSlot(stack);
	}

	public record EquipResult(boolean success, String errorCode, EquipmentSlot slot) {
		static EquipResult ok(EquipmentSlot slot) {
			return new EquipResult(true, null, slot);
		}

		static EquipResult fail(String code) {
			return new EquipResult(false, code, null);
		}
	}

	/**
	 * 把主背包某一槽的物品装备上；原装备回到背包。整个过程只搬运，不复制
	 * （方案 C2：Guard 自动换装不得复制物品）。
	 */
	public EquipResult equipFromMain(int mainSlot, EquipmentSlot target) {
		ItemStack candidate = getStack(mainSlot);
		if (candidate.isEmpty()) {
			return EquipResult.fail("INSUFFICIENT_ITEM");
		}
		EquipmentSlot slot = target == null ? naturalSlotFor(candidate) : target;
		ItemStack previous = equipped(slot);

		// 顺序很重要：先腾空来源槽，再判断旧装备放不放得下。反过来做的话，
		// insert() 部分放入后再回滚会把已经进背包的那部分又变出一份来。
		ItemStack moved = candidate.split(candidate.getCount());
		setStack(mainSlot, ItemStack.EMPTY);
		if (!previous.isEmpty() && insertableAmount(previous) < previous.getCount()) {
			setStack(mainSlot, moved); // 只需撤销这一步：装备槽还没动过
			return EquipResult.fail("INVENTORY_FULL");
		}
		setEquipped(slot, moved);
		if (!previous.isEmpty()) {
			insert(previous); // 已预检放得下
		}
		return EquipResult.ok(slot);
	}

	/** 卸下装备回到主背包；背包满时保持已装备状态并返回 INVENTORY_FULL。 */
	public EquipResult unequip(EquipmentSlot slot) {
		ItemStack current = equipped(slot);
		if (current.isEmpty()) {
			return EquipResult.fail("INSUFFICIENT_ITEM");
		}
		if (insertableAmount(current) < current.getCount()) {
			return EquipResult.fail("INVENTORY_FULL");
		}
		setEquipped(slot, ItemStack.EMPTY);
		ItemStack leftover = insert(current);
		if (!leftover.isEmpty()) { // 理论上不会发生（已预检）
			setEquipped(slot, leftover);
			return EquipResult.fail("INVENTORY_FULL");
		}
		return EquipResult.ok(slot);
	}

	// ------------------------------------------------------------------ 工具/武器选择

	/**
	 * 采集时应当使用的真实槽位（方案 C2）：优先已装备主手，其次背包里挖掘速度最快的
	 * 合规工具。返回 {@code null} 表示背包里没有比空手更好的选择。
	 */
	public SlotRef bestToolSlot(BlockState state) {
		SlotRef best = null;
		float bestSpeed = 1.0f;
		boolean bestSuitable = false;

		ItemStack hand = equipped(EquipmentSlot.MAINHAND);
		if (!hand.isEmpty()) {
			float speed = hand.getMiningSpeedMultiplier(state);
			boolean suitable = hand.isSuitableFor(state);
			if (suitable || speed > bestSpeed) {
				best = SlotRef.of(EquipmentSlot.MAINHAND);
				bestSpeed = speed;
				bestSuitable = suitable;
			}
		}
		for (int i = 0; i < main.size(); i++) {
			ItemStack stack = main.getStack(i);
			if (stack.isEmpty()) {
				continue;
			}
			boolean suitable = stack.isSuitableFor(state);
			float speed = stack.getMiningSpeedMultiplier(state);
			boolean better = (suitable && !bestSuitable)
				|| (suitable == bestSuitable && speed > bestSpeed);
			if (better) {
				best = SlotRef.main(i);
				bestSpeed = speed;
				bestSuitable = suitable;
			}
		}
		return best;
	}

	/** 背包/主手中攻击力最高的一叠（Guard 自动选武器，方案 C2）。 */
	public SlotRef bestWeaponSlot() {
		SlotRef best = null;
		double bestDamage = attackDamageOf(equipped(EquipmentSlot.MAINHAND));
		if (bestDamage > 0) {
			best = SlotRef.of(EquipmentSlot.MAINHAND);
		}
		for (int i = 0; i < main.size(); i++) {
			double damage = attackDamageOf(main.getStack(i));
			if (damage > bestDamage) {
				bestDamage = damage;
				best = SlotRef.main(i);
			}
		}
		return best;
	}

	/**
	 * 背包/手上最好的<b>弓</b>，没有就返回 null。
	 *
	 * <p>必须和近战分开找：弓的伤害来自箭的初速，身上<b>没有</b>
	 * {@code GENERIC_ATTACK_DAMAGE} 修饰符，所以 {@link #bestWeaponSlot()} 给它算
	 * 出来的分数是 0——一把满配弓永远赢不了一把木剑。玩家看到的就是「他只会拿近战」。
	 * </p>
	 */
	public SlotRef bestBowSlot() {
		SlotRef best = null;
		double bestScore = bowScoreOf(equipped(EquipmentSlot.MAINHAND));
		if (bestScore > 0) {
			best = SlotRef.of(EquipmentSlot.MAINHAND);
		}
		for (int i = 0; i < main.size(); i++) {
			double score = bowScoreOf(main.getStack(i));
			if (score > bestScore) {
				bestScore = score;
				best = SlotRef.main(i);
			}
		}
		return best;
	}

	/**
	 * 主背包里攻击力最高的一叠，<b>不含</b>已装备的主手。
	 *
	 * <p>守卫 Lv.2「装备意识」用它回答「手上这把快断了，包里还有别的吗」。
	 * {@link #bestWeaponSlot()} 在这件事上没用：手上那把往往仍然是分数最高的一把，
	 * 于是他会一直用到它碎在手里。</p>
	 */
	public SlotRef bestBackpackWeaponSlot() {
		SlotRef best = null;
		double bestDamage = 0;
		for (int i = 0; i < main.size(); i++) {
			double damage = attackDamageOf(main.getStack(i));
			if (damage > bestDamage) {
				bestDamage = damage;
				best = SlotRef.main(i);
			}
		}
		return best;
	}

	/**
	 * 主背包里最好的一把<b>弓</b>，<b>不含</b>已装备的主手；没有就返回 null。
	 *
	 * <p>和 {@link #bestBackpackWeaponSlot()} 是一对：守卫 Lv.2「装备意识」要按
	 * 手上那件东西的<b>种类</b>找备用的。手上的弓快断了就该换另一把弓——换成剑
	 * 等于替玩家改了打法。</p>
	 */
	public SlotRef bestBackpackBowSlot() {
		SlotRef best = null;
		double bestScore = 0;
		for (int i = 0; i < main.size(); i++) {
			double score = bowScoreOf(main.getStack(i));
			if (score > bestScore) {
				bestScore = score;
				best = SlotRef.main(i);
			}
		}
		return best;
	}

	/**
	 * 副手或背包里的盾；没有就返回 null。
	 *
	 * <p>盾之间没什么可比的（原版只有一种），所以先看副手——已经拿着的那面就是
	 * 最好的一面，免得每拍都从包里再搬一次。</p>
	 */
	public SlotRef bestShieldSlot() {
		if (equipped(EquipmentSlot.OFFHAND).getItem()
				instanceof net.minecraft.item.ShieldItem) {
			return SlotRef.of(EquipmentSlot.OFFHAND);
		}
		for (int i = 0; i < main.size(); i++) {
			if (main.getStack(i).getItem() instanceof net.minecraft.item.ShieldItem) {
				return SlotRef.main(i);
			}
		}
		return null;
	}

	/** 背包里第一支能射的箭；没有就返回 null。 */
	public SlotRef firstArrowSlot() {
		for (int i = 0; i < main.size(); i++) {
			if (main.getStack(i).getItem() instanceof net.minecraft.item.ArrowItem
					&& !main.getStack(i).isEmpty()) {
				return SlotRef.main(i);
			}
		}
		return null;
	}

	/**
	 * 弓（<b>只有</b>弓）的相对好坏，按力量附魔加权。
	 *
	 * <p><b>弩不在这里。</b>之前它得 12 分、比弓还高，于是会被选中拿到手上；可
	 * {@code AvatarEntity.shoot()} 只接受 {@code BowItem}，每 tick 判定「该射箭」
	 * 然后失败、退回近战、下一拍重来——拿着弩就等于站着发呆。弩的装填是两段式的
	 * （charge → 一直保持已装填 → 发射），和拉弓那个单段状态机对不上，硬塞进去只会
	 * 做出比现在更糟的动画。先老实说「这不是远程武器」，让他去拿剑。</p>
	 */
	private static double bowScoreOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()
				|| !(stack.getItem() instanceof net.minecraft.item.BowItem)) {
			return 0;
		}
		return 10 + EnchantmentHelper.getLevel(
			net.minecraft.enchantment.Enchantments.POWER, stack);
	}

	/** 对应护甲槽里防御值最高的一件（含已装备的那件）。 */
	public SlotRef bestArmorSlot(EquipmentSlot armorSlot) {
		SlotRef best = null;
		int bestProtection = armorProtection(equipped(armorSlot), armorSlot);
		if (bestProtection > 0) {
			best = SlotRef.of(armorSlot);
		}
		for (int i = 0; i < main.size(); i++) {
			int protection = armorProtection(main.getStack(i), armorSlot);
			if (protection > bestProtection) {
				bestProtection = protection;
				best = SlotRef.main(i);
			}
		}
		return best;
	}

	/** 这件东西作为近战武器的伤害。战斗层用它判断「手上拿的算不算武器」。 */
	public static double attackDamageOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		double base = 0;
		for (EntityAttributeModifier modifier : stack
				.getAttributeModifiers(EquipmentSlot.MAINHAND)
				.get(EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
			if (modifier.getOperation() == EntityAttributeModifier.Operation.ADDITION) {
				base += modifier.getValue();
			}
		}
		return base + EnchantmentHelper.getLevel(
			net.minecraft.enchantment.Enchantments.SHARPNESS, stack) * 0.5;
	}

	private static int armorProtection(ItemStack stack, EquipmentSlot slot) {
		if (stack == null || stack.isEmpty()
				|| !(stack.getItem() instanceof ArmorItem armor)
				|| armor.getSlotType() != slot) {
			return 0;
		}
		return armor.getProtection() * 4 + EnchantmentHelper.getLevel(
			net.minecraft.enchantment.Enchantments.PROTECTION, stack);
	}

	// ------------------------------------------------------------------ 耐久写回

	/**
	 * 破坏/使用完成后把结果写回同一个槽（方案 C2）。传入的 {@code after} 就是被
	 * 使用过的那个 stack 对象（耐久/附魔消耗已应用）；数量归零表示工具损坏。
	 */
	public void writeBackTool(SlotRef ref, ItemStack after) {
		if (ref == null) {
			return;
		}
		setAt(ref, after == null || after.isEmpty() || after.getCount() <= 0
			? ItemStack.EMPTY : after);
	}

	// ------------------------------------------------------------------ 事务

	public Snapshot snapshot() {
		List<ItemStack> mainCopy = new ArrayList<>(main.size());
		for (int i = 0; i < main.size(); i++) {
			mainCopy.add(main.getStack(i).copy());
		}
		List<ItemStack> equipCopy = new ArrayList<>(EQUIPMENT_SLOTS.size());
		for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
			equipCopy.add(avatar.getEquippedStack(slot).copy());
		}
		return new Snapshot(List.copyOf(mainCopy), List.copyOf(equipCopy),
			backpackStack().copy());
	}

	public void restore(Snapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		for (int i = 0; i < main.size() && i < snapshot.main().size(); i++) {
			main.setStack(i, snapshot.main().get(i).copy());
		}
		for (int i = 0; i < EQUIPMENT_SLOTS.size() && i < snapshot.equipment().size(); i++) {
			avatar.equipStack(EQUIPMENT_SLOTS.get(i), snapshot.equipment().get(i).copy());
		}
		backpackSlot.setStack(0, snapshot.backpack() == null
			? ItemStack.EMPTY : snapshot.backpack().copy());
		backpackSlot.markDirty();
		main.markDirty();
	}
}
