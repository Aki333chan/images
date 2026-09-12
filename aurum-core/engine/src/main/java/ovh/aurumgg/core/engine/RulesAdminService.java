package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AurumRulesAdminApi;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.RuleApplyResult;
import ovh.aurumgg.core.api.RuleChangePreview;
import ovh.aurumgg.core.api.RuleMutationRequest;
import ovh.aurumgg.core.api.RuleResource;
import ovh.aurumgg.core.api.RuleType;
import ovh.aurumgg.core.api.TransactionCategory;

/** Trusted two-phase editor backed by the same repositories as server commands. */
public final class RulesAdminService implements AurumRulesAdminApi {
    private static final int MAX_PREVIEWS = 256;

    private final CurrencySpec primary;
    private final Map<String, CurrencySpec> currencies;
    private final PolicyRepository policies;
    private final ExchangeRepository exchanges;
    private final PolicyRegistry policyRegistry;
    private final ExchangeRegistry exchangeRegistry;
    private final Executor executor;
    private final Clock clock;
    private final Duration previewTtl;
    private final int maxPolicies;
    private final int maxExchanges;
    /** Accessed only on the single database executor. */
    private final LinkedHashMap<String, PreviewEntry> previews = new LinkedHashMap<>();

    public RulesAdminService(
            CurrencySpec primary,
            Map<String, CurrencySpec> currencies,
            PolicyRepository policies,
            ExchangeRepository exchanges,
            PolicyRegistry policyRegistry,
            ExchangeRegistry exchangeRegistry,
            Executor executor,
            Clock clock,
            Duration previewTtl,
            int maxPolicies,
            int maxExchanges
    ) {
        this.primary = primary;
        this.currencies = Map.copyOf(currencies);
        this.policies = policies;
        this.exchanges = exchanges;
        this.policyRegistry = policyRegistry;
        this.exchangeRegistry = exchangeRegistry;
        this.executor = executor;
        this.clock = clock;
        this.previewTtl = previewTtl;
        this.maxPolicies = maxPolicies;
        this.maxExchanges = maxExchanges;
    }

    @Override
    public CompletionStage<Optional<List<RuleResource>>> list(RuleType type) {
        if (type == null) return CompletableFuture.completedFuture(Optional.empty());
        return supply(() -> Optional.of(switch (type) {
            case POLICY -> policyRegistry.snapshot().stream().map(RulesAdminService::resource).toList();
            case EXCHANGE -> exchangeRegistry.snapshot().stream().map(RulesAdminService::resource).toList();
        })).exceptionally(failure -> Optional.empty());
    }

    @Override
    public CompletionStage<RuleChangePreview> preview(RuleMutationRequest request, String actor) {
        if (request == null) return CompletableFuture.completedFuture(invalidPreview("missing-request"));
        try {
            validateActor(actor);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(invalidPreview(invalid.getMessage()));
        }
        return supply(() -> previewBlocking(request, actor)).exceptionally(failure ->
                new RuleChangePreview(RuleChangePreview.Status.UNAVAILABLE, "", null, null,
                        List.of(), "rules-unavailable", null));
    }

    private RuleChangePreview previewBlocking(RuleMutationRequest request, String actor) throws Exception {
        cleanup();
        RuleResource current = current(request.type(), request.id()).orElse(null);
        long actual = current == null ? 0 : current.revision();
        if (actual != request.expectedRevision()) {
            return new RuleChangePreview(RuleChangePreview.Status.CONFLICT, "", current, null,
                    List.of(), "revision-conflict", null);
        }
        try {
            Object parsed = parse(request);
            if (actual == 0) {
                int count = request.type() == RuleType.POLICY
                        ? policyRegistry.snapshot().size() : exchangeRegistry.snapshot().size();
                int maximum = request.type() == RuleType.POLICY ? maxPolicies : maxExchanges;
                if (count >= maximum) throw new IllegalArgumentException("Configured rule limit reached");
            }
            RuleResource proposed = parsed instanceof FinancialRule policy
                    ? resource(policy) : resource((ExchangeRule) parsed);
            String token = UUID.randomUUID().toString();
            Instant expires = Instant.now(clock).plus(previewTtl);
            while (previews.size() >= MAX_PREVIEWS) previews.remove(previews.keySet().iterator().next());
            previews.put(token, new PreviewEntry(request.type(), parsed, request.expectedRevision(),
                    actor.trim(), expires));
            return new RuleChangePreview(RuleChangePreview.Status.READY, token, current, proposed,
                    warnings(parsed), "ready", expires);
        } catch (RuntimeException invalid) {
            return new RuleChangePreview(RuleChangePreview.Status.INVALID, "", current, null,
                    List.of(), safe(root(invalid)), null);
        }
    }

