package dev.addons.npc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;

class NpcSagaTest {
    @Test
    void appliedSagaPreservesTheExactReservedTransactionIntent() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000101");
        HoldSnapshot hold = new HoldSnapshot(UUID.randomUUID(), "npc-shop:operation",
                AccountId.player(player), new AccountId(AccountType.NPC_SHOP, "food"),
                new CurrencySpec("coins", "Coins", "$", 2), new BigDecimal("12.50"),
                new BigDecimal("13.75"), TransactionCategory.NPC_PURCHASE, "npc-shop", "food:4",
                HoldSnapshot.Status.HELD, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"), Map.of("plugin", "AddonsNPC"));

        NpcSaga applied = NpcSaga.held(NpcSaga.Kind.SHOP_PURCHASE, player, hold)
                .state(NpcSaga.State.APPLIED);

        assertEquals(NpcSaga.State.APPLIED, applied.state());
        assertEquals(hold.id(), applied.holdId());
        assertEquals(hold.from(), applied.captureRequest().from());
        assertEquals(hold.to(), applied.captureRequest().to());
        assertEquals(hold.amount(), applied.captureRequest().amount());
        assertEquals(hold.metadata(), applied.captureRequest().metadata());
    }
}
