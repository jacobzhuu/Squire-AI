package dev.squire.common.protocol;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Protocol DTO invariants (spec section 23). */
class AgentRequestTest {
	private final UUID sender = UUID.randomUUID();
	private final UUID agent = UUID.randomUUID();

	@Test
	void validRequestCarriesFields() {
		AgentRequest request = new AgentRequest(UUID.randomUUID(), sender, agent, "hi");
		assertEquals(sender, request.senderId());
		assertEquals(agent, request.agentId());
		assertEquals("hi", request.message());
	}

	@Test
	void blankMessageRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> new AgentRequest(UUID.randomUUID(), sender, agent, "   "));
		assertThrows(IllegalArgumentException.class,
			() -> new AgentRequest(UUID.randomUUID(), sender, agent, null));
	}

	@Test
	void missingSenderOrRequestIdRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> new AgentRequest(null, sender, agent, "hi"));
		assertThrows(IllegalArgumentException.class,
			() -> new AgentRequest(UUID.randomUUID(), null, agent, "hi"));
	}

	@Test
	void responseNullTextBecomesEmpty() {
		assertEquals("", new AgentResponse(null).text());
		assertEquals("ok", AgentResponse.of("ok").text());
	}
}
