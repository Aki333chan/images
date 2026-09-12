package ovh.aurumgg.core.paper;

import java.io.File;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class LanguageBundle {
    static final List<String> REQUIRED_KEYS = List.of(
            "prefix", "passive-only", "no-permission", "player-only", "player-not-online",
            "balance-missing", "balance", "treasury-unavailable", "status-header", "status-line",
            "active-only", "migration-shadow-only", "migration-database-unavailable",
            "migration-vault-unavailable", "migration-busy", "migration-started", "migration-saved",
            "migration-failed", "migration-not-found", "migration-summary", "migration-confirm",
            "migration-importing", "migration-imported", "migration-blocked", "migration-exported",
            "migration-invalid-id", "migration-usage", "migration-provider-changed",
            "active-not-ready", "payments-disabled", "pay-usage", "player-unknown", "pay-self",
            "pay-limits", "pay-cooldown", "pay-sent", "pay-received", "money-unavailable",
            "insufficient-funds", "economy-usage", "economy-success", "invalid-amount", "treasury",
            "policy-unavailable", "policy-usage", "policy-invalid", "policy-list-header",
            "policy-list-line", "policy-inspect", "policy-history-header", "policy-history-line",
            "policy-reloaded", "policy-saved", "policy-transaction-rejected",
            "policy-import-confirm", "policy-imported", "policy-saved-reload-failed"
    );

    private final YamlConfiguration messages;

    LanguageBundle(JavaPlugin plugin, String language) {
        for (String locale : List.of("en", "ru", "pl")) {
            String resource = "lang/messages_" + locale + ".yml";
            if (!new File(plugin.getDataFolder(), resource).isFile()) plugin.saveResource(resource, false);
        }
        File selected = new File(plugin.getDataFolder(), "lang/messages_" + language + ".yml");
        if (!selected.isFile()) selected = new File(plugin.getDataFolder(), "lang/messages_en.yml");
        messages = YamlConfiguration.loadConfiguration(selected);
    }

    Component component(String key, Map<String, String> replacements) {
        String value = messages.getString(key, "&cMissing language key: " + key);
        value = messages.getString("prefix", "") + value;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value);
    }

    Component component(String key) {
        return component(key, Map.of());
    }

    /**
     * The same text without the chat prefix.
     *
     * <p>For window titles and item names: a prefix belongs in front of a chat
     * line, not on the label of a button.
     */
    Component label(String key, Map<String, String> replacements) {
        String value = messages.getString(key, "Missing language key: " + key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
}
