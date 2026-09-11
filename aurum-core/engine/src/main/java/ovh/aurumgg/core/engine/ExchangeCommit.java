package ovh.aurumgg.core.engine;

import java.util.Map;
import java.util.Objects;
import ovh.aurumgg.core.api.ExchangeQuote;

public record ExchangeCommit(Status status, ExchangeQuote quote,
                             Map<CurrencyAccountKey, java.math.BigDecimal> balancesAfter,
                             String message) {
    public ExchangeCommit {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(quote, "quote");
        balancesAfter = Map.copyOf(balancesAfter);
        message = Objects.requireNonNullElse(message, "");
    }
    public enum Status { COMMITTED, DUPLICATE, REJECTED }
}
