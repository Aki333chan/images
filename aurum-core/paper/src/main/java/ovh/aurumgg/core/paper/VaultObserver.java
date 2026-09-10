package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.aurumgg.core.api.CurrencySpec;

final class VaultObserver implements BalanceObserver {
    private final JavaPlugin plugin;
    private final CurrencySpec currency;

    VaultObserver(JavaPlugin plugin, CurrencySpec currency) {
        this.plugin = plugin;
        this.currency = currency;
    }

    Optional<Economy> provider() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null
                && plugin.getServer().getPluginManager().getPlugin("VaultUnlocked") == null) {
            return Optional.empty();
        }
        RegisteredServiceProvider<Economy> registration =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return registration == null ? Optional.empty() : Optional.ofNullable(registration.getProvider());
    }

    @Override
    public Optional<BigDecimal> balance(OfflinePlayer player) {
        try {
            Optional<Economy> provider = provider();
            if (provider.isEmpty()) return Optional.empty();
            double value = provider.get().getBalance(player);
            if (!Double.isFinite(value)) return Optional.empty();
            return Optional.of(BigDecimal.valueOf(value).setScale(currency.scale(), RoundingMode.HALF_UP));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not observe Vault balance for " + player.getUniqueId()
                    + ": " + exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public String providerName() {
        return provider().map(Economy::getName).orElse("unavailable");
    }
}
