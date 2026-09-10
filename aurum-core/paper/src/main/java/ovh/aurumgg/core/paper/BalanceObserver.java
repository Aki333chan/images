package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.OfflinePlayer;

interface BalanceObserver {
    Optional<BigDecimal> balance(OfflinePlayer player);
    String providerName();
}
