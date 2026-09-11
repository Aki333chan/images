package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Request to reserve funds for a short cross-system operation such as item delivery. */
public record HoldRequest(String idempotencyKey, AccountId from, AccountId to, String currencyId,
                          BigDecimal amount, TransactionCategory category, String purpose,
                          String referenceId, Instant expiresAt, Map<String, String> metadata) {
    public HoldRequest {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        currencyId = Objects.requireNonNull(currencyId, "currencyId").trim().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(category, "category");
        purpose = Objects.requireNonNull(purpose, "purpose").trim().toLowerCase(Locale.ROOT);
        referenceId = Objects.requireNonNull(referenceId, "referenceId").trim();
        Objects.requireNonNull(expiresAt, "expiresAt");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 191 || from.equals(to)
                || !currencyId.matches("[a-z0-9][a-z0-9_-]{0,31}")
                || amount.signum() <= 0 || purpose.isEmpty() || purpose.length() > 48
                || !purpose.matches("[a-z0-9][a-z0-9_.-]*") || referenceId.isEmpty()
                || referenceId.length() > 128 || referenceId.chars().anyMatch(Character::isISOControl)
                || metadata.size() > 32) {
            throw new IllegalArgumentException("Invalid hold request");
        }
        metadata.forEach((key, value) -> {
            if (key == null || value == null || key.isBlank() || key.length() > 64 || value.length() > 255
                    || key.chars().anyMatch(Character::isISOControl)
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid hold metadata");
            }
        });
    }

    public TransactionRequest transaction(String key, Map<String, String> resolvedMetadata) {
        return new TransactionRequest(key, from, to, currencyId, amount, category, resolvedMetadata);
    }
}
