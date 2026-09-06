package dev.addons.npc.service;

import dev.addons.npc.model.GuildBonusType;
import dev.addons.npc.model.TimedPercentage;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageService {
    private static final List<String> SUPPORTED = List.of("en", "pl", "ru");

    private final JavaPlugin plugin;
    private YamlConfiguration english;
    private YamlConfiguration selected;
    private String language = "en";

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        installDefaults();
        reload();
    }

    public void reload() {
        installDefaults();
        english = load("en");
        String configured = plugin.getConfig().getString("language", "en");
        language = normalize(configured);
        selected = language.equals("en") ? english : load(language);
        plugin.getLogger().info("Language: " + language + ".");
    }

    public String language() { return language; }

    public void send(CommandSender sender, String key) { send(sender, key, Map.of()); }

    public void send(CommandSender sender, String key, Map<String, ?> values) {
        sender.sendMessage(colorize(prefix() + replace(text("messages." + key), values)));
    }

    /** Sends stored/user-authored text without translating it. */
    public void raw(CommandSender sender, String message, Map<String, ?> values) {
        sender.sendMessage(colorize(replace(message, values)));
    }

    /** Localizes legacy command output assembled by the command handler. */
    public void localizedRaw(CommandSender sender, String message, Map<String, ?> values) {
        sender.sendMessage(colorize(replace(translateLiteral(message), values)));
    }

    public String text(String key) {
        String value = selected == null ? null : selected.getString(key);
        if (value == null && english != null) value = english.getString(key);
        return value == null ? key : value;
    }

    public List<String> lines(String key) {
        List<String> result = selected == null ? List.of() : selected.getStringList(key);
        if (result.isEmpty() && english != null) result = english.getStringList(key);
        return result;
    }

    public String formatKey(String key, Map<String, ?> values) {
        return colorize(replace(text(key), values));
    }

    public String format(String message, Map<String, ?> values) {
        return colorize(replace(message, values));
    }

    public String prefix() { return text("messages.prefix"); }

    public String remaining(TimedPercentage percentage, long now) {
        if (!percentage.active(now)) return text("time.expired");
        if (percentage.permanent()) return text("time.until-disabled");
        long seconds = Math.max(1, (percentage.expiresAtMillis() - now + 999) / 1000);
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        if (days > 0) return formatKey("time.days-hours", Map.of("days", days, "hours", hours));
        if (hours > 0) return formatKey("time.hours-minutes", Map.of("hours", hours, "minutes", minutes));
        if (minutes > 0) return formatKey("time.minutes-seconds", Map.of("minutes", minutes, "seconds", seconds));
        return formatKey("time.seconds", Map.of("seconds", seconds));
    }

    public String duration(Duration duration) {
        if (duration == null) return text("time.forever");
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds >= 1209600 && seconds % 604800 == 0) return seconds / 604800 + text("time.week-short");
        if (seconds >= 86400 && seconds % 86400 == 0) return seconds / 86400 + text("time.day-short");
        if (seconds >= 3600 && seconds % 3600 == 0) return seconds / 3600 + text("time.hour-short");
        if (seconds >= 60 && seconds % 60 == 0) return seconds / 60 + text("time.minute-short");
        return seconds + text("time.second-short");
    }

    public String bonusTitle(GuildBonusType type) {
        return text("guild-bonus-types." + type.name().toLowerCase(Locale.ROOT) + ".title");
    }

    public String bonusDescription(GuildBonusType type, double magnitude) {
        if (type.kind() == GuildBonusType.Kind.MULTIPLIER) return "×" + number(magnitude);
        return text("guild-bonus-types." + type.name().toLowerCase(Locale.ROOT) + ".short") + " " + number(magnitude);
    }

    public String rank(String rank) { return text("guild-ranks." + rank.toLowerCase(Locale.ROOT)); }

    public static String replace(String input, Map<String, ?> values) {
        String result = input == null ? "" : input;
        Map<String, Object> stableValues = new LinkedHashMap<>(values);
        for (Map.Entry<String, Object> entry : stableValues.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    public static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private String translateLiteral(String input) {
        String result = input == null ? "" : input;
        for (Map<?, ?> replacement : selected.getMapList("literal-replacements")) {
            Object from = replacement.get("from");
            Object to = replacement.get("to");
            if (from != null && to != null) result = result.replace(String.valueOf(from), String.valueOf(to));
        }
        return result;
    }

    private void installDefaults() {
        File directory = new File(plugin.getDataFolder(), "locales");
        if (!directory.exists() && !directory.mkdirs()) plugin.getLogger().warning("Could not create the locales directory.");
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
        try { yaml.load(file); }
        catch (Exception exception) {
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

    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
