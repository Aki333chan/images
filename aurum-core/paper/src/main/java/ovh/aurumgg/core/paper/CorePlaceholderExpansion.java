package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ovh.aurumgg.core.api.AccountId;

final class CorePlaceholderExpansion extends PlaceholderExpansion {
    private final AurumCorePlugin plugin;

    CorePlaceholderExpansion(AurumCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "aurum"; }
    @Override public @NotNull String getAuthor() { return "Aurum"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.startsWith("currency_") && key.endsWith("_symbol")) {
            String id = key.substring("currency_".length(), key.length() - "_symbol".length());
            var currency = plugin.settings().currencies().get(id);
            return currency == null ? null : currency.symbol();
        }
        if (key.startsWith("treasury_balance_")) {
            String id = key.substring("treasury_balance_".length());
            return plugin.cachedGlobalSnapshot(id).filter(it -> it.authoritative())
                    .map(it -> raw(it.treasuryBalance())).orElse("");
        }
        if (key.startsWith("money_supply_")) {
            String id = key.substring("money_supply_".length());
            return plugin.cachedGlobalSnapshot(id).filter(it -> it.authoritative())
                    .map(it -> raw(it.moneySupply())).orElse("");
        }
        if (player != null && (key.startsWith("balance_") || key.startsWith("balance_raw_"))) {
            boolean raw = key.startsWith("balance_raw_");
            String id = key.substring(raw ? "balance_raw_".length() : "balance_".length());
            var currency = plugin.settings().currencies().get(id);
            if (currency == null) return null;
            return plugin.cachedBalance(AccountId.player(player.getUniqueId()), id)
                    .map(snapshot -> raw ? raw(snapshot.balance())
                            : raw(snapshot.balance()) + currency.symbol()).orElse("");
        }
        if (key.equals("currency")) return plugin.settings().currency().displayName();
        if (key.equals("currency_symbol")) return plugin.settings().currency().symbol();
        if (key.equals("treasury_balance")) {
            var snapshot = plugin.cachedGlobalSnapshot();
            return snapshot.authoritative() ? raw(snapshot.treasuryBalance()) : "";
        }
        if (key.equals("money_supply")) {
            var snapshot = plugin.cachedGlobalSnapshot();
            return snapshot.authoritative() ? raw(snapshot.moneySupply()) : "";
        }
        if (key.equals("taxes_collected")) {
            var snapshot = plugin.cachedGlobalSnapshot();
            return snapshot.authoritative() ? raw(snapshot.taxesCollected()) : "";
        }
        if (player == null || (!key.equals("balance") && !key.equals("balance_raw"))) return null;
        return plugin.cachedBalance(AccountId.player(player.getUniqueId()))
                .map(snapshot -> key.equals("balance_raw")
                        ? raw(snapshot.balance())
                        : raw(snapshot.balance()) + plugin.settings().currency().symbol())
                .orElse("");
    }

    private static String raw(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
