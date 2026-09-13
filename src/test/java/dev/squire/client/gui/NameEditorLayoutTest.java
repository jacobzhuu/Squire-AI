package dev.squire.client.gui;

import dev.squire.server.gui.SquireScreenHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameEditorLayoutTest {
    @Test void sidebarActionsHaveSeparateRowsAboveTheFooter() {
        assertTrue(SquireScreen.RECALL_BUTTON_Y >= IpnCompat.stripBottom() + 4);
        assertTrue(SquireScreen.EQUIP_BUTTON_Y >= SquireScreen.RECALL_BUTTON_Y + 18 + 4);
        assertTrue(SquireScreen.EQUIP_BUTTON_Y + 18 <= SquireScreen.CONTENT_BOTTOM);
    }

    @Test void profileEditorHasSeparateHitAreasBelowTheCompanionSwitcher() {
        var content = new PanelLayout.Rect(8, SquireScreenHandler.CONTENT_TOP,
            SquireScreen.CONTENT_RIGHT, SquireScreen.CONTENT_BOTTOM);
        var header = new PanelLayout.Rect(0, 0, SquireScreen.PANEL_WIDTH, SquireScreen.STATE_ROW_Y);
        var controls = PanelLayout.nameEditorRow(8, SquireScreenHandler.CONTENT_TOP + 30,
            SquireScreen.contentWidth(), 18);
        for (int i = 0; i < controls.size(); i++) {
            assertTrue(content.contains(controls.get(i)));
            assertFalse(header.intersects(controls.get(i)));
            for (int j = i + 1; j < controls.size(); j++) {
                assertFalse(controls.get(i).intersects(controls.get(j)));
            }
        }
        assertTrue(controls.get(0).right() - controls.get(0).left() >= 100);
    }
}
