package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimedPercentageTest {
    @Test
    void appliesDiscountAndBonusWhileActive() {
        long now = 1_000_000L;
        TimedPercentage modifier = new TimedPercentage(20, now + 60_000);

        assertTrue(modifier.active(now));
        assertEquals(80.0, modifier.discount(100, now));
        assertEquals(120.0, modifier.bonus(100, now));
    }

    @Test
    void expiredModifierDoesNotChangePrices() {
        TimedPercentage modifier = new TimedPercentage(50, 999);

        assertFalse(modifier.active(1_000));
        assertEquals(100.0, modifier.discount(100, 1_000));
        assertEquals(100.0, modifier.bonus(100, 1_000));
    }

    @Test
    void permanentModifierHasNoExpiry() {
        TimedPercentage modifier = new TimedPercentage(15, 0);

        assertTrue(modifier.permanent());
        assertTrue(modifier.active(Long.MAX_VALUE));
        assertEquals("до отключения", modifier.remaining(Long.MAX_VALUE));
    }
}
