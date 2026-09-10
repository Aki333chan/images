package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import ovh.aurumgg.core.api.AccountId;

public record LedgerCommit(
        Status status,
        UUID transactionId,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal sourceBalance,
        BigDecimal targetBalance,
        String message,
        Map<AccountId, BigDecimal> balancesAfter
) {
    public LedgerCommit {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(netAmount, "netAmount");
        Objects.requireNonNull(taxAmount, "taxAmount");
        message = Objects.requireNonNullElse(message, "");
        balancesAfter = Map.copyOf(Objects.requireNonNull(balancesAfter, "balancesAfter"));
    }

    public LedgerCommit(Status status, UUID transactionId, BigDecimal grossAmount,
                        BigDecimal netAmount, BigDecimal taxAmount, BigDecimal sourceBalance,
                        BigDecimal targetBalance, String message) {
        this(status, transactionId, grossAmount, netAmount, taxAmount, sourceBalance,
                targetBalance, message, Map.of());
    }

    public enum Status { COMMITTED, DUPLICATE, INSUFFICIENT_FUNDS, REJECTED }
}
