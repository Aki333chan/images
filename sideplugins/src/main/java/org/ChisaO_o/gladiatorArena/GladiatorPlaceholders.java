package org.ChisaO_o.gladiatorArena;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class GladiatorPlaceholders extends PlaceholderExpansion {
    private final GladiatorArena plugin;

    GladiatorPlaceholders(GladiatorArena plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "gladiatorarena"; }
    @Override public @NotNull String getAuthor() { return "ChisaO_o"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String identifier) {
        if (offlinePlayer == null) return "";
        Player player = offlinePlayer.getPlayer();
        if (player != null) return plugin.placeholder(player, identifier);
        DatabaseManager.PlayerStats stats = plugin.stats(offlinePlayer.getUniqueId());
        return switch (identifier.toLowerCase()) {
            case "wins" -> String.valueOf(stats.wins());
            case "losses" -> String.valueOf(stats.losses());
            case "streak" -> String.valueOf(stats.streak());
            case "best_streak" -> String.valueOf(stats.bestStreak());
            case "earnings" -> String.format(java.util.Locale.US, "%.1f", stats.earnings());
            default -> "";
        };
    }
}
