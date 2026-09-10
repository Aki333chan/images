package ovh.aurumgg.core.paper;

import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.engine.db.MariaDbSettings;

record CoreSettings(
        String language,
        String configuredMode,
        CurrencySpec currency,
        int refreshTicks,
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
        boolean enabled = config.getBoolean("database.enabled", false);
        MariaDbSettings database = new MariaDbSettings(
                config.getString("database.jdbc-url", "jdbc:mariadb://127.0.0.1:3306/aurum_core"),
                config.getString("database.username", "aurum"),
                config.getString("database.password", "change-me"),
                Math.max(1, Math.min(16, config.getInt("database.pool-size", 3)))
        );
        return new CoreSettings(language, mode, currency, refresh, enabled, database);
    }
}
