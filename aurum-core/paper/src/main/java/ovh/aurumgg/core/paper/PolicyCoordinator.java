package ovh.aurumgg.core.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.engine.FinancialRule;
import ovh.aurumgg.core.engine.PolicyKind;
import ovh.aurumgg.core.engine.PolicyRegistry;
import ovh.aurumgg.core.engine.PolicyRepository;
import ovh.aurumgg.core.engine.PolicyRevision;
import ovh.aurumgg.core.engine.PolicyValidator;

final class PolicyCoordinator {
    private final AurumCorePlugin plugin;
    private final PolicyRepository repository;
    private final PolicyRegistry registry;
    private final Executor executor;

    PolicyCoordinator(AurumCorePlugin plugin, PolicyRepository repository,
                      PolicyRegistry registry, Executor executor) {
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
                case "import-config" -> importConfig(sender, args);
                case "create" -> create(sender, args);
                case "enable" -> toggle(sender, args, true);
                case "disable" -> toggle(sender, args, false);
                case "priority" -> priority(sender, args);
                case "schedule" -> schedule(sender, args);
                case "categories" -> categories(sender, args);
                case "rate" -> rate(sender, args);
                case "account" -> account(sender, args);
                case "condition" -> condition(sender, args);
                case "range" -> range(sender, args);
                case "exempt" -> exempt(sender, args);
                default -> usage(sender);
            };
        } catch (RuntimeException exception) {
            sender.sendMessage(plugin.messages().component("policy-invalid",
                    Map.of("error", safe(exception.getMessage()))));
            return true;
        }
    }

    List<String> ids(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return registry.snapshot().stream().map(FinancialRule::id)
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private boolean list(CommandSender sender) {
        List<FinancialRule> rules = registry.snapshot();
        sender.sendMessage(plugin.messages().component("policy-list-header",
                Map.of("count", Integer.toString(rules.size()),
                        "state", plugin.settings().policies().enabled() ? "ENABLED" : "DISABLED")));
        rules.forEach(rule -> sender.sendMessage(plugin.messages().component("policy-list-line", Map.of(
                "id", rule.id(), "kind", rule.kind().name(), "enabled", Boolean.toString(rule.enabled()),
                "priority", Integer.toString(rule.priority())))));
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        FinancialRule rule = requiredRule(args);
        sender.sendMessage(plugin.messages().component("policy-inspect", Map.of(
                "id", rule.id(), "kind", rule.kind().name(), "enabled", Boolean.toString(rule.enabled()),
                "priority", Integer.toString(rule.priority()),
                "categories", joinCategories(rule.categories()),
                "definition", rule.definition().toString(),
                "from", time(rule.effectiveFrom()), "until", time(rule.effectiveUntil()))));
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (args.length < 3) return usage(sender);
        String id = args[2];
        executor.execute(() -> {
            try {
                List<PolicyRevision> revisions = repository.history(id, 10, plugin.settings().currency());
                reply(() -> {
                    sender.sendMessage(plugin.messages().component("policy-history-header",
                            Map.of("id", id, "count", Integer.toString(revisions.size()))));
                    revisions.forEach(revision -> sender.sendMessage(plugin.messages().component(
                            "policy-history-line", Map.of("revision", Long.toString(revision.revision()),
                                    "enabled", Boolean.toString(revision.rule().enabled()),
                                    "actor", revision.changedBy(), "reason", revision.reason()))));
                });
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean reload(CommandSender sender) {
        executor.execute(() -> {
            try {
                refresh();
                reply(() -> sender.sendMessage(plugin.messages().component("policy-reloaded",
                        Map.of("count", Integer.toString(registry.snapshot().size())))));
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean importConfig(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[2].equals("CONFIRM")) {
            sender.sendMessage(plugin.messages().component("policy-import-confirm"));
            return true;
        }
        List<FinancialRule> configured = plugin.settings().policies().bootstrapRules();
        if (configured.isEmpty()) throw new IllegalArgumentException("No config policies to import");
        if (configured.size() > plugin.settings().policies().maxRules()) {
            throw new IllegalArgumentException("Config exceeds the policy limit");
        }
        long newIds = configured.stream().map(FinancialRule::id)
                .filter(id -> registry.snapshot().stream().noneMatch(rule -> rule.id().equalsIgnoreCase(id)))
                .count();
        if (registry.snapshot().size() + newIds > plugin.settings().policies().maxRules()) {
            throw new IllegalArgumentException("Config import would exceed the policy limit");
        }
        executor.execute(() -> {
            try {
                repository.saveAll(configured, plugin.settings().currency(), sender.getName(),
                        "confirmed config import");
                try {
                    refresh();
                    reply(() -> sender.sendMessage(plugin.messages().component("policy-imported",
                            Map.of("count", Integer.toString(configured.size())))));
                } catch (Exception refreshFailure) {
                    committedButStale(sender, "config import", refreshFailure);
                }
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (args.length < 6) return usage(sender);
        if (registry.snapshot().size() >= plugin.settings().policies().maxRules()) {
            throw new IllegalArgumentException("Configured policy limit reached");
        }
        String id = args[2];
        PolicyKind kind = PolicyKind.valueOf(args[3].toUpperCase(Locale.ROOT));
        Map<String, String> definition = new LinkedHashMap<>();
        switch (kind) {
            case TAX, FEE, COMMISSION -> {
                definition.put("rate", args[4]);
                definition.put("mode", args.length >= 7 ? args[6].toUpperCase(Locale.ROOT) : "INCLUDED");
            }
            case CASHBACK, SUBSIDY -> definition.put("rate", args[4]);
            case LIMIT -> {
                String[] range = args[4].split(":", -1);
                if (range.length != 2) throw new IllegalArgumentException("LIMIT value must be minimum:maximum");
                if (!range[0].equals("-") && !range[0].isBlank()) definition.put("minimum", range[0]);
                if (!range[1].equals("-") && !range[1].isBlank()) definition.put("maximum", range[1]);
            }
            case EXEMPTION -> definition.put("kinds", args[4]);
            case CUSTOM -> throw new IllegalArgumentException("CUSTOM needs a registered handler");
        }
        Set<TransactionCategory> categories = parseCategories(args[5]);
        FinancialRule rule = PolicyValidator.validate(new FinancialRule(id, kind, 1, categories,
                definition, 0, false, null, null), plugin.settings().currency());
        executor.execute(() -> {
            try {
                if (repository.find(id, plugin.settings().currency()).isPresent()) {
                    throw new IllegalArgumentException("Policy already exists");
                }
                long revision = repository.save(rule, plugin.settings().currency(), sender.getName(),
                        "created via command");
                refreshCommitted(sender, rule.id(), revision);
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private boolean toggle(CommandSender sender, String[] args, boolean enabled) {
        if (args.length < 3) return usage(sender);
        return mutate(sender, args[2], rule -> copy(rule, rule.definition(), rule.priority(), enabled,
                rule.effectiveFrom(), rule.effectiveUntil()), reason(args, 3, enabled ? "enabled" : "disabled"));
    }

    private boolean priority(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        int value = Integer.parseInt(args[3]);
        return mutate(sender, args[2], rule -> copy(rule, rule.definition(), value, rule.enabled(),
                rule.effectiveFrom(), rule.effectiveUntil()), reason(args, 4, "priority changed"));
    }

    private boolean schedule(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        Instant from = PolicyConfiguration.instant(args[3]);
        Instant until = PolicyConfiguration.instant(args[4]);
        return mutate(sender, args[2], rule -> copy(rule, rule.definition(), rule.priority(), rule.enabled(),
                from, until), reason(args, 5, "schedule changed"));
    }

    private boolean categories(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        Set<TransactionCategory> categories = parseCategories(args[3]);
        return mutate(sender, args[2], rule -> new FinancialRule(rule.id(), rule.kind(), rule.handlerVersion(),
                categories, rule.definition(), rule.priority(), rule.enabled(), rule.effectiveFrom(),
                rule.effectiveUntil()), reason(args, 4, "categories changed"));
    }

    private boolean rate(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        new BigDecimal(args[3]);
        return mutate(sender, args[2], rule -> {
            if (rule.kind() == PolicyKind.LIMIT || rule.kind() == PolicyKind.EXEMPTION
                    || rule.kind() == PolicyKind.CUSTOM) {
                throw new IllegalArgumentException("This policy kind has no percentage rate");
            }
            Map<String, String> definition = new LinkedHashMap<>(rule.definition());
            definition.put("rate", args[3]);
            if (args.length >= 5 && (rule.kind() == PolicyKind.TAX || rule.kind() == PolicyKind.FEE
                    || rule.kind() == PolicyKind.COMMISSION)) definition.put("mode", args[4]);
            return copy(rule, definition, rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil());
        }, "rate changed");
    }

    private boolean account(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        AccountType.valueOf(args[3].toUpperCase(Locale.ROOT));
        return mutate(sender, args[2], rule -> {
            if (rule.kind() == PolicyKind.LIMIT || rule.kind() == PolicyKind.EXEMPTION
                    || rule.kind() == PolicyKind.CUSTOM) {
                throw new IllegalArgumentException("This policy kind has no money account");
            }
            Map<String, String> definition = new LinkedHashMap<>(rule.definition());
            String prefix = rule.kind() == PolicyKind.CASHBACK || rule.kind() == PolicyKind.SUBSIDY
                    ? "funding" : "recipient";
            definition.put(prefix + "-type", args[3].toUpperCase(Locale.ROOT));
            definition.put(prefix + "-id", args[4]);
            return copy(rule, definition, rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil());
        }, reason(args, 5, "account changed"));
    }

    private boolean condition(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        String field = args[3].toLowerCase(Locale.ROOT);
        if (!List.of("source-type", "source-id", "target-type", "target-id", "metadata")
                .contains(field)) throw new IllegalArgumentException("Unknown condition field");
        return mutate(sender, args[2], rule -> {
            Map<String, String> definition = new LinkedHashMap<>(rule.definition());
            String value = args[4];
            if (field.equals("metadata")) {
                definition.remove("condition-metadata-key");
                definition.remove("condition-metadata-value");
                if (!value.equals("-")) {
                    String[] pair = value.split("=", 2);
                    if (pair.length != 2) throw new IllegalArgumentException("Metadata must be key=value");
                    definition.put("condition-metadata-key", pair[0]);
                    definition.put("condition-metadata-value", pair[1]);
                }
            } else {
                String key = "condition-" + field;
                if (value.equals("-")) definition.remove(key);
                else definition.put(key, value.toUpperCase(Locale.ROOT));
            }
            return copy(rule, definition, rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil());
        }, reason(args, 5, "condition changed"));
    }

    private boolean range(CommandSender sender, String[] args) {
        if (args.length < 5) return usage(sender);
        return mutate(sender, args[2], rule -> {
            if (rule.kind() != PolicyKind.LIMIT) throw new IllegalArgumentException("Only LIMIT has a range");
            Map<String, String> definition = new LinkedHashMap<>(rule.definition());
            if (args[3].equals("-")) definition.remove("minimum");
            else definition.put("minimum", args[3]);
            if (args[4].equals("-")) definition.remove("maximum");
            else definition.put("maximum", args[4]);
            return copy(rule, definition, rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil());
        }, reason(args, 5, "range changed"));
    }

    private boolean exempt(CommandSender sender, String[] args) {
        if (args.length < 4) return usage(sender);
        return mutate(sender, args[2], rule -> {
            if (rule.kind() != PolicyKind.EXEMPTION) {
                throw new IllegalArgumentException("Only EXEMPTION has exempted kinds");
            }
            Map<String, String> definition = new LinkedHashMap<>(rule.definition());
            definition.put("kinds", args[3]);
            return copy(rule, definition, rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil());
        }, reason(args, 4, "exempted kinds changed"));
    }

    private boolean mutate(CommandSender sender, String id, UnaryOperator<FinancialRule> operation, String reason) {
        executor.execute(() -> {
            try {
                FinancialRule current = repository.find(id, plugin.settings().currency())
                        .orElseThrow(() -> new IllegalArgumentException("Policy not found"));
                FinancialRule changed = PolicyValidator.validate(operation.apply(current), plugin.settings().currency());
                long revision = repository.save(changed, plugin.settings().currency(), sender.getName(), reason);
                refreshCommitted(sender, id, revision);
            } catch (Exception exception) { fail(sender, exception); }
        });
        return true;
    }

    private void refresh() throws Exception {
        List<FinancialRule> stored = repository.list(plugin.settings().currency());
        if (stored.size() > plugin.settings().policies().maxRules()) {
            throw new IllegalStateException("Stored policy count exceeds configured maximum");
        }
        registry.replace(stored);
    }

    private FinancialRule requiredRule(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Policy id is required");
        return registry.snapshot().stream().filter(rule -> rule.id().equalsIgnoreCase(args[2]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Policy not found"));
    }

    private Set<TransactionCategory> parseCategories(String raw) {
        return PolicyConfiguration.categories(Arrays.asList(raw.split(",")));
    }

    private void success(CommandSender sender, String id, long revision) {
        reply(() -> sender.sendMessage(plugin.messages().component("policy-saved",
                Map.of("id", id, "revision", Long.toString(revision)))));
    }

    private void refreshCommitted(CommandSender sender, String id, long revision) {
        try {
            refresh();
            success(sender, id, revision);
        } catch (Exception exception) {
            committedButStale(sender, id, exception);
        }
    }

    private void committedButStale(CommandSender sender, String id, Exception exception) {
        plugin.getLogger().severe("Policy " + id + " was committed but runtime reload failed: " + root(exception));
        reply(() -> sender.sendMessage(plugin.messages().component("policy-saved-reload-failed",
                Map.of("id", id))));
    }

    private void fail(CommandSender sender, Exception exception) {
        reply(() -> sender.sendMessage(plugin.messages().component("policy-invalid",
                Map.of("error", safe(root(exception))))));
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(plugin.messages().component("policy-usage"));
        return true;
    }

    private void reply(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    private static FinancialRule copy(FinancialRule rule, Map<String, String> definition,
                                      int priority, boolean enabled, Instant from, Instant until) {
        return new FinancialRule(rule.id(), rule.kind(), rule.handlerVersion(), rule.categories(),
                definition, priority, enabled, from, until);
    }

    private static String joinCategories(Set<TransactionCategory> categories) {
        return categories.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    private static String time(Instant value) { return value == null ? "-" : value.toString(); }
    private static String reason(String[] args, int from, String fallback) {
        return args.length <= from ? fallback : String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }
    private static String root(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "invalid value";
        return value.length() <= 180 ? value : value.substring(0, 180);
    }
}
