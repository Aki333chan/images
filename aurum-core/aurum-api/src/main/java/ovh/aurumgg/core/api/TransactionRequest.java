package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public record TransactionRequest(
        String idempotencyKey,
        AccountId from,
        AccountId to,
        String currencyId,
        BigDecimal amount,
        TransactionCategory category,
        Map<String, String> metadata
) {
    public TransactionRequest {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        currencyId = Objects.requireNonNull(currencyId, "currencyId").trim();
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(category, "category");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 191) {
            throw new IllegalArgumentException("Idempotency key must contain 1..191 characters");
        }
        if (from.equals(to) || amount.signum() <= 0) {
            throw new IllegalArgumentException("Transaction needs different accounts and positive amount");
        }
        if (metadata.size() > 32 || metadata.entrySet().stream().anyMatch(entry ->
                entry.getKey().isBlank() || entry.getKey().length() > 64 || entry.getValue().length() > 512
                        || entry.getKey().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Transaction metadata exceeds its safe limits");
        }
    }
}
