package dev.squire.server.task.executors;

import dev.squire.server.profile.SquireProfile;
import dev.squire.server.profession.SquireProfession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineerMovementTest {
    @Test void preciseTravelSharesLevelAndAttributeScalingOnBroadGround() {
        assertEquals(.1, EngineerMovement.directSpeed(2, 1, .35), 1e-9);
        assertEquals(.145, EngineerMovement.directSpeed(2, 1.45, .35), 1e-9);
        assertEquals(.12, EngineerMovement.directSpeed(2, 1, .42), 1e-9);
        assertEquals(.05, EngineerMovement.directSpeed(2, 1, .175), 1e-9);
    }
    @Test void narrowEdgesRetainSafetyCapAndShortStepsNeverOvershoot() {
        assertEquals(.1, EngineerMovement.directSpeed(2, .5, .7), 1e-9);
        assertEquals(.03, EngineerMovement.directSpeed(.03, 1.45, .42), 1e-9);
        assertEquals(.2, EngineerMovement.directSpeed(2, 4, 1.4), 1e-9);
        assertEquals(0, EngineerMovement.directSpeed(0, 1, .35));
        assertEquals(0, EngineerMovement.directSpeed(2, 1, 0));
    }
    @Test void engineeringTravelUsesEveryLevelWithoutChangingOtherProfessions() {
        var profile = new SquireProfile();
        profile.profession.setProfession(SquireProfession.ENGINEER);
        for (int level = 1; level <= 10; level++) {
            profile.profession.level = level;
            assertEquals(1 + (level - 1) * .05, EngineerMovement.options(profile).speed(), 1e-9);
        }
        profile.profession.setProfession(SquireProfession.GUARD);
        assertEquals(1, EngineerMovement.options(profile).speed());
        assertEquals(1, EngineerMovement.options(null).speed());
    }
}
