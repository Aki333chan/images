package dev.addons.npc.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageService {
    private final JavaPlugin plugin;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, ?> values) {
        String message = plugin.getConfig().getString("messages." + key, key);
        sender.sendMessage(colorize(prefix() + replace(message, values)));
    }

    public void raw(CommandSender sender, String message, Map<String, ?> values) {
        sender.sendMessage(colorize(replace(message, values)));
    }

    public String format(String message, Map<String, ?> values) {
        return colorize(replace(message, values));
    }

    public String prefix() {
        return plugin.getConfig().getString("messages.prefix", "");
    }

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
}