    @Override
    public CompletionStage<RuleApplyResult> apply(String token, String actor, String reason) {
        try {
            validateText(token, 64, "token");
            validateActor(actor);
            validateReason(reason);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(new RuleApplyResult(
                    RuleApplyResult.Status.INVALID, null, invalid.getMessage()));
        }
        return supply(() -> applyBlocking(token, actor, reason)).exceptionally(failure ->
                new RuleApplyResult(RuleApplyResult.Status.UNAVAILABLE, null, "rules-unavailable"));
    }

    private RuleApplyResult applyBlocking(String token, String actor, String reason) throws Exception {
        cleanup();
        PreviewEntry entry = previews.get(token);
        if (entry == null || !entry.actor().equals(actor.trim())) {
            return new RuleApplyResult(RuleApplyResult.Status.EXPIRED, null, "preview-expired");
        }
        // One attempt only. A lost successful response cannot create another
        // revision; a new preview must observe the committed current row.
        previews.remove(token);
        try {
            long revision;
            RuleResource saved;
            if (entry.type() == RuleType.POLICY) {
                FinancialRule proposed = (FinancialRule) entry.proposed();
                revision = policies.saveIfRevision(proposed, primary, entry.expectedRevision(),
                        actor.trim(), reason.trim());
                saved = resource(withRevision(proposed, revision));
            } else {
                ExchangeRule proposed = (ExchangeRule) entry.proposed();
                revision = exchanges.saveRuleIfRevision(proposed, currencies, entry.expectedRevision(),
                        actor.trim(), reason.trim());
                saved = resource(withRevision(proposed, revision));
            }
            try {
                refresh(entry.type());
                return new RuleApplyResult(RuleApplyResult.Status.APPLIED, saved, "applied");
            } catch (Exception reloadFailure) {
                return new RuleApplyResult(RuleApplyResult.Status.APPLIED_RELOAD_FAILED, saved,
                        safe(root(reloadFailure)));
            }
        } catch (StaleRuleRevisionException stale) {
            RuleResource current = current(entry.type(), id(entry.proposed())).orElse(null);
            return new RuleApplyResult(RuleApplyResult.Status.CONFLICT, current, "revision-conflict");
        } catch (IllegalArgumentException invalid) {
            return new RuleApplyResult(RuleApplyResult.Status.INVALID, null, safe(root(invalid)));
        }
    }

    private Optional<RuleResource> current(RuleType type, String id) throws Exception {
        return switch (type) {
            case POLICY -> policies.find(id, primary).map(RulesAdminService::resource);
            case EXCHANGE -> exchanges.findRule(id, currencies).map(RulesAdminService::resource);
        };
    }

    private Object parse(RuleMutationRequest request) {
        return switch (request.type()) {
            case POLICY -> parsePolicy(request);
            case EXCHANGE -> parseExchange(request);
        };
    }

