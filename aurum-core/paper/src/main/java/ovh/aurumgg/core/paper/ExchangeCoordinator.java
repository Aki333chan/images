package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;
import ovh.aurumgg.core.engine.ExchangeRegistry;
import ovh.aurumgg.core.engine.ExchangeRepository;
import ovh.aurumgg.core.engine.ExchangeRule;
import ovh.aurumgg.core.engine.ExchangeSettlement;

final class ExchangeCoordinator {
    private final AurumCorePlugin plugin;
    private final ExchangeRepository repository;
    private final ExchangeRegistry registry;
    private final Executor executor;

    ExchangeCoordinator(AurumCorePlugin plugin, ExchangeRepository repository,
                        ExchangeRegistry registry, Executor executor) {
        this.plugin = plugin;
        this.repository = repository;
        this.registry = registry;
        this.executor = executor;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) return usage(sender);
        try {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "list" -> list(sender);
                case "inspect" -> inspect(sender, args);
                case "history" -> history(sender, args);
                case "reload" -> reload(sender);
                case "create" -> create(sender, args);
                case "enable" -> toggle(sender, args, true);
                case "disable" -> toggle(sender, args, false);
                case "rate" -> rate(sender, args);
                case "range" -> range(sender, args);
                case "priority" -> priority(sender, args);
                case "schedule" -> schedule(sender, args);
                case "settlement" -> settlement(sender, args);
                case "condition" -> condition(sender, args);
                case "reserve" -> reserve(sender, args);
                default -> usage(sender);
            };
        } catch (RuntimeException exception) {
            sender.sendMessage(plugin.messages().component("exchange-invalid",
                    Map.of("error", safe(exception.getMessage()))));
            return true;
        }
    }

    List<String> ids(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return registry.snapshot().stream().map(ExchangeRule::id)
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private boolean list(CommandSender sender) {
        sender.sendMessage(plugin.messages().component("exchange-list-header", Map.of(
                "count", Integer.toString(registry.snapshot().size()),
                "state", plugin.settings().exchange().enabled() ? "ENABLED" : "DISABLED")));
        registry.snapshot().forEach(rule -> sender.sendMessage(plugin.messages().component(
                "exchange-list-line", Map.of("id", rule.id(), "from", rule.fromCurrencyId(),
                        "to", rule.toCurrencyId(), "rate", number(rule.rate()),
                        "fee", number(rule.feeRate()), "enabled", Boolean.toString(rule.enabled())))));
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        ExchangeRule rule = required(args);
        sender.sendMessage(plugin.messages().component("exchange-inspect", Map.ofEntries(
                Map.entry("id", rule.id()), Map.entry("revision", Long.toString(rule.revision())),
                Map.entry("from", rule.fromCurrencyId()), Map.entry("to", rule.toCurrencyId()),
                Map.entry("rate", number(rule.rate())), Map.entry("fee", number(rule.feeRate())),
                Map.entry("minimum", nullable(rule.minimumSource())),
                Map.entry("maximum", nullable(rule.maximumSource())),
                Map.entry("settlement", rule.settlement().name()),
                Map.entry("conditions", rule.conditions().toString()),
                Map.entry("priority", Integer.toString(rule.priority())),
                Map.entry("enabled", Boolean.toString(rule.enabled())),
                Map.entry("fromTime", time(rule.effectiveFrom())),
                Map.entry("until", time(rule.effectiveUntil())))));
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (args.length < 3) return usage(sender);
        String id = args[2];
        executor.execute(() -> {
            try {
                var values = repository.history(id, 10, plugin.settings().currencies());
                reply(() -> {
                    sender.sendMessage(plugin.messages().component("exchange-history-header",
                            Map.of("id", id, "count", Integer.toString(values.size()))));
                    values.forEach(value -> sender.sendMessage(plugin.messages().component(
                            "exchange-history-line", Map.of("revision", Long.toString(value.rule().revision()),
                                    "rate", number(value.rule().rate()), "enabled",
                                    Boolean.toString(value.rule().enabled()), "actor", value.changedBy(),
                                    "reason", value.reason()))));
                });
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean reload(CommandSender sender) {
        executor.execute(() -> {
            try {
                refresh();
                reply(() -> sender.sendMessage(plugin.messages().component("exchange-reloaded",
                        Map.of("count", Integer.toString(registry.snapshot().size())))));
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (args.length < 6) return usage(sender);
        if (registry.snapshot().size() >= plugin.settings().exchange().maxRules()) {
            throw new IllegalArgumentException("Configured exchange rule limit reached");
        }
        String id = args[2];
        if (!plugin.settings().currencies().containsKey(args[3].toLowerCase(Locale.ROOT))
                || !plugin.settings().currencies().containsKey(args[4].toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unknown currency");
        }
        BigDecimal rate = new BigDecimal(args[5]);
        BigDecimal fee = args.length >= 7 ? new BigDecimal(args[6]) : BigDecimal.ZERO;
        ExchangeSettlement mode = args.length >= 8 ? mode(args[7]) : ExchangeSettlement.MINT_BURN;
        ExchangeRule rule = new ExchangeRule(id, 1, args[3], args[4], rate, fee,
                null, null, mode, Map.of(), 0, false, null, null);
        executor.execute(() -> {
            try {
                if (repository.findRule(id, plugin.settings().currencies()).isPresent()) {
                    throw new IllegalArgumentException("Exchange rule already exists");
                }
                saveAndRefresh(sender, rule, "created via command");
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean toggle(CommandSender sender, String[] args, boolean enabled) {
        if (args.length < 3) return usage(sender);
        return mutate(sender, args[2], rule -> copy(rule, rule.rate(), rule.feeRate(),
                rule.minimumSource(), rule.maximumSource(), rule.settlement(), rule.conditions(),
                rule.priority(), enabled, rule.effectiveFrom(), rule.effectiveUntil()),
                enabled ? "enabled" : "disabled");
    }

    private boolean rate(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        BigDecimal value = new BigDecimal(args[3]);
        BigDecimal fee = args.length >= 5 ? new BigDecimal(args[4]) : null;
        return mutate(sender, args[2], rule -> copy(rule, value, fee == null ? rule.feeRate() : fee,
                rule.minimumSource(), rule.maximumSource(), rule.settlement(), rule.conditions(),
                rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil()), "rate changed");
    }

    private boolean range(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        return mutate(sender, args[2], rule -> copy(rule, rule.rate(), rule.feeRate(),
                decimal(args[3]), decimal(args[4]), rule.settlement(), rule.conditions(),
                rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil()), "range changed");
    }

    private boolean priority(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        int value = Integer.parseInt(args[3]);
        return mutate(sender, args[2], rule -> copy(rule, rule.rate(), rule.feeRate(),
                rule.minimumSource(), rule.maximumSource(), rule.settlement(), rule.conditions(),
                value, rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil()), "priority changed");
    }

    private boolean schedule(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        Instant from = PolicyConfiguration.instant(args[3]);
        Instant until = PolicyConfiguration.instant(args[4]);
        return mutate(sender, args[2], rule -> copy(rule, rule.rate(), rule.feeRate(),
                rule.minimumSource(), rule.maximumSource(), rule.settlement(), rule.conditions(),
                rule.priority(), rule.enabled(), from, until), "schedule changed");
    }

    private boolean settlement(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        ExchangeSettlement value = mode(args[3]);
        return mutate(sender, args[2], rule -> copy(rule, rule.rate(), rule.feeRate(),
                rule.minimumSource(), rule.maximumSource(), value, rule.conditions(),
                rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil()), "settlement changed");
    }

    private boolean condition(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        String key = args[3].toLowerCase(Locale.ROOT);
        if (!List.of("account-type", "account-id", "metadata").contains(key)) {
            throw new IllegalArgumentException("Unknown condition");
        }
        if (key.equals("account-type") && !args[4].equals("-")) {
            AccountType.valueOf(args[4].toUpperCase(Locale.ROOT));
        }
        return mutate(sender, args[2], rule -> {
            Map<String, String> values = new LinkedHashMap<>(rule.conditions());
            if (key.equals("metadata")) {
                values.remove("metadata-key");
                values.remove("metadata-value");
                if (!args[4].equals("-")) {
                    if (args.length < 6) throw new IllegalArgumentException("Metadata needs key and value");
                    values.put("metadata-key", args[4]);
                    values.put("metadata-value", args[5]);
                }
            } else if (args[4].equals("-")) values.remove(key); else values.put(key, args[4]);
            return copy(rule, rule.rate(), rule.feeRate(), rule.minimumSource(), rule.maximumSource(),
                    rule.settlement(), values, rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil());
        }, "condition changed");
    }

    private boolean reserve(CommandSender sender, String[] args) {
        if (args.length < 6) return usage(sender);
        if (!plugin.activeReady()) throw new IllegalStateException("Active economy is not ready");
        ExchangeRule rule = required(args);
        var currency = plugin.settings().currencies().get(args[3].toLowerCase(Locale.ROOT));
        if (currency == null || !(currency.id().equals(rule.fromCurrencyId())
                || currency.id().equals(rule.toCurrencyId()))) {
            throw new IllegalArgumentException("Currency is not part of that exchange pair");
        }
        boolean give = args[4].equalsIgnoreCase("give");
        if (!give && !args[4].equalsIgnoreCase("take")) throw new IllegalArgumentException("Use give or take");
        BigDecimal amount = currency.requireAmount(new BigDecimal(args[5]));
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        AccountId reserve = new AccountId(AccountType.EXCHANGE_RESERVE, rule.id());
        AccountId system = new AccountId(give ? AccountType.SYSTEM_SOURCE : AccountType.SYSTEM_SINK, "global");
        TransactionRequest request = new TransactionRequest("exchange-reserve:" + UUID.randomUUID(),
                give ? system : reserve, give ? reserve : system, currency.id(), amount,
                TransactionCategory.ADMIN_ADJUSTMENT,
                Map.of("actor", sender.getName(), "reason", "exchange reserve " + args[4]));
        plugin.activeEconomy().transfer(request).whenComplete((result, error) -> reply(() -> {
            if (error != null || result.status() == TransactionResult.Status.UNAVAILABLE) {
                sender.sendMessage(plugin.messages().component("money-unavailable"));
            } else if (result.status() == TransactionResult.Status.REJECTED) {
                sender.sendMessage(plugin.messages().component("insufficient-funds"));
            } else {
                sender.sendMessage(plugin.messages().component("exchange-reserve-saved", Map.of(
                        "rule", rule.id(), "operation", args[4].toLowerCase(Locale.ROOT),
                        "amount", number(amount), "currency", currency.id())));
            }
        }));
        return true;
    }

    private boolean mutate(CommandSender sender, String id, UnaryOperator<ExchangeRule> update, String reason) {
        ExchangeRule changed = update.apply(registry.snapshot().stream()
                .filter(rule -> rule.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown exchange rule")));
        executor.execute(() -> {
            try { saveAndRefresh(sender, changed, reason); }
            catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private void saveAndRefresh(CommandSender sender, ExchangeRule rule, String reason) throws Exception {
        long revision = repository.saveRule(rule, plugin.settings().currencies(), sender.getName(), reason);
        refresh();
        reply(() -> sender.sendMessage(plugin.messages().component("exchange-saved",
                Map.of("id", rule.id(), "revision", Long.toString(revision)))));
    }

    private void refresh() throws Exception {
        List<ExchangeRule> values = repository.listRules(plugin.settings().currencies());
        if (values.size() > plugin.settings().exchange().maxRules()) throw new IllegalStateException("Rule limit exceeded");
        registry.replace(values);
    }

    private ExchangeRule required(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Rule id is required");
        return registry.snapshot().stream().filter(rule -> rule.id().equalsIgnoreCase(args[2]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown exchange rule"));
    }

    private static ExchangeRule copy(ExchangeRule rule, BigDecimal rate, BigDecimal fee,
                                     BigDecimal min, BigDecimal max, ExchangeSettlement settlement,
                                     Map<String, String> conditions, int priority, boolean enabled,
                                     Instant from, Instant until) {
        return new ExchangeRule(rule.id(), rule.revision(), rule.fromCurrencyId(), rule.toCurrencyId(),
                rate, fee, min, max, settlement, conditions, priority, enabled, from, until);
    }
    private boolean usage(CommandSender sender) {
        sender.sendMessage(plugin.messages().component("exchange-usage")); return true;
    }
    private void fail(CommandSender sender, Exception exception) {
        reply(() -> sender.sendMessage(plugin.messages().component("exchange-invalid",
                Map.of("error", safe(exception.getMessage())))));
    }
    private void reply(Runnable task) { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task); }
    private static ExchangeSettlement mode(String value) {
        return ExchangeSettlement.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
    }
    private static BigDecimal decimal(String value) { return value.equals("-") ? null : new BigDecimal(value); }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String nullable(BigDecimal value) { return value == null ? "-" : number(value); }
    private static String time(Instant value) { return value == null ? "-" : value.toString(); }
    private static String safe(String value) { return value == null ? "unknown error" : value.substring(0, Math.min(200, value.length())); }
}
