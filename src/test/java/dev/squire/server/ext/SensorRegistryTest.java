package dev.squire.server.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.squire.api.sensor.SensorContext;
import dev.squire.api.sensor.SensorDefinition;
import dev.squire.api.sensor.SensorResult;
import dev.squire.api.sensor.SquireSensor;
import dev.squire.server.security.ToolTrust;

/**
 * Third-party sensor registry unit tests (spec section 56): budget enforcement,
 * degradation isolation, duplicate refusal.
 */
class SensorRegistryTest {

	private final SensorRegistry registry = new SensorRegistry();
	private final UUID agent = UUID.randomUUID();
	private final UUID owner = UUID.randomUUID();

	private static SquireSensor sensor(String id, int budget,
			java.util.function.Function<SensorContext, SensorResult> body) {
		return new SquireSensor() {
			@Override
			public SensorDefinition definition() {
				return new SensorDefinition(id, 8, 16, 50, budget);
			}

			@Override
			public SensorResult observe(SensorContext context) {
				return body.apply(context);
			}
		};
	}

	@Test
	void outputIsSortedKeyValuesWithinBudget() {
		assertTrue(registry.register(
			sensor("t:budget", 64, ctx -> SensorResult.of(Map.of("zeta", "1", "alpha", "2"))),
			ToolTrust.UNTRUSTED, "mod:t"));

		List<String> lines = registry.observe(agent, owner, 777L);
		assertEquals(1, lines.size());
		String line = lines.get(0);
		assertTrue(line.startsWith("[sensor t:budget]"), line);
		assertTrue(line.indexOf("alpha") >= 0 && line.indexOf("alpha") < line.indexOf("zeta"),
			line); // sorted keys survive intact
		assertTrue(line.length() <= 64, line); // budget holds
	}

	@Test
	void oversizedLineTruncatedWithEllipsis() {
		assertTrue(registry.register(sensor("t:big", 32, ctx -> SensorResult.of(Map.of(
			"k", "x".repeat(500)))), ToolTrust.UNTRUSTED, "mod:t"));
		String line = registry.observe(agent, owner, 1L).get(0);
		assertEquals(32, line.length());
		assertTrue(line.endsWith("…"));
	}

	@Test
	void throwingSensorDegradesInsteadOfPropagating() {
		assertTrue(registry.register(sensor("t:boom", 64,
			ctx -> {
				throw new IllegalStateException("provider bug");
			}), ToolTrust.UNTRUSTED, "mod:t"));
		List<String> lines = registry.observe(agent, owner, 5L); // must not throw
		assertEquals(1, lines.size());
		assertTrue(lines.get(0).contains("degraded"), lines.get(0));
	}

	@Test
	void nullResultAlsoDegrades() {
		assertTrue(registry.register(sensor("t:null", 64, ctx -> null),
			ToolTrust.UNTRUSTED, "mod:t"));
		assertTrue(registry.observe(agent, owner, 5L).get(0).contains("degraded"));
	}

	@Test
	void unhealthyFlagSurfacesStatusMessage() {
		assertTrue(registry.register(sensor("t:sick", 128,
			ctx -> SensorResult.degraded("cold storage unreachable")),
			ToolTrust.UNTRUSTED, "mod:t"));
		String line = registry.observe(agent, owner, 9L).get(0);
		assertFalse(line.startsWith("[sensor t:sick]")); // '!' marker instead of ']'
		assertTrue(line.contains("status=cold storage unreachable"));
	}

	@Test
	void duplicateIdRefusedAndCapHolds() {
		assertTrue(registry.register(
			sensor("t:dup", 64, ctx -> SensorResult.of(Map.of())), ToolTrust.UNTRUSTED,
			"mod:a"));
		assertFalse(registry.register(
			sensor("t:dup", 64, ctx -> SensorResult.of(Map.of())), ToolTrust.UNTRUSTED,
			"mod:b"));
		for (int i = 0; i < 64; i++) {
			registry.register(sensor("t:filler" + i, 32, ctx -> SensorResult.of(Map.of())),
				ToolTrust.UNTRUSTED, "mod:t");
		}
		assertEquals(SensorRegistry.MAX_SENSORS, registry.all().size());
	}
}
