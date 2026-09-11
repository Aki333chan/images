package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.engine.ExchangeRule;
import ovh.aurumgg.core.engine.ExchangeSettlement;

record ExchangeConfiguration(boolean enabled, boolean bootstrapOnEmpty, int maxRules,
                             int quoteTtlSeconds, List<ExchangeRule> bootstrapRules) {
    static ExchangeConfiguration read(FileConfiguration config, Map<String, CurrencySpec> currencies) {
        boolean enabled = config.getBoolean("exchange.enabled", false);
        boolean bootstrap = config.getBoolean("exchange.bootstrap-on-empty", true);
        int maxRules = Math.max(1, Math.min(500, config.getInt("exchange.max-rules", 100)));
        int ttl = Math.max(5, Math.min(300, config.getInt("exchange.quote-ttl-seconds", 30)));
        ConfigurationSection root = config.getConfigurationSection("exchange.rules");
        if (root == null) return new ExchangeConfiguration(enabled, bootstrap, maxRules, ttl, List.of());
        List<ExchangeRule> rules = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) throw new IllegalArgumentException("Exchange " + id + " must be a section");
            String from = section.getString("from", "").toLowerCase(Locale.ROOT);
            String to = section.getString("to", "").toLowerCase(Locale.ROOT);
            CurrencySpec fromSpec = currencies.get(from);
            if (fromSpec == null || !currencies.containsKey(to)) {
                throw new IllegalArgumentException("Exchange " + id + " uses an unknown currency");
            }
            BigDecimal minimum = decimal(section.getString("minimum"), fromSpec);
            BigDecimal maximum = decimal(section.getString("maximum"), fromSpec);
            Map<String, String> conditions = new LinkedHashMap<>();
            ConfigurationSection conditionSection = section.getConfigurationSection("conditions");
            if (conditionSection != null) conditionSection.getKeys(false).forEach(key ->
                    conditions.put(key, conditionSection.getString(key, "")));
            rules.add(new ExchangeRule(id, 1, from, to,
                    new BigDecimal(section.getString("rate", "1")),
                    new BigDecimal(section.getString("fee-rate", "0")), minimum, maximum,
                    ExchangeSettlement.valueOf(section.getString("settlement", "MINT_BURN")
                            .replace('-', '_').toUpperCase(Locale.ROOT)), conditions,
                    section.getInt("priority", 0), section.getBoolean("enabled", false),
                    PolicyConfiguration.instant(section.getString("effective-from")),
                    PolicyConfiguration.instant(section.getString("effective-until"))));
        }
        if (rules.size() > maxRules) throw new IllegalArgumentException("Too many configured exchange rules");
        return new ExchangeConfiguration(enabled, bootstrap, maxRules, ttl, List.copyOf(rules));
    }

    private static BigDecimal decimal(String value, CurrencySpec currency) {
        return value == null || value.isBlank() || value.equals("-")
                ? null : currency.requireAmount(new BigDecimal(value));
    }
}
