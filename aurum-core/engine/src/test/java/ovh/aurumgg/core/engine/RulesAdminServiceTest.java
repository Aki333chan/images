package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.RuleApplyResult;
import ovh.aurumgg.core.api.RuleChangePreview;
import ovh.aurumgg.core.api.RuleMutationRequest;
import ovh.aurumgg.core.api.RuleType;

class RulesAdminServiceTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final CurrencySpec TOKENS = new CurrencySpec("tokens", "Tokens", "T", 0);
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);

    private MemoryPolicies policies;
    private MemoryExchanges exchanges;
    private PolicyRegistry policyRegistry;
    private ExchangeRegistry exchangeRegistry;
    private RulesAdminService service;

    @BeforeEach
    void setUp() {
        policies = new MemoryPolicies();
        exchanges = new MemoryExchanges();
        policyRegistry = new PolicyRegistry(true, COINS.id());
        exchangeRegistry = new ExchangeRegistry();
        service = new RulesAdminService(COINS, Map.of(COINS.id(), COINS, TOKENS.id(), TOKENS),
                policies, exchanges, policyRegistry, exchangeRegistry, Runnable::run, NOW,
                Duration.ofMinutes(5), 10, 10);
    }

    @Test
    void previewAndApplyCreateOneAuditedRevisionAndConsumeToken() throws Exception {
        RuleChangePreview preview = service.preview(policyRequest(0, "0.30"), "panel:alice")
                .toCompletableFuture().get();

        assertEquals(RuleChangePreview.Status.READY, preview.status());
        assertEquals(List.of("high-rate"), preview.warnings());
        assertEquals(1, preview.proposed().revision());

        RuleApplyResult applied = service.apply(preview.token(), "panel:alice", "create sales tax")
                .toCompletableFuture().get();
        assertEquals(RuleApplyResult.Status.APPLIED, applied.status());
        assertEquals(1, applied.current().revision());
        assertEquals(1, policyRegistry.snapshot().size());
        assertEquals("create sales tax", policies.lastReason);

        RuleApplyResult repeated = service.apply(preview.token(), "panel:alice", "retry same change")
                .toCompletableFuture().get();
        assertEquals(RuleApplyResult.Status.EXPIRED, repeated.status());
        assertEquals(1, policies.find("sales-tax", COINS).orElseThrow().revision());
    }

    @Test
    void stalePreviewAndChangedAfterPreviewFailWithoutOverwrite() throws Exception {
        FinancialRule current = policy("0.10", 1);
        policies.values.put(current.id(), current);
        policyRegistry.replace(List.of(current));

        RuleChangePreview stale = service.preview(policyRequest(0, "0.20"), "panel:bob")
                .toCompletableFuture().get();
        assertEquals(RuleChangePreview.Status.CONFLICT, stale.status());
        assertEquals(1, stale.current().revision());

        RuleChangePreview ready = service.preview(policyRequest(1, "0.20"), "panel:bob")
                .toCompletableFuture().get();
        policies.values.put(current.id(), policy("0.15", 2));
        RuleApplyResult conflict = service.apply(ready.token(), "panel:bob", "update sales tax")
                .toCompletableFuture().get();

        assertEquals(RuleApplyResult.Status.CONFLICT, conflict.status());
        assertEquals(2, conflict.current().revision());
        assertEquals("0.15", conflict.current().fields().get("definition.rate"));
    }

    @Test
    void previewTokenIsActorBoundAndInvalidExchangeNeverReachesRepository() throws Exception {
        RuleChangePreview ready = service.preview(exchangeRequest("coins", "tokens"), "panel:owner")
                .toCompletableFuture().get();
        assertEquals(RuleChangePreview.Status.READY, ready.status());
        assertTrue(ready.warnings().contains("mint-burn"));

        RuleApplyResult other = service.apply(ready.token(), "panel:other", "try stolen token")
                .toCompletableFuture().get();
        assertEquals(RuleApplyResult.Status.EXPIRED, other.status());

        RuleApplyResult owner = service.apply(ready.token(), "panel:owner", "publish exchange")
                .toCompletableFuture().get();
        assertEquals(RuleApplyResult.Status.APPLIED, owner.status());

        RuleMutationRequest invalidRequest = exchangeRequest("coins", "missing");
        RuleChangePreview invalid = service.preview(new RuleMutationRequest(RuleType.EXCHANGE,
                        "bad-exchange", 0, invalidRequest.fields()), "panel:owner")
                .toCompletableFuture().get();
        assertEquals(RuleChangePreview.Status.INVALID, invalid.status());
        assertEquals(1, exchanges.values.size());
    }

    @Test
    void reasonAndActorAreRequiredBeforeDatabaseWork() throws Exception {
        assertEquals(RuleChangePreview.Status.INVALID,
                service.preview(policyRequest(0, "0.10"), " ").toCompletableFuture().get().status());
        RuleChangePreview ready = service.preview(policyRequest(0, "0.10"), "panel:alice")
                .toCompletableFuture().get();
        RuleApplyResult invalid = service.apply(ready.token(), "panel:alice", "x")
                .toCompletableFuture().get();
        assertEquals(RuleApplyResult.Status.INVALID, invalid.status());

        RuleApplyResult valid = service.apply(ready.token(), "panel:alice", "valid reason")
                .toCompletableFuture().get();
        assertEquals(RuleApplyResult.Status.APPLIED, valid.status());
    }

    @Test
    void unknownFieldsAreRejectedInsteadOfSilentlyDiscarded() throws Exception {
        RuleMutationRequest base = policyRequest(0, "0.10");
        Map<String, String> fields = new LinkedHashMap<>(base.fields());
        fields.put("priorty", "500");
        RuleChangePreview result = service.preview(new RuleMutationRequest(
                        base.type(), base.id(), base.expectedRevision(), fields), "panel:alice")
                .toCompletableFuture().get();
        assertEquals(RuleChangePreview.Status.INVALID, result.status());
        assertTrue(result.message().contains("priorty"));
    }

    private static RuleMutationRequest policyRequest(long revision, String rate) {
        return new RuleMutationRequest(RuleType.POLICY, "sales-tax", revision, Map.of(
                "kind", "TAX", "handlerVersion", "1", "categories", "NPC_PURCHASE",
                "definition.rate", rate, "definition.mode", "INCLUDED", "priority", "100",
                "enabled", "true", "effectiveFrom", "", "effectiveUntil", ""));
    }

    private static FinancialRule policy(String rate, long revision) {
        return new FinancialRule("sales-tax", revision, PolicyKind.TAX, 1,
                java.util.Set.of(ovh.aurumgg.core.api.TransactionCategory.NPC_PURCHASE),
                Map.of("rate", rate, "mode", "INCLUDED"), 100, true, null, null);
    }

    private static RuleMutationRequest exchangeRequest(String from, String to) {
        return new RuleMutationRequest(RuleType.EXCHANGE, "coins-to-tokens", 0, Map.ofEntries(
                Map.entry("fromCurrency", from), Map.entry("toCurrency", to), Map.entry("rate", "0.1"),
                Map.entry("feeRate", "0.02"), Map.entry("minimum", "10.00"),
                Map.entry("maximum", "1000.00"), Map.entry("settlement", "MINT_BURN"),
                Map.entry("priority", "10"), Map.entry("enabled", "true"),
                Map.entry("effectiveFrom", ""), Map.entry("effectiveUntil", "")));
    }

    private static final class MemoryPolicies implements PolicyRepository {
        private final Map<String, FinancialRule> values = new LinkedHashMap<>();
        private String lastReason = "";

        @Override public List<FinancialRule> list(CurrencySpec currency) { return List.copyOf(values.values()); }
        @Override public Optional<FinancialRule> find(String id, CurrencySpec currency) {
            return Optional.ofNullable(values.get(id));
        }
        @Override public long save(FinancialRule rule, CurrencySpec currency, String actor, String reason) {
            return saveUnchecked(rule, values.containsKey(rule.id()) ? values.get(rule.id()).revision() : 0, reason);
        }
        @Override public long saveIfRevision(FinancialRule rule, CurrencySpec currency, long expected,
                                             String actor, String reason) throws SQLException {
            long actual = values.containsKey(rule.id()) ? values.get(rule.id()).revision() : 0;
            if (actual != expected) throw new StaleRuleRevisionException(expected, actual);
            return saveUnchecked(rule, actual, reason);
        }
        private long saveUnchecked(FinancialRule rule, long actual, String reason) {
            long next = actual + 1;
            values.put(rule.id(), new FinancialRule(rule.id(), next, rule.kind(), rule.handlerVersion(),
                    rule.categories(), rule.definition(), rule.priority(), rule.enabled(),
                    rule.effectiveFrom(), rule.effectiveUntil()));
            lastReason = reason;
            return next;
        }
        @Override public Map<String, Long> saveAll(List<FinancialRule> rules, CurrencySpec currency,
                                                   String actor, String reason) {
            Map<String, Long> result = new LinkedHashMap<>();
            rules.forEach(rule -> result.put(rule.id(), save(rule, currency, actor, reason)));
            return result;
        }
        @Override public List<PolicyRevision> history(String id, int limit, CurrencySpec currency) {
            return List.of();
        }
    }

    private static final class MemoryExchanges implements ExchangeRepository {
        private final Map<String, ExchangeRule> values = new LinkedHashMap<>();

        @Override public List<ExchangeRule> listRules(Map<String, CurrencySpec> currencies) {
            return List.copyOf(values.values());
        }
        @Override public Optional<ExchangeRule> findRule(String id, Map<String, CurrencySpec> currencies) {
            return Optional.ofNullable(values.get(id));
        }
        @Override public List<ExchangeRevision> history(String id, int limit,
                                                        Map<String, CurrencySpec> currencies) {
            return List.of();
        }
        @Override public long saveRule(ExchangeRule rule, Map<String, CurrencySpec> currencies,
                                       String actor, String reason) {
            return saveUnchecked(rule, values.containsKey(rule.id()) ? values.get(rule.id()).revision() : 0);
        }
        @Override public long saveRuleIfRevision(ExchangeRule rule, Map<String, CurrencySpec> currencies,
                                                 long expected, String actor, String reason) throws SQLException {
            long actual = values.containsKey(rule.id()) ? values.get(rule.id()).revision() : 0;
            if (actual != expected) throw new StaleRuleRevisionException(expected, actual);
            return saveUnchecked(rule, actual);
        }
        private long saveUnchecked(ExchangeRule rule, long actual) {
            long next = actual + 1;
            values.put(rule.id(), new ExchangeRule(rule.id(), next, rule.fromCurrencyId(), rule.toCurrencyId(),
                    rule.rate(), rule.feeRate(), rule.minimumSource(), rule.maximumSource(), rule.settlement(),
                    rule.conditions(), rule.priority(), rule.enabled(), rule.effectiveFrom(), rule.effectiveUntil()));
            return next;
        }
        @Override public Optional<ExchangeCommit> findByIdempotency(String key, CurrencySpec from,
                                                                    CurrencySpec to) {
            return Optional.empty();
        }
        @Override public ExchangeCommit execute(ExchangePlan plan, CurrencySpec from, CurrencySpec to) {
            throw new UnsupportedOperationException();
        }
    }
}
