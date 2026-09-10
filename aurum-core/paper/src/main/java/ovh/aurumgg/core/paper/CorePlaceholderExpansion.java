package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.engine.PassiveEconomyService;

final class CorePlaceholderExpansion extends PlaceholderExpansion {
    private final AurumCorePlugin plugin;
    private final PassiveEconomyService economy;

    CorePlaceholderExpansion(AurumCorePlugin plugin, PassiveEconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override public @NotNull String getIdentifier() { return "aurum"; }
    @Override public @NotNull String getAuthor() { return "Aurum"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("currency")) return economy.primaryCurrency().displayName();
        if (key.equals("currency_symbol")) return economy.primaryCurrency().symbol();
        if (key.equals("treasury_balance") || key.equals("money_supply") || key.equals("taxes_collected")) {
            return ""; // Deliberately unavailable until the authoritative ledger is enabled.
        }
        if (player == null || (!key.equals("balance") && !key.equals("balance_raw"))) return null;
        return economy.cachedBalance(AccountId.player(player.getUniqueId()))
                .map(snapshot -> key.equals("balance_raw")
                        ? raw(snapshot.balance())
                        : raw(snapshot.balance()) + economy.primaryCurrency().symbol())
                .orElse("");
    }

    private static String raw(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
