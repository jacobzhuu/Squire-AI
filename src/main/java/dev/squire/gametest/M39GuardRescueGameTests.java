package dev.squire.gametest;

import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.combat.CombatStyle;
import dev.squire.server.combat.GuardRescue;
import dev.squire.server.combat.ProfessionCombatRules;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.profile.AutonomyLevel;
import dev.squire.server.registry.SquireEntities;
import dev.squire.server.registry.SquireItems;
import dev.squire.server.runtime.AutonomyController;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public final class M39GuardRescueGameTests implements FabricGameTest {
    private record Setup(SquireRuntime runtime, net.minecraft.server.network.ServerPlayerEntity owner, AvatarEntity guard) { }

    private Setup setup(TestContext c, int level) {
        SquireRuntime.ensureInitialized(c.getWorld().getServer());
        var runtime = SquireRuntime.get();
        var profile = new GameProfile(UUID.randomUUID(), "rescue-test");
        var owner = new net.minecraft.server.network.ServerPlayerEntity(c.getWorld().getServer(), c.getWorld(), profile);
        // Real server player damage semantics; use Fabric's inert connection only for packets.
        owner.networkHandler = FakePlayer.get(c.getWorld(), profile).networkHandler;
        var pos = c.getAbsolutePos(new BlockPos(3, 2, 3));
        owner.refreshPositionAndAngles(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        owner.setHealth(20);
        owner.getAbilities().invulnerable = false;
        var guard = runtime.summonFor(owner);
        guard.refreshPositionAndAngles(owner.getX() + 1, owner.getY(), owner.getZ(), 0, 0);
        guard.profile().profession.setProfession(SquireProfession.GUARD);
        guard.profile().profession.level = level;
        guard.profile().autonomy = AutonomyLevel.CONSERVATIVE.id();
        return new Setup(runtime, owner, guard);
    }

    private boolean rescue(Setup s, List<AvatarEntity> guards) {
        s.owner.setHealth(0);
        return GuardRescue.rescue(s.owner, s.owner.getDamageSources().generic(), guards);
    }

    private void cleanup(Setup s) {
        s.guard.endOath();
        s.guard.discard();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void totemRestoresVanillaEffectsAndConsumesExactlyOne(TestContext c) {
        var s = setup(c, 6);
        s.guard.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING, 2));
        s.owner.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.POISON, 100));
        c.assertTrue(rescue(s, List.of(s.guard)), "Lv6 passive rescues even in conservative mode");
        c.assertTrue(s.owner.getHealth() == 1 && s.guard.isAlive(), "owner restored and guard survives");
        c.assertTrue(!s.owner.hasStatusEffect(StatusEffects.POISON), "vanilla totem clears existing effects");
        c.assertTrue(s.owner.getStatusEffect(StatusEffects.REGENERATION).getDuration() == 900, "regeneration II 45s");
        c.assertTrue(s.owner.getStatusEffect(StatusEffects.ABSORPTION).getAmplifier() == 1, "absorption II");
        c.assertTrue(s.guard.inventory().countOf("minecraft:totem_of_undying") == 1, "one item consumed");
        var record = s.runtime.agentStore().recordOfAgent(s.guard.agentId()).orElseThrow();
        c.assertTrue(record.inventory.stream().filter(i -> i.isOf(Items.TOTEM_OF_UNDYING)).mapToInt(ItemStack::getCount).sum() == 1,
            "consumption immediately snapshotted");
        c.assertTrue(!GuardRescue.rescue(s.owner, s.owner.getDamageSources().generic(), List.of(s.guard)), "same hit cannot consume twice");
        cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void ownerTotemAndBypassingDamageNeverConsumeGuardResources(TestContext c) {
        var s = setup(c, 10);
        s.guard.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING));
        s.owner.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        c.assertTrue(!rescue(s, List.of(s.guard)), "owner hand wins before guard totem");
        s.owner.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        c.assertTrue(!GuardRescue.rescue(s.owner, s.owner.getDamageSources().outOfWorld(), List.of(s.guard)), "void excluded");
        c.assertTrue(s.guard.inventory().countOf("minecraft:totem_of_undying") == 1 && !s.guard.oathActive(), "no cost or sacrifice");
        cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void levelAndDistanceGatesPreventRescue(TestContext c) {
        var s = setup(c, 5);
        s.guard.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING));
        c.assertTrue(!rescue(s, List.of(s.guard)), "Lv5 cannot use backpack totem");
        s.guard.profile().profession.level = 8;
        s.guard.items().extractMatching(i -> true, 100);
        c.assertTrue(!rescue(s, List.of(s.guard)), "Lv8 cannot sacrifice");
        s.guard.profile().profession.level = 9;
        s.guard.refreshPositionAndAngles(s.owner.getX() + 16.01, s.owner.getY(), s.owner.getZ(), 0, 0);
        c.assertTrue(!rescue(s, List.of(s.guard)), "outside rescue radius");
        s.guard.profile().profession.setProfession(SquireProfession.ENGINEER);
        s.guard.refreshPositionAndAngles(s.owner.getX() + 1, s.owner.getY(), s.owner.getZ(), 0, 0);
        c.assertTrue(!rescue(s, List.of(s.guard)), "engineer cannot rescue");
        cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void allGuardsTotemsPrecedeAnySacrifice(TestContext c) {
        var s = setup(c, 10);
        var second = SquireEntities.AVATAR.create(c.getWorld());
        second.setOwner(s.owner.getUuid());
        second.refreshPositionAndAngles(s.owner.getX() + 2, s.owner.getY(), s.owner.getZ(), 0, 0);
        var profile = new dev.squire.server.profile.SquireProfile();
        profile.profession.setProfession(SquireProfession.GUARD); profile.profession.level = 6;
        second.attachProfile(() -> profile);
        second.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING));
        c.assertTrue(rescue(s, List.of(s.guard, second)), "lower-level guard donates before higher-level sacrifices");
        c.assertTrue(!s.guard.oathActive() && s.guard.isAlive(), "master was not sacrificed");
        c.assertTrue(second.inventory().countOf("minecraft:totem_of_undying") == 0, "donor consumed once");
        second.discard(); cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void sacrificePreservesBelongingsAndAppliesGoldenApple(TestContext c) {
        var s = setup(c, 9);
        var sword = new ItemStack(Items.NETHERITE_SWORD); sword.setDamage(97);
        s.guard.equipStack(EquipmentSlot.MAINHAND, sword);
        s.guard.insertStack(new ItemStack(Items.DIAMOND, 3));
        c.assertTrue(rescue(s, List.of(s.guard)), "Lv9 sacrifices");
        c.assertTrue(!s.guard.isAlive() && s.owner.getHealth() == 1, "one dies, owner survives");
        c.assertTrue(s.owner.getStatusEffect(StatusEffects.REGENERATION).getDuration() == 400, "apple regeneration");
        c.assertTrue(s.owner.getStatusEffect(StatusEffects.ABSORPTION).getAmplifier() == 3, "apple absorption IV");
        c.assertTrue(s.owner.getStatusEffect(StatusEffects.RESISTANCE).getDuration() == 6000, "apple resistance 5m");
        var record = s.runtime.agentStore().recordOfAgent(s.guard.agentId()).orElseThrow();
        c.assertTrue(record.deathPending && record.reviveAvailableTick > record.deathTick, "ordinary recall cooldown");
        c.assertTrue(record.mainHand.getDamage() == 97, "durability retained");
        c.assertTrue(record.inventory.stream().filter(i -> i.isOf(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum() == 3, "bag preserved");
        cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 340, batchId = "squire-oath")
    public void oathTauntsPersistsAndDiesExactlyAtDeadline(TestContext c) {
        var s = setup(c, 10);
        c.assertTrue(rescue(s, List.of(s.guard)), "oath rescues");
        long deadline = s.guard.oathDeadline();
        c.assertTrue(deadline - c.getWorld().getServer().getOverworld().getTime() == 300, "15 seconds");
        float health = s.guard.getHealth();
        s.guard.damage(s.guard.getDamageSources().generic(), 1000);
        c.assertTrue(s.guard.getHealth() == health, "invulnerable");
        c.assertTrue(!rescue(s, List.of(s.guard)), "cannot rescue twice while oath active");
        s.owner.setHealth(1);
        var bell = SquireItems.boundRecallBell(s.owner.getUuid(), s.guard.agentId());
        c.assertTrue(!s.runtime.recallWithBell(s.owner, bell).success(), "recall blocked");
        c.assertTrue(!s.runtime.executeControl(s.owner, SquireRuntime.ControlIntent.DISMISS).success(), "dismiss blocked");
        var zombie = EntityType.ZOMBIE.create(c.getWorld());
        zombie.refreshPositionAndAngles(s.guard.getX() + 5, s.guard.getY(), s.guard.getZ(), 0, 0);
        c.getWorld().spawnEntity(zombie);
        GuardRescue.tickOath(s.guard);
        c.assertTrue(zombie.getTarget() == s.guard, "nearby hostile taunted");
        NbtCompound nbt = new NbtCompound(); s.guard.writeCustomDataToNbt(nbt);
        var restored = SquireEntities.AVATAR.create(c.getWorld()); restored.readCustomDataFromNbt(nbt);
        c.assertTrue(restored.oathDeadline() == deadline && restored.isAiDisabled(), "NBT retains commitment and disables ordinary AI");
        c.assertTrue(restored.oathAnchor().equals(s.guard.oathAnchor()), "rescue anchor survives reload");
        restored.discard(); zombie.discard();
        s.guard.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING));
        c.runAtTick(299, () -> c.assertTrue(s.guard.isAlive(), "alive until deadline"));
        c.runAtTick(301, () -> {
            c.assertTrue(!s.guard.isAlive(), "dies when 300 ticks expire despite newly supplied totem");
            var record = s.runtime.agentStore().recordOfAgent(s.guard.agentId()).orElseThrow();
            c.assertTrue(record.deathPending && record.oathDeadline == 0, "death and cleared oath persisted");
            cleanup(s); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void engineerDamageCooldownAndBowRestrictionsUseActualCombat(TestContext c) {
        var s = setup(c, 3);
        s.guard.profile().profession.setProfession(SquireProfession.ENGINEER);
        var sword = new ItemStack(Items.NETHERITE_SWORD); sword.addEnchantment(Enchantments.SHARPNESS, 5);
        s.guard.equipStack(EquipmentSlot.MAINHAND, sword);
        var cow = EntityType.COW.create(c.getWorld());
        cow.refreshPositionAndAngles(s.guard.getX() + 1, s.guard.getY(), s.guard.getZ(), 0, 0);
        c.getWorld().spawnEntity(cow);
        c.runAtTick(2, () -> {
            double before = cow.getHealth();
            c.assertTrue(s.guard.tryAttack(cow), "engineer melee hit");
            c.assertTrue(Math.abs(before - cow.getHealth() - 6.5) < .01, "2 base + (7 weapon + 3 sharpness) * .45");
            c.assertTrue(!s.guard.tryAttack(cow), "same-tick duplicate blocked");
            c.assertTrue(ProfessionCombatRules.attackInterval(s.guard) == 20, "engineer sword interval is ceil(13 * 1.5)");
            s.guard.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            s.guard.insertStack(new ItemStack(Items.ARROW));
            c.assertTrue(!s.guard.shoot(cow.getUuid()).success(), "direct shoot cannot bypass engineer restriction");
            c.assertTrue(!CombatStyle.gatesFor(s.guard.profile().profession).bow(), "selection gate agrees");
            cow.discard(); cleanup(s); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void cooperativeHuntRequiresGuardLevelThree(TestContext c) {
        var s = setup(c, 2);
        c.assertTrue(!AutonomyController.canHunt(s.guard.profile()), "Lv2 locked");
        s.guard.profile().profession.level = 3;
        c.assertTrue(AutonomyController.canHunt(s.guard.profile()), "Lv3 unlocked");
        s.guard.profile().profession.setProfession(SquireProfession.ENGINEER);
        s.guard.profile().profession.level = 10;
        c.assertTrue(!AutonomyController.canHunt(s.guard.profile()), "engineers never hunt cooperatively");
        cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void exactRadiusWorksButOtherDimensionAndOtherOwnerDoNot(TestContext c) {
        var s = setup(c, 6);
        s.guard.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING));
        var nether = c.getWorld().getServer().getWorld(net.minecraft.world.World.NETHER);
        var elsewhere = FakePlayer.get(nether, s.owner.getGameProfile());
        elsewhere.setHealth(0);
        c.assertTrue(!GuardRescue.rescue(elsewhere, elsewhere.getDamageSources().generic(), List.of(s.guard)), "different dimension excluded");
        var stranger = FakePlayer.get(c.getWorld(), new GameProfile(UUID.randomUUID(), "stranger"));
        stranger.setHealth(0);
        stranger.refreshPositionAndAngles(s.owner.getX(), s.owner.getY(), s.owner.getZ(), 0, 0);
        c.assertTrue(!GuardRescue.rescue(stranger, stranger.getDamageSources().generic(), List.of(s.guard)), "different owner excluded");
        s.guard.refreshPositionAndAngles(s.owner.getX() + 16, s.owner.getY(), s.owner.getZ(), 0, 0);
        c.assertTrue(rescue(s, List.of(s.guard)), "exact 16-block boundary is included");
        cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void unloadedOathExpiresWithoutAllowingAReplacementBody(TestContext c) {
        var s = setup(c, 10);
        s.guard.insertStack(new ItemStack(Items.DIAMOND, 2));
        c.assertTrue(rescue(s, List.of(s.guard)), "oath begun");
        s.runtime.persistSnapshot(s.guard);
        var record = s.runtime.agentStore().recordOfAgent(s.guard.agentId()).orElseThrow();
        var storeNbt = s.runtime.agentStore().writeNbt(new NbtCompound());
        c.assertTrue(storeNbt.toString().contains("oathDeadline"), "world store persists commitment independent of chunk");
        s.guard.remove(net.minecraft.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
        SquireRuntime.onAvatarUnload(s.guard);
        var bell = SquireItems.boundRecallBell(s.owner.getUuid(), record.agentId);
        c.assertTrue(!s.runtime.recallWithBell(s.owner, bell).success(), "unloaded committed guard cannot be replaced");
        record.oathDeadline = c.getWorld().getServer().getOverworld().getTime();
        c.assertTrue(!s.runtime.recallWithBell(s.owner, bell).success(), "expired oath still requires normal death cooldown");
        c.assertTrue(record.deathPending && record.oathDeadline == 0, "expired unloaded oath settles death once");
        long available = record.reviveAvailableTick;
        s.runtime.recallWithBell(s.owner, bell);
        c.assertTrue(record.reviveAvailableTick == available, "repeated recall does not restart death cooldown");
        c.assertTrue(record.inventory.stream().filter(i -> i.isOf(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum() == 2, "unloaded belongings retained");
        c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void engineerAndGuardKeepIdenticalArmorProtection(TestContext c) {
        var s = setup(c, 1);
        for (var pair : List.of(new Object[]{EquipmentSlot.HEAD, Items.NETHERITE_HELMET},
                new Object[]{EquipmentSlot.CHEST, Items.NETHERITE_CHESTPLATE},
                new Object[]{EquipmentSlot.LEGS, Items.NETHERITE_LEGGINGS},
                new Object[]{EquipmentSlot.FEET, Items.NETHERITE_BOOTS})) {
            var stack = new ItemStack((net.minecraft.item.Item) pair[1]); stack.addEnchantment(Enchantments.PROTECTION, 4);
            s.guard.equipStack((EquipmentSlot) pair[0], stack);
        }
        c.runAtTick(2, () -> {
            s.guard.setHealth(20);
            s.guard.damage(s.guard.getDamageSources().mobAttack(s.guard), 16);
            float guardDamage = 20 - s.guard.getHealth();
            s.guard.profile().profession.setProfession(SquireProfession.ENGINEER);
            s.guard.setHealth(20); s.guard.timeUntilRegen = 0;
            s.guard.damage(s.guard.getDamageSources().mobAttack(s.guard), 16);
            c.assertTrue(guardDamage > 0 && Math.abs(20 - s.guard.getHealth() - guardDamage) < .001, "same armor and protection mitigation");
            cleanup(s); c.complete();
        });
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue")
    public void engineerSelfDefenceCannotFollowAMovingTargetBeyondHitOrigin(TestContext c) {
        var s = setup(c, 1);
        s.guard.profile().profession.setProfession(SquireProfession.ENGINEER);
        var zombie = EntityType.ZOMBIE.create(c.getWorld());
        var origin = s.guard.getPos();
        zombie.refreshPositionAndAngles(origin.x + 1, origin.y, origin.z, 0, 0);
        s.guard.damage(s.guard.getDamageSources().mobAttack(zombie), 1);
        c.assertTrue(s.guard.selfDefenceOrigin().equals(origin), "origin recorded at actual hit");
        c.assertTrue(AutonomyController.engineerCanPursue(s.guard, zombie, origin), "nearby attacker can be fought");
        s.guard.refreshPositionAndAngles(origin.x + 3, origin.y, origin.z, 0, 0);
        zombie.refreshPositionAndAngles(origin.x + 4.1, origin.y, origin.z, 0, 0);
        c.assertTrue(!AutonomyController.engineerCanPursue(s.guard, zombie, origin), "nearby target beyond original perimeter is abandoned");
        c.assertTrue(!AutonomyController.engineerCanPursue(s.guard, s.owner, origin), "owner never a self-defence target");
        zombie.discard(); cleanup(s); c.complete();
    }

    @GameTest(templateName = M0SpikeGameTests.FLOOR, batchId = "squire-rescue-event")
    public void lethalEventRescuesButMitigatedDamageDoesNot(TestContext c) {
        var s = setup(c, 6);
        s.guard.insertStack(new ItemStack(Items.TOTEM_OF_UNDYING, 2));
        // This test player is not in PlayerManager: explicitly age through join protection.
        for (int i = 0; i < 65; i++) s.owner.tick();
        s.owner.setHealth(20);
        s.owner.setAbsorptionAmount(40);
        s.owner.damage(s.owner.getDamageSources().generic(), 10);
        c.assertTrue(s.guard.inventory().countOf("minecraft:totem_of_undying") == 2, "absorption prevents false lethal prediction");
        s.owner.timeUntilRegen = 0;
        s.owner.damage(s.owner.getDamageSources().generic(), 1000);
        c.assertTrue(s.owner.isAlive() && s.owner.getHealth() == 1, "registered event cancels real death; hp=" + s.owner.getHealth());
        c.assertTrue(s.guard.inventory().countOf("minecraft:totem_of_undying") == 1, "event consumed one donor totem");
        cleanup(s); c.complete();
    }
}
