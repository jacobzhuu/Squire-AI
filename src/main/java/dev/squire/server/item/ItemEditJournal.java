package dev.squire.server.item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.item.ItemStack;

/**
 * 改动玩家物品之前的快照，用来撤销。
 *
 * <p>为什么不复用 {@code UndoJournal}：那一份是按 {@code BlockPos + BlockState} 组织的
 * 方块日志，把物品塞进去要伪造坐标。物品编辑是另一种东西，就该有自己的日志。</p>
 *
 * <p><b>只在本次运行期间有效</b>（进程内，不落盘）。改动玩家物品是可逆的、而且玩家
 * 就在现场立刻能看到结果，所以撤销窗口天然很短；把它做成持久存储会引入一堆
 * 「重启后撤销一个你早忘了的改动」的坑。回执里会如实写明这一点，而不是让玩家以为
 * 它永远可撤。</p>
 */
public final class ItemEditJournal {

	/** 每个玩家保留多少次改动。够撤回"手滑说错的那一句"，不做长期历史。 */
	private static final int MAX_PER_PLAYER = 8;

	/** 一次改动里的一格快照。 */
	public record SlotSnapshot(Slot slot, int index, ItemStack before) { }

	/** 快照的位置：护甲槽 / 主手 / 背包第 N 格。 */
	public enum Slot { ARMOR, MAIN_HAND, INVENTORY }

	/** 一次改动。 */
	public record Edit(UUID editId, UUID playerId, String description,
			List<SlotSnapshot> snapshots, long tick) {
		public Edit {
			snapshots = List.copyOf(snapshots);
		}

		public int size() {
			return snapshots.size();
		}
	}

	private final Map<UUID, Deque<Edit>> byPlayer = new ConcurrentHashMap<>();

	/** 记一次改动；返回它的 id。 */
	public Edit record(UUID playerId, String description, List<SlotSnapshot> snapshots,
			long tick) {
		Edit edit = new Edit(UUID.randomUUID(), playerId, description, snapshots, tick);
		Deque<Edit> stack = byPlayer.computeIfAbsent(playerId, id -> new ArrayDeque<>());
		synchronized (stack) {
			stack.push(edit);
			while (stack.size() > MAX_PER_PLAYER) {
				stack.removeLast();
			}
		}
		return edit;
	}

	/** 最近一次改动（不移除）。 */
	public Edit peek(UUID playerId) {
		Deque<Edit> stack = byPlayer.get(playerId);
		if (stack == null) {
			return null;
		}
		synchronized (stack) {
			return stack.peek();
		}
	}

	/** 取出并移除最近一次改动。 */
	public Edit pop(UUID playerId) {
		Deque<Edit> stack = byPlayer.get(playerId);
		if (stack == null) {
			return null;
		}
		synchronized (stack) {
			return stack.poll();
		}
	}

	public List<Edit> list(UUID playerId) {
		Deque<Edit> stack = byPlayer.get(playerId);
		if (stack == null) {
			return List.of();
		}
		synchronized (stack) {
			return new ArrayList<>(stack);
		}
	}

	public void clear(UUID playerId) {
		byPlayer.remove(playerId);
	}
}
