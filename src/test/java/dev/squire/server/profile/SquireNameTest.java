package dev.squire.server.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SquireNameTest {

	@Test
	void chineseAndOrdinaryCallSignsAreAcceptedAndTrimmed() {
		var chinese = SquireName.validate("  豆包  ");
		assertTrue(chinese.valid());
		assertEquals("豆包", chinese.value());
		assertTrue(SquireName.validate("O'Brien-2").valid());
	}

	@Test
	void emptyOverlongAndFormattingNamesAreRejected() {
		assertEquals(SquireName.Error.EMPTY, SquireName.validate("   ").error());
		assertEquals(SquireName.Error.TOO_LONG,
			SquireName.validate("a".repeat(SquireName.MAX_LENGTH + 1)).error());
		assertEquals(SquireName.Error.ILLEGAL_CHARACTER,
			SquireName.validate("name\nspoof").error());
		assertEquals(SquireName.Error.ILLEGAL_CHARACTER,
			SquireName.validate("\u00a7cAdmin").error());
		assertFalse(SquireName.validate("path/to/me").valid());
	}
}
