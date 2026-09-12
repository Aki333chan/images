package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.BalanceSetRequest;
import ovh.aurumgg.core.api.BalanceSetResult;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.TransactionCategory;

/** Authoritative service. Paper does not expose this implementation until migration is verified. */
public final class LedgerEconomyService implements AurumEconomyApi {
    private final CurrencySpec currency;
    private final LedgerRepository repository;
    private final FinancialRuleResolver policies;
    private final Executor executor;
    private final Clock clock;
    private final Object mutationLock;
    private final ConcurrentMap<AccountId, BigDecimal> balanceCache = new ConcurrentHashMap<>();
    private final AtomicReference<GlobalEconomySnapshot> globalCache = new AtomicReference<>();

    public LedgerEconomyService(CurrencySpec currency, LedgerRepository repository,
                                FinancialRuleResolver policies, Executor executor, Clock clock) {
        this(currency, repository, policies, executor, clock, new Object());
    }

    public LedgerEconomyService(CurrencySpec currency, LedgerRepository repository,
                                FinancialRuleResolver policies, Executor executor, Clock clock,
                                Object mutationLock) {
        this.currency = currency;
        this.repository = repository;
        this.policies = policies;
        this.executor = executor;
        this.clock = clock;
        this.mutationLock = java.util.Objects.requireNonNull(mutationLock, "mutationLock");
    }

    @Override public EconomyMode mode() { return EconomyMode.ACTIVE; }
    @Override public CurrencySpec primaryCurrency() { return currency; }

