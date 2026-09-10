package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.OfflinePlayer;

final class UnavailableBalanceObserver implements BalanceObserver {
    @Override public Optional<BigDecimal> balance(OfflinePlayer player) { return Optional.empty(); }
    @Override public String providerName() { return "unavailable"; }
}
