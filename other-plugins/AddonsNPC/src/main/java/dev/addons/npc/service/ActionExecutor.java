package dev.addons.npc.service;

import dev.addons.npc.model.ActionDefinition;
import dev.addons.npc.model.NpcDefinition;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class ActionExecutor {
    private final MessageService messages;
    private final ShopService shops;
    private final BuyerService buyers;
    private final GuildTraderService guildTraders;

    public ActionExecutor(MessageService messages, ShopService shops, BuyerService buyers, GuildTraderService guildTraders) {
        this.messages = messages;
        this.shops = shops;
        this.buyers = buyers;
        this.guildTraders = guildTraders;
    }

    public void execute(Player player, NpcDefinition npc, ActionDefinition action, Map<String, ?> placeholders) {
        String value = MessageService.replace(action.value(), placeholders);
        switch (action.type()) {
            case MESSAGE -> messages.raw(player, value, placeholders);
            case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(value));
            case PLAYER -> player.performCommand(stripSlash(value));
            case SHOP -> shops.open(player, value);
            case BUYER -> buyers.open(player, value);
            case GUILD_TRADER -> guildTraders.open(player, value);
            case SOUND -> playSound(player, value);
            case TITLE -> showTitle(player, value);
        }
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static void playSound(Player player, String value) {
        String[] parts = value.split("\\|", -1);
        String sound = parts[0].contains(":") ? parts[0] : "minecraft:" + parts[0].toLowerCase();
        float volume = parts.length > 1 ? parseFloat(parts[1], 1.0f) : 1.0f;
        float pitch = parts.length > 2 ? parseFloat(parts[2], 1.0f) : 1.0f;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private static void showTitle(Player player, String value) {
        String[] parts = value.split("\\|", 2);
        String title = MessageService.colorize(parts[0]);
        String subtitle = parts.length > 1 ? MessageService.colorize(parts[1]) : "";
        player.sendTitle(title, subtitle, 10, 50, 10);
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
