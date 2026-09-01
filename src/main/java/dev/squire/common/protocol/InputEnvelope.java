package dev.squire.common.protocol;

import java.util.UUID;

/**
 * Unified input envelope entering the Input Gateway (spec section 12).
 *
 * <p>Pure protocol DTO: sender / agent / role / permission are resolved BEFORE any NLP runs.
 * This type must stay free of Minecraft imports.</p>
 */
public record InputEnvelope(
	UUID senderId,
	InputSource source,
	UUID agentId,
	String rawText,
	long receivedTick
) {
	public InputEnvelope {
		if (senderId == null) {
			throw new IllegalArgumentException("senderId must not be null");
		}
		if (source == null) {
			throw new IllegalArgumentException("source must not be null");
		}
		if (rawText == null) {
			throw new IllegalArgumentException("rawText must not be null");
		}
	}

	public static InputEnvelope chat(UUID senderId, UUID agentId, String rawText, long tick) {
		return new InputEnvelope(senderId, InputSource.CHAT, agentId, rawText, tick);
	}

	public boolean hasAgent() {
		return agentId != null;
	}
}
