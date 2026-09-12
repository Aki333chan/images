package ovh.aurumgg.companion.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Panel-authored absolute balance replacement with optimistic concurrency. */
public record BalanceSetMutation(
        String idempotencyKey,
        BigDecimal expectedBalance,
        BigDecimal targetBalance,
        String currencyId,
        String actor,
        String reason) {

    public BalanceSetMutation {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        Objects.requireNonNull(expectedBalance, "expectedBalance");
        Objects.requireNonNull(targetBalance, "targetBalance");
        currencyId = currencyId == null ? "" : currencyId.trim();
        actor = Objects.requireNonNull(actor, "actor").trim();
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 160) {
            throw new IllegalArgumentException("idempotencyKey must contain 1..160 characters");
        }
        if (expectedBalance.signum() < 0 || targetBalance.signum() < 0) {
            throw new IllegalArgumentException("balances cannot be negative");
        }
        if (actor.isEmpty() || actor.length() > 128) throw new IllegalArgumentException("invalid actor");
        if (reason.isEmpty() || reason.length() > 200) throw new IllegalArgumentException("invalid reason");
    }
}
