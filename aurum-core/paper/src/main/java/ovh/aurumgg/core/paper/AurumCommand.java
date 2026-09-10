package ovh.aurumgg.core.paper;

import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ovh.aurumgg.core.api.AccountId;

final class AurumCommand implements CommandExecutor, TabCompleter {
    private final AurumCorePlugin plugin;

    AurumCommand(AurumCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("aurum.admin")) return deny(sender);
            plugin.sendStatus(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("treasury")) {
            if (!sender.hasPermission("aurum.admin")) return deny(sender);
            sender.sendMessage(plugin.messages().component("treasury-unavailable"));
            return true;
        }
        if (args[0].equalsIgnoreCase("balance")) {
            if (!sender.hasPermission("aurum.balance")) return deny(sender);
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("aurum.balance.others")) return deny(sender);
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.messages().component("player-not-online"));
                    return true;
                }
            } else if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(plugin.messages().component("player-only"));
                return true;
            }
            var snapshot = plugin.economy().cachedBalance(AccountId.player(target.getUniqueId()));
            if (snapshot.isEmpty()) {
                sender.sendMessage(plugin.messages().component("balance-missing"));
            } else {
                sender.sendMessage(plugin.messages().component("balance", Map.of(
                        "player", target.getName(),
                        "amount", snapshot.get().balance().stripTrailingZeros().toPlainString(),
                        "symbol", snapshot.get().currency().symbol()
                )));
            }
            return true;
        }
        sender.sendMessage(plugin.messages().component("passive-only"));
        return true;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage(plugin.messages().component("no-permission"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("balance", "status", "treasury").stream()
                .filter(it -> it.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("balance")
                && sender.hasPermission("aurum.balance.others")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(it -> it.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
