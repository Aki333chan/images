package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class OfferStockTest {
    @Test void fullShelfWaitsForFirstTradeAndOtherTradesDoNotRestartTimer() {
        long now = System.currentTimeMillis();
        OfferStock stock = new OfferStock(64);
        stock.configure(64, 60, now);
        assertEquals(0, stock.dueAt());
        stock.consume(4, now);
        assertEquals(now + 60_000, stock.dueAt());
        stock.consume(8, now + 10_000);
        assertEquals(now + 60_000, stock.dueAt());
        assertEquals(52, stock.remaining());
        assertFalse(stock.refresh(now + 59_999));
        assertTrue(stock.refresh(now + 60_000));
        assertEquals(64, stock.remaining());
        assertEquals(0, stock.dueAt());
    }

    @Test void noMoreThanRemainingCanBeReservedAndFailureRestoresOnlyOwnQuantity() {
        long now = System.currentTimeMillis();
        OfferStock stock = new OfferStock(10);
        stock.configure(10, 60, now);
        String cycle = stock.consume(6, now);
        stock.consume(3, now);
        assertThrows(IllegalArgumentException.class, () -> stock.consume(2, now));
        stock.restore(6, cycle, now);
        assertEquals(7, stock.remaining());
        assertNotEquals(0, stock.dueAt());
        stock.restore(3, cycle, now);
        assertEquals(10, stock.remaining());
        assertEquals(0, stock.dueAt());
    }

    @Test void lateRollbackCannotAddStockIntoNewCycle() {
        long now = System.currentTimeMillis();
        OfferStock stock = new OfferStock(10);
        stock.configure(10, 60, now);
        String old = stock.consume(6, now);
        stock.consume(3, now + 60_001);
        stock.restore(6, old, now + 60_002);
        assertEquals(7, stock.remaining());
    }

    @Test void deadlineAndMaximumSurviveRestartIncludingOfflineExpiry() {
        long now = System.currentTimeMillis();
        OfferStock stock = new OfferStock(64);
        stock.configure(64, 60, now);
        String cycle = stock.consume(12, now);
        var yaml = new YamlConfiguration();
        stock.save(yaml.createSection("restock"));
        OfferStock restored = new OfferStock(stock.remaining());
        restored.load(yaml.getConfigurationSection("restock"));
        assertEquals(52, restored.remaining());
        assertEquals(64, restored.maximum());
        assertEquals(cycle, restored.cycle());
        assertEquals(now + 60_000, restored.dueAt());
        assertTrue(restored.refresh(now + 120_000));
        assertEquals(64, restored.remaining());
        assertEquals(0, restored.dueAt());
    }

    @Test void unlimitedAndDisabledRefillNeverStartClock() {
        long now = System.currentTimeMillis();
        OfferStock stock = new OfferStock(-1);
        stock.consume(1000, now);
        assertEquals(-1, stock.remaining());
        assertEquals(0, stock.dueAt());
        stock.configure(10, 60, now);
        stock.consume(1, now);
        stock.disableRefill();
        assertFalse(stock.refresh(now + 3600_000));
        assertEquals(9, stock.remaining());
        assertEquals(0, stock.dueAt());
    }
}
