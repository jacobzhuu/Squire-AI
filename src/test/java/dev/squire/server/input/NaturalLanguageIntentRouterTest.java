package dev.squire.server.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NaturalLanguageIntentRouterTest {
	@Test
	void recognisesHighConfidenceDialogueControlsOnly() {
		assertEquals(NaturalLanguageIntentRouter.Kind.CANCEL,
			NaturalLanguageIntentRouter.route("算了！").kind());
		assertEquals(NaturalLanguageIntentRouter.Kind.CORRECTION,
			NaturalLanguageIntentRouter.route("不是村庄，是古城").kind());
		assertEquals(NaturalLanguageIntentRouter.Kind.CONTINUATION,
			NaturalLanguageIntentRouter.route("继续").kind());
		assertEquals(NaturalLanguageIntentRouter.Kind.ORDINARY,
			NaturalLanguageIntentRouter.route("给我盖一座房子").kind());
		assertTrue(NaturalLanguageIntentRouter.route("改成两层").confidence() > 0.9);
	}
}
