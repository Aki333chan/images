package ovh.aurumgg.core.paper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.engine.FinancialRule;
import ovh.aurumgg.core.engine.PolicyKind;
import ovh.aurumgg.core.engine.PolicyValidator;

record PolicyConfiguration(boolean enabled, boolean bootstrapOnEmpty, int maxRules,
                           List<FinancialRule> bootstrapRules) {
    static PolicyConfiguration read(FileConfiguration config, CurrencySpec currency) {
        boolean enabled = config.getBoolean("financial-policies.enabled", false);
        boolean bootstrap = config.getBoolean("financial-policies.bootstrap-on-empty", true);
        int maxRules = Math.max(1, Math.min(500, config.getInt("financial-policies.max-rules", 100)));
        ConfigurationSection root = config.getConfigurationSection("financial-policies.rules");
        if (root == null) return new PolicyConfiguration(enabled, bootstrap, maxRules, List.of());
        var rules = new java.util.ArrayList<FinancialRule>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) throw new IllegalArgumentException("Policy " + id + " must be a section");
            PolicyKind kind = PolicyKind.valueOf(section.getString("kind", "").toUpperCase(Locale.ROOT));
            Set<TransactionCategory> categories = categories(section.getStringList("categories"));
            Map<String, String> definition = definition(section, kind);
            FinancialRule rule = new FinancialRule(id, kind, section.getInt("handler-version", 1),
                    categories, definition, section.getInt("priority", 0),
                    section.getBoolean("enabled", false), instant(section.getString("effective-from")),
                    instant(section.getString("effective-until")));
            rules.add(PolicyValidator.validate(rule, currency));
        }
        if (rules.size() > maxRules) throw new IllegalArgumentException("Too many configured policies");
        return new PolicyConfiguration(enabled, bootstrap, maxRules, List.copyOf(rules));
    }

    private static Map<String, String> definition(ConfigurationSection section, PolicyKind kind) {
        Map<String, String> values = new LinkedHashMap<>();
        copy(section, values, "rate", "mode", "minimum", "maximum",
                "recipient-type", "recipient-id", "funding-type", "funding-id",
                "condition-source-type", "condition-source-id", "condition-target-type",
                "condition-target-id", "condition-metadata-key", "condition-metadata-value");
        if (kind == PolicyKind.EXEMPTION) {
            List<String> kinds = section.getStringList("kinds");
            if (!kinds.isEmpty()) values.put("kinds", String.join(",", kinds));
            else copy(section, values, "kinds");
        }
        return values;
    }

    private static void copy(ConfigurationSection section, Map<String, String> target, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) target.put(key, section.getString(key, ""));
        }
    }

    static Set<TransactionCategory> categories(List<String> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Policy categories cannot be empty");
        Set<TransactionCategory> categories = new LinkedHashSet<>();
        for (String value : values) {
            if (value.equals("*")) Arrays.stream(TransactionCategory.values())
                    .filter(PolicyValidator::policyEligible).forEach(categories::add);
            else categories.add(TransactionCategory.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        return Set.copyOf(categories);
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank() || value.equals("-")) return null;
        if (value.equalsIgnoreCase("now")) return Instant.now();
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Use ISO-8601 time, for example 2026-12-31T23:00:00Z");
        }
    }
}
