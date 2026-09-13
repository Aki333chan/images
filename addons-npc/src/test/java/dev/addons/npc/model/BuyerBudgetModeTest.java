package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BuyerBudgetModeTest {
    @Test
    void aliasesResolveToIndependentBuyerBudget() {
        assertEquals(BuyerBudgetMode.BUYER, BuyerBudgetMode.parse("buyer"));
        assertEquals(BuyerBudgetMode.BUYER, BuyerBudgetMode.parse("own"));
        assertEquals(BuyerBudgetMode.BUYER, BuyerBudgetMode.parse("npc_buyer"));
    }

    @Test
    void invalidModeIsRejectedInsteadOfMinting() {
        assertThrows(IllegalArgumentException.class, () -> BuyerBudgetMode.parse("system"));
    }
}
