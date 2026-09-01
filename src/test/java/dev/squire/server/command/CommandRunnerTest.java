package dev.squire.server.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandRunnerTest {

	@Test
	void routesOnlyCommandsOverInlineLimitThroughCommandBlock() {
		assertFalse(CommandRunner.requiresCommandBlock(null));
		assertFalse(CommandRunner.requiresCommandBlock("x".repeat(
			CommandRunner.INLINE_LIMIT)));
		assertFalse(CommandRunner.requiresCommandBlock("/" + "x".repeat(
			CommandRunner.INLINE_LIMIT)));
		assertTrue(CommandRunner.requiresCommandBlock("x".repeat(
			CommandRunner.INLINE_LIMIT + 1)));
		assertTrue(CommandRunner.requiresCommandBlock("/" + "x".repeat(
			CommandRunner.INLINE_LIMIT + 1)));
	}
}
