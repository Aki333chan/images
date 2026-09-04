package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class BuyerOfferTest {
    @Test
    void calculatesSingleAndAllUnitSales() {
        BuyerOffer offer = new BuyerOffer(0, new ItemStack(Material.WHEAT), 1.25);

        assertEquals(new BuyerOffer.SaleQuote(1, 1.25), offer.quote(BuyerOffer.SaleMode.UNIT_ONE, 130));
        assertEquals(new BuyerOffer.SaleQuote(130, 162.5), offer.quote(BuyerOffer.SaleMode.UNIT_ALL, 130));
        assertNull(offer.quote(BuyerOffer.SaleMode.UNIT_ONE, 0));
    }

    @Test
    void bulkAllSellsOnlyCompleteLotsAndLeavesRemainder() {
        BuyerOffer offer = new BuyerOffer(0, new ItemStack(Material.WHEAT), 1.25);
        offer.bulk(64, 100.0);

        assertEquals(new BuyerOffer.SaleQuote(64, 100.0), offer.quote(BuyerOffer.SaleMode.BULK_ONE, 130));
        assertEquals(new BuyerOffer.SaleQuote(128, 200.0), offer.quote(BuyerOffer.SaleMode.BULK_ALL, 130));
        assertNull(offer.quote(BuyerOffer.SaleMode.BULK_ONE, 63));
    }

    @Test
    void disablingBulkLeavesUnitSalesAvailable() {
        BuyerOffer offer = new BuyerOffer(0, new ItemStack(Material.DIAMOND), 50.0);
        offer.bulk(0, 0);

        assertFalse(offer.bulkEnabled());
        assertNull(offer.quote(BuyerOffer.SaleMode.BULK_ALL, 64));
        assertEquals(new BuyerOffer.SaleQuote(64, 3200.0), offer.quote(BuyerOffer.SaleMode.UNIT_ALL, 64));
    }

    @Test
    void materialModeMatchesOnlyTheConfiguredMaterial() {
        BuyerOffer offer = new BuyerOffer(0, new ItemStack(Material.IRON_INGOT), 2.0);

        assertTrue(offer.matches(new ItemStack(Material.IRON_INGOT, 32)));
        assertFalse(offer.matches(new ItemStack(Material.GOLD_INGOT)));
        assertFalse(offer.matches(null));
    }
}
