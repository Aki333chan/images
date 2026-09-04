package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ShopOfferTest {
    @Test
    void unlimitedStockNeverDecreases() {
        ShopOffer offer = new ShopOffer(0, Material.BREAD, -1, 5.0);
        offer.quantity(8);
        offer.consume();
        assertTrue(offer.unlimited());
        assertTrue(offer.available());
        assertEquals(-1, offer.stock());
    }

    @Test
    void finiteStockDecreasesByPurchaseQuantity() {
        ShopOffer offer = new ShopOffer(0, Material.BREAD, 10, 5.0);
        offer.quantity(4);
        offer.consume();
        assertEquals(6, offer.stock());
        assertTrue(offer.available());
        offer.consume();
        assertEquals(2, offer.stock());
        assertFalse(offer.available());
    }
}
