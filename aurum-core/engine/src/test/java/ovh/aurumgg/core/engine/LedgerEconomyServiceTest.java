package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

class LedgerEconomyServiceTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final AccountId ALICE = AccountId.player(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId BOB = AccountId.player(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private java.util.concurrent.ExecutorService executor;
    private MemoryLedger repository;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(8);
        repository = new MemoryLedger();
        repository.put(ALICE, "100.00");
        repository.put(BOB, "0.00");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void repeatedIdempotencyKeyCommitsOnlyOnceUnderConcurrency() {
        LedgerEconomyService service = service(TaxRuleResolver.none());
        TransactionRequest request = request("pay:same", "10.00");
        List<CompletableFuture<TransactionResult>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) futures.add(service.transfer(request).toCompletableFuture());
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long successes = futures.stream().map(CompletableFuture::join)
                .filter(it -> it.status() == TransactionResult.Status.SUCCESS).count();
        long duplicates = futures.stream().map(CompletableFuture::join)
                .filter(it -> it.status() == TransactionResult.Status.DUPLICATE).count();
        assertEquals(1, successes);
        assertEquals(49, duplicates);
        assertEquals(new BigDecimal("90.00"), repository.value(ALICE));
        assertEquals(new BigDecimal("10.00"), repository.value(BOB));
    }

    @Test
    void concurrentDebitsNeverMakeBalanceNegative() {
        LedgerEconomyService service = service(TaxRuleResolver.none());
        List<CompletableFuture<TransactionResult>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(service.transfer(request("pay:" + i, "10.00")).toCompletableFuture());
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long successes = futures.stream().map(CompletableFuture::join)
                .filter(it -> it.status() == TransactionResult.Status.SUCCESS).count();
        assertEquals(10, successes);
        assertEquals(new BigDecimal("0.00"), repository.value(ALICE));
        assertEquals(new BigDecimal("100.00"), repository.value(BOB));
    }

    @Test
    void selectedTaxRuleCreditsGlobalTreasuryInSamePlan() {
        TaxRule rule = new TaxRule("income-tax", Set.of(TransactionCategory.PLAYER_PAYMENT),
                new BigDecimal("0.10"), TaxMode.INCLUDED, AccountId.globalTreasury(), 10, true);
        LedgerEconomyService service = service(ignored -> Optional.of(rule));
        TransactionResult result = service.transfer(request("pay:tax", "100.00")).toCompletableFuture().join();

        assertEquals(TransactionResult.Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("90.00"), repository.value(BOB));
        assertEquals(new BigDecimal("10.00"), repository.value(AccountId.globalTreasury()));
        assertEquals(new BigDecimal("10.00"), result.taxAmount());
    }

    @Test
    void committedTransferUpdatesAuthoritativeCache() {
        LedgerEconomyService service = service(TaxRuleResolver.none());
        service.seedBalances(Map.of(ALICE, new BigDecimal("100.00"), BOB, new BigDecimal("0.00")));

        service.transfer(request("pay:cached", "25.00")).toCompletableFuture().join();

        assertEquals(new BigDecimal("75.00"), service.cachedBalance(ALICE).orElseThrow().balance());
        assertEquals(new BigDecimal("25.00"), service.cachedBalance(BOB).orElseThrow().balance());
    }

    @Test
    void administrativeSetIsSerializedAndCanSetZero() {
        LedgerEconomyService service = service(TaxRuleResolver.none());
        service.seedBalances(Map.of(ALICE, new BigDecimal("100.00")));

        TransactionResult result = service.setPlayerBalance(ALICE, BigDecimal.ZERO,
                "admin:set:zero", Map.of("actor", "console")).toCompletableFuture().join();

        assertEquals(TransactionResult.Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("0.00"), repository.value(ALICE));
        assertEquals(new BigDecimal("0.00"), service.cachedBalance(ALICE).orElseThrow().balance());
        assertEquals(new BigDecimal("100.00"),
                repository.value(new AccountId(AccountType.SYSTEM_SINK, "global")));
    }

    private LedgerEconomyService service(TaxRuleResolver resolver) {
        return new LedgerEconomyService(COINS, repository, resolver, executor, Clock.systemUTC());
    }

    private static TransactionRequest request(String key, String amount) {
        return new TransactionRequest(key, ALICE, BOB, "coins", new BigDecimal(amount),
                TransactionCategory.PLAYER_PAYMENT, Map.of());
    }

    private static final class MemoryLedger implements LedgerRepository {
        private final Map<AccountId, BigDecimal> balances = new HashMap<>();
        private final Map<String, LedgerCommit> committed = new HashMap<>();

        void put(AccountId account, String balance) { balances.put(account, new BigDecimal(balance)); }
        synchronized BigDecimal value(AccountId account) {
            return balances.getOrDefault(account, new BigDecimal("0.00"));
        }

        @Override public void initialize(CurrencySpec currency) {}
        @Override public synchronized Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) {
            return Optional.ofNullable(balances.get(account));
        }
        @Override public synchronized GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) {
            BigDecimal treasury = value(AccountId.globalTreasury());
            BigDecimal supply = balances.entrySet().stream()
                    .filter(entry -> entry.getKey().type() != AccountType.SYSTEM_SOURCE
                            && entry.getKey().type() != AccountType.SYSTEM_SINK)
                    .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(currency.scale());
            return new GlobalEconomySnapshot(currency, treasury, supply, BigDecimal.ZERO.setScale(currency.scale()),
                    Instant.now(), true, true);
        }

        @Override public synchronized LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) {
            LedgerCommit duplicate = committed.get(plan.request().idempotencyKey());
            if (duplicate != null) return new LedgerCommit(LedgerCommit.Status.DUPLICATE,
                    duplicate.transactionId(), duplicate.grossAmount(), duplicate.netAmount(), duplicate.taxAmount(),
                    duplicate.sourceBalance(), duplicate.targetBalance(), "Duplicate");
            for (LedgerPosting posting : plan.postings()) {
                BigDecimal after = value(posting.account()).add(posting.amount()).setScale(currency.scale());
                if (after.signum() < 0 && posting.account().type() != AccountType.SYSTEM_SOURCE) {
                    BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
                    return new LedgerCommit(LedgerCommit.Status.INSUFFICIENT_FUNDS, null,
                            currency.requireAmount(plan.request().amount()), plan.targetCredit(), plan.taxCredit(),
                            zero, zero, "Insufficient funds");
                }
            }
            for (LedgerPosting posting : plan.postings()) {
                balances.put(posting.account(), value(posting.account()).add(posting.amount()).setScale(currency.scale()));
            }
            LedgerCommit result = new LedgerCommit(LedgerCommit.Status.COMMITTED, UUID.randomUUID(),
                    currency.requireAmount(plan.request().amount()), plan.targetCredit(), plan.taxCredit(),
                    value(plan.request().from()), value(plan.request().to()), "Committed");
            committed.put(plan.request().idempotencyKey(), result);
            return result;
        }
    }
}
