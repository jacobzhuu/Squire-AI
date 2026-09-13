package dev.squire.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameEditStateTest {
    @Test void backgroundRefreshDoesNotEraseTyping() {
        var editor = new NameEditState();
        editor.sync("engineer", "Builder");
        editor.edit("New name");
        editor.sync("engineer", "Builder");
        assertEquals("New name", editor.draft());
        assertTrue(editor.dirty());
    }

    @Test void switchingCompanionsCannotCarryARenameToTheOtherCompanion() {
        var editor = new NameEditState();
        editor.sync("engineer", "Builder");
        editor.edit("Unsent draft");
        editor.sync("guard", "Knight");
        assertEquals("Knight", editor.draft());
        assertFalse(editor.dirty());
    }

    @Test void serverAcknowledgementAcceptsDraftAndLaterUpdatesRemainVisible() {
        var editor = new NameEditState();
        editor.sync("engineer", "Builder");
        editor.edit("New name");
        editor.sync("engineer", "New name");
        assertFalse(editor.dirty());
        editor.sync("engineer", "Renamed externally");
        assertEquals("Renamed externally", editor.draft());
    }

    @Test void resetRestoresLatestAuthoritativeName() {
        var editor = new NameEditState();
        editor.sync("engineer", "Builder");
        editor.edit("");
        editor.sync("engineer", "Renamed externally");
        assertEquals("", editor.draft());
        editor.reset();
        assertEquals("Renamed externally", editor.draft());
        assertFalse(editor.dirty());
    }
}
