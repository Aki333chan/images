package org.ChisaO_o.simpleSlots;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

final class LocaleService {
    private static final List<String> SUPPORTED = List.of("en", "pl", "ru");

    private final SimpleSlots plugin;
    private YamlConfiguration english;
    private YamlConfiguration selected;
    private String language = "en";

    LocaleService(SimpleSlots plugin) {
        this.plugin = plugin;
        installDefaults();
        reload();
    }

    void reload() {
        installDefaults();
        english = load("en");
        String configured = plugin.getConfig().contains("language", true)
                ? plugin.getConfig().getString("language", "en")
                : plugin.getConfig().getString("LANGUAGE", "en");
        language = normalize(configured);
        selected = language.equals("en") ? english : load(language);
        plugin.getLogger().info("Language: " + language + ".");
    }

    String language() {
        return language;
    }

    String get(String key) {
        String value = selected == null ? null : selected.getString(key);
        if (value == null && english != null) value = english.getString(key);
        return color(value == null ? key : value);
    }

    List<String> list(String key) {
        List<String> values = selected == null ? List.of() : selected.getStringList(key);
        if (values.isEmpty() && english != null) values = english.getStringList(key);
        return values.stream().map(LocaleService::color).toList();
    }

    String format(String key, Map<String, ?> values) {
        String result = get(key);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private void installDefaults() {
        File directory = new File(plugin.getDataFolder(), "locales");
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("Could not create the locales directory.");
        }
        for (String code : SUPPORTED) {
            File file = new File(directory, code + ".yml");
            if (!file.exists()) plugin.saveResource("locales/" + code + ".yml", false);
        }
    }

    private YamlConfiguration load(String code) {
        File file = new File(new File(plugin.getDataFolder(), "locales"), code + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                java.util.Objects.requireNonNull(plugin.getResource("locales/" + code + ".yml")),
                StandardCharsets.UTF_8)) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load bundled locale " + code, exception);
        }
        try {
            yaml.load(file);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load locale " + code + " from " + file, exception);
        }
        return yaml;
    }

    private String normalize(String raw) {
        String code = raw == null ? "en" : raw.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED.contains(code)) return code;
        plugin.getLogger().warning("Unsupported language '" + raw + "'; using English. Supported: en, pl, ru.");
        return "en";
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
