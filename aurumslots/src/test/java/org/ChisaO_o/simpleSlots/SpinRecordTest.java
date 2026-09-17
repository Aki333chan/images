package org.ChisaO_o.simpleSlots;

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

class SpinRecordTest {
    @Test
    void capturedBetKeepsExactCoreIntent() {
        UUID operation = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HoldSnapshot hold = new HoldSnapshot(UUID.randomUUID(), "slots-bet:" + operation,
                AccountId.player(player), new AccountId(AccountType.SLOTS, "spawn"),
                new CurrencySpec("coins", "Coins", "$", 2), new BigDecimal("10.00"),
                new BigDecimal("10.00"), TransactionCategory.SLOT_BET, "slot-spin", "spawn",
                HoldSnapshot.Status.HELD, Instant.now(), Instant.now().plusSeconds(60), Map.of());

        AccountId payoutSource = new AccountId(AccountType.SLOTS, "spawn");
        SpinRecord record = SpinRecord.accepted(operation, player, "spawn", hold, payoutSource);
        var capture = record.captureRequest();

        assertEquals("slots-capture:" + operation, capture.idempotencyKey());
        assertEquals(AccountId.player(player), capture.from());
        assertEquals(new AccountId(AccountType.SLOTS, "spawn"), capture.to());
        assertEquals(new AccountId(AccountType.SLOTS, "spawn"), record.machineAccount());
        assertEquals(new BigDecimal("10.00"), capture.amount());
        assertEquals(new BigDecimal("10.00"), record.reservedDebit());
        assertEquals(TransactionCategory.SLOT_BET, capture.category());
    }

    @Test
    void generatedMachineAccountSurvivesCaptureIdentity() {
        UUID operation = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        AccountId generated = new AccountId(AccountType.SLOTS, "spawn~generation");
        HoldSnapshot hold = new HoldSnapshot(UUID.randomUUID(), "slots-bet:" + operation,
                AccountId.player(player), generated,
                new CurrencySpec("coins", "Coins", "$", 2), new BigDecimal("5.00"),
                new BigDecimal("5.00"), TransactionCategory.SLOT_BET, "slot-spin", "spawn",
                HoldSnapshot.Status.HELD, Instant.now(), Instant.now().plusSeconds(60), Map.of());

        SpinRecord record = SpinRecord.accepted(operation, player, "spawn", hold, generated);

        assertEquals(generated, record.machineAccount());
        assertEquals(generated, record.captureRequest().to());
    }

    @Test
    void pendingPayoutDoesNotMutateOriginalRecord() {
        UUID operation = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        HoldSnapshot hold = new HoldSnapshot(UUID.randomUUID(), "slots-bet:" + operation,
                AccountId.player(player), new AccountId(AccountType.SLOTS, "spawn"),
                new CurrencySpec("coins", "Coins", "$", 2), new BigDecimal("5.00"),
                new BigDecimal("5.00"), TransactionCategory.SLOT_BET, "slot-spin", "spawn",
                HoldSnapshot.Status.CAPTURED, Instant.now(), Instant.now().plusSeconds(60), Map.of());
        AccountId payoutSource = new AccountId(AccountType.TREASURY, "casino");
        SpinRecord accepted = SpinRecord.accepted(operation, player, "spawn", hold, payoutSource);

        SpinRecord pending = accepted.payout(new BigDecimal("15.00"));

        assertEquals(SpinRecord.State.ACCEPTED, accepted.state());
        assertEquals(SpinRecord.State.PAYOUT_PENDING, pending.state());
        assertEquals(new BigDecimal("15.00"), pending.payout());
        assertEquals(payoutSource, pending.payoutSource());
    }
}
