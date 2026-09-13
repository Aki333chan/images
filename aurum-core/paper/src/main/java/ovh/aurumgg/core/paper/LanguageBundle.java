package ovh.aurumgg.core.paper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
            "policy-import-confirm", "policy-imported", "policy-saved-reload-failed",
            "account-usage", "fund-usage", "account-list-header", "account-list-empty",
            "account-list-line", "account-not-found", "account-inspect", "account-member",
            "account-invalid", "account-close-confirm", "account-operation-success",
            "account-operation-failed"
    );

    private final YamlConfiguration messages;

    LanguageBundle(JavaPlugin plugin, String language) {
        for (String locale : List.of("en", "ru", "pl")) {
            String resource = "lang/messages_" + locale + ".yml";
            if (!new File(plugin.getDataFolder(), resource).isFile()) plugin.saveResource(resource, false);
        }
        String selectedLocale = List.of("en", "ru", "pl").contains(language) ? language : "en";
        String selectedResource = "lang/messages_" + selectedLocale + ".yml";
        File selected = new File(plugin.getDataFolder(), selectedResource);
        if (!selected.isFile()) {
            selectedLocale = "en";
            selectedResource = "lang/messages_en.yml";
            selected = new File(plugin.getDataFolder(), selectedResource);
        }
        messages = load(selected, plugin.getResource(selectedResource));
    }

    /**
     * Keep administrator edits, but obtain keys introduced by a newer plugin
     * from that release's bundled locale. Bukkit deliberately does not replace
     * an existing file in {@code saveResource(..., false)}, so loading only the
     * disk copy would leave upgraded servers with "Missing language key".
     */
    static YamlConfiguration load(File selected, InputStream bundledDefaults) {
        YamlConfiguration configured = YamlConfiguration.loadConfiguration(selected);
        if (bundledDefaults == null) return configured;
        try (InputStream input = bundledDefaults;
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            configured.setDefaults(YamlConfiguration.loadConfiguration(reader));
            return configured;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load bundled language defaults", failure);
        }
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
