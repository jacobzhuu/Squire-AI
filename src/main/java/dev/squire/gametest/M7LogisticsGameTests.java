package dev.squire.gametest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.body.avatar.AvatarInventory;
import dev.squire.server.compat.BackpackCompat;
import dev.squire.server.input.InputGateway;
import dev.squire.server.memory.LocationMemory;
import dev.squire.server.memory.LocationMemoryStore;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.task.RetryPolicy;
import dev.squire.server.task.Task;
import dev.squire.server.task.TaskPriority;
import dev.squire.server.task.executors.ContainerExecutors;
import dev.squire.server.world.ContainerAccess;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/**
 * 工作包 C/D/E 黑盒验收：真实背包与装备、容器物流事务、长期 Guard、真实玩家救助，
 * 以及带维度的长期位置记忆。
 *
 * <p>每个测试都检查最终世界状态（容器内容、耐久、玩家状态），而不是执行器自己的
 * 记账；物品守恒在每一处搬运后都被显式验证。</p>
 */
public final class M7LogisticsGameTests implements FabricGameTest {
	public static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static FakePlayer fakeOwner(ServerWorld world, String name) {
		return FakePlayer.get(world,
			new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
	}

	private static void place(net.minecraft.entity.Entity entity, Vec3d feetCenter) {
		entity.refreshPositionAndAngles(feetCenter.x, feetCenter.y, feetCenter.z, 0.0f, 0.0f);
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static Inventory chestAt(TestContext context, BlockPos rel) {
		BlockEntity entity = context.getWorld().getBlockEntity(context.getAbsolutePos(rel));
		if (!(entity instanceof Inventory inventory)) {
			throw new AssertionError("expected a container at " + rel);
		}
		return inventory;
	}

	private static int ownedItemCount(AvatarInventory items) {
		int total = 0;
		for (int i = 0; i < items.size(); i++) {
			total += items.getStack(i).getCount();
		}
		for (EquipmentSlot slot : AvatarInventory.EQUIPMENT_SLOTS) {
			total += items.equipped(slot).getCount();
		}
		return total;
	}

	// ============================================================ C1：权威背包

	/**
	 * C1：插入/提取搬运的是真实 {@link ItemStack} 对象，NBT、附魔和耐久都必须原样
	 * 保留——按 item id 重建 stack 会把一把附魔镐变成普通镐。
	 */
	/**
	 * 拿起武器要真的变强，不能只是"手里多了个模型"。玩家反馈"他好像不会用武器"，
	 * 这条测试把"装到主手"和"攻击力真的提高了"两件事一起钉死。
	 */
	@GameTest(templateName = FLOOR)
	public void equippingAWeaponRaisesTheAttackDamageHeActuallyHits(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "weapon-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		double[] bare = new double[1];
		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			bare[0] = avatar.getAttributeValue(
				net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);

			var items = avatar.items();
			items.insert(new ItemStack(Items.NETHERITE_SWORD));
			var result = items.equipFromMain(
				items.findSlotOf(new Identifier("minecraft:netherite_sword")), null);
			context.assertTrue(result.success(),
				"a sword must equip into the natural slot: " + result.errorCode());
			context.assertTrue(result.slot() == EquipmentSlot.MAINHAND,
				"a sword belongs in the main hand, not on his head");
			context.assertTrue(avatar.getEquippedStack(EquipmentSlot.MAINHAND)
				.getItem() == Items.NETHERITE_SWORD, "the sword is actually held");
		});
		// 原版是在 tickMovement 里把物品的属性修饰符挂上去的，同一 tick 里读不到，
		// 所以攻击力要隔几 tick 再验。
		context.runAtTick(20, () -> {
			AvatarEntity avatar = rt.agents().resolveForOwner(owner.getUuid()).orElseThrow();
			double armed = avatar.getAttributeValue(
				net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
			context.assertTrue(armed > bare[0],
				"holding a sword must raise attack damage (" + bare[0] + " -> " + armed
					+ "); otherwise the weapon is pure decoration");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR)
	public void inventoryPreservesNbtEnchantmentsAndDurability(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "c1-nbt-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			AvatarInventory items = avatar.items();

			ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
			pickaxe.addEnchantment(Enchantments.EFFICIENCY, 3);
			pickaxe.setDamage(42);
			pickaxe.setCustomName(net.minecraft.text.Text.literal("Old Faithful"));
			items.insert(pickaxe);

			ItemStack recovered = items.extract(
				new Identifier("minecraft:iron_pickaxe"), 1).get(0);
			context.assertTrue(recovered.getDamage() == 42,
				"durability must survive the round trip, was " + recovered.getDamage());
			context.assertTrue(net.minecraft.enchantment.EnchantmentHelper.getLevel(
					Enchantments.EFFICIENCY, recovered) == 3,
				"enchantments must survive the round trip");
			context.assertTrue(recovered.getName().getString().equals("Old Faithful"),
				"custom name must survive the round trip");

			// 不同 NBT 的同种物品绝不能被合并成一叠
			items.insert(recovered);
			items.insert(new ItemStack(Items.IRON_PICKAXE));
			int slotsUsed = 0;
			for (int i = 0; i < items.size(); i++) {
				if (!items.getStack(i).isEmpty()) {
					slotsUsed++;
				}
			}
			context.assertTrue(slotsUsed == 2,
				"an enchanted tool must not stack onto a plain one, slots=" + slotsUsed);

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** C1：事务快照能把一串失败的搬运整体回滚，物品总量不变。 */
	@GameTest(templateName = FLOOR)
	public void inventorySnapshotRollsBackEveryChange(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "c1-tx-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			AvatarInventory items = avatar.items();
			items.insert(new ItemStack(Items.COBBLESTONE, 30));
			items.insert(new ItemStack(Items.IRON_INGOT, 4));

			var snapshot = items.snapshot();
			items.extract(new Identifier("minecraft:cobblestone"), 25);
			items.insert(new ItemStack(Items.DIAMOND, 3));
			items.equipFromMain(items.findSlotOf(new Identifier("minecraft:iron_ingot")),
				EquipmentSlot.MAINHAND);

			items.restore(snapshot);
			context.assertTrue(items.countOf(new Identifier("minecraft:cobblestone")) == 30,
				"rollback restores the exact cobblestone count");
			context.assertTrue(items.countOf(new Identifier("minecraft:iron_ingot")) == 4,
				"rollback puts equipped items back in the backpack");
			context.assertTrue(items.countOf(new Identifier("minecraft:diamond")) == 0,
				"rollback removes what the failed transaction added");
			context.assertTrue(items.equipped(EquipmentSlot.MAINHAND).isEmpty(),
				"rollback restores the equipment slots too");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// ============================================================ C2：真实工具与装备

	/**
	 * C2：装备是"搬运"而不是"复制"。装上后背包里不能再留一份，卸下后必须完整回来。
	 */
	@GameTest(templateName = FLOOR)
	public void equipMovesTheItemInsteadOfCopyingIt(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "c2-equip-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			AvatarInventory items = avatar.items();
			items.insert(new ItemStack(Items.IRON_CHESTPLATE));
			Identifier chestplate = new Identifier("minecraft:iron_chestplate");

			var equipped = items.equipFromMain(items.findSlotOf(chestplate), null);
			context.assertTrue(equipped.success(), "armour goes to its natural slot");
			context.assertTrue(equipped.slot() == EquipmentSlot.CHEST,
				"iron chestplate belongs on the chest");
			context.assertTrue(items.countOf(chestplate) == 0,
				"the equipped piece must NOT still sit in the backpack (no duplication)");
			context.assertTrue(!items.equipped(EquipmentSlot.CHEST).isEmpty(),
				"the chest slot really holds it");

			context.assertTrue(items.unequip(EquipmentSlot.CHEST).success(), "unequip ok");
			context.assertTrue(items.countOf(chestplate) == 1,
				"exactly one chestplate exists after the round trip");
			context.assertTrue(items.equipped(EquipmentSlot.CHEST).isEmpty(),
				"the chest slot is empty again");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/**
	 * C2：换装时背包已经满了。旧装备必须完好无损地回到背包，新装备装上，而且
	 * 总量严格守恒——这条路径上一次部分插入再回滚就会凭空多出物品。
	 */
	@GameTest(templateName = FLOOR)
	public void equipSwapOnAFullBackpackConservesEveryItem(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "c2-fullswap-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			AvatarInventory items = avatar.items();
			Identifier iron = new Identifier("minecraft:iron_chestplate");
			Identifier diamond = new Identifier("minecraft:diamond_chestplate");

			items.setEquipped(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			// 每一格都塞满不可堆叠物品：换下来的铁胸甲只能回到被腾出的那一格
			for (int i = 0; i < items.size(); i++) {
				items.setStack(i, new ItemStack(Items.DIAMOND_PICKAXE));
			}
			items.setStack(0, new ItemStack(Items.DIAMOND_CHESTPLATE));
			context.assertTrue(items.freeSlots() == 0, "the backpack starts completely full");

			var result = items.equipFromMain(0, EquipmentSlot.CHEST);
			context.assertTrue(result.success(), "the swap succeeds: "
				+ result.errorCode());
			context.assertTrue(items.equipped(EquipmentSlot.CHEST).isOf(
					Items.DIAMOND_CHESTPLATE), "the new piece is worn");
			context.assertTrue(items.countOf(iron) == 1,
				"exactly ONE iron chestplate came back, found " + items.countOf(iron));
			context.assertTrue(items.countOf(diamond) == 0,
				"the equipped diamond piece is not also still in the backpack");
			context.assertTrue(items.countOf(new Identifier("minecraft:diamond_pickaxe"))
					== items.size() - 1,
				"nothing else was displaced or duplicated");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 自动装备只搬运已有物品；Protection IV 钻石胸甲可以胜过无附魔下界合金。 */
	@GameTest(templateName = FLOOR)
	public void autoArmorPrefersStrongSurvivalEnchantmentsWithoutGeneratingItems(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "best-armor-enchant-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarInventory items = rt.summonFor(owner).items();
			items.setEquipped(EquipmentSlot.CHEST,
				new ItemStack(Items.NETHERITE_CHESTPLATE));
			ItemStack diamond = new ItemStack(Items.DIAMOND_CHESTPLATE);
			diamond.addEnchantment(Enchantments.PROTECTION, 4);
			items.insert(diamond);
			int before = ownedItemCount(items);

			var result = items.autoEquipBestArmor();
			context.assertTrue(result.success(), "auto armor failed: " + result.errorCode());
			context.assertTrue(result.changedSlots() == 1, "exactly the chest slot changes");
			context.assertTrue(items.equipped(EquipmentSlot.CHEST)
				.isOf(Items.DIAMOND_CHESTPLATE),
				"Protection IV diamond must beat plain netherite by the real score");
			context.assertTrue(net.minecraft.enchantment.EnchantmentHelper.getLevel(
				Enchantments.PROTECTION, items.equipped(EquipmentSlot.CHEST)) == 4,
				"the selected enchanted stack itself is worn");
			context.assertTrue(items.countOf(new Identifier(
				"minecraft:netherite_chestplate")) == 1,
				"the old armor returns to the Squire backpack");
			context.assertTrue(ownedItemCount(items) == before,
				"auto armor must not create or destroy items");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 濒坏的高材质护甲要被明显降权，不能替换一件完好的实用护甲。 */
	@GameTest(templateName = FLOOR)
	public void autoArmorStronglyPenalizesLowDurability(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "best-armor-durability-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarInventory items = rt.summonFor(owner).items();
			items.setEquipped(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			ItemStack almostBroken = new ItemStack(Items.DIAMOND_CHESTPLATE);
			almostBroken.setDamage(almostBroken.getMaxDamage() - 1);
			items.insert(almostBroken);
			int before = ownedItemCount(items);

			var result = items.autoEquipBestArmor();
			context.assertTrue(result.success(), "scoring itself must not fail");
			context.assertTrue(result.changedSlots() == 0,
				"near-broken diamond armor must lose to full-durability iron");
			context.assertTrue(items.equipped(EquipmentSlot.CHEST)
				.isOf(Items.IRON_CHESTPLATE), "the useful armor stays worn");
			context.assertTrue(ownedItemCount(items) == before, "item count stays constant");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 绑定诅咒候选是硬排除：即使材质更好，也不会被自动穿上。 */
	@GameTest(templateName = FLOOR)
	public void autoArmorNeverEquipsBindingCurse(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "best-armor-binding-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarInventory items = rt.summonFor(owner).items();
			ItemStack cursed = new ItemStack(Items.NETHERITE_BOOTS);
			cursed.addEnchantment(Enchantments.BINDING_CURSE, 1);
			items.insert(cursed);
			items.insert(new ItemStack(Items.IRON_BOOTS));
			int before = ownedItemCount(items);

			var result = items.autoEquipBestArmor();
			context.assertTrue(result.success(), "a safe non-cursed candidate exists");
			context.assertTrue(items.equipped(EquipmentSlot.FEET).isOf(Items.IRON_BOOTS),
				"Binding Curse must be rejected regardless of material");
			context.assertTrue(items.countOf(new Identifier("minecraft:netherite_boots")) == 1,
				"the cursed candidate remains untouched in the Squire backpack");
			context.assertTrue(ownedItemCount(items) == before, "item count stays constant");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 四个槽位独立选优；即使主背包全满，旧装备也只能回到腾出的候选槽，不能丢。 */
	@GameTest(templateName = FLOOR)
	public void autoArmorSwapsAllSlotsAtomicallyOnAFullBackpack(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "best-armor-full-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarInventory items = rt.summonFor(owner).items();
			items.setEquipped(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			items.setEquipped(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
			items.setEquipped(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
			items.setEquipped(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
			for (int i = 0; i < items.size(); i++) {
				items.setStack(i, new ItemStack(Items.DIAMOND_PICKAXE));
			}
			items.setStack(0, new ItemStack(Items.DIAMOND_HELMET));
			items.setStack(1, new ItemStack(Items.DIAMOND_CHESTPLATE));
			items.setStack(2, new ItemStack(Items.DIAMOND_LEGGINGS));
			items.setStack(3, new ItemStack(Items.DIAMOND_BOOTS));
			int before = ownedItemCount(items);
			context.assertTrue(items.freeSlots() == 0, "the Squire backpack starts full");

			var result = items.autoEquipBestArmor();
			context.assertTrue(result.success(), "full-inventory swaps should be safe");
			context.assertTrue(result.changedSlots() == 4, "all four slots choose independently");
			context.assertTrue(items.equipped(EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET),
				"helmet selected");
			context.assertTrue(items.equipped(EquipmentSlot.CHEST)
				.isOf(Items.DIAMOND_CHESTPLATE), "chest selected");
			context.assertTrue(items.equipped(EquipmentSlot.LEGS)
				.isOf(Items.DIAMOND_LEGGINGS), "leggings selected");
			context.assertTrue(items.equipped(EquipmentSlot.FEET).isOf(Items.DIAMOND_BOOTS),
				"boots selected");
			context.assertTrue(items.freeSlots() == 0,
				"each old piece safely occupies the candidate's vacated slot");
			context.assertTrue(ownedItemCount(items) == before,
				"the atomic four-slot exchange conserves every item");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/**
	 * C2：采集用的是背包里那把真镐，破坏之后耐久必须写回同一个槽——而不是用一把
	 * 凭空 new 出来的副本挖矿。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600)
	public void gatherWearsDownTheRealToolInInventory(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "c2-durability-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
			avatar.setStayMode();
			avatar.items().insert(new ItemStack(Items.IRON_PICKAXE));
			for (int dz = 0; dz < 3; dz++) {
				context.setBlockState(new BlockPos(5, 2, 3 + dz), Blocks.STONE);
			}
			Task task = new Task(avatar.agentId(), owner.getUuid(),
				dev.squire.server.task.executors.GatherBlockExecutor.TYPE,
				TaskPriority.P3_USER_TASK, "gather 3 cobblestone", null,
				dev.squire.server.task.executors.GatherBlockExecutor.hasItems(
					"minecraft:cobblestone", 3),
				500L, RetryPolicy.DEFAULT, true, "c2",
				java.util.Map.of("blockId", "minecraft:stone",
					"itemId", "minecraft:cobblestone", "count", 3, "targetCount", 3));
			rt.scheduler().submit(task, world.getTime());
		});

		AtomicBoolean checked = new AtomicBoolean(false);
		context.runAtTick(400, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			AvatarInventory items = avatar.items();
			context.assertTrue(items.countOf(new Identifier("minecraft:cobblestone")) >= 3,
				"three real blocks were mined");
			int slot = items.findSlotOf(new Identifier("minecraft:iron_pickaxe"));
			ItemStack tool = slot >= 0 ? items.getStack(slot)
				: items.equipped(EquipmentSlot.MAINHAND);
			context.assertTrue(!tool.isEmpty(), "the pickaxe is still owned by the agent");
			context.assertTrue(tool.getDamage() >= 3,
				"durability must be written back to the real slot, damage="
					+ tool.getDamage());
			checked.set(true);
			rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(560, () -> {
			if (!checked.get()) {
				context.throwGameTestException("gather never produced a durability check");
			}
		});
	}

	// ============================================================ 玩家报告的回归

	/**
	 * 回归：跟随目标停下来时会无条件 {@code navigation.stop()}。任务调用 moveTo 会把
	 * 模式切成 IDLE，跟随目标因此在<b>下一 tick</b> 退出——正好把任务刚发起的寻路
	 * 一起掐掉。玩家看到的就是"说了目标已创建，然后它只会跟着我"。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300)
	public void followModeDoesNotCancelATaskNavigation(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "regress-follow-owner");
		AtomicBoolean checked = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(8, 2, 8))));
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, 2, 1))));
			// 距离够远，跟随目标会真的开始跑
			avatar.setFollowMode(owner.getUuid());
		});

		context.runAtTick(15, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"the body starts in FOLLOW, like it does for a real player");
			// 运行时接管移动。以前这一步会把模式改成 IDLE，也就是把玩家的
			// 长期命令静默抹掉；现在模式保留，跟随 Goal 只是在任务期间让位。
			BlockPos target = context.getAbsolutePos(new BlockPos(1, 2, 7));
			var handle = avatar.moveTo(new dev.squire.api.body.TargetPosition(
				world.getRegistryKey().getValue().toString(),
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				dev.squire.api.body.MoveOptions.WALK);
			context.assertTrue(handle.state() == dev.squire.api.body.MoveHandle.State.MOVING,
				"the runtime move started");
		});

		// 让跟随目标有机会在下一次 ai pass 里退出
		context.runAtTick(22, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(avatar.mode() == AvatarEntity.MovementMode.FOLLOW,
				"the standing order survives a task taking over the body - erasing it "
					+ "is how a companion ends up idle forever after one task");
			context.assertTrue(avatar.taskDriven(),
				"but while the move runs, the follow goal must stand down");
			context.assertTrue(!avatar.getNavigation().isIdle(),
				"the FOLLOW goal stopping must NOT cancel the runtime's own path");
			context.assertTrue(avatar.lastMoveErrorCode() == null,
				"and the move must not have been reported stuck: "
					+ avatar.lastMoveErrorCode());
			checked.set(true);
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(280, () -> {
			if (!checked.get()) {
				context.throwGameTestException("follow/navigation regression never ran");
			}
		});
	}

	/**
	 * 回归：附近没有目标资源时，采集执行器以前连续扫 3 tick 就直接失败——伙伴
	 * 一步都不迈。现在它必须走出去换地方找。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 300)
	public void gatherWalksOnLookingInsteadOfGivingUpWhereItStands(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "regress-search-owner");
		AtomicReference<UUID> taskId = new AtomicReference<>();
		AtomicBoolean checked = new AtomicBoolean(false);

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
			// 故意找一种周围绝对没有的方块
			Task task = new Task(avatar.agentId(), owner.getUuid(),
				dev.squire.server.task.executors.GatherBlockExecutor.TYPE,
				TaskPriority.P3_USER_TASK, "gather something absent", null,
				dev.squire.server.task.executors.GatherBlockExecutor.hasItems(
					"minecraft:diamond", 1),
				2000L, RetryPolicy.DEFAULT, true, "regress",
				java.util.Map.of("blockId", "minecraft:diamond_ore",
					"itemId", "minecraft:diamond", "count", 1, "targetCount", 1));
			taskId.set(task.taskId());
			rt.scheduler().submit(task, world.getTime());
		});

		// 3 tick 就放弃的旧行为会在这里已经 FAILED；新行为应该正在换地方找
		context.runAtTick(40, () -> {
			Task task = rt.scheduler().findTask(taskId.get()).orElse(null);
			context.assertTrue(task != null, "the task still exists");
			context.assertTrue(!task.state().isTerminal(),
				"it must NOT have given up where it stood, state=" + task.state());
			var progress = task.executionState();
			context.assertTrue(progress
					instanceof dev.squire.server.task.executors.GatherBlockExecutor.Progress,
				"progress is tracked");
			var p = (dev.squire.server.task.executors.GatherBlockExecutor.Progress) progress;
			context.assertTrue(p.relocations() > 0,
				"the agent went looking somewhere else, relocations=" + p.relocations());
			checked.set(true);
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(280, () -> {
			if (!checked.get()) {
				context.throwGameTestException("search regression never ran");
			}
		});
	}

	// ============================================================ C3：容器物流

	/**
	 * C3：deposit 是两阶段事务——先算可移动量再一起提交。存完之后背包和箱子的
	 * 总量必须严格守恒。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600)
	public void depositMovesItemsIntoTheRealChestWithoutLoss(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "c3-deposit-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
		BlockPos chestRel = new BlockPos(5, 2, 4);

		context.runAtTick(5, () -> {
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
			avatar.setStayMode();
			context.setBlockState(chestRel, Blocks.CHEST);
			avatar.items().insert(new ItemStack(Items.IRON_ORE, 12));
			avatar.items().insert(new ItemStack(Items.OAK_LOG, 5));

			Task task = new Task(avatar.agentId(), owner.getUuid(),
				ContainerExecutors.Deposit.TYPE, TaskPriority.P3_USER_TASK,
				"deposit ores", null,
				ContainerExecutors.avatarHoldsAtMost(rt.runtimeServicesForTest(),
					avatar.agentId(), ContainerExecutors.itemFilter(null, "ORES"),
					"ores", 0),
				500L, RetryPolicy.DEFAULT, true, "c3",
				java.util.Map.of("x", context.getAbsolutePos(chestRel).getX(),
					"y", context.getAbsolutePos(chestRel).getY(),
					"z", context.getAbsolutePos(chestRel).getZ(),
					ContainerExecutors.PARAM_FILTER, "ORES"));
			rt.scheduler().submit(task, world.getTime());
		});

		context.runAtTick(200, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			Inventory chest = chestAt(context, chestRel);
			int inChest = ContainerAccess.count(chest,
				s -> s.getItem() == Items.IRON_ORE);
			int carried = avatar.items().countOf(new Identifier("minecraft:iron_ore"));
			context.assertTrue(inChest == 12,
				"all 12 ores really reached the chest, found " + inChest);
			context.assertTrue(carried == 0, "and left the backpack, still carrying "
				+ carried);
			context.assertTrue(inChest + carried == 12, "item conservation across the move");
			context.assertTrue(avatar.items().countOf(new Identifier("minecraft:oak_log")) == 5,
				"the ORES filter must not sweep unrelated items into the chest");
			rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** C3：整理箱子必须合并堆叠、保留 NBT、并且一件物品都不能少。 */
	@GameTest(templateName = FLOOR)
	public void sortMergesStacksWithoutLosingAnything(TestContext context) {
		BlockPos chestRel = new BlockPos(3, 2, 3);
		context.setBlockState(chestRel, Blocks.CHEST);

		context.runAtTick(2, () -> {
			Inventory chest = chestAt(context, chestRel);
			chest.setStack(0, new ItemStack(Items.COBBLESTONE, 10));
			chest.setStack(3, new ItemStack(Items.DIAMOND, 2));
			chest.setStack(5, new ItemStack(Items.COBBLESTONE, 20));
			ItemStack named = new ItemStack(Items.COBBLESTONE, 4);
			named.setCustomName(net.minecraft.text.Text.literal("keepsake"));
			chest.setStack(7, named);

			var before = ContainerAccess.contents(chest);
			String error = ContainerAccess.sort(chest);
			context.assertTrue(error == null, "sorting a chest must succeed, got " + error);

			var after = ContainerAccess.contents(chest);
			context.assertTrue(after.equals(before),
				"sorting must conserve every item: " + before + " -> " + after);
			int occupied = chest.size() - ContainerAccess.freeSlots(chest);
			context.assertTrue(occupied == 3,
				"plain cobble merges into one stack; the named one stays separate, slots="
					+ occupied);
			boolean keepsakeIntact = false;
			for (int i = 0; i < chest.size(); i++) {
				ItemStack stack = chest.getStack(i);
				if (!stack.isEmpty() && stack.getName().getString().equals("keepsake")) {
					keepsakeIntact = stack.getCount() == 4;
				}
			}
			context.assertTrue(keepsakeIntact,
				"a named stack keeps its NBT and count through sorting");
			context.complete();
		});
	}

	/** C3：容器解析必须 fail-closed —— 不是容器、够不到、区块没加载都给结构化错误。 */
	@GameTest(templateName = FLOOR)
	public void containerResolutionFailsClosedOnBadTargets(TestContext context) {
		ServerWorld world = context.getWorld();
		BlockPos stoneAbs = context.getAbsolutePos(new BlockPos(2, 2, 2));
		context.setBlockState(new BlockPos(2, 2, 2), Blocks.STONE);
		BlockPos chestAbs = context.getAbsolutePos(new BlockPos(6, 2, 6));
		context.setBlockState(new BlockPos(6, 2, 6), Blocks.CHEST);

		context.runAtTick(2, () -> {
			var notContainer = ContainerAccess.resolve(world, stoneAbs,
				Vec3d.ofCenter(stoneAbs), null,
				dev.squire.server.world.ProtectionAdapter.ALLOW_ALL, false);
			context.assertTrue(!notContainer.ok()
					&& "NOT_A_CONTAINER".equals(notContainer.errorCode()),
				"a stone block is not a container: " + notContainer.errorCode());

			var tooFar = ContainerAccess.resolve(world, chestAbs,
				Vec3d.ofCenter(chestAbs.add(40, 0, 0)), null,
				dev.squire.server.world.ProtectionAdapter.ALLOW_ALL, true);
			context.assertTrue(!tooFar.ok() && "UNREACHABLE".equals(tooFar.errorCode()),
				"out-of-range containers are refused: " + tooFar.errorCode());

			var denied = ContainerAccess.resolve(world, chestAbs,
				Vec3d.ofCenter(chestAbs), null, denyAll(), true);
			context.assertTrue(!denied.ok() && "PROTECTED_REGION".equals(denied.errorCode()),
				"protection refusals are structured: " + denied.errorCode());

			var ok = ContainerAccess.resolve(world, chestAbs, Vec3d.ofCenter(chestAbs),
				null, dev.squire.server.world.ProtectionAdapter.ALLOW_ALL, true);
			context.assertTrue(ok.ok(), "a reachable, unprotected chest resolves");
			context.complete();
		});
	}

	private static dev.squire.server.world.ProtectionAdapter denyAll() {
		return new dev.squire.server.world.ProtectionAdapter() {
			@Override
			public PermissionDecision canBreak(ServerWorld w, BlockPos p, UUID a) {
				return PermissionDecision.deny("test protection");
			}

			@Override
			public PermissionDecision canPlace(ServerWorld w, BlockPos p, UUID a) {
				return PermissionDecision.deny("test protection");
			}

			@Override
			public PermissionDecision canInteract(ServerWorld w, BlockPos p, UUID a) {
				return PermissionDecision.deny("test protection");
			}

			@Override
			public PermissionDecision canEditRegion(ServerWorld w,
					dev.squire.server.world.BoundedRegion r, UUID a) {
				return PermissionDecision.deny("test protection");
			}
		};
	}

	// ============================================================ D1：长期 Guard

	/**
	 * B05/D1：“保护我”创建的是长期策略而不是一次性任务，本地战斗运行时消灭威胁，
	 * 且策略会被写进档案，重启后由 GuardRuntime.load() 重新装载。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 900, batchId = "squire-guard-d1")
	public void protectMeCreatesADurableGuardPolicyAndFights(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "d1-guard-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			TestSupport.clearHostilesNear(world, context.getAbsolutePos(
				new BlockPos(4, 2, 4)), 16);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 5))));
			avatar.items().insert(new ItemStack(Items.DIAMOND_SWORD));
			var routed = InputGateway.acceptChat(owner, "保护我").orElseThrow();
			context.assertTrue(routed.success(), "FastPath guard: " + routed.message());
			var policy = rt.guards().policyOf(avatar.agentId()).orElseThrow();
			context.assertTrue(policy.enabled(), "the policy is live");
			context.assertTrue(rt.agentStore().recordOfAgent(avatar.agentId()).orElseThrow()
					.persistentPolicies.stream().anyMatch(p -> p.startsWith("guard:")),
				"the policy is persisted on the AgentRecord, not just in memory");
		});

		context.runAtTick(20, () -> {
			var zombie = net.minecraft.entity.EntityType.ZOMBIE.create(world);
			zombie.refreshPositionAndAngles(Vec3d.ofBottomCenter(
				context.getAbsolutePos(new BlockPos(6, 2, 4))).x,
				Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(6, 2, 4))).y,
				Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(6, 2, 4))).z,
				0.0f, 0.0f);
			zombie.setPersistent();
			world.spawnEntity(zombie);
		});

		AtomicBoolean done = new AtomicBoolean(false);
		context.runAtTick(40, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(!avatar.items().equipped(EquipmentSlot.MAINHAND).isEmpty(),
				"the guard equips a real weapon out of its own backpack");
			context.assertTrue(avatar.items().countOf(
					new Identifier("minecraft:diamond_sword")) == 0,
				"equipping moved the sword, it was not duplicated");
		});

		context.runAtTick(300, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			context.assertTrue(owner.isAlive(), "the owner survives");
			// “停止保护”必须真的解除，并且落盘为 disabled
			var stopped = InputGateway.acceptChat(owner, "停止保护").orElseThrow();
			context.assertTrue(stopped.success(), "guard stop: " + stopped.message());
			context.assertTrue(rt.guards().policyOf(avatar.agentId()).isEmpty(),
				"the policy is gone after an explicit stop");
			boolean persistedOff = rt.agentStore().recordOfAgent(avatar.agentId())
				.orElseThrow().persistentPolicies.stream()
				.anyMatch(p -> p.startsWith("guard:false"));
			context.assertTrue(persistedOff,
				"the disabled state is persisted so a restart cannot resurrect it");
			done.set(true);
			TestSupport.clearHostilesNear(world, context.getAbsolutePos(
				new BlockPos(4, 2, 4)), 16);
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(870, () -> {
			if (!done.get()) {
				context.throwGameTestException("guard policy test never reached its checks");
			}
		});
	}

	// ============================================================ D2：真实玩家救助

	/**
	 * B06：“我快死了”伤的是 OWNER。救助必须消耗 Avatar 背包里的真实物品，并让
	 * 玩家的状态真的变好——绝不是给伙伴自己回血。
	 */
	@GameTest(templateName = FLOOR, tickLimit = 900, batchId = "squire-aid-d2")
	public void aidOwnerHealsThePlayerWithRealItems(TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "d2-aid-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			world.spawnEntity(owner);
			TestSupport.clearHostilesNear(world, context.getAbsolutePos(
				new BlockPos(4, 2, 4)), 16);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 5))));
			avatar.items().insert(new ItemStack(Items.GOLDEN_APPLE, 3));
			// FakePlayer ignores damage sources, so set the wound directly — the point
			// of B06 is WHO gets healed, not how the player got hurt.
			owner.setHealth(6.0f);
			context.assertTrue(owner.getHealth() <= 7.0f,
				"the OWNER is the one who is hurt, hp=" + owner.getHealth());
			var routed = InputGateway.acceptChat(owner, "我快死了").orElseThrow();
			context.assertTrue(routed.success(), "FastPath aid: " + routed.message());
		});

		AtomicBoolean checked = new AtomicBoolean(false);
		context.runAtTick(120, () -> {
			AvatarEntity avatar = rt.resolveAvatarFor(owner.getUuid()).orElseThrow();
			int left = avatar.items().countOf(new Identifier("minecraft:golden_apple"));
			context.assertTrue(left < 3,
				"a real healing item was consumed from the agent's backpack, left=" + left);
			boolean improved = owner.hasStatusEffect(
					net.minecraft.entity.effect.StatusEffects.REGENERATION)
				|| owner.hasStatusEffect(
					net.minecraft.entity.effect.StatusEffects.ABSORPTION)
				|| owner.getHealth() > 7.0f;
			context.assertTrue(improved,
				"the OWNER's state improved, hp=" + owner.getHealth());
			context.assertTrue(avatar.getHealth() >= avatar.getMaxHealth() - 0.01f,
				"the agent never healed itself instead of the player");
			checked.set(true);
			rt.scheduler().cancelAgent(avatar.agentId(), "TEST_DONE");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
		context.runAtTick(870, () -> {
			if (!checked.get()) {
				context.throwGameTestException("owner aid never took effect");
			}
		});
	}

	/** D2：背包里没有治疗物品时必须如实报错，绝不凭空生成一个金苹果。 */
	@GameTest(templateName = FLOOR)
	public void aidOwnerRefusesWhenNoHealingItemIsCarried(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "d2-noitem-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 8));
			var routed = InputGateway.acceptChat(owner, "我快死了").orElseThrow();
			context.assertTrue(!routed.success(),
				"no healing item must be an honest refusal, got: " + routed.message());
			context.assertTrue("INSUFFICIENT_HEALING_ITEM".equals(routed.errorCode()),
				"the refusal carries the structured reason: " + routed.errorCode());
			context.assertTrue(avatar.items().countOf(
					new Identifier("minecraft:golden_apple")) == 0,
				"nothing was conjured into the backpack");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// ============================================================ E：长期位置记忆

	/**
	 * B11：“上次矿洞在哪”返回最近访问过的矿洞，维度和坐标都必须正确——两个不同
	 * 维度的矿洞不能混为一谈。
	 */
	@GameTest(templateName = FLOOR)
	public void lastMineResolvesToTheMostRecentlyVisitedOneWithItsDimension(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "e1-mine-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			LocationMemoryStore store = rt.locations();
			UUID agentId = rt.summonFor(owner).agentId();

			GlobalPos overworldMine = GlobalPos.create(net.minecraft.world.World.OVERWORLD,
				new BlockPos(100, 40, 200));
			GlobalPos netherMine = GlobalPos.create(net.minecraft.world.World.NETHER,
				new BlockPos(-12, 55, 8));
			var older = LocationMemory.explicit(owner.getUuid(), agentId,
				LocationMemory.Type.MINE, "旧矿洞", overworldMine, 100L);
			var newer = LocationMemory.explicit(owner.getUuid(), agentId,
				LocationMemory.Type.MINE, "下界矿洞", netherMine, 200L);
			store.remember(older);
			store.remember(newer);
			store.update(older.visited(100L));
			store.update(newer.visited(500L));

			var resolved = store.resolve(owner.getUuid(), "上次矿洞");
			context.assertTrue(resolved.unique(),
				"'the last mine' resolves to exactly one place");
			context.assertTrue(resolved.best().dimensionId().equals("minecraft:the_nether"),
				"the dimension travels with the memory, got "
					+ resolved.best().dimensionId());
			context.assertTrue(resolved.best().pos().getPos().equals(
					new BlockPos(-12, 55, 8)),
				"and so do the exact coordinates");

			// 按名字问则各自命中，不受 lastVisitedAt 影响
			context.assertTrue(rt.locations().resolve(owner.getUuid(), "旧矿洞")
					.best().dimensionId().equals("minecraft:overworld"),
				"an explicit name still finds the older mine");

			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** E1：位置记忆跨存档往返；同名多处时必须给候选而不是静默选一个。 */
	@GameTest(templateName = FLOOR)
	public void locationMemorySurvivesNbtRoundTripAndReportsAmbiguity(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "e1-nbt-owner");

		context.runAtTick(5, () -> {
			LocationMemoryStore store = rt.locations();
			GlobalPos a = GlobalPos.create(net.minecraft.world.World.OVERWORLD,
				new BlockPos(1, 64, 1));
			GlobalPos b = GlobalPos.create(net.minecraft.world.World.OVERWORLD,
				new BlockPos(90, 64, 90));
			store.remember(LocationMemory.explicit(owner.getUuid(), null,
				LocationMemory.Type.WAREHOUSE, "北仓库", a, 1L));
			store.remember(LocationMemory.explicit(owner.getUuid(), null,
				LocationMemory.Type.WAREHOUSE, "南仓库", b, 1L));

			NbtCompound nbt = store.writeNbt(new NbtCompound());
			LocationMemoryStore revived = LocationMemoryStore.createFromNbtPublic(nbt);
			context.assertTrue(revived.ofOwner(owner.getUuid()).size() >= 2,
				"both warehouses survive the save/load round trip");

			var ambiguous = revived.resolve(owner.getUuid(), "仓库");
			context.assertTrue(ambiguous.ambiguous(),
				"two equally trusted warehouses must be reported as a choice, not guessed");
			var byName = revived.resolve(owner.getUuid(), "南仓库");
			context.assertTrue(byName.unique() && byName.best().pos().getPos()
					.equals(new BlockPos(90, 64, 90)),
				"an exact name still resolves uniquely");
			context.complete();
		});
	}

	/**
	 * Container logistics stays available internally for old tasks, but is no longer
	 * started by companion chat after the physical-behaviour scope reduction.
	 */
	@GameTest(templateName = FLOOR, tickLimit = 600, batchId = "squire-warehouse-e3")
	public void storeOresInWarehouseMovesRealItemsIntoTheRememberedChest(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		ServerWorld world = context.getWorld();
		FakePlayer owner = fakeOwner(world, "e3-warehouse-owner");
		BlockPos chestRel = new BlockPos(5, 2, 4);

		context.runAtTick(5, () -> {
			place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
			world.spawnEntity(owner);
			context.setBlockState(chestRel, Blocks.CHEST);
			AvatarEntity avatar = rt.summonFor(owner);
			place(avatar, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));
			avatar.setStayMode();
			avatar.items().insert(new ItemStack(Items.RAW_IRON, 9));
			avatar.items().insert(new ItemStack(Items.COBBLESTONE, 6));
			var remembered = InputGateway.acceptChat(owner, "这里是仓库").orElseThrow();
			context.assertTrue(remembered.success(),
				"the warehouse must bind a real container: " + remembered.message());
			var stored = InputGateway.acceptChat(owner, "把矿放回仓库");
			context.assertTrue(stored.isEmpty(),
				"container transport must not match a physical FastPath");
			Inventory chest = chestAt(context, chestRel);
			int inChest = ContainerAccess.count(chest, s -> s.getItem() == Items.RAW_IRON);
			context.assertTrue(inChest == 0,
				"the removed chat route must not mutate the chest");
			context.assertTrue(avatar.items().countOf(
					new Identifier("minecraft:raw_iron")) == 9,
				"the avatar inventory remains untouched");
			context.assertTrue(avatar.items().countOf(
					new Identifier("minecraft:cobblestone")) == 6,
				"non-ore items stay with the agent");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// ============================================================ 面板：整理背包

	/**
	 * 物品页的「整理背包」：合并同类、归拢到前面，<b>装备槽一动不动</b>。
	 *
	 * <p>这件事我们自己做，是因为「一键背包整理 Next」会把伙伴这一侧的 6 个装备槽
	 * 和 36 格背包当成一个大箱子一起排序（见
	 * {@code dev.squire.client.gui.IpnCompat}）。测试走的是<b>按钮那条路</b>而不是直接
	 * 调 {@code sort()}——按钮上写着什么，点下去就得做什么。</p>
	 */
	@GameTest(templateName = FLOOR)
	public void sortingTheBackpackMergesStacksAndNeverTouchesEquipment(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "sort-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			AvatarInventory items = avatar.items();
			for (int i = 0; i < items.size(); i++) {
				items.setStack(i, ItemStack.EMPTY);
			}

			// 装备槽里放一顶头盔：整理不许碰它。
			items.setEquipped(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));

			// 散落的同类，中间隔着空格：90 个圆石 + 17 支火把 + 两把镐。
			items.setStack(0, new ItemStack(Items.COBBLESTONE, 30));
			items.setStack(3, new ItemStack(Items.TORCH, 5));
			items.setStack(7, new ItemStack(Items.COBBLESTONE, 20));
			items.setStack(9, new ItemStack(Items.TORCH, 12));
			items.setStack(15, new ItemStack(Items.COBBLESTONE, 40));
			ItemStack enchanted = new ItemStack(Items.IRON_PICKAXE);
			enchanted.addEnchantment(Enchantments.EFFICIENCY, 3);
			items.setStack(20, enchanted);
			items.setStack(21, new ItemStack(Items.IRON_PICKAXE));

			var action = dev.squire.server.gui.SquireActions.byId(
				dev.squire.server.gui.SquireScreenHandler.BUTTON_SORT_ITEMS);
			context.assertTrue(action != null, "面板上必须有「整理背包」这个按钮");
			action.handler().run(owner, avatar);

			context.assertTrue(
				items.countOf(new Identifier("minecraft:cobblestone")) == 90,
				"整理不许凭空多出或吃掉东西：圆石="
					+ items.countOf(new Identifier("minecraft:cobblestone")));
			context.assertTrue(items.countOf(new Identifier("minecraft:torch")) == 17,
				"火把也一样：" + items.countOf(new Identifier("minecraft:torch")));

			// 90 圆石 = 64 + 26 两叠，17 火把一叠，两把镐各一格 —— 5 格，且连续。
			context.assertTrue(items.occupiedSlots() == 5,
				"合并后应该只剩 5 格，实际=" + items.occupiedSlots());
			context.assertTrue(items.getStack(0).getItem() == Items.COBBLESTONE
					&& items.getStack(0).getCount() == 64,
				"最满的一叠圆石排在最前：" + items.getStack(0));
			context.assertTrue(items.getStack(1).getItem() == Items.COBBLESTONE
					&& items.getStack(1).getCount() == 26,
				"同类必须挨在一起：" + items.getStack(1));
			context.assertTrue(items.getStack(4).getItem() == Items.TORCH,
				"按物品 id 排，火把在铁镐之后：" + items.getStack(4));
			context.assertTrue(items.getStack(5).isEmpty(),
				"第 6 格开始必须是空的：" + items.getStack(5));

			// 附魔镐没有被并进普通镐（canCombine 认 NBT）。
			int enchantedLeft = items.countMatching(stack ->
				stack.getItem() == Items.IRON_PICKAXE && stack.hasEnchantments());
			context.assertTrue(enchantedLeft == 1,
				"附魔镐不许被并成普通镐，剩余=" + enchantedLeft);

			context.assertTrue(avatar.getEquippedStack(EquipmentSlot.HEAD).getItem()
					== Items.IRON_HELMET, "整理背包不许动装备槽");
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	// ============================================================ 背上的背包（外挂容量）

	/**
	 * 一个假背包：把某件物品映射到一个真实的按格子分的存储。
	 *
	 * <p>真实实现要靠「精妙背包」注册在 porting_lib 上的物品存储，而那个模组不在开发
	 * 环境里——{@code BackpackCompat} 之所以把查找表抽成一个接口，就是为了让溢出、
	 * 清点、取料这些<b>我们自己的逻辑</b>能在这里被完整跑一遍。真正没被自动化测试
	 * 覆盖的只剩「反射读到那张表」这一步。</p>
	 */
	private static final class FakeBackpack implements BackpackCompat.Lookup {
		private final net.minecraft.item.Item marker;
		private final SimpleInventory contents;
		private final InventoryStorage storage;

		FakeBackpack(net.minecraft.item.Item marker, int slots) {
			this.marker = marker;
			this.contents = new SimpleInventory(slots);
			this.storage = InventoryStorage.of(contents, null);
		}

		@Override
		public Storage<ItemVariant> find(ItemStack stack, ContainerItemContext context) {
			return stack.getItem() == marker ? storage : null;
		}
	}

	/**
	 * 背上背包之后，他的载重<b>真的</b>变大：装满 36 格还能继续往里收。
	 *
	 * <p>四件事一起验，因为少任何一件玩家都会看到"背了等于没背"：溢出进得去、
	 * 清点算得上、取料掏得出、装备用得到。</p>
	 */
	@GameTest(templateName = FLOOR)
	public void aWornBackpackAddsRealCarryingCapacity(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "backpack-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			BackpackCompat.useLookupForTesting(new FakeBackpack(Items.CHEST, 9));
			try {
				AvatarInventory items = avatar.items();
				for (int i = 0; i < items.size(); i++) {
					items.setStack(i, new ItemStack(Items.COBBLESTONE, 64));
				}
				context.assertTrue(items.freeSlots() == 0, "先把 36 格塞满");

				// 没背包时：装不下就是装不下。
				ItemStack refused = items.insert(new ItemStack(Items.DIAMOND, 5));
				context.assertTrue(refused.getCount() == 5,
					"没背包的时候满了就该原样退回来，退回=" + refused.getCount());

				avatar.setBackpackStack(new ItemStack(Items.CHEST));
				context.assertTrue(items.backpackSize() == 9,
					"背上之后应该多出 9 格，实际=" + items.backpackSize());

				// 溢出：主背包满了，东西进背包。
				ItemStack left = items.insert(new ItemStack(Items.DIAMOND, 5));
				context.assertTrue(left.isEmpty(),
					"背包该把这 5 颗钻石接住，剩余=" + left);
				context.assertTrue(items.countOf(new Identifier("minecraft:diamond")) == 5,
					"清点必须算上背包里的东西，实际="
						+ items.countOf(new Identifier("minecraft:diamond")));

				// 预检也要算上背包剩下的空间。
				context.assertTrue(
					items.insertableAmount(new ItemStack(Items.REDSTONE, 64)) == 64,
					"容量预检漏掉了背包里的空格");

				// 取料：主背包没有的东西，从背包里掏。
				var taken = items.extractMatching(
					stack -> stack.getItem() == Items.DIAMOND, 3);
				int got = taken.stream().mapToInt(ItemStack::getCount).sum();
				context.assertTrue(got == 3, "该从背包里掏出 3 颗，实际=" + got);
				context.assertTrue(items.countOf(new Identifier("minecraft:diamond")) == 2,
					"掏完之后背包里应该只剩 2 颗");

				// 装备/使用这条路只认主背包的下标，所以要能把东西先取回来。
				items.setStack(0, ItemStack.EMPTY);
				int pulled = items.findSlotOf(new Identifier("minecraft:diamond"));
				context.assertTrue(pulled >= 0,
					"背包里背着的东西必须找得到（会先取回主背包）");
				context.assertTrue(items.getStack(pulled).getItem() == Items.DIAMOND,
					"取回来的应该就是那件东西");
				context.assertTrue(items.countOf(new Identifier("minecraft:diamond")) == 2,
					"取回主背包不是变出新的：总数还是 2");
			} finally {
				BackpackCompat.useLookupForTesting(null);
			}
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/**
	 * 背包槽只收背包，而且它的存在不能把 shift+点击的分区搞错。
	 *
	 * <p>那一格是插在装备和伙伴背包之间的，所有按下标算的边界都得跟着挪一格——
	 * 算错的话玩家 shift+点一下石头，石头会消失在某个奇怪的地方。</p>
	 */
	@GameTest(templateName = FLOOR)
	public void theBackpackSlotOnlyTakesBackpacksAndKeepsShiftClickRangesRight(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "backpack-slot-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			BackpackCompat.useLookupForTesting(new FakeBackpack(Items.CHEST, 9));
			try {
				var handler = new dev.squire.server.gui.SquireScreenHandler(1,
					owner.getInventory(), avatar.items().mainInventory(),
					new dev.squire.server.gui.AvatarEquipmentInventory(avatar,
						dev.squire.server.gui.SquireScreenHandler.EQUIPMENT_ORDER),
					avatar.backpackSlotInventory(), avatar);

				var slot = handler.slots.get(
					dev.squire.server.gui.SquireScreenHandler.BACKPACK_SLOT_INDEX);
				context.assertTrue(slot.canInsert(new ItemStack(Items.CHEST)),
					"背包槽必须收得下背包");
				context.assertFalse(slot.canInsert(new ItemStack(Items.STONE)),
					"背包槽不许变成第二个杂物格");
				context.assertFalse(
					slot.canInsert(new ItemStack(Items.NETHERITE_CHESTPLATE)),
					"胸甲有它自己的格子");

				// shift+点击：玩家背包里的石头必须落进伙伴的 36 格，而不是背包槽。
				int playerFirst = dev.squire.server.gui.SquireScreenHandler
					.AVATAR_GRID_START + AvatarEntity.MAIN_INVENTORY_SIZE;
				handler.slots.get(playerFirst).setStack(new ItemStack(Items.STONE, 7));
				handler.quickMove(owner, playerFirst);
				context.assertTrue(avatar.items().countOf(
						new Identifier("minecraft:stone")) == 7,
					"shift+点击应该把石头交给伙伴，实际="
						+ avatar.items().countOf(new Identifier("minecraft:stone")));
				context.assertTrue(avatar.backpackStack().isEmpty(),
					"石头不该被塞进背包槽");
			} finally {
				BackpackCompat.useLookupForTesting(null);
			}
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/**
	 * 面板里看得见背囊里的东西，也拿得出来。
	 *
	 * <p>「背了却看不见里面有什么」等于背包是个黑箱。这条走的是<b>真实的槽位</b>：
	 * 背囊那 36 格和伙伴自己的网格重合，同一时刻只亮一套，shift+点击要能把东西
	 * 从背囊搬到玩家背包，而且总数一个不多一个不少。</p>
	 */
	@GameTest(templateName = FLOOR)
	public void theBackpackContentsAreVisibleAndCanBeTakenOutFromThePanel(
			TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "backpack-view-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity avatar = rt.summonFor(owner);
			BackpackCompat.useLookupForTesting(new FakeBackpack(Items.CHEST, 9));
			try {
				AvatarInventory items = avatar.items();
				for (int i = 0; i < items.size(); i++) {
					items.setStack(i, ItemStack.EMPTY);
				}
				avatar.setBackpackStack(new ItemStack(Items.CHEST));
				var view = items.backpack();
				context.assertTrue(view != null, "假背包应该打得开");
				context.assertTrue(view.insert(new ItemStack(Items.EMERALD, 12)).isEmpty(),
					"先往背囊里放 12 颗绿宝石");

				var handler = new dev.squire.server.gui.SquireScreenHandler(1,
					owner.getInventory(), items.mainInventory(),
					new dev.squire.server.gui.AvatarEquipmentInventory(avatar,
						dev.squire.server.gui.SquireScreenHandler.EQUIPMENT_ORDER),
					avatar.backpackSlotInventory(), avatar);

				int first = dev.squire.server.gui.SquireScreenHandler
					.BACKPACK_CONTENTS_START;
				ItemStack shown = handler.slots.get(first).getStack();
				context.assertTrue(shown.getItem() == Items.EMERALD
						&& shown.getCount() == 12,
					"面板第一格应该就是那 12 颗绿宝石，实际=" + shown);

				// shift+点击：背囊 → 玩家背包，总数守恒。
				owner.getInventory().clear();
				handler.quickMove(owner, first);
				int inPlayer = 0;
				for (int i = 0; i < owner.getInventory().size(); i++) {
					ItemStack stack = owner.getInventory().getStack(i);
					if (stack.getItem() == Items.EMERALD) {
						inPlayer += stack.getCount();
					}
				}
				context.assertTrue(inPlayer == 12,
					"12 颗应该整叠到玩家背包，实际=" + inPlayer);
				context.assertTrue(
					items.countOf(new Identifier("minecraft:emerald")) == 0,
					"搬走之后背囊里不该还留着一份——那就是凭空变出 12 颗");
			} finally {
				BackpackCompat.useLookupForTesting(null);
			}
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	/** 重新召唤会丢掉旧身体：背包必须跟着档案回来，否则等于连包带货一起没收。 */
	@GameTest(templateName = FLOOR)
	public void theWornBackpackSurvivesAResummon(TestContext context) {
		SquireRuntime rt = runtime(context);
		FakePlayer owner = fakeOwner(context.getWorld(), "backpack-resummon-owner");
		place(owner, Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(4, 2, 4))));

		context.runAtTick(5, () -> {
			context.getWorld().spawnEntity(owner);
			AvatarEntity first = rt.summonFor(owner);
			first.setBackpackStack(new ItemStack(Items.CHEST));
			rt.persistSnapshot(first);

			AvatarEntity second = rt.summonFor(owner);
			context.assertTrue(second.backpackStack().getItem() == Items.CHEST,
				"重新召唤之后背包应该还背在身上，实际=" + second.backpackStack());
			rt.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}
}
