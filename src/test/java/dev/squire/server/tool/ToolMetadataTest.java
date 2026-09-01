package dev.squire.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolMetadataTest {
	@Test
	void descriptorCarriesRegistryOwnedRetrievalMetadata() {
		ToolDefinition definition = ToolDefinition.builder("weatherbox:forecast")
			.description("天气预报").permission(AgentPermission.QUERY)
			.tag("气象").alias("天气预报").build();
		var descriptor = definition.descriptor();
		assertEquals(java.util.List.of("气象"), descriptor.tags());
		assertEquals(java.util.List.of("天气预报"), descriptor.aliases());
		assertTrue(descriptor.readOnly());
	}
}
