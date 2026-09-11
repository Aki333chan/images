package dev.addons.npc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExchangerDefinitionTest {
    @Test
    void validatesOffersAndMenuResize() {
        ExchangerDefinition exchanger = new ExchangerDefinition("Bank", "&8Bank", 27);
        ExchangeOffer offer = new ExchangeOffer(26, "coins", "gems", new BigDecimal("10.50"));
        exchanger.offers().put(offer.slot(), offer);

        assertEquals("bank", exchanger.id());
        assertEquals("coins", offer.fromCurrency());
        assertThrows(IllegalArgumentException.class, () -> exchanger.size(18));
    }

    @Test
    void rejectsInvalidPairsAndAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExchangeOffer(0, "coins", "coins", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new ExchangeOffer(0, "coins", "gems", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExchangeOffer(54, "coins", "gems", BigDecimal.ONE));
    }
}
