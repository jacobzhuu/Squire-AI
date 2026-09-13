package dev.squire.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.*;

/** Full real-NPC build, including travel, supply stages, lighting and temporary cleanup. */
public final class M31EngineerEfficiencyGameTests implements FabricGameTest {
    private static final java.util.Map<Integer, Integer> TIMES = new java.util.HashMap<>();
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-efficiency-level-one")
    public void apprenticeBuildsTheWholeResidence(TestContext c) { build(c, 1, 12); }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-efficiency-level-ten")
    public void masterBuildsTheWholeResidence(TestContext c) { build(c, 10, 13); }
    private static void build(TestContext c, int level, int index) {
        M16ProjectGameTests.importedBuild(c, "keepitlevel_residence", index, level, ticks -> {
            TIMES.put(level, ticks);
            org.slf4j.LoggerFactory.getLogger(M31EngineerEfficiencyGameTests.class).info("Full residence build Lv{}: {} ticks", level, ticks);
            if (TIMES.size() == 2) {
                double ratio = TIMES.get(1) / (double) TIMES.get(10);
                try { java.nio.file.Files.writeString(java.nio.file.Path.of("engineer-efficiency.json"),
                    "{\"level1Ticks\":" + TIMES.get(1) + ",\"level10Ticks\":" + TIMES.get(10) + ",\"ratio\":" + ratio + "}"); }
                catch (java.io.IOException bad) { throw new IllegalStateException(bad); }
                c.assertTrue(ratio > 1.0, "master should finish faster with the new curve, measured " + ratio);
            }
        });
    }
}
