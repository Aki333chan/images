package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record HoldSnapshot(UUID id, String idempotencyKey, AccountId from, AccountId to,
                           CurrencySpec currency, BigDecimal amount, BigDecimal reservedAmount,
                           TransactionCategory category, String purpose, String referenceId,
                           Status status, Instant createdAt, Instant expiresAt,
                           Map<String, String> metadata) {
    public HoldSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(currency, "currency");
        amount = currency.requireAmount(amount);
        reservedAmount = currency.requireAmount(reservedAmount);
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        metadata = Map.copyOf(metadata);
    }

    public enum Status { HELD, CAPTURED, RELEASED, EXPIRED, REJECTED }
}
