package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record ExchangeRule(
        String id,
        long revision,
        String fromCurrencyId,
        String toCurrencyId,
        BigDecimal rate,
        BigDecimal feeRate,
        BigDecimal minimumSource,
        BigDecimal maximumSource,
        ExchangeSettlement settlement,
        Map<String, String> conditions,
        int priority,
        boolean enabled,
        Instant effectiveFrom,
        Instant effectiveUntil
) {
    public ExchangeRule {
        id = Objects.requireNonNull(id, "id").trim();
        fromCurrencyId = Objects.requireNonNull(fromCurrencyId, "fromCurrencyId").trim().toLowerCase(Locale.ROOT);
        toCurrencyId = Objects.requireNonNull(toCurrencyId, "toCurrencyId").trim().toLowerCase(Locale.ROOT);
        rate = Objects.requireNonNull(rate, "rate").stripTrailingZeros();
        feeRate = Objects.requireNonNull(feeRate, "feeRate").stripTrailingZeros();
        Objects.requireNonNull(settlement, "settlement");
        conditions = Map.copyOf(Objects.requireNonNull(conditions, "conditions"));
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}") || revision < 1
                || fromCurrencyId.equals(toCurrencyId) || rate.signum() <= 0
                || rate.precision() > 36 || rate.scale() > 18 || rate.precision() - rate.scale() > 18
                || feeRate.scale() > 12 || feeRate.signum() < 0
                || feeRate.compareTo(BigDecimal.ONE) >= 0 || conditions.size() > 16) {
            throw new IllegalArgumentException("Invalid exchange rule");
        }
        if (minimumSource != null && minimumSource.signum() <= 0) {
            throw new IllegalArgumentException("Exchange minimum must be positive");
        }
        if (maximumSource != null && (maximumSource.signum() <= 0
                || minimumSource != null && maximumSource.compareTo(minimumSource) < 0)) {
            throw new IllegalArgumentException("Invalid exchange maximum");
        }
        if (effectiveFrom != null && effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("Exchange end must be after start");
        }
        conditions.forEach((key, value) -> {
            if (key == null || value == null || key.isBlank() || key.length() > 64 || value.length() > 255
                    || key.chars().anyMatch(Character::isISOControl)
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid exchange condition");
            }
        });
    }

    public boolean activeAt(Instant now) {
        return enabled && (effectiveFrom == null || !now.isBefore(effectiveFrom))
                && (effectiveUntil == null || now.isBefore(effectiveUntil));
    }
}
