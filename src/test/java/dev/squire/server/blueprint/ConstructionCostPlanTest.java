package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.squire.server.profession.EngineerProgression;

class ConstructionCostPlanTest {
    @Test void levelOnePaysBaseAndMasterSavesHalf() {
        var growth = EngineerProgression.current();
        assertEquals(0, ConstructionCostPlan.waste(100, growth.wasteBasisPoints(1)));
        assertEquals(-20, ConstructionCostPlan.waste(100, growth.wasteBasisPoints(5)));
        assertEquals(-50, ConstructionCostPlan.waste(100, growth.wasteBasisPoints(10)));
        for (int level = 1; level <= 10; level++) for (int base = 0; base <= 10000; base++)
            {
            int cost = base + ConstructionCostPlan.waste(base, growth.wasteBasisPoints(level));
            assertTrue(cost >= (base + 1) / 2 && cost <= base);
        }
    }
    @Test void cumulativeRoundingDoesNotChargeEveryBlockExtra() {
        int extra = 0;
        for (int i = 1; i <= 100; i++) extra += ConstructionCostPlan.waste(i, 2000) - ConstructionCostPlan.waste(i - 1, 2000);
        assertEquals(20, extra);
        assertThrows(IllegalArgumentException.class, () -> ConstructionCostPlan.waste(100, -5001));
    }
    @Test void savingsAreCumulativeAndAtomicCostsMayBeZero() {
        for (int level = 1; level <= 10; level++) {
            int rate = EngineerProgression.current().wasteBasisPoints(level);
            int paid = 0;
            for (int i = 1; i <= 101; i++) {
                var op = new ConstructionCostPlan.Operation("cell:" + i,
                    new net.minecraft.util.Identifier("minecraft:stone"), 1,
                    ConstructionCostPlan.waste(i, rate) - ConstructionCostPlan.waste(i - 1, rate), java.util.List.of());
                assertTrue(op.total() >= 0 && op.total() <= 1);
                paid += op.total();
            }
            assertEquals(101 + ConstructionCostPlan.waste(101, rate), paid);
        }
        assertEquals(-50, ConstructionCostPlan.waste(101, -5000));
    }
    @Test void configRejectsSavingsAboveHalf() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/squire/engineer_progression.json")) {
            String json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> EngineerProgression.parse(json.replace("5000", "5001")));
        }
    }
    @Test void fractionalCadenceGivesEveryLevelADistinctSpeedWithoutIdleBursts() {
        int previousCount = 0;
        for (int level = 1; level <= 10; level++) {
            double interval = EngineerProgression.current().placementInterval(level), next = 0;
            int count = 0;
            for (long tick = 0; tick < 4000; tick++) if (tick >= next) {
                count++;
                next = EngineerProgression.nextWorkTick(tick, next, interval);
                assertTrue(next > tick);
            }
            assertEquals(4000 / interval, count, 1.0);
            assertTrue(count > previousCount);
            previousCount = count;
            assertEquals(100 + interval, EngineerProgression.nextWorkTick(100, 0, interval));
        }
    }
    @Test void growthIsMonotonicAndMovementIsModest() {
        var g = EngineerProgression.current();
        for (int level = 2; level <= 10; level++) {
            assertTrue(g.efficiency(level) > g.efficiency(level - 1));
            assertTrue(g.wasteBasisPoints(level) < g.wasteBasisPoints(level - 1));
            assertTrue(g.movement(level) <= 1.45);
            assertTrue(g.placementInterval(level) < g.placementInterval(level - 1));
        }
        assertEquals(1.0, g.movement(1));
        assertEquals(1.45, g.movement(10));
        assertEquals(4, g.efficiency(10));
        assertEquals(4, g.placementInterval(1));
        assertEquals(1, g.placementInterval(10));
    }
}
