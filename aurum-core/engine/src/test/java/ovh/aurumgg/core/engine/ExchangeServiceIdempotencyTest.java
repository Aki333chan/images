package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ExchangeQuote;
import ovh.aurumgg.core.api.ExchangeRequest;
import ovh.aurumgg.core.api.ExchangeResult;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;

class ExchangeServiceIdempotencyTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final CurrencySpec GEMS = new CurrencySpec("gems", "Gems", "G", 2);
    private static final AccountId PLAYER = AccountId.player(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void committedRetryWinsEvenAfterQuoteExpired() {
        Instant quotedAt = Instant.parse("2026-01-01T00:00:00Z");
        ExchangeQuote quote = new ExchangeQuote("coins-gems", 3, PLAYER, COINS, GEMS,
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("9.00"),
                new BigDecimal("18.00"), quotedAt, quotedAt.plusSeconds(15));
        MemoryExchangeRepository repository = new MemoryExchangeRepository(new ExchangeCommit(
                ExchangeCommit.Status.DUPLICATE, quote, Map.of(), "Already committed"));
        MultiCurrencyEconomyService economy = economy();
        ExchangeService service = new ExchangeService(Map.of("coins", COINS, "gems", GEMS),
                new ExchangeRegistry(), repository, economy, Runnable::run,
                Clock.fixed(quotedAt.plusSeconds(60), ZoneOffset.UTC), Duration.ofSeconds(15), new Object());
        ExchangeRequest request = new ExchangeRequest("npc:retry", PLAYER, "coins", "gems",
                new BigDecimal("10.00"), "coins-gems", 3, new BigDecimal("18.00"),
                quote.expiresAt(), Map.of());

        ExchangeResult result = service.exchange(request).toCompletableFuture().join();

        assertEquals(ExchangeResult.Status.DUPLICATE, result.status());
        assertTrue(repository.lookupCalled);
        assertFalse(repository.executeCalled);
    }

    private static MultiCurrencyEconomyService economy() {
        LedgerRepository unused = new LedgerRepository() {
            @Override public void initialize(CurrencySpec currency) {}
            @Override public Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) {
                return Optional.empty();
            }
            @Override public GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) {
                throw new UnsupportedOperationException();
            }
            @Override public LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) {
                throw new UnsupportedOperationException();
            }
        };
        Clock clock = Clock.systemUTC();
        return new MultiCurrencyEconomyService(COINS, Map.of(
                "coins", new LedgerEconomyService(COINS, unused, FinancialRuleResolver.none(), Runnable::run, clock),
                "gems", new LedgerEconomyService(GEMS, unused, FinancialRuleResolver.none(), Runnable::run, clock)));
    }

    private static final class MemoryExchangeRepository implements ExchangeRepository {
        private final ExchangeCommit existing;
        private boolean lookupCalled;
        private boolean executeCalled;
        private MemoryExchangeRepository(ExchangeCommit existing) { this.existing = existing; }
        @Override public List<ExchangeRule> listRules(Map<String, CurrencySpec> currencies) { return List.of(); }
        @Override public Optional<ExchangeRule> findRule(String id, Map<String, CurrencySpec> currencies) {
            return Optional.empty();
        }
        @Override public List<ExchangeRevision> history(String id, int limit,
                                                        Map<String, CurrencySpec> currencies) { return List.of(); }
        @Override public long saveRule(ExchangeRule rule, Map<String, CurrencySpec> currencies,
                                       String actor, String reason) { return 0; }
        @Override public Optional<ExchangeCommit> findByIdempotency(String key, CurrencySpec from,
                                                                    CurrencySpec to) {
            lookupCalled = true;
            return Optional.of(existing);
        }
        @Override public ExchangeCommit execute(ExchangePlan plan, CurrencySpec from, CurrencySpec to)
                throws SQLException {
            executeCalled = true;
            throw new SQLException("must not execute a committed retry");
        }
    }
}
