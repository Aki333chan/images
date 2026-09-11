package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

public record ExchangeRequest(
        String idempotencyKey,
        AccountId account,
        String fromCurrencyId,
        String toCurrencyId,
        BigDecimal sourceAmount,
        String expectedRuleId,
        long expectedRuleRevision,
        BigDecimal expectedTargetAmount,
        Instant quoteExpiresAt,
        Map<String, String> metadata
) {
    public ExchangeRequest {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
        Objects.requireNonNull(account, "account");
        fromCurrencyId = Objects.requireNonNull(fromCurrencyId, "fromCurrencyId").trim().toLowerCase(Locale.ROOT);
        toCurrencyId = Objects.requireNonNull(toCurrencyId, "toCurrencyId").trim().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(sourceAmount, "sourceAmount");
        expectedRuleId = Objects.requireNonNull(expectedRuleId, "expectedRuleId").trim();
        Objects.requireNonNull(expectedTargetAmount, "expectedTargetAmount");
        Objects.requireNonNull(quoteExpiresAt, "quoteExpiresAt");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 191 || expectedRuleId.isEmpty()
                || expectedRuleRevision < 1 || fromCurrencyId.equals(toCurrencyId)
                || sourceAmount.signum() <= 0 || expectedTargetAmount.signum() <= 0 || metadata.size() > 32) {
            throw new IllegalArgumentException("Invalid exchange request");
        }
        metadata.forEach((key, value) -> {
            if (key == null || value == null || key.isBlank() || key.length() > 64 || value.length() > 255
                    || key.chars().anyMatch(Character::isISOControl)
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid exchange metadata");
            }
        });
    }
}
