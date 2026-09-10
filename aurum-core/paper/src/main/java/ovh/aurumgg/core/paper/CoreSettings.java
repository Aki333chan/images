package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.engine.db.MariaDbSettings;

record CoreSettings(
        String language,
        String configuredMode,
        CurrencySpec currency,
        int refreshTicks,
        int migrationPlayersPerTick,
        boolean requireVerifiedMigration,
        int globalRefreshTicks,
        boolean paymentsEnabled,
        BigDecimal paymentMinimum,
        BigDecimal paymentMaximum,
        int paymentCooldownSeconds,
        boolean databaseEnabled,
        MariaDbSettings database
) {
    static CoreSettings read(FileConfiguration config) {
        String language = config.getString("language", "en").toLowerCase(Locale.ROOT);
        String mode = config.getString("economy.mode", "passive").toLowerCase(Locale.ROOT);
        CurrencySpec currency = new CurrencySpec(
                config.getString("economy.currency.id", "coins"),
                config.getString("economy.currency.display-name", "Coins"),
                config.getString("economy.currency.symbol", "$"),
                config.getInt("economy.currency.scale", 2)
        );
        int refresh = Math.max(20, config.getInt("passive.refresh-ticks", 100));
        int migrationPlayersPerTick = Math.max(1, Math.min(200,
                config.getInt("migration.players-per-tick", 20)));
        boolean requireVerifiedMigration = config.getBoolean("active.require-verified-migration", true);
        int globalRefreshTicks = Math.max(20, config.getInt("active.global-refresh-ticks", 100));
        boolean paymentsEnabled = config.getBoolean("payments.enabled", true);
        BigDecimal paymentMinimum = currency.requireAmount(new BigDecimal(
                config.getString("payments.minimum", currency.scale() == 0 ? "1" : "0.01")));
        BigDecimal paymentMaximum = currency.requireAmount(new BigDecimal(
                config.getString("payments.maximum", "1000000.00")));
        if (paymentMinimum.signum() <= 0 || paymentMaximum.compareTo(paymentMinimum) < 0) {
            throw new IllegalArgumentException("Invalid payment minimum/maximum");
        }
        int paymentCooldownSeconds = Math.max(0, Math.min(3600,
                config.getInt("payments.cooldown-seconds", 2)));
        boolean enabled = config.getBoolean("database.enabled", false);
        MariaDbSettings database = new MariaDbSettings(
                config.getString("database.jdbc-url", "jdbc:mariadb://127.0.0.1:3306/aurum_core"),
                config.getString("database.username", "aurum"),
                config.getString("database.password", "change-me"),
                Math.max(1, Math.min(16, config.getInt("database.pool-size", 3)))
        );
        return new CoreSettings(language, mode, currency, refresh, migrationPlayersPerTick,
                requireVerifiedMigration, globalRefreshTicks, paymentsEnabled, paymentMinimum,
                paymentMaximum, paymentCooldownSeconds, enabled, database);
    }
}
