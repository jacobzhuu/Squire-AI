package dev.squire.server.combat;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.profession.ProfessionConfig;
import dev.squire.server.profession.SquireProfession;
import dev.squire.server.runtime.SquireRuntime;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;

/** Shared by all combat entry points; equipment protection is never modified. */
public final class ProfessionCombatRules {
    private ProfessionCombatRules() { }

    public static ProfessionConfig config() {
        return SquireRuntime.isAlive() ? SquireRuntime.get().professionConfig() : ProfessionConfig.defaults();
    }

    public static boolean engineer(AvatarEntity avatar) {
        return avatar.profile() != null
            && avatar.profile().profession.profession() == SquireProfession.ENGINEER;
    }

    public static int interval(double speed, double factor) {
        int normal = (int) Math.ceil(20.0 / Math.max(0.1, speed));
        return Math.max(1, (int) Math.ceil(normal * factor));
    }

    public static int attackInterval(AvatarEntity avatar) {
        // Avatars use MobEntity's attribute container, which has no attack speed by default.
        var speed = new EntityAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED, ignored -> { });
        speed.setBaseValue(4.0);
        for (var modifier : avatar.getMainHandStack().getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(EntityAttributes.GENERIC_ATTACK_SPEED)) speed.addTemporaryModifier(modifier);
        return interval(speed.getValue(), engineer(avatar) ? config().engineerAttackIntervalFactor : 1.0);
    }

    public static float damage(AvatarEntity avatar, float vanillaDamage) {
        if (!engineer(avatar)) return vanillaDamage;
        var unarmed = new EntityAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE, ignored -> { });
        unarmed.setFrom(avatar.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE));
        for (var modifier : avatar.getMainHandStack().getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(EntityAttributes.GENERIC_ATTACK_DAMAGE)) unarmed.removeModifier(modifier.getId());
        return scaledDamage(vanillaDamage, unarmed.getValue(), config().engineerWeaponDamageFactor);
    }

    public static float scaledDamage(double total, double unarmed, double factor) {
        return (float) Math.max(0, unarmed + (total - unarmed) * factor);
    }
}