    @Override
    public CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account) {
        BigDecimal cached = balanceCache.get(account);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(snapshot(account, cached)));
        return supply(() -> {
            BigDecimal value = repository.balance(account, currency)
                    .orElse(BigDecimal.ZERO.setScale(currency.scale()));
            balanceCache.put(account, value);
            return Optional.of(snapshot(account, value));
        });
    }

    @Override
    public CompletionStage<GlobalEconomySnapshot> globalSnapshot() {
        return supply(() -> {
            GlobalEconomySnapshot snapshot = repository.globalSnapshot(currency);
            globalCache.set(snapshot);
            return snapshot;
        });
    }

    @Override
    public CompletionStage<List<BalanceSnapshot>> richest(String currencyId, int limit) {
        // Чужая валюта — пустой список, а не список этой валюты: молча выдать
        // не то, о чём спросили, хуже, чем не выдать ничего.
        if (!currency.id().equals(currencyId)) return CompletableFuture.completedFuture(List.of());
        return supply(() -> repository.richest(currency, limit)).exceptionally(exception -> List.of());
    }

    @Override
    public CompletionStage<TransactionResult> transfer(TransactionRequest request) {
        return supply(() -> transferBlocking(request)).exceptionally(exception -> unavailable(request, exception));
    }

    /** Vault is synchronous, so its adapter calls this and receives a definitive database result. */
    public TransactionResult transferBlocking(TransactionRequest request) throws Exception {
        return transferBlocking(request, null);
    }

    TransactionResult transferBlocking(TransactionRequest request, java.util.UUID excludedHold) throws Exception {
        synchronized (mutationLock) {
            TransactionPlan plan;
            try {
                plan = TransactionPlanner.plan(request, currency,
                        policies.select(request, Instant.now(clock)), Instant.now(clock));
            } catch (PolicyRejectedException exception) {
                BigDecimal amount = currency.requireAmount(request.amount());
                return new TransactionResult(TransactionResult.Status.REJECTED,
                        request.idempotencyKey(), amount, amount,
                        BigDecimal.ZERO.setScale(currency.scale()), "POLICY:" + exception.getMessage());
            }
            return commitPlanned(plan, excludedHold);
        }
    }

    /** Caller must hold the shared mutation lock. */
    TransactionResult commitPlanned(TransactionPlan plan, java.util.UUID excludedHold) throws Exception {
        TransactionRequest request = plan.request();
        LedgerCommit commit = repository.commit(plan, currency, excludedHold);
        if (commit.status() == LedgerCommit.Status.COMMITTED) {
            if (commit.balancesAfter().isEmpty()) {
                balanceCache.put(request.from(), commit.sourceBalance());
                balanceCache.put(request.to(), commit.targetBalance());
            } else {
                balanceCache.putAll(commit.balancesAfter());
            }
        }
        TransactionResult.Status status = switch (commit.status()) {
            case COMMITTED -> TransactionResult.Status.SUCCESS;
            case DUPLICATE -> TransactionResult.Status.DUPLICATE;
            case CONFLICT, INSUFFICIENT_FUNDS, REJECTED -> TransactionResult.Status.REJECTED;
        };
        return new TransactionResult(status, request.idempotencyKey(), commit.grossAmount(),
                commit.netAmount(), commit.taxAmount(), commit.message());
    }

    /** Caller must hold the shared mutation lock. */
    boolean transactionCommitted(String idempotencyKey) throws Exception {
        return repository.transactionCommitted(idempotencyKey);
    }

    TransactionPlan planFor(TransactionRequest request) {
        return TransactionPlanner.plan(request, currency,
                policies.select(request, Instant.now(clock)), Instant.now(clock));
    }

    /** Atomic compare-and-set used by Companion and other retrying clients. */
    @Override
    public CompletionStage<BalanceSetResult> setBalance(BalanceSetRequest request) {
        return supply(() -> setBalanceBlocking(request)).exceptionally(exception -> {
            BigDecimal expected = normalizedOrZero(request.expectedBalance());
            BigDecimal target = normalizedOrZero(request.targetBalance());
            return new BalanceSetResult(BalanceSetResult.Status.UNAVAILABLE,
                    request.idempotencyKey(), expected, target, expected, expected, expected,
                    rootMessage(exception));
        });
    }

    private BalanceSetResult setBalanceBlocking(BalanceSetRequest request) throws Exception {
        synchronized (mutationLock) {
            if (!currency.id().equalsIgnoreCase(request.currencyId())) {
                return setRejected(request, "Unknown currency: " + request.currencyId());
            }
            if (request.account().type() != AccountType.PLAYER) {
                return setRejected(request, "Only player balances can be replaced");
            }
            BigDecimal expected = currency.requireAmount(request.expectedBalance());
            BigDecimal target = currency.requireAmount(request.targetBalance());
            if (expected.signum() < 0 || target.signum() < 0) {
                return setRejected(request, "Player balance cannot be negative");
            }

            BigDecimal difference = target.subtract(expected).setScale(currency.scale());
            AccountId system = new AccountId(
                    difference.signum() >= 0 ? AccountType.SYSTEM_SOURCE : AccountType.SYSTEM_SINK,
                    "global");
            Map<String, String> metadata = new java.util.LinkedHashMap<>(request.metadata());
            // These fields bind the compare-and-set intent to the existing
            // transaction request hash, including a zero-delta receipt.
            metadata.put("operation", "set");
            metadata.put("expected-balance", expected.toPlainString());
            metadata.put("target-balance", target.toPlainString());
            TransactionRequest transaction = new TransactionRequest(
                    request.idempotencyKey(),
                    difference.signum() >= 0 ? system : request.account(),
                    difference.signum() >= 0 ? request.account() : system,
                    currency.id(), difference.abs(), TransactionCategory.ADMIN_ADJUSTMENT, metadata);
            // A set has to land on the exact target. Taxes, fees and subsidies
            // would change that meaning, so administrative replacement does
            // not run through policy selection.
            TransactionPlan plan = TransactionPlanner.plan(transaction, currency, List.of(), Instant.now(clock));
            LedgerCommit commit = repository.commit(plan, currency,
                    new BalanceExpectation(request.account(), expected));
            BigDecimal current = repository.balance(request.account(), currency)
                    .orElse(BigDecimal.ZERO.setScale(currency.scale()));
            balanceCache.put(request.account(), current);
            if (commit.status() == LedgerCommit.Status.COMMITTED) {
                balanceCache.putAll(commit.balancesAfter());
            }

            BalanceSetResult.Status status = switch (commit.status()) {
                case COMMITTED -> BalanceSetResult.Status.SUCCESS;
                case DUPLICATE -> BalanceSetResult.Status.DUPLICATE;
                case CONFLICT -> BalanceSetResult.Status.CONFLICT;
                case INSUFFICIENT_FUNDS, REJECTED -> BalanceSetResult.Status.REJECTED;
            };
            BigDecimal before = status == BalanceSetResult.Status.CONFLICT
                    ? conflictBalance(commit.message()).orElse(current)
                    : expected;
            BigDecimal after = status == BalanceSetResult.Status.SUCCESS
                    || status == BalanceSetResult.Status.DUPLICATE ? target : before;
            return new BalanceSetResult(status, request.idempotencyKey(), expected, target,
                    before, after, current, commit.message());
        }
    }

    private Optional<BigDecimal> conflictBalance(String message) {
        String prefix = "EXPECTED_BALANCE_MISMATCH:";
        if (message == null || !message.startsWith(prefix)) return Optional.empty();
        try {
            return Optional.of(currency.requireAmount(new BigDecimal(message.substring(prefix.length()))));
        } catch (RuntimeException invalidStoredReason) {
            return Optional.empty();
        }
    }

    private BalanceSetResult setRejected(BalanceSetRequest request, String message) {
        BigDecimal expected = normalizedOrZero(request.expectedBalance());
        BigDecimal target = normalizedOrZero(request.targetBalance());
        return new BalanceSetResult(BalanceSetResult.Status.REJECTED, request.idempotencyKey(),
                expected, target, expected, expected, expected, message);
    }

    private BigDecimal normalizedOrZero(BigDecimal amount) {
        try { return currency.requireAmount(amount); }
        catch (RuntimeException invalid) { return BigDecimal.ZERO.setScale(currency.scale()); }
    }

    /** Serialized convenience used by the interactive administrative command. */
    public CompletionStage<TransactionResult> setPlayerBalance(AccountId player, BigDecimal target,
                                                                String idempotencyKey, Map<String, String> metadata) {
        return supply(() -> {
            synchronized (mutationLock) {
                BigDecimal wanted = currency.requireAmount(target);
                if (wanted.signum() < 0 || player.type() != AccountType.PLAYER) {
                    throw new IllegalArgumentException("Player balance cannot be negative");
                }
                BigDecimal current = balanceCache.computeIfAbsent(player, ignored -> {
                    try {
                        return repository.balance(player, currency)
                                .orElse(BigDecimal.ZERO.setScale(currency.scale()));
                    } catch (Exception exception) {
                        throw new LedgerAccessException(exception);
                    }
                });
                BalanceSetResult result = setBalanceBlocking(new BalanceSetRequest(
                        idempotencyKey, player, currency.id(), current, wanted, metadata));
                TransactionResult.Status status = switch (result.status()) {
                    case SUCCESS -> TransactionResult.Status.SUCCESS;
                    case DUPLICATE -> TransactionResult.Status.DUPLICATE;
                    case CONFLICT, REJECTED -> TransactionResult.Status.REJECTED;
                    case UNAVAILABLE -> TransactionResult.Status.UNAVAILABLE;
                };
                BigDecimal changed = wanted.subtract(current).abs();
                return new TransactionResult(status, idempotencyKey, changed, changed,
                        BigDecimal.ZERO.setScale(currency.scale()), result.message());
            }
        }).exceptionally(exception -> {
            BigDecimal amount;
            try { amount = currency.requireAmount(target); }
            catch (RuntimeException invalid) { amount = BigDecimal.ZERO.setScale(currency.scale()); }
            return new TransactionResult(TransactionResult.Status.UNAVAILABLE, idempotencyKey,
                    amount, amount, BigDecimal.ZERO.setScale(currency.scale()), rootMessage(exception));
        });
    }

    public void seedBalances(Map<AccountId, BigDecimal> balances) {
        balances.forEach((account, amount) -> balanceCache.put(account, currency.requireAmount(amount)));
    }

    public Optional<BalanceSnapshot> cachedBalance(AccountId account) {
        BigDecimal value = balanceCache.get(account);
        return value == null ? Optional.empty() : Optional.of(snapshot(account, value));
    }

    public GlobalEconomySnapshot cachedGlobalSnapshot() {
        GlobalEconomySnapshot value = globalCache.get();
        if (value != null) return value;
        BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
        return new GlobalEconomySnapshot(currency, zero, zero, zero, Instant.now(clock), false, false);
    }

    public int cachedAccountCount() { return balanceCache.size(); }

    public void cacheZeroIfAbsent(AccountId account) {
        balanceCache.putIfAbsent(account, BigDecimal.ZERO.setScale(currency.scale()));
    }

    public void applyCommittedBalance(AccountId account, BigDecimal balance) {
        balanceCache.put(account, currency.requireAmount(balance));
    }

    private BalanceSnapshot snapshot(AccountId account, BigDecimal value) {
        return new BalanceSnapshot(account, currency, value, Instant.now(clock), true);
    }

    private TransactionResult unavailable(TransactionRequest request, Throwable exception) {
        BigDecimal amount;
        try { amount = currency.requireAmount(request.amount()); }
        catch (RuntimeException invalid) { amount = BigDecimal.ZERO.setScale(currency.scale()); }
        return new TransactionResult(TransactionResult.Status.UNAVAILABLE, request.idempotencyKey(),
                amount, amount, BigDecimal.ZERO.setScale(currency.scale()), rootMessage(exception));
    }

    private <T> CompletableFuture<T> supply(CheckedSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (Exception exception) {
                throw new LedgerAccessException(exception);
            }
        }, executor);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return "Ledger operation failed: " + current.getClass().getSimpleName();
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    private static final class LedgerAccessException extends RuntimeException {
        LedgerAccessException(Throwable cause) { super(cause); }
    }
}
