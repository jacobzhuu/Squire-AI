package dev.squire.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profile.Trait;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** Server-authoritative personality rerolling and its two-inventory transaction. */
public final class SquirePersonalityService {

	public static final int REROLL_COST = 4;

	public static Item rerollItem() {
		return Items.AMETHYST_SHARD;
	}

	private final SquireRuntime runtime;

	SquirePersonalityService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	/** The panel and the commit path count the exact same inventories. */
	public static int countShards(ServerPlayerEntity player, AvatarEntity avatar) {
		int total = 0;
		if (avatar != null) {
			total += count(avatar.items().mainInventory(), rerollItem());
		}
		if (player != null) {
			total += count(player.getInventory(), rerollItem());
		}
		return total;
	}

	public SquireRuntime.ExecutionResult reroll(ServerPlayerEntity sender,
			AvatarEntity avatar) {
		if (sender == null || avatar == null || !avatar.isAlive()) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场，不能洗练性格。");
		}
		var record = runtime.agentStore().recordOfAgent(avatar.agentId()).orElse(null);
		if (record == null) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_profile",
				"[Squire] 找不到这只侍从的长期档案。");
		}
		if (!record.ownerId.equals(sender.getUuid()) && !sender.hasPermissionLevel(2)) {
			return SquireRuntime.ExecutionResult.fail("feedback.not_owner",
				"[Squire] 你不能洗练别人的侍从。");
		}

		SquireProfile profile = record.profile;
		List<Trait> previous = profile.traitList();
		int traitCount = previous.isEmpty() ? 2 : Math.min(2, previous.size());

		// Build the complete debit plan before changing a single stack. The server tick
		// is single-threaded, so plan+commit is atomic with respect to play.
		List<Inventory> sources = List.of(avatar.items().mainInventory(),
			sender.getInventory());
		if (!consumeExactly(sources, rerollItem(), REROLL_COST)) {
			int missing = Math.max(0, REROLL_COST - countShards(sender, avatar));
			return SquireRuntime.ExecutionResult.fail("feedback.personality_materials",
				"[Squire] 缺少紫水晶碎片 ×" + missing + "。");
		}

		List<Trait> next = Trait.reroll(randomOf(avatar), traitCount, previous);
		profile.traits.clear();
		for (Trait trait : next) {
			profile.traits.add(trait.id());
		}
		avatar.applyTraits(profile.traits);
		runtime.agentStore().put(record);
		runtime.persistSnapshot(avatar);
		return SquireRuntime.ExecutionResult.ok("feedback.personality_rerolled",
			"[Squire] 性格已经重新生成：" + next.stream()
				.map(Trait::displayName).reduce((a, b) -> a + "、" + b).orElse("无") + "。");
	}

	private static RandomGenerator randomOf(AvatarEntity avatar) {
		var random = avatar.getRandom();
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return random.nextLong();
			}

			@Override
			public int nextInt(int bound) {
				return random.nextInt(bound);
			}
		};
	}

	/** Package-visible for the transaction regression tests. */
	static boolean consumeExactly(List<? extends Inventory> sources, Item item,
			int required) {
		if (required <= 0) {
			return true;
		}
		List<ItemStack> matchingStacks = new ArrayList<>();
		for (Inventory inventory : sources) {
			if (inventory == null) {
				continue;
			}
			for (int slot = 0; slot < inventory.size(); slot++) {
				ItemStack stack = inventory.getStack(slot);
				if (stack.isOf(item)) {
					matchingStacks.add(stack);
				}
			}
		}
		int[] available = matchingStacks.stream().mapToInt(ItemStack::getCount).toArray();
		int[] plan = debitPlan(available, required);
		if (plan == null) {
			return false;
		}
		for (int i = 0; i < plan.length; i++) {
			matchingStacks.get(i).decrement(plan[i]);
		}
		for (Inventory inventory : sources) {
			if (inventory != null) {
				inventory.markDirty();
			}
		}
		return true;
	}

	/**
	 * Creates a complete first-source-first debit plan without mutating its input.
	 * A {@code null} result means the combined total is insufficient.
	 */
	static int[] debitPlan(int[] available, int required) {
		int[] plan = new int[available.length];
		int remaining = Math.max(0, required);
		for (int i = 0; i < available.length && remaining > 0; i++) {
			plan[i] = Math.min(remaining, Math.max(0, available[i]));
			remaining -= plan[i];
		}
		return remaining == 0 ? plan : null;
	}

	private static int count(Inventory inventory, Item item) {
		if (inventory == null) {
			return 0;
		}
		int total = 0;
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack.isOf(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}
}
