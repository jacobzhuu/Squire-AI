package dev.squire.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.command.StructuredCommandCompiler;
import dev.squire.server.command.StructuredCommandCompiler.EnchantSpec;
import dev.squire.server.item.ItemEditJournal;
import dev.squire.server.nlu.ItemOperation;
import dev.squire.server.nlu.ItemOperationParser;
import dev.squire.server.task.executors.ItemEditExecutor;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 物品能力的<b>唯一</b>执行入口：{@link #execute}。
 *
 * <p>接受的是一个完整的 {@link ItemOperation}——<b>从哪拿 × 哪几件 × 做什么 × 归谁</b>。
 * 聊天里的自然语言、面板按钮、模型工具全部先变成这个对象再进来，因此不存在
 * 「某条路会某件事、另一条路不会」的情况。</p>
 *
 * <p>两条实现分支对应两种来源：{@code CONJURE} 走「伙伴用指令给自己 → 走过来交接
 * / 穿上」；其余作用域走「走到你跟前 → 原地改你已有的东西 → 留下可撤销的快照」。
 * 共同点是物品的每一步都看得见，没有任何东西凭空变化。</p>
 */
final class SquireItemService {

	/** 单次兑现的件数上限，和 {@code minecraft.command.give} 的口径一致。 */
	private static final int MAX_FULFILL_COUNT = ItemOperationParser.MAX_TOTAL_ITEMS;

	private final SquireRuntime runtime;
	private final ItemEditJournal editJournal = new ItemEditJournal();

	SquireItemService(SquireRuntime runtime) {
		this.runtime = runtime;
	}

	ItemEditJournal editJournal() {
		return editJournal;
	}

	// --------------------------------------------------------------- 统一执行入口

	public SquireRuntime.ExecutionResult execute(ServerPlayerEntity sender,
			ItemOperation operation) {
		Optional<AvatarEntity> found = runtime.agents().resolveForOwner(sender.getUuid());
		if (found.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.no_agent",
				"[Squire] 侍从不在场。请右键召集铃；首次召唤方法可按 K 查看。");
		}
		if (!runtime.permissions().has(sender,
				dev.squire.server.security.PermissionNodes.COMMAND_GIVE)) {
			return SquireRuntime.ExecutionResult.fail("feedback.permission_denied",
				operation.isConjure()
					? "[Squire] 你没有使用物品指令兑现的权限。"
					: "[Squire] 你没有让侍从改动装备的权限。");
		}
		return operation.isConjure()
			? conjure(sender, found.get(), operation)
			: submitEdit(sender, found.get(), operation);
	}

	// ------------------------------------------------------------------ 凭空取物

	private SquireRuntime.ExecutionResult conjure(ServerPlayerEntity sender,
			AvatarEntity avatar, ItemOperation operation) {
		if (operation.totalItems() > MAX_FULFILL_COUNT) {
			return SquireRuntime.ExecutionResult.fail("feedback.bad_count",
				"[Squire] 一次最多 " + MAX_FULFILL_COUNT + " 件，这次要得太多了。");
		}
		// id 解析放在分派之前：一件不认识就整单退回，不做"先给一半"。
		Map<Identifier, Integer> wanted = new LinkedHashMap<>();
		for (ItemOperation.Line line : operation.mergedLines()) {
			Identifier id;
			try {
				id = line.itemId().contains(":") ? new Identifier(line.itemId())
					: new Identifier("minecraft", line.itemId());
			} catch (RuntimeException e) {
				return SquireRuntime.ExecutionResult.fail("feedback.bad_item",
					"[Squire] 认不出这个物品：" + line.itemId());
			}
			if (!StructuredCommandCompiler.itemExists(id)) {
				return SquireRuntime.ExecutionResult.fail("feedback.bad_item",
					"[Squire] 注册表里没有 " + id + "。");
			}
			wanted.merge(id, line.count(), Integer::sum);
		}
		// 「满配附魔」由服务端按物品推导出具体附魔列表，玩家从不直接指定 NBT。
		boolean maxEnchant =
			operation.transform() == ItemOperation.Transform.SET_MAX_ENCHANTS;
		Map<Identifier, List<EnchantSpec>> enchants = new LinkedHashMap<>();
		int enchantable = 0;
		for (Identifier id : wanted.keySet()) {
			List<EnchantSpec> specs = maxEnchant
				? StructuredCommandCompiler.maxEnchantmentsFor(id) : List.of();
			enchants.put(id, specs);
			enchantable += specs.isEmpty() ? 0 : 1;
		}
		if (maxEnchant && enchantable == 0) {
			return SquireRuntime.ExecutionResult.fail("feedback.not_enchantable",
				"[Squire] " + describe(operation, wanted) + "没有可以附的魔。");
		}
		return operation.delivery() == ItemOperation.Delivery.TO_AGENT_EQUIP
			? equipAll(sender, avatar, wanted, enchants, operation)
			: deliverAll(sender, avatar, wanted, enchants, operation);
	}

	/**
	 * 流程是：<b>伙伴用指令给自己</b>（在脚下 summon 出掉落物，自己捡起来）→
	 * 走到玩家跟前 → 把东西扔在他脚边。这样物品的每一步都看得见，而不是凭空出现。
	 */
	private SquireRuntime.ExecutionResult deliverAll(ServerPlayerEntity sender,
			AvatarEntity avatar, Map<Identifier, Integer> wanted,
			Map<Identifier, List<EnchantSpec>> enchants, ItemOperation operation) {
		boolean viaCommandBlock = false;
		for (Map.Entry<Identifier, Integer> entry : wanted.entrySet()) {
			Identifier id = entry.getKey();
			List<EnchantSpec> specs = enchants.get(id);
			// 按堆叠上限拆成多条，summon 的 Count 是个字节。
			// 附魔物品不可堆叠，必须一件一个掉落物实体。
			int stackSize = specs.isEmpty()
				? Math.max(1, net.minecraft.registry.Registries.ITEM.get(id).getMaxCount())
				: 1;
			int remaining = entry.getValue();
			while (remaining > 0) {
				int chunk = Math.min(remaining, stackSize);
				var outcome = StructuredCommandCompiler.executeAcquireForAgent(
					runtime.server, avatar,
					new StructuredCommandCompiler.AcquireIntent(id, chunk, specs),
					runtime.protectionAdapter);
				viaCommandBlock |= outcome.viaCommandBlock();
				if (!outcome.success()) {
					return SquireRuntime.ExecutionResult.fail("feedback.command_failed",
						"[Squire] 取货指令失败：" + outcome.errorDetail());
				}
				remaining -= chunk;
			}
		}
		// 立刻把刚 summon 出来的掉落物收进背包。不能等每 10 tick 一次的自动捡拾——
		// 交付任务下一 tick 就会启动，那时背包还是空的，会直接判 INSUFFICIENT_ITEM。
		dev.squire.server.task.executors.ContainerExecutors.PickupNearby.sweep(avatar, 3.0);

		// 每种物品一条交付任务，由伙伴走过来扔给玩家。完成判定看玩家背包的最终数量。
		for (Map.Entry<Identifier, Integer> entry : wanted.entrySet()) {
			submitDelivery(sender, avatar, entry.getKey(), entry.getValue());
		}
		String carrier = viaCommandBlock ? "（指令过长，走了临时命令方块）" : "";
		String enchantNote =
			operation.transform() == ItemOperation.Transform.SET_MAX_ENCHANTS
				? "（满配附魔）" : "";
		return SquireRuntime.ExecutionResult.ok("feedback.command_fulfilled",
			"[Squire] 好的，" + describe(operation, wanted) + enchantNote
				+ " 已经到手，这就给你送过来。" + carrier);
	}

	private void submitDelivery(ServerPlayerEntity sender, AvatarEntity avatar,
			Identifier id, int count) {
		int playerTarget = dev.squire.server.task.executors.DeliverToOwnerExecutor
			.playerCount(sender, id) + count;
		Map<String, Object> params = new LinkedHashMap<>();
		params.put(dev.squire.server.task.executors.DeliverToOwnerExecutor.PARAM_ITEM_ID,
			id.toString());
		params.put(dev.squire.server.task.executors.DeliverToOwnerExecutor
			.PARAM_TARGET_PLAYER_COUNT, playerTarget);
		runtime.scheduler().submit(new dev.squire.server.task.Task(
			avatar.agentId(), sender.getUuid(),
			dev.squire.server.task.executors.DeliverToOwnerExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P3_USER_TASK,
			"送 " + count + " 个" + itemName(id) + "给你", null,
			dev.squire.server.task.executors.DeliverToOwnerExecutor.playerHas(
				runtime.runtimeServices, sender.getUuid(), id.toString(), playerTarget),
			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "give-by-command",
			params), runtime.currentTick());
	}

	/**
	 * 「给自己装备下界合金套装」：物品进伙伴自己的背包并<b>穿到身上</b>，不给玩家。
	 * 他只有一副身板，所以这条路每种物品只取一件。
	 */
	private SquireRuntime.ExecutionResult equipAll(ServerPlayerEntity sender,
			AvatarEntity avatar, Map<Identifier, Integer> wanted,
			Map<Identifier, List<EnchantSpec>> enchants, ItemOperation operation) {
		List<String> worn = new ArrayList<>();
		List<String> failed = new ArrayList<>();
		for (Identifier id : wanted.keySet()) {
			var outcome = StructuredCommandCompiler.executeAcquireForAgent(
				runtime.server, avatar,
				new StructuredCommandCompiler.AcquireIntent(id, 1, enchants.get(id)),
				runtime.protectionAdapter);
			if (!outcome.success()) {
				failed.add(itemName(id) + "（取货失败：" + outcome.errorDetail() + "）");
				continue;
			}
			dev.squire.server.task.executors.ContainerExecutors.PickupNearby
				.sweep(avatar, 3.0);
			int slot = avatar.items().findSlotOf(id);
			if (slot < 0) {
				failed.add(itemName(id) + "（没进背包）");
				continue;
			}
			var equipped = avatar.items().equipFromMain(slot, null);
			if (equipped.success()) {
				worn.add(itemName(id));
				if (equipped.slot() == net.minecraft.entity.EquipmentSlot.MAINHAND) {
					// 「给自己拿一把下界合金剑」是玩家点名要的武器，和从面板里
					// 拖进主手是同一件事——战斗层不许再自动把它换掉。
					avatar.markWeaponChosenByPlayer();
				}
			} else {
				failed.add(itemName(id) + "（" + equipped.errorCode() + "）");
			}
		}
		if (worn.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.equip_failed",
				"[Squire] 一件都没穿上：" + String.join("、", failed));
		}
		String tail = failed.isEmpty() ? "" : "；没搞定：" + String.join("、", failed);
		String enchantNote =
			operation.transform() == ItemOperation.Transform.SET_MAX_ENCHANTS
				? "（满配附魔）" : "";
		return SquireRuntime.ExecutionResult.ok("feedback.equipped",
			"[Squire] 已经装备好了" + enchantNote + "：" + String.join("、", worn) + tail);
	}

	// -------------------------------------------------------------- 改动已有物品

	/**
	 * 提交一条「走过去改」的任务。刻意不同步执行：隔着半个世界让玩家身上的盔甲
	 * 自己变样，正是这个模组一直在反对的凭空发生。
	 */
	private SquireRuntime.ExecutionResult submitEdit(ServerPlayerEntity sender,
			AvatarEntity avatar, ItemOperation operation) {
		if (!(operation.selector() instanceof ItemOperation.Selector.Filter filter)) {
			return SquireRuntime.ExecutionResult.fail("feedback.bad_item",
				"[Squire] 没听懂要改哪几件。");
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put(ItemEditExecutor.PARAM_SCOPE, operation.scope().name());
		params.put(ItemEditExecutor.PARAM_SELECTOR, filter.kind().name());
		if (filter.itemId() != null) {
			params.put(ItemEditExecutor.PARAM_SELECTOR_ITEM, filter.itemId());
		}
		params.put(ItemEditExecutor.PARAM_TRANSFORM, operation.transform().name());
		params.put(ItemEditExecutor.PARAM_ENCHANTMENTS,
			ItemEditExecutor.joinIds(operation.enchantmentIds()));
		String label = operation.label() == null ? "改动装备" : operation.label();
		params.put(ItemEditExecutor.PARAM_LABEL, label);

		runtime.scheduler().submit(new dev.squire.server.task.Task(
			avatar.agentId(), sender.getUuid(), ItemEditExecutor.TYPE,
			dev.squire.server.task.TaskPriority.P3_USER_TASK,
			label, null, ItemEditExecutor.applied(),
			1200L, dev.squire.server.task.RetryPolicy.DEFAULT, true, "item-edit",
			params), runtime.currentTick());

		return SquireRuntime.ExecutionResult.ok("feedback.item_edit_started",
			"[Squire] 好的，" + label + "（" + scopeName(operation.scope())
				+ "）。我这就过来动手。");
	}

	/** 撤销最近一次物品改动：把快照原样放回原来的格子。 */
	public SquireRuntime.ExecutionResult undoLastEdit(ServerPlayerEntity sender) {
		ItemEditJournal.Edit edit = editJournal.pop(sender.getUuid());
		if (edit == null) {
			// 说「撤销」的人未必是想撤物品改动，别让他在这里断掉。
			return SquireRuntime.ExecutionResult.fail("feedback.item_undo_none",
				"[Squire] 本次游戏里我没有改过你的东西。");
		}
		var inventory = sender.getInventory();
		int restored = 0;
		List<String> conflicts = new ArrayList<>();
		for (ItemEditJournal.SlotSnapshot snapshot : edit.snapshots()) {
			ItemStack before = snapshot.before();
			switch (snapshot.slot()) {
				case ARMOR -> {
					if (snapshot.index() >= 0 && snapshot.index() < inventory.armor.size()) {
						inventory.armor.set(snapshot.index(), before.copy());
						restored++;
					}
				}
				case MAIN_HAND -> {
					if (snapshot.index() >= 0 && snapshot.index() < inventory.main.size()) {
						inventory.main.set(snapshot.index(), before.copy());
						restored++;
					}
				}
				case INVENTORY -> {
					if (snapshot.index() >= 0 && snapshot.index() < inventory.main.size()) {
						inventory.main.set(snapshot.index(), before.copy());
						restored++;
					}
				}
				default -> conflicts.add("未知槽位");
			}
		}
		inventory.markDirty();
		sender.playerScreenHandler.sendContentUpdates();
		if (restored == 0) {
			return SquireRuntime.ExecutionResult.fail("feedback.item_undo_failed",
				"[Squire] 那些格子已经不在原来的位置了，没法还原。");
		}
		String tail = conflicts.isEmpty() ? "" : "；跳过 " + conflicts.size() + " 处";
		return SquireRuntime.ExecutionResult.ok("feedback.item_undo_done",
			"[Squire] 已还原「" + edit.description() + "」，共 " + restored + " 件。" + tail);
	}

	// ------------------------------------------------------------------ 兼容入口

	/** 面板的"给我这个"按钮和旧调用点：单件、不附魔。 */
	public SquireRuntime.ExecutionResult giveByCommand(ServerPlayerEntity sender,
			String itemId, int count) {
		return giveByCommand(sender, itemId, count, false);
	}

	public SquireRuntime.ExecutionResult giveByCommand(ServerPlayerEntity sender,
			String itemId, int count, boolean enchanted) {
		if (count < 1 || count > MAX_FULFILL_COUNT) {
			return SquireRuntime.ExecutionResult.fail("feedback.bad_count",
				"[Squire] 数量得在 1 到 " + MAX_FULFILL_COUNT + " 之间。");
		}
		return execute(sender, ItemOperation.conjureOne(itemId, count,
			enchanted ? ItemOperation.Transform.SET_MAX_ENCHANTS
				: ItemOperation.Transform.NONE,
			ItemOperation.Delivery.TO_PLAYER, null));
	}

	/** 聊天里的「给自己装备…」：解析成结构后走同一个执行器。 */
	public SquireRuntime.ExecutionResult equipSelf(ServerPlayerEntity sender, String phrase) {
		var parsed = ItemOperationParser.parse(phrase, runtime.vocabulary());
		if (parsed.isEmpty() || !parsed.get().isConjure()) {
			return SquireRuntime.ExecutionResult.fail("feedback.bad_item",
				"[Squire] 没听懂要穿什么。可以说「给自己装备下界合金套装」"
					+ "或「给自己拿一把下界合金剑」。");
		}
		ItemOperation parsedOp = parsed.get();
		// 这条入口的语义就是"他自己穿"，哪怕句子里没写"自己"也一样。
		return execute(sender, ItemOperation.conjure(parsedOp.lines(),
			parsedOp.transform(), parsedOp.enchantmentIds(),
			ItemOperation.Delivery.TO_AGENT_EQUIP, parsedOp.label()));
	}

	/**
	 * 面板套装按钮的直连入口：材质前缀（{@code iron} / {@code diamond} /
	 * {@code netherite}）+ 整套盔甲，不经过自然语言解析。
	 */
	public SquireRuntime.ExecutionResult equipSelfSet(ServerPlayerEntity sender,
			String materialPrefix, boolean enchanted) {
		var operation = ItemOperationParser.armorSet(
			ItemOperation.Delivery.TO_AGENT_EQUIP, materialPrefix, 1,
			enchanted ? ItemOperation.Transform.SET_MAX_ENCHANTS
				: ItemOperation.Transform.NONE,
			runtime.vocabulary());
		if (operation.isEmpty()) {
			return SquireRuntime.ExecutionResult.fail("feedback.bad_item",
				"[Squire] 这个材质没有整套盔甲：" + materialPrefix);
		}
		return execute(sender, operation.get());
	}

	// ----------------------------------------------------------------------- 文案

	private static String scopeName(ItemOperation.Scope scope) {
		return switch (scope) {
			case PLAYER_EQUIPPED -> "你身上穿的";
			case PLAYER_HELD -> "你手上那件";
			case PLAYER_INVENTORY -> "你背包里的";
			case AGENT_EQUIPPED -> "我身上的";
			case CONJURE -> "新取的";
		};
	}

	/** 回执里的物品概括：解析器给了套装名就用它，否则逐项列出。 */
	private String describe(ItemOperation operation, Map<Identifier, Integer> wanted) {
		if (operation.label() != null && !operation.label().isBlank()) {
			return operation.label();
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<Identifier, Integer> entry : wanted.entrySet()) {
			parts.add(entry.getValue() + " 个" + itemName(entry.getKey()));
		}
		return String.join("、", parts);
	}

	/**
	 * 物品的中文名。回执里出现 {@code minecraft:oak_log} 对玩家等同于噪音，
	 * 别名表本来就是一份现成的中文映射，反查即可。
	 */
	private String itemName(Identifier id) {
		return runtime.itemAliases().displayName(id.toString());
	}
}
