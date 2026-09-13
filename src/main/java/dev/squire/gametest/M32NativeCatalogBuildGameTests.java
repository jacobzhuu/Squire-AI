package dev.squire.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.*;

/** Newly imported native NBT follows the same real-inventory/NPC pipeline as legacy six. */
public final class M32NativeCatalogBuildGameTests implements FabricGameTest {
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-native-sawmill")
    public void tierTwoSawmillCompletesWithExactNativePreview(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/craftsmanship/carpentry/sawmill2", 20, 10, ignored -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-native-tier-five")
    public void tierFiveRailStationCompletesAndReclaimsAccess(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/infrastructure/railroad/railrd_md_station5", 21, 10, ignored -> {});
    }
    @GameTest(templateName = M0SpikeGameTests.FLOOR, tickLimit = 45000, batchId = "squire-native-component")
    public void roadComponentUsesItsNativeFrameAndBill(TestContext c) {
        M16ProjectGameTests.importedBuild(c, "squire:keepitlevel/infrastructure/roads/road_md3", 22, 10, ignored -> {});
    }
}
