package ovh.aurumgg.core.engine.migration;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PlayerLedgerBalance(UUID playerId, BigDecimal balance) {
    public PlayerLedgerBalance {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(balance, "balance");
    }
}
