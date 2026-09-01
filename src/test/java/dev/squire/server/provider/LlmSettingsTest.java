package dev.squire.server.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmSettingsTest {
	@TempDir Path dir;

	@Test
	void legacySingleProviderConfigStillLoads() throws Exception {
		Path file = dir.resolve("llm.json");
		Files.writeString(file, """
			{"type":"openai-compatible","baseUrl":"http://localhost:11434/v1",
			 "apiKey":"local","model":"qwen","toolCandidateLimit":12}
			""");
		LlmSettings settings = LlmSettings.load(file).orElseThrow();
		assertEquals(1, settings.providers().size());
		assertEquals(12, settings.providers().get(0).config().toolCandidateLimit());
		assertFalse(settings.allowLocalToCloudFailover());
	}

	@Test
	void providerChainCarriesExplicitBoundariesAndPrimaryOrder() throws Exception {
		Path file = dir.resolve("llm.json");
		Files.writeString(file, """
			{"primary":"local","allowLocalToCloudFailover":true,"providers":[
			 {"name":"cloud","type":"openai-compatible","baseUrl":"https://api.example/v1",
			  "apiKey":"cloud-key","model":"cloud-model","dataBoundary":"cloud","priority":20},
			 {"name":"local","type":"openai-compatible","baseUrl":"http://localhost:11434/v1",
			  "apiKey":"local-key","model":"local-model","dataBoundary":"local","priority":10}
			]}
			""");
		LlmSettings settings = LlmSettings.load(file).orElseThrow();
		assertTrue(settings.allowLocalToCloudFailover());
		assertEquals("local", settings.ordered().get(0).name());
		assertEquals(LlmSettings.DataBoundary.CLOUD,
			settings.providers().get(0).dataBoundary());
	}
}
