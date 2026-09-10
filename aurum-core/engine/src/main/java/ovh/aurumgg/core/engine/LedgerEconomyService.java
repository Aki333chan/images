package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

/** Authoritative service. Paper does not expose this implementation until migration is verified. */
public final class LedgerEconomyService implements AurumEconomyApi {
    private final CurrencySpec currency;
    private final LedgerRepository repository;
    private final TaxRuleResolver taxRules;
    private final Executor executor;
    private final Clock clock;

    public LedgerEconomyService(CurrencySpec currency, LedgerRepository repository,
                                TaxRuleResolver taxRules, Executor executor, Clock clock) {
        this.currency = currency;
        this.repository = repository;
        this.taxRules = taxRules;
        this.executor = executor;
        this.clock = clock;
    }

    @Override public EconomyMode mode() { return EconomyMode.ACTIVE; }
    @Override public CurrencySpec primaryCurrency() { return currency; }

    @Override
    public CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account) {
        return supply(() -> repository.balance(account, currency)
                .map(value -> new BalanceSnapshot(account, currency, value, Instant.now(clock), true)));
    }

    @Override
    public CompletionStage<GlobalEconomySnapshot> globalSnapshot() {
        return supply(() -> repository.globalSnapshot(currency));
    }

    @Override
    public CompletionStage<TransactionResult> transfer(TransactionRequest request) {
        return supply(() -> {
            TransactionPlan plan = TransactionPlanner.plan(request, currency, taxRules.select(request));
            LedgerCommit commit = repository.commit(plan, currency);
            TransactionResult.Status status = switch (commit.status()) {
                case COMMITTED -> TransactionResult.Status.SUCCESS;
                case DUPLICATE -> TransactionResult.Status.DUPLICATE;
                case INSUFFICIENT_FUNDS, REJECTED -> TransactionResult.Status.REJECTED;
            };
            return new TransactionResult(status, request.idempotencyKey(), commit.grossAmount(),
                    commit.netAmount(), commit.taxAmount(), commit.message());
        }).exceptionally(exception -> {
            BigDecimal amount;
            try { amount = currency.requireAmount(request.amount()); }
            catch (RuntimeException invalid) { amount = BigDecimal.ZERO.setScale(currency.scale()); }
            return new TransactionResult(TransactionResult.Status.UNAVAILABLE, request.idempotencyKey(),
                    amount, amount, BigDecimal.ZERO.setScale(currency.scale()), rootMessage(exception));
        });
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
