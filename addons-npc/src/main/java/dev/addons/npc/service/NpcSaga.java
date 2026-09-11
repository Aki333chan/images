package dev.addons.npc.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

/** Durable description of the money side of one NPC/Minecraft saga. */
public record NpcSaga(UUID id, Kind kind, State state, UUID holdId, String holdKey, UUID playerId,
                      AccountId from, AccountId to, String currencyId, BigDecimal amount,
                      TransactionCategory category, String referenceId, long createdAt,
                      Map<String, String> metadata) {
    public NpcSaga {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(state);
        Objects.requireNonNull(holdId); Objects.requireNonNull(holdKey); Objects.requireNonNull(playerId);
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(currencyId);
        Objects.requireNonNull(amount); Objects.requireNonNull(category); Objects.requireNonNull(referenceId);
        metadata = Map.copyOf(metadata);
    }

    public static NpcSaga held(Kind kind, UUID playerId, HoldSnapshot hold) {
        return new NpcSaga(UUID.randomUUID(), kind, State.HELD, hold.id(), hold.idempotencyKey(), playerId,
                hold.from(), hold.to(), hold.currency().id(), hold.amount(), hold.category(),
                hold.referenceId(), System.currentTimeMillis(), hold.metadata());
    }

    public NpcSaga state(State next) {
        return new NpcSaga(id, kind, next, holdId, holdKey, playerId, from, to, currencyId, amount,
                category, referenceId, createdAt, metadata);
    }

    public TransactionRequest captureRequest() {
        return new TransactionRequest("npc-saga:" + id, from, to, currencyId, amount, category, metadata);
    }

    public enum Kind { SHOP_PURCHASE, BUYER_SALE, GUILD_BONUS }
    public enum State { HELD, APPLIED }

    static AccountId account(String type, String reference) {
        return new AccountId(AccountType.valueOf(type), reference);
    }
}
