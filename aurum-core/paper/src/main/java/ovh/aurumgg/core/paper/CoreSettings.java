package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.engine.db.MariaDbSettings;

record CoreSettings(
        String language,
        String configuredMode,
        CurrencySpec currency,
        Map<String, CurrencySpec> currencies,
        int refreshTicks,
        int migrationPlayersPerTick,
        boolean requireVerifiedMigration,
        int globalRefreshTicks,
        boolean paymentsEnabled,
        BigDecimal paymentMinimum,
        BigDecimal paymentMaximum,
        int paymentCooldownSeconds,
        PolicyConfiguration policies,
        ExchangeConfiguration exchange,
        int holdMaxTtlSeconds,
        int claimMaxLeaseSeconds,
        int claimMaxAttempts,
        boolean tradingEnabled,
        int tradeInviteTimeoutSeconds,
        int tradeSessionTimeoutSeconds,
        boolean databaseEnabled,
        MariaDbSettings database
) {
    static CoreSettings read(FileConfiguration config) {
        String language = config.getString("language", "en").toLowerCase(Locale.ROOT);
        String mode = config.getString("economy.mode", "passive").toLowerCase(Locale.ROOT);
        Map<String, CurrencySpec> currencies = readCurrencies(config);
        String primaryId = config.getString("economy.primary-currency",
                config.getString("economy.currency.id", "coins")).toLowerCase(Locale.ROOT);
        CurrencySpec currency = currencies.get(primaryId);
        if (currency == null) throw new IllegalArgumentException("Primary currency is not enabled: " + primaryId);
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
        PolicyConfiguration policies = PolicyConfiguration.read(config, currency);
        ExchangeConfiguration exchange = ExchangeConfiguration.read(config, currencies);
        int holdMaxTtlSeconds = Math.max(10, Math.min(3600,
                config.getInt("holds.max-ttl-seconds", 300)));
        // A lease is how long a crashed server may keep a player's goods locked
        // away, so the ceiling is deliberately low.
        int claimMaxLeaseSeconds = Math.max(10, Math.min(600,
                config.getInt("claims.max-lease-seconds", 120)));
        int claimMaxAttempts = Math.max(1, Math.min(50,
                config.getInt("claims.max-attempts", 5)));
        boolean tradingEnabled = config.getBoolean("trading.enabled", false);
        int tradeInviteTimeoutSeconds = Math.max(5, Math.min(600,
                config.getInt("trading.invite-timeout-seconds", 30)));
        int tradeSessionTimeoutSeconds = Math.max(30, Math.min(3600,
                config.getInt("trading.session-timeout-seconds", 300)));
        boolean enabled = config.getBoolean("database.enabled", false);
        MariaDbSettings database = new MariaDbSettings(
                config.getString("database.jdbc-url", "jdbc:mariadb://127.0.0.1:3306/aurum_core"),
                config.getString("database.username", "aurum"),
                config.getString("database.password", "change-me"),
                Math.max(1, Math.min(16, config.getInt("database.pool-size", 3)))
        );
        return new CoreSettings(language, mode, currency, currencies, refresh, migrationPlayersPerTick,
                requireVerifiedMigration, globalRefreshTicks, paymentsEnabled, paymentMinimum,
                paymentMaximum, paymentCooldownSeconds, policies, exchange, holdMaxTtlSeconds, claimMaxLeaseSeconds, claimMaxAttempts,
                tradingEnabled, tradeInviteTimeoutSeconds, tradeSessionTimeoutSeconds,
                enabled, database);
    }

    private static Map<String, CurrencySpec> readCurrencies(FileConfiguration config) {
        var root = config.getConfigurationSection("economy.currencies");
        Map<String, CurrencySpec> values = new LinkedHashMap<>();
        if (root != null) {
            for (String key : root.getKeys(false)) {
                var section = root.getConfigurationSection(key);
                if (section == null || !section.getBoolean("enabled", true)) continue;
                CurrencySpec spec = new CurrencySpec(key,
                        section.getString("display-name", key), section.getString("symbol", ""),
                        section.getInt("scale", 2));
                values.put(spec.id(), spec);
            }
        }
        if (values.isEmpty()) {
            CurrencySpec legacy = new CurrencySpec(
                    config.getString("economy.currency.id", "coins"),
                    config.getString("economy.currency.display-name", "Coins"),
                    config.getString("economy.currency.symbol", "$"),
                    config.getInt("economy.currency.scale", 2));
            values.put(legacy.id(), legacy);
        }
        if (values.size() > 16) throw new IllegalArgumentException("At most 16 currencies are supported");
        return Map.copyOf(values);
    }
}
