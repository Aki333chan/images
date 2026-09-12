package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface AurumEconomyApi {
    EconomyMode mode();

    CurrencySpec primaryCurrency();

    default List<CurrencySpec> currencies() { return List.of(primaryCurrency()); }

    default Optional<CurrencySpec> currency(String currencyId) {
        return currencies().stream().filter(value -> value.id().equalsIgnoreCase(currencyId)).findFirst();
    }

    CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account);

    default CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account, String currencyId) {
        if (!primaryCurrency().id().equalsIgnoreCase(currencyId)) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return balance(account);
    }

    CompletionStage<GlobalEconomySnapshot> globalSnapshot();

    /**
     * The richest player accounts, biggest first.
     *
     * <p>One indexed query over the accounts table. The alternative — walking
     * every player who ever joined and asking their balance one at a time — is
     * what a Vault-era rich list had to do, and it costs a round trip per
     * player for an answer the ledger already has in one place.
     *
     * <p>Player accounts only: a treasury or an escrow holding more than
     * anybody is not news, and putting it on a leaderboard makes the leaderboard
     * meaningless.
     *
     * @return empty when the economy is not authoritative here — a passive Core
     *         has no ledger to rank
     */
    default CompletionStage<List<BalanceSnapshot>> richest(String currencyId, int limit) {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }

    default CompletionStage<Optional<GlobalEconomySnapshot>> globalSnapshot(String currencyId) {
        if (!primaryCurrency().id().equalsIgnoreCase(currencyId)) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return globalSnapshot().thenApply(Optional::of);
    }

    CompletionStage<TransactionResult> transfer(TransactionRequest request);

    /** Atomic administrative compare-and-set; unsupported providers fail closed. */
    default CompletionStage<BalanceSetResult> setBalance(BalanceSetRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(new BalanceSetResult(
                BalanceSetResult.Status.UNAVAILABLE, request.idempotencyKey(),
                request.expectedBalance(), request.targetBalance(), request.expectedBalance(),
                request.expectedBalance(), request.expectedBalance(),
                "Absolute balance replacement is unavailable"));
    }

    default CompletionStage<HoldResult> createHold(HoldRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(new HoldResult(
                HoldResult.Status.UNAVAILABLE, Optional.empty(), "Holds are unavailable"));
    }

    default CompletionStage<HoldResult> captureHold(java.util.UUID holdId, TransactionRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(new HoldResult(
                HoldResult.Status.UNAVAILABLE, Optional.empty(), "Holds are unavailable"));
    }

    default CompletionStage<HoldResult> releaseHold(java.util.UUID holdId) {
        return java.util.concurrent.CompletableFuture.completedFuture(new HoldResult(
                HoldResult.Status.UNAVAILABLE, Optional.empty(), "Holds are unavailable"));
    }

    default CompletionStage<Optional<HoldSnapshot>> hold(String idempotencyKey) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }

    default CompletionStage<Optional<ExchangeQuote>> quoteExchange(
            AccountId account, String fromCurrencyId, String toCurrencyId,
            BigDecimal sourceAmount, Map<String, String> metadata) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }

    default CompletionStage<ExchangeResult> exchange(ExchangeRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(new ExchangeResult(
                ExchangeResult.Status.UNAVAILABLE, request.idempotencyKey(), Optional.empty(),
                "Currency exchange is unavailable"));
    }
}
