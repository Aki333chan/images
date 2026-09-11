package ovh.aurumgg.core.api;

import java.util.Objects;
import java.util.Optional;

public record ExchangeResult(Status status, String idempotencyKey,
                             Optional<ExchangeQuote> quote, String message) {
    public ExchangeResult {
        Objects.requireNonNull(status, "status");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        quote = Objects.requireNonNull(quote, "quote");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status { SUCCESS, DUPLICATE, REJECTED, UNAVAILABLE }
}
