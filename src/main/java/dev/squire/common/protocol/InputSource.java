package dev.squire.common.protocol;

/** Where an {@link InputEnvelope} came from (spec section 12). */
public enum InputSource {
	CHAT,
	COMMAND,
	GUI,
	QUICK_ACTION,
	SYSTEM
}
