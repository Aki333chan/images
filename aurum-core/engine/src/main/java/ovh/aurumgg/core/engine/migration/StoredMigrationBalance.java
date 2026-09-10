package ovh.aurumgg.core.engine.migration;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record StoredMigrationBalance(
        UUID playerId,
        String username,
        BigDecimal externalBalance,
        BigDecimal internalBalance,
        BigDecimal difference
) {
    public StoredMigrationBalance {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(externalBalance, "externalBalance");
        Objects.requireNonNull(internalBalance, "internalBalance");
        Objects.requireNonNull(difference, "difference");
    }
}
