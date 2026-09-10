package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import ovh.aurumgg.core.api.CurrencySpec;

public final class TaxCalculator {
    private TaxCalculator() {}

    public static TaxBreakdown calculate(BigDecimal amount, CurrencySpec currency, TaxRule rule) {
        BigDecimal normalized = currency.requireAmount(amount);
        if (normalized.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");

        BigDecimal tax = normalized.multiply(rule.rate()).setScale(currency.scale(), RoundingMode.HALF_UP);
        return switch (rule.mode()) {
            case INCLUDED -> new TaxBreakdown(normalized, normalized.subtract(tax), tax);
            case ADDED -> new TaxBreakdown(normalized.add(tax), normalized, tax);
        };
    }

    public static TaxBreakdown untaxed(BigDecimal amount, CurrencySpec currency) {
        BigDecimal normalized = currency.requireAmount(amount);
        if (normalized.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        return new TaxBreakdown(normalized, normalized, BigDecimal.ZERO.setScale(currency.scale()));
    }
}
