package dev.squire.server.combat;

import java.util.Comparator;
import java.util.List;
import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.ProfessionAbility;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Server-thread-only lethal rescue. All candidates are considered before a sacrifice. */
public final class GuardRescue {
    private GuardRescue() { }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity owner) || !SquireRuntime.isAlive()) return true;
            return !rescue(owner, source, SquireRuntime.get().agents().resolveAllForOwner(owner.getUuid()));
        });
    }

    public static boolean can(AvatarEntity avatar, ProfessionAbility ability) {
        return avatar.profile() != null && avatar.profile().profession.can(ability);
    }

    public static boolean rescue(ServerPlayerEntity owner, DamageSource source, List<AvatarEntity> companions) {
        if (owner.getHealth() > 0 || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || owner.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || owner.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
        double radius = ProfessionCombatRules.config().guardRescueRadius;
        var candidates = companions.stream().filter(a -> a.isAlive() && !a.isRemoved() && !a.oathActive()
                && a.isOwner(owner) && a.getWorld() == owner.getWorld()
                && a.squaredDistanceTo(owner) <= radius * radius
                && can(a, ProfessionAbility.GUARD_PROTECTIVE_TOTEM))
            .sorted(Comparator.<AvatarEntity>comparingInt(a -> -a.profile().profession.level)
                .thenComparingDouble(a -> a.squaredDistanceTo(owner)).thenComparing(a -> a.agentId().toString()))
            .toList();
        for (var guard : candidates) {
            // Extraction searches the ordinary bag, then supported external backpack contents.
            var extracted = guard.items().extractMatching(s -> s.isOf(Items.TOTEM_OF_UNDYING), 1);
            if (extracted.isEmpty()) continue;
            owner.incrementStat(net.minecraft.stat.Stats.USED.getOrCreateStat(Items.TOTEM_OF_UNDYING));
            net.minecraft.advancement.criterion.Criteria.USED_TOTEM.trigger(owner, extracted.get(0));
            owner.setHealth(1.0f);
            owner.clearStatusEffects();
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 1));
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1));
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0));
            owner.getWorld().sendEntityStatus(owner, EntityStatuses.USE_TOTEM_OF_UNDYING);
            persist(guard);
            link(guard, owner);
            announce(owner, "totem", guard);
            return true;
        }
        for (var guard : candidates) {
            if (!can(guard, ProfessionAbility.GUARD_SELF_SACRIFICE)) continue;
            owner.setHealth(1.0f);
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 400, 1));
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 2400, 3));
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 6000, 0));
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 6000, 0));
            link(guard, owner);
            flashToOwner(guard, owner, source);
            var world = (ServerWorld) owner.getWorld();
            world.spawnParticles(ParticleTypes.FLASH, owner.getX(), owner.getBodyY(0.5), owner.getZ(), 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, owner.getX(), owner.getBodyY(0.5), owner.getZ(), 60, .7, 1, .7, .15);
            world.playSound(null, guard.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1, .65f);
            if (SquireRuntime.isAlive()) SquireRuntime.get().scheduler().cancelAgent(guard.agentId(), "guard sacrifice");
            if (can(guard, ProfessionAbility.GUARD_IMMORTAL_OATH)) {
                guard.beginOath(world.getServer().getOverworld().getTime()
                    + ProfessionCombatRules.config().guardOathDurationTicks);
                persist(guard);
                announce(owner, "oath", guard);
            } else {
                announce(owner, "sacrifice", guard);
                die(guard);
            }
            return true;
        }
        return false;
    }

    private static void persist(AvatarEntity guard) {
        if (SquireRuntime.isAlive()) SquireRuntime.get().persistSnapshot(guard);
    }

    private static void announce(ServerPlayerEntity owner, String kind, AvatarEntity guard) {
        Text text = Text.translatable("squire.rescue." + kind, guard.getName());
        owner.sendMessage(text, false);
        owner.sendMessage(text, true);
    }

    private static void link(AvatarEntity guard, ServerPlayerEntity owner) {
        var world = (ServerWorld) guard.getWorld();
        Vec3d start = guard.getPos().add(0, 1, 0), end = owner.getPos().add(0, 1, 0);
        for (int i = 0; i <= 24; i++) {
            Vec3d point = start.lerp(end, i / 24.0);
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, point.x, point.y, point.z, 2, .04, .04, .04, .02);
        }
    }

    private static void flashToOwner(AvatarEntity guard, ServerPlayerEntity owner, DamageSource source) {
        Vec3d direction = source.getAttacker() == null ? owner.getRotationVector()
            : source.getAttacker().getPos().subtract(owner.getPos());
        direction = new Vec3d(direction.x, 0, direction.z).normalize();
        BlockPos preferred = BlockPos.ofFloored(owner.getPos().add(direction.multiply(1.5)));
        var positions = new java.util.ArrayList<BlockPos>();
        positions.add(preferred);
        BlockPos.iterateOutwards(owner.getBlockPos(), 2, 1, 2).forEach(p -> positions.add(p.toImmutable()));
        for (BlockPos pos : positions) {
            Vec3d destination = Vec3d.ofBottomCenter(pos);
            var floor = guard.getWorld().getBlockState(pos.down());
            if (floor.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)
                    || floor.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                    || floor.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE)
                    || floor.isOf(net.minecraft.block.Blocks.CACTUS)) continue;
            if (!guard.getWorld().getBlockState(pos).isAir() || !guard.getWorld().getBlockState(pos.up()).isAir()) continue;
            if (!guard.getWorld().getWorldBorder().contains(pos)
                    || !guard.getWorld().getBlockState(pos.down()).isSolidBlock(guard.getWorld(), pos.down())
                    || !guard.getWorld().getFluidState(pos).isEmpty()
                    || !guard.getWorld().isSpaceEmpty(guard, guard.getBoundingBox().offset(destination.subtract(guard.getPos())))) continue;
            guard.requestTeleport(destination.x, destination.y, destination.z);
            guard.getNavigation().stop();
            guard.setVelocity(Vec3d.ZERO);
            return;
        }
    }

    /** Called before normal entity AI; the deadline uses persisted total world time, not day time. */
    public static boolean expireOath(AvatarEntity guard) {
        if (!guard.oathActive() || !(guard.getWorld() instanceof ServerWorld world)) return false;
        if (world.getServer().getOverworld().getTime() < guard.oathDeadline()) return false;
        world.spawnParticles(ParticleTypes.END_ROD, guard.getX(), guard.getBodyY(.5), guard.getZ(), 40, .5, 1, .5, .1);
        world.playSound(null, guard.getBlockPos(), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.NEUTRAL, 1, .7f);
        var owner = world.getServer().getPlayerManager().getPlayer(guard.ownerId());
        if (owner != null) announce(owner, "oath_end", guard);
        guard.endOath();
        die(guard);
        return true;
    }

    private static void die(AvatarEntity guard) {
        // Direct death deliberately bypasses held totems and healing: the sacrifice is committed.
        guard.setHealth(0);
        guard.onDeath(guard.getDamageSources().generic());
    }

    public static void tickOath(AvatarEntity guard) {
        if (!guard.oathActive() || !guard.isAlive() || !(guard.getWorld() instanceof ServerWorld world)) return;
        guard.getNavigation().stop();
        guard.setVelocity(Vec3d.ZERO);
        Vec3d anchor = guard.oathAnchor();
        if (guard.squaredDistanceTo(anchor) > .01) guard.requestTeleport(anchor.x, anchor.y, anchor.z);
        double radius = ProfessionCombatRules.config().guardOathTauntRadius;
        var targets = world.getEntitiesByClass(MobEntity.class, guard.getBoundingBox().expand(radius),
            mob -> mob instanceof Monster && mob.isAlive() && !mob.isTeammate(guard)
                && mob.squaredDistanceTo(guard) <= radius * radius);
        for (var mob : targets) {
            mob.setTarget(guard);
            var brain = mob.getBrain();
            if (brain.isMemoryInState(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET,
                    net.minecraft.entity.ai.brain.MemoryModuleState.REGISTERED)) {
                brain.remember(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET, guard);
            }
            if (mob instanceof net.minecraft.entity.mob.WardenEntity warden && guard.age % 20 == 0)
                warden.increaseAngerAt(guard, 150, true);
        }
        LivingEntity target = targets.stream().min(Comparator.comparingDouble(guard::squaredDistanceTo)).orElse(null);
        if (target != null) {
            boolean ranged = CombatStyle.prepare(guard, target, guard.combatStyle(), CombatStyle.gatesFor(guard.profile().profession));
            if (ranged) guard.shoot(target.getUuid());
            else if (guard.squaredDistanceTo(target) <= AvatarEntity.MELEE_REACH_SQ) guard.attack(target.getUuid());
        }
        if (guard.age % 4 == 0) {
            for (int i = 0; i < 16; i++) {
                double angle = Math.PI * 2 * i / 16 + guard.age * .04;
                world.spawnParticles(ParticleTypes.END_ROD, guard.getX() + Math.cos(angle) * 1.2,
                    guard.getY() + .15, guard.getZ() + Math.sin(angle) * 1.2, 1, 0, .04, 0, 0);
            }
        }
        if (guard.age % 20 == 0) {
            var owner = world.getServer().getPlayerManager().getPlayer(guard.ownerId());
            if (owner != null) owner.sendMessage(Text.translatable("squire.rescue.countdown", guard.getName(),
                (guard.oathDeadline() - world.getServer().getOverworld().getTime() + 19) / 20), true);
            world.spawnParticles(ParticleTypes.ENCHANT, guard.getX(), guard.getBodyY(.5), guard.getZ(), 24, 2, .4, 2, .1);
        }
    }
}