    private FinancialRule parsePolicy(RuleMutationRequest request) {
        Map<String, String> values = request.fields();
        rejectUnknown(values, Set.of("kind", "handlerVersion", "categories", "priority", "enabled",
                "effectiveFrom", "effectiveUntil"), "definition.");
        PolicyKind kind = PolicyKind.valueOf(required(values, "kind").toUpperCase(Locale.ROOT));
        Set<TransactionCategory> categories = EnumSet.noneOf(TransactionCategory.class);
        for (String name : required(values, "categories").split(",")) {
            categories.add(TransactionCategory.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        }
        Map<String, String> definition = prefixed(values, "definition.");
        FinancialRule rule = new FinancialRule(request.id(), Math.max(1, request.expectedRevision()), kind,
                integer(values, "handlerVersion"), categories, definition,
                integer(values, "priority"), bool(values, "enabled"),
                instant(values.get("effectiveFrom")), instant(values.get("effectiveUntil")));
        validatePolicyCurrencies(rule);
        return PolicyValidator.validate(rule, primary);
    }

    private ExchangeRule parseExchange(RuleMutationRequest request) {
        Map<String, String> values = request.fields();
        rejectUnknown(values, Set.of("fromCurrency", "toCurrency", "rate", "feeRate", "minimum",
                "maximum", "settlement", "priority", "enabled", "effectiveFrom", "effectiveUntil"),
                "condition.");
        ExchangeRule rule = new ExchangeRule(request.id(), Math.max(1, request.expectedRevision()),
                required(values, "fromCurrency"), required(values, "toCurrency"),
                decimal(values, "rate"), decimal(values, "feeRate"),
                optionalDecimal(values.get("minimum")), optionalDecimal(values.get("maximum")),
                ExchangeSettlement.valueOf(required(values, "settlement").toUpperCase(Locale.ROOT)),
                prefixed(values, "condition."), integer(values, "priority"), bool(values, "enabled"),
                instant(values.get("effectiveFrom")), instant(values.get("effectiveUntil")));
        CurrencySpec from = currencies.get(rule.fromCurrencyId());
        CurrencySpec to = currencies.get(rule.toCurrencyId());
        if (from == null || to == null) throw new IllegalArgumentException("Exchange uses an unknown currency");
        if (rule.minimumSource() != null) from.requireAmount(rule.minimumSource());
        if (rule.maximumSource() != null) from.requireAmount(rule.maximumSource());
        String accountType = rule.conditions().get("account-type");
        if (accountType != null) ovh.aurumgg.core.api.AccountType.valueOf(accountType.toUpperCase(Locale.ROOT));
        if (rule.conditions().containsKey("metadata-key") != rule.conditions().containsKey("metadata-value")) {
            throw new IllegalArgumentException("Incomplete exchange metadata condition");
        }
        return rule;
    }

    private void refresh(RuleType type) throws Exception {
        if (type == RuleType.POLICY) {
            List<FinancialRule> values = policies.list(primary);
            if (values.size() > maxPolicies) throw new IllegalStateException("Policy limit exceeded");
            policyRegistry.replace(values);
        } else {
            List<ExchangeRule> values = exchanges.listRules(currencies);
            if (values.size() > maxExchanges) throw new IllegalStateException("Exchange limit exceeded");
            exchangeRegistry.replace(values);
        }
    }

    private void validatePolicyCurrencies(FinancialRule rule) {
        String configured = rule.definition().get("currencies");
        if (configured == null || configured.isBlank() || configured.equals("*")) return;
        for (String id : configured.split(",")) {
            if (!currencies.containsKey(id.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unknown policy currency: " + id.trim());
            }
        }
    }

    private void cleanup() {
        Instant now = Instant.now(clock);
        previews.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private <T> CompletionStage<T> supply(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor);
    }

    private static RuleResource resource(FinancialRule rule) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("kind", rule.kind().name());
        fields.put("handlerVersion", Integer.toString(rule.handlerVersion()));
        fields.put("categories", rule.categories().stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(",")));
        fields.put("priority", Integer.toString(rule.priority()));
        fields.put("enabled", Boolean.toString(rule.enabled()));
        fields.put("effectiveFrom", time(rule.effectiveFrom()));
        fields.put("effectiveUntil", time(rule.effectiveUntil()));
        rule.definition().forEach((key, value) -> fields.put("definition." + key, value));
        return new RuleResource(RuleType.POLICY, rule.id(), rule.revision(), fields);
    }

