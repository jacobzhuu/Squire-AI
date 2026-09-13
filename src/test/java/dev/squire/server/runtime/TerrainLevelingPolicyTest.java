package dev.squire.server.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.squire.server.blueprint.TerrainLeveling;
import dev.squire.server.profession.*;
import dev.squire.server.project.*;
import java.util.List;

class TerrainLevelingPolicyTest {
    @Test void sameMaximumAreaIsAvailableAtEveryEngineerLevel() {
        var blueprint = new TerrainLeveling.Spec(128, 128).blueprint();
        for (int level = 1; level <= 10; level++) {
            var data = new ProfessionData(); data.setProfession(SquireProfession.ENGINEER); data.level = level;
            var decision = EngineerBuildPolicy.evaluate(blueprint, data, ProfessionConfig.defaults());
            assertTrue(decision.allowed()); assertEquals(1, decision.minLevel());
            assertFalse(decision.rotation()); assertFalse(decision.materials());
        }
        var data = new ProfessionData();
        assertFalse(EngineerBuildPolicy.evaluate(blueprint, data, ProfessionConfig.defaults()).allowed());
        data.setProfession(SquireProfession.GUARD); data.level = 10;
        assertFalse(EngineerBuildPolicy.evaluate(blueprint, data, ProfessionConfig.defaults()).allowed());
        assertFalse(EngineerBuildPolicy.evaluate(blueprint, null, ProfessionConfig.defaults()).allowed());
    }
    @Test void durableSpecsRejectOutOfBoundsAndMalformedRequests() {
        for (String id : List.of("terrain_level/0/3", "terrain_level/129/3", "terrain_level/1/-1", "terrain_level/1/1/x", "terrain_level/a/3", "project/terrain"))
            assertTrue(TerrainLeveling.parse(id).isEmpty(), id);
        var spec = new TerrainLeveling.Spec(7, 12);
        assertEquals(spec, TerrainLeveling.parse(spec.id()).orElseThrow());
    }
    @Test void terrainPipelineHasNoBuildingOrLightingRewardStage() {
        assertEquals(List.of(Stage.Kind.FULFIL_MATERIALS, Stage.Kind.EXCAVATE, Stage.Kind.HAUL, Stage.Kind.VERIFY),
            ProjectCoordinator.compileTerrainStages().stream().map(s -> s.kind).toList());
    }
}
