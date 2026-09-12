package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Compare-and-set for an administrative player balance replacement.
 *
 * <p>The expected value is part of the immutable intent. A caller that loses
 * the response repeats this exact request and key; it never reads a newer
 * balance and silently turns the same key into a different delta.
 */
public record BalanceSetRequest(
        String idempotencyKey,
        AccountId account,
        String currencyId,
        BigDecimal expectedBalance,
        BigDecimal targetBalance,
        Map<String, String> metadata) {

    public BalanceSetRequest {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        Objects.requireNonNull(account, "account");
        currencyId = Objects.requireNonNull(currencyId, "currencyId").trim();
        Objects.requireNonNull(expectedBalance, "expectedBalance");
        Objects.requireNonNull(targetBalance, "targetBalance");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 191) {
            throw new IllegalArgumentException("Idempotency key must contain 1..191 characters");
        }
        if (currencyId.isEmpty() || currencyId.length() > 32) {
            throw new IllegalArgumentException("Currency id must contain 1..32 characters");
        }
        if (expectedBalance.signum() < 0 || targetBalance.signum() < 0) {
            throw new IllegalArgumentException("Expected and target balances cannot be negative");
        }
        if (metadata.size() > 32 || metadata.entrySet().stream().anyMatch(entry ->
                entry.getKey().isBlank() || entry.getKey().length() > 64 || entry.getValue().length() > 512
                        || entry.getKey().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Balance set metadata exceeds its safe limits");
        }
    }
}
