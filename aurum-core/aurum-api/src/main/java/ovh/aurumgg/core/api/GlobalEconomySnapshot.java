package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record GlobalEconomySnapshot(
        CurrencySpec currency,
        BigDecimal treasuryBalance,
        BigDecimal moneySupply,
        BigDecimal taxesCollected,
        Instant observedAt,
        boolean available,
        boolean authoritative
) {
    public GlobalEconomySnapshot {
        Objects.requireNonNull(currency, "currency");
        treasuryBalance = currency.requireAmount(treasuryBalance);
        moneySupply = currency.requireAmount(moneySupply);
        taxesCollected = currency.requireAmount(taxesCollected);
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
