package dev.squire.server.blueprint;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BuildingCatalogTest {
    @Test void retiredAbilityDescriptionsAreHiddenWithoutDeletingTheirGates() {
        var legacy = dev.squire.server.profession.ProfessionAbility.ENGINEER_MULTI_FLOOR;
        var engineer = dev.squire.server.profession.SquireProfession.ENGINEER;
        assertTrue(dev.squire.server.profession.ProfessionAbility.of(engineer).contains(legacy));
        assertFalse(dev.squire.server.profession.ProfessionAbility.playerVisible(engineer).contains(legacy));
        var view = dev.squire.server.gui.ProfessionView.EMPTY.withBuildingCatalog(BuildingCatalog.bundled(), "", 1);
        assertFalse(view.constructionGrowth().contains("参数"));
        assertTrue(view.blueprintLibrary().stream().allMatch(v -> v.kind().equals("family")));
    }
    @Test void everyEngineerLevelUnlocksValidatedContent() {
        var variants = BuildingCatalog.bundled().variants();
        for (int level = 1; level <= 10; level++) {
            final int l = level;
            assertTrue(variants.stream().anyMatch(v -> v.buildable() && v.requiredEngineerLevel() == l), "no unlocks at " + l);
        }
        assertTrue(variants.stream().filter(BuildingCatalog.BuildingVariantDefinition::buildable).allMatch(v -> v.allowed(10)));
    }
    @Test void sixLegacyDimensionsDescribePinnedGeometry() {
        var registry = new BlueprintRegistry();
        for (var v : registry.catalog().variants().stream().filter(v -> v.id().startsWith("keepitlevel_")).toList()) {
            var bounds = registry.byId(v.id()).orElseThrow().bounds(net.minecraft.util.math.BlockPos.ORIGIN, net.minecraft.util.math.Direction.NORTH);
            assertEquals(v.width(), bounds.max().getX() - bounds.min().getX() + 1, v.id());
            assertEquals(v.height(), bounds.max().getY() - bounds.min().getY() + 1, v.id());
            assertEquals(v.depth(), bounds.max().getZ() - bounds.min().getZ() + 1, v.id());
        }
    }
    @Test void retiredPlayerContentDoesNotRemoveItsGenericParser() {
        var policy = BuildingContentPolicy.current();
        assertTrue(policy.retired("mine_outpost")); assertTrue(policy.retired("project/house/anything"));
        assertFalse(policy.retired("keepitlevel_residence"));
        assertTrue(new BlueprintRegistry().byId("mine_outpost").isPresent());
        assertFalse(new BlueprintRegistry().playerIds().contains("mine_outpost"));
        assertEquals(10, policy.catalogFullMaterialLevel());
    }
    @Test void completePinnedInventoryHasFamiliesInsteadOf750MenuEntries() {
        var c = BuildingCatalog.bundled();
        assertEquals(750, c.variants().size()); assertEquals(72, c.families().size()); assertEquals(9, c.categories().size());
        assertEquals(1, c.variants().stream().filter(v -> v.status() == BuildingCatalog.Status.INTERNAL).count());
        assertTrue(c.variants().stream().allMatch(v -> v.license().equals("MIT") && v.sourceSha256().matches("[0-9a-f]{64}")));
    }
    @Test void deckAndTierAreVariantsAndTavernDoesNotInventLevels() {
        var c = BuildingCatalog.bundled();
        var home = c.family("squire:keepitlevel/residence").orElseThrow();
        assertEquals(10, home.variants().size()); assertEquals(5, home.variants().stream().filter(v -> v.deck()).count());
        assertEquals(3, c.family("squire:keepitlevel/tavern").orElseThrow().variants().stream().mapToInt(v -> v.tier()).max().orElseThrow());
        assertTrue(c.family("squire:keepitlevel/plantation").isPresent());
        assertTrue(c.family("squire:keepitlevel/plantation_fields").isPresent());
    }
    @Test void unsupportedContentCannotReachGeometryLoader() {
        var r = new BlueprintRegistry();
        assertEquals(0, r.repository().cachedCount());
        var bad = r.catalog().variants().stream().filter(v -> !v.buildable()).findFirst().orElseThrow();
        assertTrue(r.byId(bad.id()).isEmpty()); assertEquals(0, r.repository().cachedCount());
    }
    @Test void legacyIdsKeepTheirSourceAssociation() {
        var v = BuildingCatalog.bundled().variant("keepitlevel_residence").orElseThrow();
        assertEquals(1, v.tier()); assertEquals("squire:keepitlevel/residence", v.family());
        assertTrue(v.buildable());
    }
}
