package dev.squire.mixin;

import dev.squire.server.body.avatar.AvatarEntity;
import dev.squire.server.combat.ProfessionCombatRules;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keep vanilla knockback, fire aspect, shield and enchantment callbacks intact. */
@Mixin(MobEntity.class)
abstract class AvatarMeleeDamageMixin {
    @ModifyArg(method = "tryAttack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/entity/Entity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"), index = 1)
    private float squire$weaponProficiency(float amount) {
        return (Object) this instanceof AvatarEntity avatar ? ProfessionCombatRules.damage(avatar, amount) : amount;
    }
}
