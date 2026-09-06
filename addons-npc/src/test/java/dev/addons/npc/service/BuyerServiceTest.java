package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.addons.npc.model.BuyerOffer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class BuyerServiceTest {
    @Test
    void countsAndRemovesMatchingStacksWithoutTouchingOtherItems() {
        BuyerOffer offer = new BuyerOffer(0, new ItemStack(Material.WHEAT), 1.0);
        ItemStack[] contents = {
                new ItemStack(Material.WHEAT, 64),
                new ItemStack(Material.CARROT, 12),
                new ItemStack(Material.WHEAT, 10)
        };

        assertEquals(74, BuyerService.countMatching(contents, offer));
        assertEquals(65, BuyerService.removeMatching(contents, offer, 65));
        assertNull(contents[0]);
        assertEquals(12, contents[1].getAmount());
        assertEquals(Material.CARROT, contents[1].getType());
        assertEquals(9, contents[2].getAmount());
    }

    @Test
    void removalReportsActualAmountWhenRequestIsTooLarge() {
        BuyerOffer offer = new BuyerOffer(0, new ItemStack(Material.COAL), 1.0);
        ItemStack[] contents = {new ItemStack(Material.COAL, 3)};

        assertEquals(3, BuyerService.removeMatching(contents, offer, 10));
        assertNull(contents[0]);
    }
}
