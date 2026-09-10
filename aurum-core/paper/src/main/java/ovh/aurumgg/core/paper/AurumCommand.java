package ovh.aurumgg.core.paper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        args = normalized(command.getName(), args);
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
        if (args[0].equalsIgnoreCase("migrate")) {
            if (!sender.hasPermission("aurum.admin.migrate")) return deny(sender);
            return migrate(sender, args);
        }
        if (args[0].equalsIgnoreCase("economy") || args[0].equalsIgnoreCase("eco")) {
            if (!sender.hasPermission("aurum.admin.economy")) return deny(sender);
            sender.sendMessage(plugin.messages().component("active-only"));
            return true;
        }
        sender.sendMessage(plugin.messages().component("passive-only"));
        return true;
    }

    private boolean migrate(CommandSender sender, String[] args) {
        if (!plugin.shadowMode()) {
            sender.sendMessage(plugin.messages().component("migration-shadow-only"));
            return true;
        }
        MigrationCoordinator migrations = plugin.migrations();
        if (migrations == null) {
            sender.sendMessage(plugin.messages().component("migration-database-unavailable"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.messages().component("migration-usage"));
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "dry-run" -> migrations.dryRun(sender);
            case "status" -> {
                UUID runId = args.length >= 3 ? uuid(sender, args[2]) : null;
                if (args.length < 3 || runId != null) migrations.status(sender, runId);
            }
            case "verify" -> {
                UUID runId = requiredUuid(sender, args);
                if (runId != null) migrations.verify(sender, runId);
            }
            case "import" -> {
                UUID runId = requiredUuid(sender, args);
                if (runId == null) return true;
                if (args.length < 4 || !args[3].equals("CONFIRM")) {
                    sender.sendMessage(plugin.messages().component("migration-confirm", Map.of("run", runId.toString())));
                } else {
                    migrations.importRun(sender, runId);
                }
            }
            case "rollback-export" -> migrations.rollbackExport(sender);
            default -> sender.sendMessage(plugin.messages().component("migration-usage"));
        }
        return true;
    }

    private UUID requiredUuid(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.messages().component("migration-usage"));
            return null;
        }
        return uuid(sender, args[2]);
    }

    private UUID uuid(CommandSender sender, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(plugin.messages().component("migration-invalid-id"));
            return null;
        }
    }

    private static String[] normalized(String commandName, String[] args) {
        String prefix = switch (commandName.toLowerCase()) {
            case "abal" -> "balance";
            case "atreasury" -> "treasury";
            case "amigrate" -> "migrate";
            case "aeco" -> "economy";
            default -> null;
        };
        if (prefix == null) return args;
        String[] normalized = new String[args.length + 1];
        normalized[0] = prefix;
        System.arraycopy(args, 0, normalized, 1, args.length);
        return normalized;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage(plugin.messages().component("no-permission"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        String[] values = normalized(command.getName(), args);
        if (values.length == 1) return List.of("balance", "status", "treasury", "migrate", "economy").stream()
                .filter(it -> it.startsWith(values[0].toLowerCase())).toList();
        if (values.length == 2 && values[0].equalsIgnoreCase("balance")
                && sender.hasPermission("aurum.balance.others")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(it -> it.toLowerCase().startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 2 && values[0].equalsIgnoreCase("migrate")) {
            return List.of("dry-run", "status", "verify", "import", "rollback-export").stream()
                    .filter(it -> it.startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 4 && values[0].equalsIgnoreCase("migrate")
                && values[1].equalsIgnoreCase("import")) {
            return "CONFIRM".startsWith(values[3]) ? List.of("CONFIRM") : List.of();
        }
        if (values.length == 2 && values[0].equalsIgnoreCase("economy")) {
            return List.of("give", "take", "set").stream()
                    .filter(it -> it.startsWith(values[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
