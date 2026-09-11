package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record ExchangeQuote(
        String ruleId,
        long ruleRevision,
        AccountId account,
        CurrencySpec fromCurrency,
        CurrencySpec toCurrency,
        BigDecimal sourceAmount,
        BigDecimal feeAmount,
        BigDecimal convertedAmount,
        BigDecimal targetAmount,
        Instant quotedAt,
        Instant expiresAt
) {
    public ExchangeQuote {
        ruleId = Objects.requireNonNull(ruleId, "ruleId");
        if (ruleRevision < 1) throw new IllegalArgumentException("Rule revision must be positive");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(fromCurrency, "fromCurrency");
        Objects.requireNonNull(toCurrency, "toCurrency");
        sourceAmount = fromCurrency.requireAmount(sourceAmount);
        feeAmount = fromCurrency.requireAmount(feeAmount);
        convertedAmount = fromCurrency.requireAmount(convertedAmount);
        targetAmount = toCurrency.requireAmount(targetAmount);
        Objects.requireNonNull(quotedAt, "quotedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (sourceAmount.signum() <= 0 || feeAmount.signum() < 0 || convertedAmount.signum() <= 0
                || targetAmount.signum() <= 0 || !expiresAt.isAfter(quotedAt)) {
            throw new IllegalArgumentException("Invalid exchange quote");
        }
    }
}
