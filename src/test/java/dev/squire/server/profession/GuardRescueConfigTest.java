package dev.squire.server.profession;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardRescueConfigTest {
    @Test void legacyConfigRetainsNewDefaults() {
        var config = ProfessionConfig.fromJson(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals(.45, config.engineerWeaponDamageFactor);
        assertEquals(1.5, config.engineerAttackIntervalFactor);
        assertEquals(4, config.engineerSelfDefenceRadius);
        assertEquals(16, config.guardRescueRadius);
        assertEquals(16, config.guardOathTauntRadius);
        assertEquals(300, config.guardOathDurationTicks);
    }

    @Test void combatOverridesRejectUnsafeValuesPerField() {
        var config = ProfessionConfig.fromJson(JsonParser.parseString("""
            {"combat":{"engineerWeaponDamageFactor":0.5,"engineerAttackIntervalFactor":-1,
              "engineerSelfDefenceRadius":6,"guardRescueRadius":100000,
              "guardOathTauntRadius":12,"guardOathDurationTicks":0}}
            """).getAsJsonObject());
        assertEquals(.5, config.engineerWeaponDamageFactor);
        assertEquals(1.5, config.engineerAttackIntervalFactor);
        assertEquals(6, config.engineerSelfDefenceRadius);
        assertEquals(16, config.guardRescueRadius);
        assertEquals(12, config.guardOathTauntRadius);
        assertEquals(300, config.guardOathDurationTicks);
    }

    @Test void newAbilitiesAreAdditiveAndProfessionBound() {
        var guard = new ProfessionData();
        guard.setProfession(SquireProfession.GUARD);
        for (var ability : new ProfessionAbility[]{ProfessionAbility.GUARD_COOPERATIVE_HUNT,
                ProfessionAbility.GUARD_PROTECTIVE_TOTEM, ProfessionAbility.GUARD_SELF_SACRIFICE,
                ProfessionAbility.GUARD_IMMORTAL_OATH}) {
            guard.level = ability.unlockLevel() - 1;
            assertFalse(guard.can(ability));
            guard.level++;
            assertTrue(guard.can(ability));
        }
        assertTrue(guard.can(ProfessionAbility.GUARD_GUARDIAN_PROTOCOL));
        guard.setProfession(SquireProfession.ENGINEER);
        guard.level = 10;
        assertFalse(guard.can(ProfessionAbility.GUARD_IMMORTAL_OATH));
        assertFalse(guard.can(ProfessionAbility.GUARD_COOPERATIVE_HUNT));
    }
}
