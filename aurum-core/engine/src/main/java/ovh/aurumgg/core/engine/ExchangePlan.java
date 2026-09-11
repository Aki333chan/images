package ovh.aurumgg.core.engine;

import java.util.Map;
import java.util.Objects;
import ovh.aurumgg.core.api.ExchangeQuote;
import ovh.aurumgg.core.api.ExchangeRequest;

public record ExchangePlan(ExchangeRequest request, ExchangeQuote quote,
                           ExchangeSettlement settlement,
                           Map<CurrencyAccountKey, java.math.BigDecimal> postings) {
    public ExchangePlan {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(settlement, "settlement");
        postings = Map.copyOf(postings);
    }
}
