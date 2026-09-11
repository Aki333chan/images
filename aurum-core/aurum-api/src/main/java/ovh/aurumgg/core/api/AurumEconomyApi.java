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

    default CompletionStage<Optional<GlobalEconomySnapshot>> globalSnapshot(String currencyId) {
        if (!primaryCurrency().id().equalsIgnoreCase(currencyId)) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return globalSnapshot().thenApply(Optional::of);
    }

    CompletionStage<TransactionResult> transfer(TransactionRequest request);

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
