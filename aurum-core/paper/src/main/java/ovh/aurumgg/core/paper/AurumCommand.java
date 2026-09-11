package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

final class AurumCommand implements CommandExecutor, TabCompleter {
    private final AurumCorePlugin plugin;
    private final Map<UUID, Long> paymentCooldowns = new ConcurrentHashMap<>();

    AurumCommand(AurumCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        args = normalized(command.getName(), args);
        if (args.length > 0 && args[0].equalsIgnoreCase("pay")) return pay(sender, args);
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission("aurum.admin")) return deny(sender);
            plugin.sendStatus(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("treasury")) {
            if (!sender.hasPermission("aurum.admin")) return deny(sender);
            String currencyId = args.length >= 2 ? args[1] : plugin.settings().currency().id();
            CurrencySpec selected = currency(sender, currencyId);
            if (selected == null) return true;
            var snapshot = plugin.cachedGlobalSnapshot(currencyId).orElse(null);
            if (snapshot == null || !snapshot.authoritative()) {
                sender.sendMessage(plugin.messages().component("treasury-unavailable"));
            } else {
                sender.sendMessage(plugin.messages().component("treasury", Map.of(
                        "balance", amount(snapshot.treasuryBalance()),
                        "supply", amount(snapshot.moneySupply()),
                        "taxes", amount(snapshot.taxesCollected()),
                        "symbol", selected.symbol())));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("balance")) {
            if (!sender.hasPermission("aurum.balance")) return deny(sender);
            Player target;
            String currencyId = plugin.settings().currency().id();
            boolean ownCurrency = args.length >= 2 && plugin.settings().currencies()
                    .containsKey(args[1].toLowerCase(java.util.Locale.ROOT));
            if (ownCurrency && sender instanceof Player player) {
                target = player;
                currencyId = args[1];
            } else if (args.length >= 2) {
                if (!sender.hasPermission("aurum.balance.others")) return deny(sender);
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.messages().component("player-not-online"));
                    return true;
                }
                if (args.length >= 3) currencyId = args[2];
            } else if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(plugin.messages().component("player-only"));
                return true;
            }
            CurrencySpec selected = currency(sender, currencyId);
            if (selected == null) return true;
            var snapshot = plugin.cachedBalance(AccountId.player(target.getUniqueId()), selected.id());
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
            return economy(sender, args);
        }
        if (args[0].equalsIgnoreCase("policy")) {
            if (!sender.hasPermission("aurum.admin.policy")) return deny(sender);
            if (plugin.policies() == null) {
                sender.sendMessage(plugin.messages().component("policy-unavailable"));
                return true;
            }
            return plugin.policies().execute(sender, args);
        }
        if (args[0].equalsIgnoreCase("claims")) {
            if (!sender.hasPermission("aurum.admin.claims")) return deny(sender);
            if (plugin.claimCommands() == null) {
                sender.sendMessage(plugin.messages().component("claims-unavailable"));
                return true;
            }
            return plugin.claimCommands().execute(sender, args);
        }
        if (args[0].equalsIgnoreCase("exchange")) {
            if (!sender.hasPermission("aurum.admin.exchange")) return deny(sender);
            if (plugin.exchanges() == null) {
                sender.sendMessage(plugin.messages().component("exchange-unavailable"));
                return true;
            }
            return plugin.exchanges().execute(sender, args);
        }
        sender.sendMessage(plugin.messages().component("passive-only"));
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aurum.pay")) return deny(sender);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().component("player-only"));
            return true;
        }
        if (!plugin.activeReady()) return activeUnavailable(sender);
        if (!plugin.settings().paymentsEnabled()) {
            sender.sendMessage(plugin.messages().component("payments-disabled"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.messages().component("pay-usage"));
            return true;
        }
        OfflinePlayer target = target(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.messages().component("player-unknown"));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.messages().component("pay-self"));
            return true;
        }
        BigDecimal value = parseAmount(sender, args[2], false);
        if (value == null) return true;
        if (value.compareTo(plugin.settings().paymentMinimum()) < 0
                || value.compareTo(plugin.settings().paymentMaximum()) > 0) {
            sender.sendMessage(plugin.messages().component("pay-limits", Map.of(
                    "minimum", amount(plugin.settings().paymentMinimum()),
                    "maximum", amount(plugin.settings().paymentMaximum()))));
            return true;
        }
        long now = System.currentTimeMillis();
        if (paymentCooldowns.size() > 4_096) {
            paymentCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        long until = paymentCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            long seconds = Math.max(1, (until - now + 999) / 1000);
            sender.sendMessage(plugin.messages().component("pay-cooldown", Map.of("seconds", Long.toString(seconds))));
            return true;
        }
        paymentCooldowns.put(player.getUniqueId(), now + plugin.settings().paymentCooldownSeconds() * 1000L);
        String reason = reason(args, 3);
        TransactionRequest request = new TransactionRequest(
                "pay:" + UUID.randomUUID(),
                AccountId.player(player.getUniqueId()),
                AccountId.player(target.getUniqueId()),
                plugin.settings().currency().id(), value, TransactionCategory.PLAYER_PAYMENT,
                metadata(sender, reason)
        );
        plugin.activeEconomy().transfer(request).whenComplete((result, error) -> reply(() -> {
            if (error != null || result.status() == TransactionResult.Status.UNAVAILABLE) {
                sender.sendMessage(plugin.messages().component("money-unavailable"));
            } else if (result.status() == TransactionResult.Status.REJECTED) {
                if (result.message().startsWith("POLICY:")) {
                    sender.sendMessage(plugin.messages().component("policy-transaction-rejected", Map.of(
                            "reason", result.message().substring("POLICY:".length()))));
                } else {
                    sender.sendMessage(plugin.messages().component("insufficient-funds"));
                }
            } else {
                sender.sendMessage(plugin.messages().component("pay-sent", Map.of(
                        "player", displayName(target, args[1]), "amount", amount(value),
                        "symbol", plugin.settings().currency().symbol())));
                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().sendMessage(plugin.messages().component("pay-received", Map.of(
                            "player", player.getName(), "amount", amount(result.netAmount()),
                            "symbol", plugin.settings().currency().symbol())));
                }
            }
        }));
        return true;
    }

    private boolean economy(CommandSender sender, String[] args) {
        if (!plugin.activeReady()) return activeUnavailable(sender);
        if (args.length < 4 || !List.of("give", "take", "set").contains(args[1].toLowerCase())) {
            sender.sendMessage(plugin.messages().component("economy-usage"));
            return true;
        }
        OfflinePlayer target = target(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.messages().component("player-unknown"));
            return true;
        }
        String operation = args[1].toLowerCase(java.util.Locale.ROOT);
        boolean explicitCurrency = args.length >= 5 && args[4].startsWith("currency:");
        String currencyId = explicitCurrency
                ? args[4].substring("currency:".length()) : plugin.settings().currency().id();
        CurrencySpec selected = currency(sender, currencyId);
        if (selected == null) return true;
        BigDecimal value = parseAmount(sender, args[3], operation.equals("set"), selected);
        if (value == null) return true;
        String key = "admin:" + operation + ":" + UUID.randomUUID();
        String reason = reason(args, explicitCurrency ? 5 : 4);
        var future = operation.equals("set")
                ? plugin.activeEconomy().setPlayerBalance(AccountId.player(target.getUniqueId()), selected.id(),
                        value, key, metadata(sender, reason))
                : plugin.activeEconomy().transfer(new TransactionRequest(
                        key,
                        operation.equals("give") ? system(AccountType.SYSTEM_SOURCE)
                                : AccountId.player(target.getUniqueId()),
                        operation.equals("give") ? AccountId.player(target.getUniqueId())
                                : system(AccountType.SYSTEM_SINK),
                        selected.id(), value, TransactionCategory.ADMIN_ADJUSTMENT,
                        metadata(sender, reason)));
        future.whenComplete((result, error) -> reply(() -> {
            if (error != null || result.status() == TransactionResult.Status.UNAVAILABLE) {
                sender.sendMessage(plugin.messages().component("money-unavailable"));
            } else if (result.status() == TransactionResult.Status.REJECTED) {
                sender.sendMessage(plugin.messages().component("insufficient-funds"));
            } else {
                var balance = plugin.cachedBalance(AccountId.player(target.getUniqueId()), selected.id())
                        .map(it -> amount(it.balance())).orElse("0");
                sender.sendMessage(plugin.messages().component("economy-success", Map.of(
                        "operation", operation, "player", displayName(target, args[2]),
                        "amount", amount(value), "balance", balance,
                        "symbol", selected.symbol())));
            }
        }));
        return true;
    }

    private boolean activeUnavailable(CommandSender sender) {
        sender.sendMessage(plugin.messages().component(plugin.activeMode()
                ? "active-not-ready" : "active-only"));
        return true;
    }

    private OfflinePlayer target(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getServer().getOfflinePlayerIfCached(name);
    }

    private BigDecimal parseAmount(CommandSender sender, String raw, boolean allowZero) {
        return parseAmount(sender, raw, allowZero, plugin.settings().currency());
    }

    private BigDecimal parseAmount(CommandSender sender, String raw, boolean allowZero, CurrencySpec currency) {
        try {
            BigDecimal value = currency.requireAmount(new BigDecimal(raw));
            if (value.signum() < 0 || (!allowZero && value.signum() == 0)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException exception) {
            sender.sendMessage(plugin.messages().component("invalid-amount", Map.of(
                    "scale", Integer.toString(currency.scale()))));
            return null;
        }
    }

    private CurrencySpec currency(CommandSender sender, String id) {
        CurrencySpec value = plugin.settings().currencies().get(id.toLowerCase(java.util.Locale.ROOT));
        if (value == null) sender.sendMessage(plugin.messages().component("currency-unknown", Map.of("id", id)));
        return value;
    }

    private static AccountId system(AccountType type) { return new AccountId(type, "global"); }
    private static String reason(String[] args, int from) {
        if (args.length <= from) return "unspecified";
        String value = String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
    private static Map<String, String> metadata(CommandSender sender, String reason) {
        return Map.of("actor", sender.getName(), "reason", reason);
    }
    private static String displayName(OfflinePlayer player, String fallback) {
        return player.getName() == null ? fallback : player.getName();
    }
    private static String amount(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private void reply(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
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
            case "apolicy" -> "policy";
            case "aexchange" -> "exchange";
            case "pay", "apay" -> "pay";
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
        if (values.length == 1) return List.of("balance", "status", "treasury", "migrate", "economy", "policy", "exchange", "claims").stream()
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
        if (values.length == 2 && values[0].equalsIgnoreCase("claims")
                && sender.hasPermission("aurum.admin.claims")) {
            return List.of("list", "inspect", "retry", "drop").stream()
                    .filter(it -> it.startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 2 && values[0].equalsIgnoreCase("economy")) {
            return List.of("give", "take", "set").stream()
                    .filter(it -> it.startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 2 && values[0].equalsIgnoreCase("policy")) {
            return List.of("list", "inspect", "history", "reload", "import-config", "create", "enable", "disable",
                            "priority", "schedule", "categories", "rate", "account", "condition",
                            "range", "exempt", "currency").stream()
                    .filter(it -> it.startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 2 && values[0].equalsIgnoreCase("exchange")) {
            return List.of("list", "inspect", "history", "reload", "create", "enable", "disable", "rate",
                    "range", "priority", "schedule", "settlement", "condition", "reserve").stream()
                    .filter(it -> it.startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 3 && values[0].equalsIgnoreCase("exchange")
                && List.of("inspect", "history", "enable", "disable", "rate", "range", "priority", "schedule",
                "settlement", "condition", "reserve").contains(values[1].toLowerCase())) {
            return plugin.exchanges() == null ? List.of() : plugin.exchanges().ids(values[2]);
        }
        if (values.length >= 4 && values.length <= 5 && values[0].equalsIgnoreCase("exchange")
                && values[1].equalsIgnoreCase("create")) {
            return plugin.settings().currencies().keySet().stream()
                    .filter(it -> it.startsWith(values[values.length - 1].toLowerCase())).toList();
        }
        if (values.length == 4 && values[0].equalsIgnoreCase("exchange")
                && values[1].equalsIgnoreCase("settlement")) return List.of("mint-burn", "reserve");
        if (values.length == 4 && values[0].equalsIgnoreCase("exchange")
                && values[1].equalsIgnoreCase("condition")) {
            return List.of("account-type", "account-id", "metadata");
        }
        if (values.length == 4 && values[0].equalsIgnoreCase("exchange")
                && values[1].equalsIgnoreCase("reserve")) {
            return plugin.settings().currencies().keySet().stream()
                    .filter(it -> it.startsWith(values[3].toLowerCase())).toList();
        }
        if (values.length == 5 && values[0].equalsIgnoreCase("exchange")
                && values[1].equalsIgnoreCase("reserve")) return List.of("give", "take");
        if (values.length == 3 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("import-config")) {
            return "CONFIRM".startsWith(values[2]) ? List.of("CONFIRM") : List.of();
        }
        if (values.length == 3 && values[0].equalsIgnoreCase("policy")
                && List.of("inspect", "history", "enable", "disable", "priority", "schedule",
                "categories", "rate", "account", "condition", "range", "exempt", "currency")
                .contains(values[1].toLowerCase())) {
            return plugin.policies() == null ? List.of() : plugin.policies().ids(values[2]);
        }
        if (values.length == 4 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("create")) {
            return Arrays.stream(ovh.aurumgg.core.engine.PolicyKind.values())
                    .filter(kind -> kind != ovh.aurumgg.core.engine.PolicyKind.CUSTOM).map(Enum::name)
                    .map(String::toLowerCase).filter(it -> it.startsWith(values[3].toLowerCase())).toList();
        }
        if (values.length == 6 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("create")) {
            return Arrays.stream(TransactionCategory.values())
                    .filter(ovh.aurumgg.core.engine.PolicyValidator::policyEligible).map(Enum::name)
                    .filter(it -> it.toLowerCase().startsWith(values[5].toLowerCase())).toList();
        }
        if (values.length == 7 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("create")) {
            return List.of("included", "added").stream()
                    .filter(it -> it.startsWith(values[6].toLowerCase())).toList();
        }
        if (values.length == 4 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("schedule")) return List.of("now", "-");
        if (values.length == 5 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("schedule")) return List.of("-");
        if (values.length == 4 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("account")) {
            return Arrays.stream(AccountType.values()).map(Enum::name)
                    .filter(it -> it.toLowerCase().startsWith(values[3].toLowerCase())).toList();
        }
        if (values.length == 4 && values[0].equalsIgnoreCase("policy")
                && values[1].equalsIgnoreCase("condition")) {
            return List.of("source-type", "source-id", "target-type", "target-id", "metadata").stream()
                    .filter(it -> it.startsWith(values[3].toLowerCase())).toList();
        }
        if (values.length == 2 && values[0].equalsIgnoreCase("pay")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(it -> !it.equalsIgnoreCase(sender.getName()))
                    .filter(it -> it.toLowerCase().startsWith(values[1].toLowerCase())).toList();
        }
        if (values.length == 3 && values[0].equalsIgnoreCase("economy")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(it -> it.toLowerCase().startsWith(values[2].toLowerCase())).toList();
        }
        if (values.length == 5 && values[0].equalsIgnoreCase("economy")) {
            return plugin.settings().currencies().keySet().stream().map(id -> "currency:" + id)
                    .filter(it -> it.startsWith(values[4].toLowerCase())).toList();
        }
        return List.of();
    }
}
