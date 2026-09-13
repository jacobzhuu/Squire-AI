package dev.squire.server.combat;

import java.util.*;
import dev.squire.server.body.avatar.AvatarEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.item.BowItem;

/** Immediate local defense, independent of the distant objective and weapon preference. */
public final class GuardSelfDefense {
    private GuardSelfDefense() { }
    private static final Map<AvatarEntity, State> STATES = new WeakHashMap<>();
    private static final class State {
        long scanned = Long.MIN_VALUE, until;
        LivingEntity threat;
        ItemStack chosenBow;
        boolean active;
    }
    public static LivingEntity target(AvatarEntity guard, LivingEntity objective) {
        if (guard.profile() == null || guard.profile().profession.profession()
                != dev.squire.server.profession.SquireProfession.GUARD) {
            STATES.remove(guard);
            return objective;
        }
        State s = STATES.computeIfAbsent(guard, k -> new State());
        long now = guard.getWorld().getTime();
        if (s.scanned == Long.MIN_VALUE || now - s.scanned >= 4) {
            s.scanned = now;
            s.threat = guard.getWorld().getEntitiesByClass(MobEntity.class, guard.getBoundingBox().expand(3.5),
                mob -> localThreat(guard, mob)).stream()
                .min(Comparator.comparingDouble(guard::squaredDistanceTo)).orElse(null);
        }
        LivingEntity threat = s.threat != null && localThreat(guard, s.threat) ? s.threat : null;
        if (threat == null && objective != null && localThreat(guard, objective)) threat = objective;
        if (threat != null) { s.until = now + 40; s.active = true; }
        else if (now >= s.until) s.active = false;
        return threat == null ? objective : threat;
    }
    private static boolean localThreat(AvatarEntity guard, LivingEntity enemy) {
        if (!(enemy instanceof MobEntity mob) || enemy instanceof AvatarEntity || !enemy.isAlive()
                || enemy.isTeammate(guard) || guard.isTeammate(enemy)
                || !(enemy instanceof Monster || mob.getTarget() == guard)
                || guard.squaredDistanceTo(enemy) > 3.5 * 3.5
                || Math.abs(enemy.getY() - guard.getY()) > 2 || !guard.canSee(enemy)) return false;
        var owner = guard.getWorld().getPlayerByUuid(guard.ownerId());
        return owner == null || !owner.isTeammate(enemy);
    }
    static boolean prepare(AvatarEntity guard, LivingEntity target, CombatStyle.Style style, CombatStyle.Gates gates) {
        target(guard, target);
        State s = STATES.get(guard);
        if (s == null) return false;
        if (s.active) {
            var hand = guard.items().equipped(EquipmentSlot.MAINHAND);
            if (s.chosenBow == null && guard.weaponChosenByPlayer() && hand.getItem() instanceof BowItem)
                s.chosenBow = hand.copy();
            CombatStyle.degradeToMelee(guard);
            return true;
        }
        if (s.chosenBow != null) {
            if (guard.weaponChosenByPlayer() && style == CombatStyle.Style.AUTO && gates.bow()) {
                for (int i = 0; i < guard.items().size(); i++) {
                    if (ItemStack.areEqual(guard.items().getStack(i), s.chosenBow)) {
                        if (guard.items().equipFromMain(i, EquipmentSlot.MAINHAND).success()) guard.noteMainHandSwapped();
                        break;
                    }
                }
            }
            s.chosenBow = null;
        }
        return false;
    }
    public static void reset(AvatarEntity guard) { STATES.remove(guard); }
}
