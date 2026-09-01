package dev.squire.server.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ArgDefinitionTest {

	@Test
	void requiredNullGivesMissingMessage() {
		ArgDefinition arg = ArgDefinition.required("target", ArgDefinition.ArgType.STRING, "d");
		Optional<String> problem = arg.validate(null);
		assertTrue(problem.isPresent());
		assertTrue(problem.get().contains("'target'"));
	}

	@Test
	void optionalNullIsValid() {
		ArgDefinition arg = ArgDefinition.optional("count", ArgDefinition.ArgType.INT, "d");
		assertTrue(arg.validate(null).isEmpty());
	}

	@Test
	void intAcceptsIntegralNumbersOnly() {
		ArgDefinition arg = ArgDefinition.intRange("n", 1, 64, "d");
		assertTrue(arg.validate(32).isEmpty());
		assertTrue(arg.validate(32.0).isEmpty());
		assertTrue(arg.validate(32.5).isPresent());
		assertTrue(arg.validate(0).isPresent());
		assertTrue(arg.validate(65).isPresent());
		assertTrue(arg.validate("32").isPresent());
	}

	@Test
	void identifierTypesNeedNonBlankStrings() {
		ArgDefinition arg = ArgDefinition.required("item", ArgDefinition.ArgType.ITEM_ID, "d");
		assertTrue(arg.validate("minecraft:torch").isEmpty());
		assertTrue(arg.validate("").isPresent());
		assertTrue(arg.validate(7).isPresent());
	}

	@Test
	void booleanRejectsStrings() {
		ArgDefinition arg = ArgDefinition.optional("flag", ArgDefinition.ArgType.BOOLEAN, "d");
		assertTrue(arg.validate(Boolean.TRUE).isEmpty());
		assertTrue(arg.validate("true").isPresent());
	}

	@Test
	void doubleAcceptsAnyNumber() {
		ArgDefinition arg = ArgDefinition.required("x", ArgDefinition.ArgType.DOUBLE, "d");
		assertTrue(arg.validate(1.5).isEmpty());
		assertTrue(arg.validate(2).isEmpty());
		assertTrue(arg.validate("1.5").isPresent());
	}

	@Test
	void rangeBoundsIncludedInMessage() {
		ArgDefinition arg = ArgDefinition.intRange("n", 1, 8, "d");
		String msg = arg.validate(9).orElseThrow();
		assertEquals(true, msg.contains("[1, 8]"));
		assertFalse(msg.isBlank());
	}
}
