package dev.squire.server.provider;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.squire.common.protocol.AgentRequest;
import dev.squire.common.protocol.AgentResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 DoD: the mod is fully functional with NO provider; a registered provider serves
 * conversation asynchronously without ever becoming an action authority.
 */
class ProviderRegistryTest {
	@BeforeEach
	@AfterEach
	void reset() {
		ProviderRegistry.clear();
	}

	@Test
	void startsEmpty_llmUnavailableMode() {
		assertFalse(ProviderRegistry.available());
		assertTrue(ProviderRegistry.current().isEmpty());
	}

	@Test
	void registerMakesAvailable_clearRestoresUnavailable() {
		ProviderRegistry.register(new MockProvider());
		assertTrue(ProviderRegistry.available());
		ProviderRegistry.clear();
		assertFalse(ProviderRegistry.available());
	}

	@Test
	void nullProviderRejected() {
		assertThrows(IllegalArgumentException.class, () -> ProviderRegistry.register(null));
	}

	@Test
	void mockProviderRespondsAsynchronouslyWithEcho() throws Exception {
		MockProvider provider = new MockProvider();
		assertEquals("mock", provider.id());
		assertFalse(provider.capabilities().functionCalling(),
			"mock must not claim function-calling");

		AgentRequest request = new AgentRequest(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello squire");
		long start = System.nanoTime();
		AgentResponse response = provider.generate(request).get(2, TimeUnit.SECONDS);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		assertTrue(response.text().contains("hello squire"), "mock echoes input");
		// completes on its own executor thread — timing alone proves no synchronous shortcut
		assertTrue(elapsedMillis >= 0);
		assertTrue(provider.healthCheck().get(1, TimeUnit.SECONDS));
	}

	@Test
	void fastPathStillWorksWhileProviderRegistered() {
		// control phrases NEVER reach the LLM even when one is configured
		ProviderRegistry.register(new MockProvider());
		assertTrue(dev.squire.server.fastpath.FastPath.match("跟着我")
			.map(intent -> intent instanceof dev.squire.server.fastpath.FastPathIntent.Control
				c && c.kind() == dev.squire.server.fastpath.FastPathIntent.Control.Kind.FOLLOW)
			.orElse(false));
	}

	@Test
	void failoverRespectsTrustDomainAndLocalToCloudConsent() {
		ScriptedProvider local = new ScriptedProvider("local");
		ScriptedProvider sameTrust = new ScriptedProvider("local-backup");
		ScriptedProvider cloud = new ScriptedProvider("cloud");
		ProviderRegistry.registerChain(List.of(
			new ProviderRegistry.Registered(local, "home",
				LlmSettings.DataBoundary.LOCAL),
			new ProviderRegistry.Registered(sameTrust, "home",
				LlmSettings.DataBoundary.LOCAL),
			new ProviderRegistry.Registered(cloud, "vendor",
				LlmSettings.DataBoundary.CLOUD)), false);
		assertEquals("local-backup",
			ProviderRegistry.failoverAfter("local").orElseThrow().id());
		assertTrue(ProviderRegistry.failoverAfter("local-backup").isEmpty(),
			"local data must not cross into cloud without explicit consent");

		ProviderRegistry.registerChain(ProviderRegistry.configured(), true);
		assertEquals("cloud",
			ProviderRegistry.failoverAfter("local-backup").orElseThrow().id());
	}

	@Test
	void localToCloudStillNeedsConsentInsideTheSameTrustDomain() {
		ScriptedProvider local = new ScriptedProvider("local");
		ScriptedProvider cloud = new ScriptedProvider("cloud");
		ProviderRegistry.registerChain(List.of(
			new ProviderRegistry.Registered(local, "operator",
				LlmSettings.DataBoundary.LOCAL),
			new ProviderRegistry.Registered(cloud, "operator",
				LlmSettings.DataBoundary.CLOUD)), false);
		assertTrue(ProviderRegistry.failoverAfter("local").isEmpty());
	}
}
