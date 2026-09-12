package ovh.aurumgg.companion.core.model;

import java.math.BigDecimal;
import java.util.Objects;

/** A panel-authored, retryable balance mutation. */
public record BalanceMutation(
        String idempotencyKey,
        Operation operation,
        BigDecimal amount,
        String currencyId,
        String actor,
        String reason) {

    public BalanceMutation {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(amount, "amount");
        currencyId = currencyId == null ? "" : currencyId.trim();
        actor = Objects.requireNonNull(actor, "actor").trim();
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 160) {
            throw new IllegalArgumentException("idempotencyKey must contain 1..160 characters");
        }
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (actor.isEmpty() || actor.length() > 128) throw new IllegalArgumentException("invalid actor");
        if (reason.isEmpty() || reason.length() > 200) throw new IllegalArgumentException("invalid reason");
    }

    public enum Operation { GIVE, TAKE }
}
