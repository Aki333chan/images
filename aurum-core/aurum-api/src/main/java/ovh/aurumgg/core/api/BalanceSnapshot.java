package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record BalanceSnapshot(
        AccountId account,
        CurrencySpec currency,
        BigDecimal balance,
        Instant observedAt,
        boolean authoritative
) {
    public BalanceSnapshot {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        balance = currency.requireAmount(balance);
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
