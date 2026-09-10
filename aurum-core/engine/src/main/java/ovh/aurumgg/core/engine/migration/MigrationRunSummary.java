package ovh.aurumgg.core.engine.migration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MigrationRunSummary(
        UUID runId,
        String providerName,
        String currencyId,
        String status,
        int players,
        int readFailures,
        int mismatches,
        int negativeBalances,
        BigDecimal externalTotal,
        BigDecimal internalTotal,
        Instant createdAt
) {
    public MigrationRunSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(externalTotal, "externalTotal");
        Objects.requireNonNull(internalTotal, "internalTotal");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
