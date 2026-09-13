package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.ManagedAccount;
import ovh.aurumgg.core.api.ManagedAccountAdjustRequest;
import ovh.aurumgg.core.api.ManagedAccountMember;
import ovh.aurumgg.core.api.ManagedAccountMutationResult;
import ovh.aurumgg.core.api.ManagedAccountPage;
import ovh.aurumgg.core.api.ManagedAccountQuery;
import ovh.aurumgg.core.api.ManagedAccountRegistration;
import ovh.aurumgg.core.api.ManagedAccountStatus;

/**
 * Начисление и списание меняют денежную массу, поэтому у них своя проверка.
 *
 * <p>Перевод только двигает деньги между счетами; здесь они появляются из
 * системного источника или исчезают в системном стоке. Ошибка тут не «не тому
 * заплатили», а «на сервере стало больше денег, чем должно быть».</p>
 */
class AccountRegistryAdjustTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final AccountId FUND = new AccountId(AccountType.TREASURY, "procurement");

    private ExecutorService executor;
    private MemoryLedgerRepository ledger;
    private MemoryRegistry registry;
    private AccountRegistryService service;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        ledger = new MemoryLedgerRepository();
        ledger.put(FUND, "100.00");
        LedgerEconomyService coins = new LedgerEconomyService(
                COINS, ledger, FinancialRuleResolver.none(), executor, Clock.systemUTC());
        MultiCurrencyEconomyService economy = new MultiCurrencyEconomyService(COINS, Map.of("coins", coins));
        registry = new MemoryRegistry();
        registry.put(profile("treasury:procurement", ManagedAccountStatus.ACTIVE, false));
        registry.put(profile("treasury:system", ManagedAccountStatus.ACTIVE, true));
        registry.put(profile("treasury:frozen", ManagedAccountStatus.FROZEN, false));
        service = new AccountRegistryService(registry, economy, Map.of("coins", COINS),
                executor, Clock.systemUTC(), "treasury:global");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void начислениеБеретДеньгиИзСистемногоИсточникаАНеИзНиоткуда() {
        ManagedAccountMutationResult result = adjust("treasury:procurement", "40.00", true, "grant:1");

        assertEquals(ManagedAccountMutationResult.Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("140.00"), ledger.value(FUND));
        // Источник ушёл в минус ровно на выданное: проводка сбалансирована, и
        // по нему видно, сколько денег на сервере создано.
        assertEquals(new BigDecimal("-40.00"), ledger.value(new AccountId(AccountType.SYSTEM_SOURCE, "global")));
    }

    @Test
    void списаниеУноситДеньгиВСистемныйСток() {
        ManagedAccountMutationResult result = adjust("treasury:procurement", "25.00", false, "fine:1");

        assertEquals(ManagedAccountMutationResult.Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("75.00"), ledger.value(FUND));
        assertEquals(new BigDecimal("25.00"), ledger.value(new AccountId(AccountType.SYSTEM_SINK, "global")));
    }

    @Test
    void повторТогоЖеКлючаНеНачисляетДважды() {
        assertEquals(ManagedAccountMutationResult.Status.SUCCESS, adjust("treasury:procurement", "40.00", true, "grant:same").status());
        assertEquals(ManagedAccountMutationResult.Status.DUPLICATE, adjust("treasury:procurement", "40.00", true, "grant:same").status());
        assertEquals(new BigDecimal("140.00"), ledger.value(FUND));
    }

    @Test
    void техническийИНеактивныйПрофильОтклоняются() {
        assertEquals(ManagedAccountMutationResult.Status.REJECTED, adjust("treasury:system", "10.00", true, "grant:2").status());
        assertEquals(ManagedAccountMutationResult.Status.REJECTED, adjust("treasury:frozen", "10.00", true, "grant:3").status());
        assertEquals(ManagedAccountMutationResult.Status.NOT_FOUND, adjust("treasury:missing", "10.00", true, "grant:4").status());
        // Ни один отказ не создал денег.
        assertEquals(BigDecimal.ZERO.setScale(2),
                ledger.value(new AccountId(AccountType.SYSTEM_SOURCE, "global")));
    }

    private ManagedAccountMutationResult adjust(String profile, String amount, boolean credit, String key) {
        return service.adjust(new ManagedAccountAdjustRequest(key, profile, "primary", "coins",
                new BigDecimal(amount), credit, "console", "тест")).toCompletableFuture().join();
    }

    private static ManagedAccount profile(String key, ManagedAccountStatus status, boolean technical) {
        AccountId member = new AccountId(AccountType.TREASURY, key.substring(key.indexOf(':') + 1));
        return new ManagedAccount(key, "TREASURY", key, "", "server", "global", null, "AurumCore",
                null, null, status, "treasury:global", technical,
                List.of(new ManagedAccountMember(member, "primary", 0)),
                Map.of(), Instant.now(), Instant.now(), null);
    }

    /** Реестр в памяти: тесту нужны только поиск профиля и пустой список статусов. */
    private static final class MemoryRegistry implements AccountRegistryRepository {
        private final Map<String, ManagedAccount> profiles = new HashMap<>();

        void put(ManagedAccount account) { profiles.put(account.profileKey(), account); }

        @Override public ManagedAccountPage list(ManagedAccountQuery query, Map<String, CurrencySpec> currencies) {
            return new ManagedAccountPage(List.copyOf(profiles.values()), profiles.size(), query.offset(), query.limit());
        }
        @Override public Optional<ManagedAccount> find(String profileKey, Map<String, CurrencySpec> currencies) {
            return Optional.ofNullable(profiles.get(profileKey));
        }
        @Override public WriteResult register(ManagedAccountRegistration request, String intentHash) {
            return new WriteResult(WriteStatus.SUCCESS, "created");
        }
        @Override public WriteResult synchronize(ManagedAccountRegistration request, String intentHash) {
            return new WriteResult(WriteStatus.SUCCESS, "updated");
        }
        @Override public WriteResult setStatus(String idempotencyKey, String intentHash, String profileKey,
                                               ManagedAccountStatus expectedFirst, ManagedAccountStatus expectedSecond,
                                               ManagedAccountStatus target, String operation, String actor,
                                               String reason) {
            return new WriteResult(WriteStatus.SUCCESS, "updated");
        }
        @Override public WriteResult beginClose(String idempotencyKey, String intentHash, String profileKey,
                                                String destinationProfile, String actor, String reason) {
            return new WriteResult(WriteStatus.SUCCESS, "closing");
        }
        @Override public void completeClose(String idempotencyKey, String profileKey) { }
        @Override public Optional<PendingClose> pendingClosure(String profileKey) { return Optional.empty(); }
        @Override public List<PendingClose> pendingClosures(int limit) { return List.of(); }
        @Override public boolean hasUnresolvedHolds(List<AccountId> members) { return false; }
        @Override public Map<AccountId, ManagedAccountStatus> memberStatuses() { return Map.of(); }
    }

    /** Тот же приём, что и в LedgerEconomyServiceTest: ledger без базы. */
    private static final class MemoryLedgerRepository implements LedgerRepository {
        private final Map<AccountId, BigDecimal> balances = new HashMap<>();
        private final Map<String, LedgerCommit> committed = new HashMap<>();

        void put(AccountId account, String balance) { balances.put(account, new BigDecimal(balance)); }
        synchronized BigDecimal value(AccountId account) {
            return balances.getOrDefault(account, new BigDecimal("0.00"));
        }

        @Override public void initialize(CurrencySpec currency) { }
        @Override public synchronized Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) {
            return Optional.ofNullable(balances.get(account));
        }
        @Override public synchronized GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) {
            BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
            return new GlobalEconomySnapshot(currency, zero, zero, zero, Instant.now(), true, true);
        }
        @Override public synchronized LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) {
            LedgerCommit duplicate = committed.get(plan.request().idempotencyKey());
            if (duplicate != null) {
                return new LedgerCommit(LedgerCommit.Status.DUPLICATE, duplicate.transactionId(),
                        duplicate.grossAmount(), duplicate.netAmount(), duplicate.taxAmount(),
                        duplicate.sourceBalance(), duplicate.targetBalance(), "Duplicate");
            }
            List<LedgerPosting> postings = new ArrayList<>(plan.postings());
            for (LedgerPosting posting : postings) {
                BigDecimal after = value(posting.account()).add(posting.amount()).setScale(currency.scale());
                if (after.signum() < 0 && posting.account().type() != AccountType.SYSTEM_SOURCE) {
                    BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
                    return new LedgerCommit(LedgerCommit.Status.INSUFFICIENT_FUNDS, null,
                            currency.requireAmount(plan.request().amount()), plan.targetCredit(), plan.taxCredit(),
                            zero, zero, "Insufficient funds");
                }
            }
            for (LedgerPosting posting : postings) {
                balances.put(posting.account(),
                        value(posting.account()).add(posting.amount()).setScale(currency.scale()));
            }
            LedgerCommit result = new LedgerCommit(LedgerCommit.Status.COMMITTED, UUID.randomUUID(),
                    currency.requireAmount(plan.request().amount()), plan.targetCredit(), plan.taxCredit(),
                    value(plan.request().from()), value(plan.request().to()), "Committed");
            committed.put(plan.request().idempotencyKey(), result);
            return result;
        }
    }
}