    private static RuleResource resource(ExchangeRule rule) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("fromCurrency", rule.fromCurrencyId());
        fields.put("toCurrency", rule.toCurrencyId());
        fields.put("rate", number(rule.rate()));
        fields.put("feeRate", number(rule.feeRate()));
        fields.put("minimum", number(rule.minimumSource()));
        fields.put("maximum", number(rule.maximumSource()));
        fields.put("settlement", rule.settlement().name());
        fields.put("priority", Integer.toString(rule.priority()));
        fields.put("enabled", Boolean.toString(rule.enabled()));
        fields.put("effectiveFrom", time(rule.effectiveFrom()));
        fields.put("effectiveUntil", time(rule.effectiveUntil()));
        rule.conditions().forEach((key, value) -> fields.put("condition." + key, value));
        return new RuleResource(RuleType.EXCHANGE, rule.id(), rule.revision(), fields);
    }

    private List<String> warnings(Object value) {
        List<String> result = new ArrayList<>();
        boolean enabled = value instanceof FinancialRule policy ? policy.enabled() : ((ExchangeRule) value).enabled();
        if (!enabled) result.add("disabled");
        Instant from = value instanceof FinancialRule policy ? policy.effectiveFrom() : ((ExchangeRule) value).effectiveFrom();
        Instant until = value instanceof FinancialRule policy ? policy.effectiveUntil() : ((ExchangeRule) value).effectiveUntil();
        Instant now = Instant.now(clock);
        if (from != null && from.isAfter(now)) result.add("starts-in-future");
        if (until != null && !until.isAfter(now)) result.add("already-expired");
        if (value instanceof FinancialRule policy
                && Set.of(PolicyKind.TAX, PolicyKind.FEE, PolicyKind.COMMISSION).contains(policy.kind())) {
            BigDecimal rate = new BigDecimal(policy.definition().get("rate"));
            if (rate.compareTo(new BigDecimal("0.25")) >= 0) result.add("high-rate");
        }
        if (value instanceof ExchangeRule exchange && exchange.settlement() == ExchangeSettlement.MINT_BURN) {
            result.add("mint-burn");
        }
        return List.copyOf(result);
    }

    private static FinancialRule withRevision(FinancialRule rule, long revision) {
        return new FinancialRule(rule.id(), revision, rule.kind(), rule.handlerVersion(), rule.categories(),
                rule.definition(), rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil());
    }

    private static ExchangeRule withRevision(ExchangeRule rule, long revision) {
        return new ExchangeRule(rule.id(), revision, rule.fromCurrencyId(), rule.toCurrencyId(),
                rule.rate(), rule.feeRate(), rule.minimumSource(), rule.maximumSource(), rule.settlement(),
                rule.conditions(), rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil());
    }

    private static String id(Object value) {
        return value instanceof FinancialRule policy ? policy.id() : ((ExchangeRule) value).id();
    }

    private static Map<String, String> prefixed(Map<String, String> fields, String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key.startsWith(prefix)) values.put(key.substring(prefix.length()), value);
        });
        return values;
    }

    private static void rejectUnknown(Map<String, String> fields, Set<String> exact, String prefix) {
        fields.keySet().stream().filter(key -> !exact.contains(key) && !key.startsWith(prefix))
                .findFirst().ifPresent(key -> {
                    throw new IllegalArgumentException("Unknown field: " + key);
                });
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static BigDecimal decimal(Map<String, String> values, String key) {
        return new BigDecimal(required(values, key));
    }

    private static BigDecimal optionalDecimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private static boolean bool(Map<String, String> values, String key) {
        return switch (required(values, key).toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid " + key);
        };
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    private static void validateActor(String actor) {
        validateText(actor, 128, "actor");
    }

    private static void validateReason(String reason) {
        validateText(reason, 255, "reason");
        if (reason.trim().length() < 3) throw new IllegalArgumentException("Reason is too short");
    }

    private static void validateText(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String time(Instant value) { return value == null ? "" : value.toString(); }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safe(Throwable failure) {
        String message = failure == null ? "unavailable" : failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private static RuleChangePreview invalidPreview(String message) {
        return new RuleChangePreview(RuleChangePreview.Status.INVALID, "", null, null,
                List.of(), message, null);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }

    private record PreviewEntry(RuleType type, Object proposed, long expectedRevision,
                                String actor, Instant expiresAt) {}
}
