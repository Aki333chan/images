package org.ChisaO_o.simpleSlots;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

record SpinRecord(UUID operationId, UUID holdId, String holdKey, UUID playerId, String machineId,
                  String currencyId, BigDecimal bet, BigDecimal reservedDebit, State state,
                  BigDecimal payout, long createdAt) {
    enum State { ACCEPTED, PAYOUT_PENDING }

    static SpinRecord accepted(UUID operationId, UUID playerId, String machineId, HoldSnapshot hold) {
        return new SpinRecord(operationId, hold.id(), hold.idempotencyKey(), playerId, machineId,
                hold.currency().id(), hold.amount(), hold.reservedAmount(), State.ACCEPTED,
                BigDecimal.ZERO, System.currentTimeMillis());
    }

    SpinRecord payout(BigDecimal amount) {
        return new SpinRecord(operationId, holdId, holdKey, playerId, machineId, currencyId,
                bet, reservedDebit, State.PAYOUT_PENDING, amount, createdAt);
    }

    Map<String, String> metadata() {
        return Map.of("plugin", "AurumSlots", "operation", operationId.toString(), "machine", machineId);
    }

    TransactionRequest captureRequest() {
        return new TransactionRequest("slots-capture:" + operationId, AccountId.player(playerId),
                new AccountId(AccountType.SLOTS, machineId), currencyId, bet,
                TransactionCategory.SLOT_BET, metadata());
    }
}
