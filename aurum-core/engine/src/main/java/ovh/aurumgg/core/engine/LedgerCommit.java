package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record LedgerCommit(
        Status status,
        UUID transactionId,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal sourceBalance,
        BigDecimal targetBalance,
        String message
) {
    public LedgerCommit {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(netAmount, "netAmount");
        Objects.requireNonNull(taxAmount, "taxAmount");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status { COMMITTED, DUPLICATE, INSUFFICIENT_FUNDS, REJECTED }
}
