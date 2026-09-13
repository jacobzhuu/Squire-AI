package dev.squire.gametest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.item.BellTier;
import dev.squire.server.item.BellUpgradeRecipe;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.registry.SquireItems;
import dev.squire.server.runtime.AutonomyController;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Regression coverage for configurable death revival and proactive cooperative hunting. */
public final class M27RecallBellAndHuntGameTests implements FabricGameTest {

	private static final String FLOOR = M0SpikeGameTests.FLOOR;

	private static final ScreenHandler NOOP_HANDLER = new ScreenHandler(null, 0) {
		@Override public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity player,
				int slot) { return ItemStack.EMPTY; }
		@Override public boolean canUse(net.minecraft.entity.player.PlayerEntity player) {
			return true;
		}
	};

	private static FakePlayer owner(ServerWorld world, String name) {
		return FakePlayer.get(world, new GameProfile(
			UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
	}

	private static SquireRuntime runtime(TestContext context) {
		SquireRuntime.ensureInitialized(context.getWorld().getServer());
		return SquireRuntime.get();
	}

	private static AvatarEntity summon(TestContext context, SquireRuntime runtime,
			FakePlayer owner, BlockPos relative) {
		if (!owner.isAlive()) context.getWorld().spawnEntity(owner);
		BlockPos pos = context.getAbsolutePos(relative);
		owner.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
			0.0f, 0.0f);
		AvatarEntity avatar = runtime.summonFor(owner);
		avatar.refreshPositionAndAngles(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5,
			0.0f, 0.0f);
		return avatar;
	}

	@GameTest(templateName = FLOOR, tickLimit = 120,
		batchId = "squire-recall-death-cooldown")
	public void deathCooldownIsIdentityBoundAndRevivalPreservesEveryResource(
			TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context.getWorld(), "death-cooldown-owner");
		context.runAtTick(5, () -> {
			AvatarEntity original = summon(context, runtime, owner, new BlockPos(3, 2, 3));
			original.insertStack(new ItemStack(Items.ARROW, 7));
			original.insertStack(new ItemStack(Items.BREAD, 3));
			original.insertStack(new ItemStack(Items.POTION, 2));
			ItemStack chestplate = new ItemStack(Items.IRON_CHESTPLATE);
			chestplate.setDamage(137);
			original.equipStack(EquipmentSlot.CHEST, chestplate);
			UUID agentId = original.agentId();
			original.setHealth(0.0f);
			SquireRuntime.onAvatarDeath(original);
			original.discard();

			var record = runtime.agentStore().recordOfAgent(agentId).orElseThrow();
			context.assertTrue(record.deathPending, "death must persist a dedicated gate");
			context.assertTrue(record.reviveAvailableTick - record.deathTick == 24_000L,
				"common bell cooldown is 20 in-world minutes");

			ItemStack swapped = SquireItems.boundRecallBell(owner.getUuid(), agentId);
			SquireItems.bind(swapped, owner.getUuid(), agentId, BellTier.ROYAL);
			context.assertTrue(!runtime.recallWithBell(owner, swapped).success(),
				"a different physical bell cannot bypass the identity cooldown");
			context.assertTrue(SquireItems.tierOf(swapped) == BellTier.COMMON,
				"server rewrites forged/stale item quality to the identity quality");

			record.reviveAvailableTick = runtime.tickNow();
			context.assertTrue(runtime.recallWithBell(owner, swapped).success(),
				"revival succeeds once the identity deadline is reached");
			AvatarEntity revived = runtime.agents().resolveForOwnerNow(owner.getUuid())
				.orElseThrow();
			context.assertTrue(Math.abs(revived.getHealth() - revived.getMaxHealth() * 0.20f)
					< 0.01f, "untrained common-bell revival returns at 20% Max HP");
			context.assertTrue(revived.inventory().countOf("minecraft:arrow") == 7,
				"arrows are preserved, never replenished");
			context.assertTrue(revived.inventory().countOf("minecraft:bread") == 3,
				"food count is unchanged");
			context.assertTrue(revived.inventory().countOf("minecraft:potion") == 2,
				"potion count is unchanged");
			context.assertTrue(revived.getEquippedStack(EquipmentSlot.CHEST).getDamage() == 137,
				"equipment durability is not repaired");
			context.assertTrue(!record.deathPending, "successful revival clears the gate");
			runtime.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 120,
		batchId = "squire-recall-royal-shelter")
	public void royalLevelTenRevivalCapsHealthAndAppliesOnlyDefensiveShelter(
			TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context.getWorld(), "royal-shelter-owner");
		context.runAtTick(5, () -> {
			AvatarEntity original = summon(context, runtime, owner, new BlockPos(3, 2, 3));
			var record = runtime.agentStore().recordOfAgent(original.agentId()).orElseThrow();
			record.bellTier = BellTier.ROYAL.id();
			record.profile.profession.setProfession(SquireProfession.GUARD);
			record.profile.profession.level = 10;
			original.setHealth(0.0f);
			SquireRuntime.onAvatarDeath(original);
			original.discard();
			record.reviveAvailableTick = runtime.tickNow();

			ItemStack bell = SquireItems.boundRecallBell(owner.getUuid(), record.agentId);
			context.assertTrue(runtime.recallWithBell(owner, bell).success(),
				"eligible royal revival succeeds");
			AvatarEntity revived = runtime.agents().resolveForOwnerNow(owner.getUuid())
				.orElseThrow();
			context.assertTrue(Math.abs(revived.getHealth() - revived.getMaxHealth() * 0.60f)
					< 0.01f, "royal + Lv10 is capped at 60% Max HP");
			context.assertTrue(revived.hasStatusEffect(StatusEffects.RESISTANCE),
				"royal shelter grants Resistance");
			context.assertTrue(revived.hasStatusEffect(StatusEffects.REGENERATION),
				"royal shelter grants Regeneration");
			context.assertTrue(revived.hasStatusEffect(StatusEffects.ABSORPTION),
				"royal shelter grants Absorption");
			context.assertTrue(!revived.hasStatusEffect(StatusEffects.STRENGTH),
				"recall shelter never adds attack power");
			runtime.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 100,
		batchId = "squire-recall-bell-upgrade")
	public void workbenchUpgradeConsumesTheDefinedShapeAndPreservesBinding(
			TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context.getWorld(), "bell-upgrade-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, runtime, owner, new BlockPos(3, 2, 3));
			ItemStack bell = SquireItems.boundRecallBell(owner.getUuid(), avatar.agentId());
			CraftingInventory grid = new CraftingInventory(NOOP_HANDLER, 3, 3);
			grid.setStack(0, bell);
			for (int slot = 1; slot <= 4; slot++) grid.setStack(slot,
				new ItemStack(Items.AMETHYST_SHARD));
			for (int slot = 5; slot <= 8; slot++) grid.setStack(slot,
				new ItemStack(Items.GOLD_INGOT));
			BellUpgradeRecipe recipe = new BellUpgradeRecipe(
				new Identifier("squire", "bell_upgrade_test"), CraftingRecipeCategory.MISC);
			context.assertTrue(recipe.matches(grid, context.getWorld()),
				"common -> enhanced requires exactly 4 amethyst and 4 gold slots");
			ItemStack output = recipe.craft(grid, context.getWorld().getRegistryManager());
			context.assertTrue(output.getCount() == 1 && output.isOf(SquireItems.RECALL_BELL),
				"upgrade transforms the existing bell instead of generating a new item type");
			SquireItems.RECALL_BELL.onCraft(output, context.getWorld(), owner);
			context.assertTrue(output.getNbt().getUuid(SquireItems.NBT_OWNER)
				.equals(owner.getUuid()), "owner binding survives crafting");
			context.assertTrue(output.getNbt().getUuid(SquireItems.NBT_AGENT)
				.equals(avatar.agentId()), "agent binding survives crafting");
			context.assertTrue(SquireItems.tierOf(output) == BellTier.ENHANCED,
				"crafted bell advances exactly one tier");
			context.assertTrue(BellTier.byId(runtime.agentStore().recordOfAgent(
				avatar.agentId()).orElseThrow().bellTier) == BellTier.ENHANCED,
				"server commits the same authoritative identity tier");
			runtime.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}

	@GameTest(templateName = FLOOR, tickLimit = 120,
		batchId = "squire-proactive-cooperative-hunt")
	public void proactiveHelpsHuntCowWithoutTargetingFriendsOrBypassingBowGate(
			TestContext context) {
		SquireRuntime runtime = runtime(context);
		FakePlayer owner = owner(context.getWorld(), "cooperative-hunt-owner");
		context.runAtTick(5, () -> {
			AvatarEntity avatar = summon(context, runtime, owner, new BlockPos(3, 2, 3));
			var profile = runtime.profileOf(avatar);
			profile.autonomy = AutonomyLevel.PROACTIVE.id();
			profile.profession.setProfession(SquireProfession.GUARD);
			profile.profession.level = 3;
			avatar.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
			avatar.insertStack(new ItemStack(Items.ARROW, 4));

			CowEntity cow = EntityType.COW.create(context.getWorld());
			context.assertTrue(cow != null, "cow entity exists");
			cow.refreshPositionAndAngles(avatar.getX() + 1.0, avatar.getY(), avatar.getZ(),
				0.0f, 0.0f);
			context.getWorld().spawnEntity(cow);
			float before = cow.getHealth();
			owner.onAttacking(cow);
			runtime.tickCooperativeHuntForTest(owner, avatar);
			context.assertTrue(cow.getHealth() < before,
				"PROACTIVE squire attacks the exact cow the owner struck");
			context.assertTrue(!avatar.isDrawingBow(),
				"Lv3 Guard does not gain bow use through cooperative hunting");

			WolfEntity wolf = EntityType.WOLF.create(context.getWorld());
			context.assertTrue(wolf != null, "wolf entity exists");
			wolf.setOwner(owner);
			wolf.refreshPositionAndAngles(avatar.getX() + 1.0, avatar.getY(),
				avatar.getZ() + 1.0, 0.0f, 0.0f);
			context.getWorld().spawnEntity(wolf);
			context.assertTrue(!AutonomyController.isCooperativeHuntTarget(owner, avatar, wolf),
				"the owner's tamed pet is always excluded");
			context.assertTrue(!AutonomyController.isCooperativeHuntTarget(owner, avatar, owner),
				"players are always excluded");
			cow.discard();
			wolf.discard();
			runtime.executeControl(owner, SquireRuntime.ControlIntent.DISMISS);
			context.complete();
		});
	}
}
