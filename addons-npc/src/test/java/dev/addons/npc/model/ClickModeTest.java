package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClickModeTest {
    @Test
    void acceptsConfiguredButtons() {
        assertTrue(ClickMode.RIGHT.accepts(true));
        assertFalse(ClickMode.RIGHT.accepts(false));
        assertTrue(ClickMode.LEFT.accepts(false));
        assertTrue(ClickMode.BOTH.accepts(true));
        assertTrue(ClickMode.BOTH.accepts(false));
    }
}

