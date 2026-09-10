package ovh.aurumgg.core.engine.migration;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ObservedPlayerBalance(UUID playerId, String username, BigDecimal externalBalance) {
    public ObservedPlayerBalance {
        Objects.requireNonNull(playerId, "playerId");
        username = Objects.requireNonNullElse(username, playerId.toString());
        Objects.requireNonNull(externalBalance, "externalBalance");
        if (username.length() > 64) username = username.substring(0, 64);
    }
}
