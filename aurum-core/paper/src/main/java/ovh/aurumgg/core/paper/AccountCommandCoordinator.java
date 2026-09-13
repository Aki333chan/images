package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountCloseRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountQuery;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStateRequest;
import ovh.aurumgg.core.api.ManagedAccountTransferRequest;
import ovh.aurumgg.core.engine.AccountRegistryService;

/** Small command adapter; all database and ledger work remains in the registry service. */
final class AccountCommandCoordinator {
    private final AurumCorePlugin plugin;
    private final AccountRegistryService registry;
    private volatile List<String> cachedProfiles = List.of(AccountRegistryService.GLOBAL_TREASURY_PROFILE);

    AccountCommandCoordinator(AurumCorePlugin plugin, AccountRegistryService registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) return usage(sender, args[0]);
        boolean accounts = args[0].equalsIgnoreCase("accounts");
        String action = args[1].toLowerCase(Locale.ROOT);
        try {
            if (accounts) {
                return switch (action) {
                    case "list" -> list(sender, "", args);
                    case "inspect" -> inspect(sender, args, false);
                    case "transfer" -> accountTransfer(sender, args);
                    default -> usage(sender, "accounts");
                };
            }
            return switch (action) {
                case "list" -> list(sender, "TREASURY", args);
                case "inspect" -> inspect(sender, args, true);
                case "create" -> create(sender, args);
                case "transfer" -> transfer(sender, args);
                case "pay" -> playerTransfer(sender, args, true);
                case "collect" -> playerTransfer(sender, args, false);
                case "freeze" -> frozen(sender, args, true);
                case "unfreeze" -> frozen(sender, args, false);
                case "close" -> close(sender, args);
                default -> usage(sender, "fund");
            };
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage(plugin.messages().component("account-invalid", Map.of("error", invalid.getMessage())));
            return true;
        }
    }

    private boolean list(CommandSender sender, String type, String[] args) {
        int page;
        try { page = args.length >= 3 ? Math.max(1, Integer.parseInt(args[2])) : 1; }
        catch (NumberFormatException invalid) { return usage(sender, type.isEmpty() ? "accounts" : "fund"); }
        registry.list(new ManagedAccountQuery("", type, null, type.isEmpty(), (page - 1) * 20, 20))
                .thenAccept(result -> reply(() -> {
                    cache(result.accounts());
                    sender.sendMessage(plugin.messages().component("account-list-header", Map.of(
                            "page", Integer.toString(page), "total", Long.toString(result.total()))));
                    if (result.accounts().isEmpty()) {
                        sender.sendMessage(plugin.messages().component("account-list-empty"));
                        return;
                    }
                    result.accounts().forEach(account -> sender.sendMessage(plugin.messages().component(
                            "account-list-line", Map.of("key", account.profileKey(), "name", account.displayName(),
                                    "type", account.profileType(), "status", account.status().name(),
                                    "balances", balances(account)))));
                })).exceptionally(failure -> { failed(sender, failure); return null; });
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args, boolean fund) {
        if (args.length < 3) return usage(sender, fund ? "fund" : "accounts");
        String key = fund ? fund(args[2]) : args[2].toLowerCase(Locale.ROOT);
        registry.find(key).thenAccept(found -> reply(() -> {
            if (found.isEmpty()) sender.sendMessage(plugin.messages().component("account-not-found", Map.of("key", key)));
            else {
                ManagedAccount account = found.get();
                cache(List.of(account));
                sender.sendMessage(plugin.messages().component("account-inspect", Map.of(
                        "key", account.profileKey(), "name", account.displayName(), "type", account.profileType(),
                        "status", account.status().name(), "owner", owner(account), "founder", blank(account.founderUuid()),
                        "source", account.sourcePlugin(), "linked", linked(account), "destination",
                        blank(account.closeDestinationProfile()), "balances", balances(account))));
                account.members().forEach(member -> sender.sendMessage(plugin.messages().component(
                        "account-member", Map.of("role", member.role(), "account", member.account().stableKey()))));
            }
        })).exceptionally(failure -> { failed(sender, failure); return null; });
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender, "fund");
        String id;
        try { id = id(args[2]); }
        catch (IllegalArgumentException invalid) {
            sender.sendMessage(plugin.messages().component("account-invalid", Map.of("error", invalid.getMessage())));
            return true;
        }
        String key = fund(id);
        String name = args[3].replace('_', ' ');
        String purpose = reason(args, 4, "Named treasury fund");
        ManagedAccountRegistration request = new ManagedAccountRegistration(
                "command:create-fund:" + UUID.randomUUID(), key, "TREASURY", name, purpose,
                "SERVER", "global", sender instanceof Player player ? player.getUniqueId().toString() : "",
                "AurumCore", "TREASURY", id, "",
                false, List.of(new ManagedAccountMember(new AccountId(AccountType.TREASURY, id), "primary", 0)),
                actor(sender), "create named fund");
        report(sender, registry.register(request));
        return true;
    }

    private boolean transfer(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender, "fund");
        ParsedAmount parsed = amount(sender, args, 4, 5);
        if (parsed == null) return true;
        report(sender, registry.transfer(new ManagedAccountTransferRequest(
                UUID.randomUUID().toString(), fund(args[2]), "primary", fund(args[3]), "primary",
                parsed.currency(), parsed.amount(), actor(sender), parsed.reason())));
        return true;
    }

    /**
     * Administrative transfer between explicit members of any two managed profiles.
     * This is the console counterpart of the panel's forced-transfer dialog. It still
     * obeys lifecycle gates, available balance and idempotent ledger semantics.
     */
    private boolean accountTransfer(CommandSender sender, String[] args) {
        if (args.length < 7) return usage(sender, "accounts");
        ParsedAmount parsed = amount(sender, args, 6, 7);
        if (parsed == null) return true;
        report(sender, registry.transfer(new ManagedAccountTransferRequest(
                UUID.randomUUID().toString(), profile(args[2]), role(args[3]),
                profile(args[4]), role(args[5]), parsed.currency(), parsed.amount(),
                actor(sender), parsed.reason())));
        return true;
    }

    private boolean playerTransfer(CommandSender sender, String[] args, boolean pay) {
        if (args.length < 5) return usage(sender, "fund");
        String fundId = pay ? args[2] : args[3];
        String playerName = pay ? args[3] : args[2];
        OfflinePlayer target = Bukkit.getPlayerExact(playerName);
        if (target == null) target = Bukkit.getServer().getOfflinePlayerIfCached(playerName);
        if (target == null) {
            sender.sendMessage(plugin.messages().component("player-unknown"));
            return true;
        }
        ParsedAmount parsed = amount(sender, args, 4, 5);
        if (parsed == null) return true;
        OfflinePlayer selected = target;
        ensurePlayer(selected).thenCompose(registered -> {
            if (!success(registered.status())) return java.util.concurrent.CompletableFuture.completedFuture(registered);
            String playerProfile = "player:" + selected.getUniqueId().toString().toLowerCase(Locale.ROOT);
            return registry.transfer(new ManagedAccountTransferRequest(UUID.randomUUID().toString(),
                    pay ? fund(fundId) : playerProfile, "primary",
                    pay ? playerProfile : fund(fundId), "primary", parsed.currency(), parsed.amount(),
                    actor(sender), parsed.reason()));
        }).thenAccept(result -> showResult(sender, result))
                .exceptionally(failure -> { failed(sender, failure); return null; });
        return true;
    }

    private CompletionStage<ManagedAccountMutationResult> ensurePlayer(OfflinePlayer player) {
        String uuid = player.getUniqueId().toString().toLowerCase(Locale.ROOT);
        return registry.register(new ManagedAccountRegistration(
                "core-player-profile:" + uuid, "player:" + uuid, "PLAYER",
                player.getName() == null ? uuid : player.getName(), "Player wallet", "PLAYER", uuid, uuid,
                "AurumCore", "PLAYER", uuid, "", false,
                List.of(new ManagedAccountMember(AccountId.player(player.getUniqueId()), "primary", 0)),
                "system:AurumCore", "register player wallet"));
    }

    private boolean frozen(CommandSender sender, String[] args, boolean frozen) {
        if (args.length < 3) return usage(sender, "fund");
        report(sender, registry.setFrozen(new ManagedAccountStateRequest(UUID.randomUUID().toString(),
                fund(args[2]), frozen, actor(sender), reason(args, 3, frozen ? "freeze fund" : "unfreeze fund"))));
        return true;
    }

    private boolean close(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender, "fund");
        int confirm = -1;
        for (int index = 3; index < args.length; index++) if (args[index].equals("CONFIRM")) { confirm = index; break; }
        if (confirm < 0) {
            sender.sendMessage(plugin.messages().component("account-close-confirm", Map.of("id", args[2])));
            return true;
        }
        String destination = confirm == 3 ? "" : fund(args[3]);
        report(sender, registry.close(new ManagedAccountCloseRequest(UUID.randomUUID().toString(), fund(args[2]),
                destination, actor(sender), reason(args, confirm + 1, "close fund"))));
        return true;
    }

    private void report(CommandSender sender, CompletionStage<ManagedAccountMutationResult> operation) {
        operation.thenAccept(result -> showResult(sender, result))
                .exceptionally(failure -> { failed(sender, failure); return null; });
    }

    private void showResult(CommandSender sender, ManagedAccountMutationResult result) {
        reply(() -> sender.sendMessage(plugin.messages().component(
                success(result.status()) ? "account-operation-success" : "account-operation-failed",
                Map.of("status", result.status().name(), "message", result.message(), "key",
                        result.account() == null ? "-" : result.account().profileKey()))));
    }

    private ParsedAmount amount(CommandSender sender, String[] args, int valueIndex, int nextIndex) {
        String currency = plugin.settings().currency().id();
        int reasonFrom = nextIndex;
        if (args.length > nextIndex && args[nextIndex].startsWith("currency:")) {
            currency = args[nextIndex].substring("currency:".length()).toLowerCase(Locale.ROOT);
            reasonFrom++;
        }
        var spec = plugin.settings().currencies().get(currency);
        if (spec == null) {
            sender.sendMessage(plugin.messages().component("currency-unknown", Map.of("id", currency)));
            return null;
        }
        try {
            BigDecimal amount = spec.requireAmount(new BigDecimal(args[valueIndex]));
            if (amount.signum() <= 0) throw new IllegalArgumentException();
            return new ParsedAmount(amount, currency, reason(args, reasonFrom, "managed fund transfer"));
        } catch (RuntimeException invalid) {
            sender.sendMessage(plugin.messages().component("invalid-amount", Map.of("scale", Integer.toString(spec.scale()))));
            return null;
        }
    }

    List<String> profileKeys(String prefix) {
        String value = prefix.toLowerCase(Locale.ROOT);
        return cachedProfiles.stream().filter(key -> key.startsWith(value)).limit(50).toList();
    }

    List<String> fundIds(String prefix) {
        String value = prefix.toLowerCase(Locale.ROOT);
        return cachedProfiles.stream().filter(key -> key.startsWith("treasury:"))
                .map(key -> key.substring("treasury:".length())).filter(key -> key.startsWith(value)).limit(50).toList();
    }

    private void cache(List<ManagedAccount> accounts) {
        Set<String> values = new java.util.TreeSet<>(cachedProfiles);
        accounts.forEach(account -> values.add(account.profileKey()));
        cachedProfiles = List.copyOf(values);
    }

    private boolean usage(CommandSender sender, String kind) {
        sender.sendMessage(plugin.messages().component(kind.equals("accounts")
                ? "account-usage" : "fund-usage"));
        return true;
    }

    private void failed(CommandSender sender, Throwable failure) {
        Throwable value = failure.getCause() == null ? failure : failure.getCause();
        reply(() -> sender.sendMessage(plugin.messages().component("account-operation-failed", Map.of(
                "status", "UNAVAILABLE", "message", value.getMessage() == null
                        ? value.getClass().getSimpleName() : value.getMessage(), "key", "-"))));
    }

    private void reply(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    private static boolean success(ManagedAccountMutationResult.Status status) {
        return status == ManagedAccountMutationResult.Status.SUCCESS
                || status == ManagedAccountMutationResult.Status.DUPLICATE;
    }
    private static String fund(String value) {
        String id = id(value.toLowerCase(Locale.ROOT).startsWith("treasury:")
                ? value.substring("treasury:".length()) : value);
        return "treasury:" + id;
    }
    private static String profile(String value) {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._:-]{1,159}")) {
            throw new IllegalArgumentException("profile key: 2..160 lowercase letters, digits, dot, underscore, colon or dash");
        }
        return value;
    }
    private static String role(String value) {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("member role: 1..32 lowercase letters, digits, dot, underscore or dash");
        }
        return value;
    }
    private static String id(String value) {
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("id: 1..64 lowercase letters, digits, dot, underscore, colon or dash");
        }
        return value;
    }
    private static String actor(CommandSender sender) { return "command:" + sender.getName(); }
    private static String reason(String[] args, int from, String fallback) {
        if (args.length <= from) return fallback;
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length)).trim();
        return value.isEmpty() ? fallback : value.substring(0, Math.min(255, value.length()));
    }
    private static String blank(String value) { return value == null || value.isBlank() ? "-" : value; }
    private static String owner(ManagedAccount account) {
        return account.ownerKind().isBlank() ? "-" : account.ownerKind() + ":" + account.ownerId();
    }
    private static String linked(ManagedAccount account) {
        return account.linkedObjectType().isBlank() ? "-"
                : account.linkedObjectType() + ":" + account.linkedObjectId();
    }
    private static String balances(ManagedAccount account) {
        List<String> values = new ArrayList<>();
        account.balances().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                values.add(entry.getKey() + "=" + entry.getValue().stripTrailingZeros().toPlainString()));
        return String.join(", ", values);
    }
    private record ParsedAmount(BigDecimal amount, String currency, String reason) {}
}
