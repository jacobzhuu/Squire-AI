package dev.squire.server.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BellReviveConfigTest {

	@TempDir Path temp;

	@Test
	void defaultHealthTableUsesProfessionBandsAndCapsAtSixtyPercent() {
		BellReviveConfig config = BellReviveConfig.defaults();
		assertEquals(0.20, config.reviveHealth(BellTier.COMMON, 1), 0.00001);
		assertEquals(0.25, config.reviveHealth(BellTier.COMMON, 3), 0.00001);
		assertEquals(0.40, config.reviveHealth(BellTier.COMMON, 10), 0.00001);
		assertEquals(0.50, config.reviveHealth(BellTier.ENHANCED, 10), 0.00001);
		assertEquals(0.60, config.reviveHealth(BellTier.RESONANT, 10), 0.00001);
		assertEquals(0.60, config.reviveHealth(BellTier.ROYAL, 10), 0.00001);
	}

	@Test
	void cooldownsAndCumulativeDefensiveShelterMatchTheInitialRules() {
		BellReviveConfig config = BellReviveConfig.defaults();
		assertEquals(24_000L, config.reviveCooldown(BellTier.COMMON));
		assertEquals(18_000L, config.reviveCooldown(BellTier.ENHANCED));
		assertEquals(14_400L, config.reviveCooldown(BellTier.RESONANT));
		assertEquals(12_000L, config.reviveCooldown(BellTier.ROYAL));

		assertTrue(config.reviveBuff(BellTier.ENHANCED, 4).empty());
		assertEquals(1, config.reviveBuff(BellTier.ROYAL, 5).effects().size());
		assertEquals(160, config.reviveBuff(BellTier.RESONANT, 6).durationTicks());
		assertEquals(2, config.reviveBuff(BellTier.ROYAL, 7).effects().size());
		assertEquals(3, config.reviveBuff(BellTier.ROYAL, 10).effects().size());
		assertEquals(240, config.reviveBuff(BellTier.ROYAL, 10).durationTicks());
	}

	@Test
	void generatedConfigRoundTripsAndOffensiveEffectsFailClosed() throws Exception {
		Path valid = temp.resolve("valid.json");
		Files.writeString(valid, BellReviveConfig.defaultJson());
		BellReviveConfig loaded = BellReviveConfig.load(valid);
		assertEquals(0.55, loaded.reviveHealth(BellTier.ROYAL, 3), 0.00001);

		Path bad = temp.resolve("bad.json");
		Files.writeString(bad, BellReviveConfig.defaultJson().replace(
			"minecraft:resistance", "minecraft:strength"));
		BellReviveConfig fallback = BellReviveConfig.load(bad);
		assertEquals(0.30, fallback.rule(BellTier.ENHANCED).reviveHealth(), 0.00001);
		assertEquals("minecraft:resistance",
			fallback.rule(BellTier.ENHANCED).reviveBuff().effects().get(0).effectId());
	}
}
