package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Objects;

public record TransactionResult(
        Status status,
        String idempotencyKey,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        String message
) {
    public TransactionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(netAmount, "netAmount");
        Objects.requireNonNull(taxAmount, "taxAmount");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status { SUCCESS, DUPLICATE, REJECTED, UNAVAILABLE }
}
