package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Map;
import ovh.aurumgg.core.api.*;

/** One audited singleton, not a policy applied to every transaction. */
public record StartingBalanceSettings(long revision, boolean enabled, String currencyId, BigDecimal amount) {
    public StartingBalanceSettings validate(Map<String, CurrencySpec> currencies) {
        CurrencySpec currency = currencies.get(currencyId);
        if (currency == null) throw new IllegalArgumentException("Unknown starting balance currency");
        currency.requireAmount(amount);
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000000")) > 0)
            throw new IllegalArgumentException("Starting balance must be between 0 and 1000000000");
        return this;
    }
    public boolean grants() { return enabled && amount.signum() > 0; }
    public RuleResource resource() {
        return new RuleResource(RuleType.STARTING_BALANCE, "global", revision, Map.of(
                "enabled", Boolean.toString(enabled), "currency", currencyId, "amount", amount.stripTrailingZeros().toPlainString()));
    }
}
