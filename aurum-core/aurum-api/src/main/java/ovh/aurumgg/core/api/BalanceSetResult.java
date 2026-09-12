package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Objects;

/** The durable result of one compare-and-set balance operation. */
public record BalanceSetResult(
        Status status,
        String idempotencyKey,
        BigDecimal expectedBalance,
        BigDecimal targetBalance,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        BigDecimal currentBalance,
        String message) {

    public BalanceSetResult {
        Objects.requireNonNull(status, "status");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(expectedBalance, "expectedBalance");
        Objects.requireNonNull(targetBalance, "targetBalance");
        Objects.requireNonNull(balanceBefore, "balanceBefore");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(currentBalance, "currentBalance");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        /** The account no longer had expectedBalance; no money moved. */
        CONFLICT,
        REJECTED,
        UNAVAILABLE
    }
}
