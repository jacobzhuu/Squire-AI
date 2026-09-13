package dev.squire.server.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.squire.server.blueprint.*;
import dev.squire.server.profession.*;
import dev.squire.server.gui.ProfessionView;
import java.util.*;

class EngineerBuildPolicyTest {
    @Test void undeclaredOverhangCannotBypassFootprintLimitsOrMisleadCatalog() {
        var b = new Blueprint("overhang", "overhang", 1, Blueprint.Category.HOUSING, 1, 1, 1,
            List.of(BlueprintStep.place(0, 0, -2, 0, 22, 0, 0, "minecraft:stone", "overhang", false)), Set.of());
        var data = new ProfessionData(); data.setProfession(SquireProfession.ENGINEER); data.level = 1;
        var config = ProfessionConfig.defaults();
        var decision = EngineerBuildPolicy.evaluate(b, data, config);
        assertFalse(decision.allowed()); assertEquals(8, decision.minLevel());
        var entry = ProfessionView.of(data, config, ignored -> 0).withBlueprintCatalog(List.of(b), config)
            .catalog().stream().filter(e -> e.id().equals(b.id())).findFirst().orElseThrow();
        assertEquals(23, entry.width()); assertEquals(3, entry.height());
    }
    @Test void everyCatalogCardUsesTheSameLevelAndFootprintPolicy() {
        var registry = new BlueprintRegistry(); var config = ProfessionConfig.defaults();
        for (int level = 1; level <= 10; level++) {
            var data = new ProfessionData(); data.setProfession(SquireProfession.ENGINEER); data.level = level;
            var view = ProfessionView.of(data, config, ignored -> 0).withBlueprintCatalog(registry.all(), config);
            for (var b : registry.all()) {
                var decision = EngineerBuildPolicy.evaluate(b, data, config);
                var card = view.catalog().stream().filter(e -> e.id().equals(b.id())).findFirst().orElseThrow();
                assertEquals(decision.allowed(), card.allowed(), b.id() + " Lv" + level);
                assertEquals(decision.minLevel(), card.minLevel());
            }
        }
    }
    @Test void impossibleFootprintNeverPromisesLevelTen() {
        var b = new Blueprint("too_large", "oversized", 1, Blueprint.Category.HOUSING, 200, 2, 200,
            List.of(BlueprintStep.place(0, 0, 0, 0, 0, 0, 0, "minecraft:stone", "sample", false)), Set.of());
        var data = new ProfessionData(); data.setProfession(SquireProfession.ENGINEER); data.level = 10;
        var decision = EngineerBuildPolicy.evaluate(b, data, ProfessionConfig.defaults());
        assertFalse(decision.allowed()); assertEquals(11, decision.minLevel()); assertTrue(decision.reason().contains("上限"));
    }
}
