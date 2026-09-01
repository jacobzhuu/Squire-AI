package dev.squire.api.provider;

/**
 * What a provider supports, declared statically (spec section 23).
 * The runtime degrades gracefully: no function-calling means no tool use, never a fallback to raw commands.
 */
public record ProviderCapabilities(boolean functionCalling, boolean streaming, long maxContextTokens) {
	public static ProviderCapabilities minimal() {
		return new ProviderCapabilities(false, false, 8_192);
	}

	public static ProviderCapabilities full(long maxContextTokens) {
		return new ProviderCapabilities(true, true, maxContextTokens);
	}
}
