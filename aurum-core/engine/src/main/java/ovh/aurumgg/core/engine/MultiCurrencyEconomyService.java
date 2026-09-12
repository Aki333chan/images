package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

public final class MultiCurrencyEconomyService implements AurumEconomyApi {
    private final CurrencySpec primary;
    private final Map<String, LedgerEconomyService> services;
    private volatile ExchangeService exchangeService;
    private volatile HoldService holdService;

    public MultiCurrencyEconomyService(CurrencySpec primary, Map<String, LedgerEconomyService> services) {
        this.primary = primary;
        this.services = Map.copyOf(new LinkedHashMap<>(services));
        if (!this.services.containsKey(primary.id())) {
            throw new IllegalArgumentException("Primary currency has no ledger service");
        }
    }

    @Override public EconomyMode mode() { return EconomyMode.ACTIVE; }
    @Override public CurrencySpec primaryCurrency() { return primary; }
    @Override public List<CurrencySpec> currencies() {
        return services.values().stream().map(LedgerEconomyService::primaryCurrency).toList();
    }

    @Override public CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account) {
        return primaryService().balance(account);
    }

    @Override public CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account, String currencyId) {
        LedgerEconomyService service = service(currencyId);
        return service == null ? CompletableFuture.completedFuture(Optional.empty()) : service.balance(account);
    }

    @Override public CompletionStage<GlobalEconomySnapshot> globalSnapshot() {
        return primaryService().globalSnapshot();
    }

    @Override
    public CompletionStage<List<BalanceSnapshot>> richest(String currencyId, int limit) {
        LedgerEconomyService service = services.get(currencyId);
        return service == null
                ? CompletableFuture.completedFuture(List.of())
                : service.richest(currencyId, limit);
    }

    @Override public CompletionStage<Optional<GlobalEconomySnapshot>> globalSnapshot(String currencyId) {
        LedgerEconomyService service = service(currencyId);
        return service == null ? CompletableFuture.completedFuture(Optional.empty())
                : service.globalSnapshot().thenApply(Optional::of);
    }

    @Override public CompletionStage<TransactionResult> transfer(TransactionRequest request) {
        LedgerEconomyService service = service(request.currencyId());
        if (service != null) return service.transfer(request);
        BigDecimal zero = BigDecimal.ZERO.setScale(primary.scale());
        return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.REJECTED,
                request.idempotencyKey(), zero, zero, zero, "Unknown currency: " + request.currencyId()));
    }

    @Override public CompletionStage<ovh.aurumgg.core.api.HoldResult> createHold(
            ovh.aurumgg.core.api.HoldRequest request) {
        HoldService current = holdService;
        return current == null ? AurumEconomyApi.super.createHold(request) : current.create(request);
    }

    @Override public CompletionStage<ovh.aurumgg.core.api.HoldResult> captureHold(
            java.util.UUID holdId, TransactionRequest request) {
        HoldService current = holdService;
        return current == null ? AurumEconomyApi.super.captureHold(holdId, request)
                : current.capture(holdId, request);
    }

    @Override public CompletionStage<ovh.aurumgg.core.api.HoldResult> releaseHold(java.util.UUID holdId) {
        HoldService current = holdService;
        return current == null ? AurumEconomyApi.super.releaseHold(holdId) : current.release(holdId);
    }

    @Override public CompletionStage<Optional<ovh.aurumgg.core.api.HoldSnapshot>> hold(String key) {
        HoldService current = holdService;
        return current == null ? AurumEconomyApi.super.hold(key) : current.find(key);
    }

    @Override public CompletionStage<Optional<ovh.aurumgg.core.api.ExchangeQuote>> quoteExchange(
            AccountId account, String fromCurrencyId, String toCurrencyId, BigDecimal sourceAmount,
            Map<String, String> metadata) {
        ExchangeService current = exchangeService;
        return current == null ? CompletableFuture.completedFuture(Optional.empty())
                : current.quote(account, fromCurrencyId, toCurrencyId, sourceAmount, metadata);
    }

    @Override public CompletionStage<ovh.aurumgg.core.api.ExchangeResult> exchange(
            ovh.aurumgg.core.api.ExchangeRequest request) {
        ExchangeService current = exchangeService;
        return current == null ? AurumEconomyApi.super.exchange(request) : current.exchange(request);
    }

    public void attachExchangeService(ExchangeService service) { this.exchangeService = service; }
    public void attachHoldService(HoldService service) { this.holdService = service; }

    public CompletionStage<TransactionResult> setPlayerBalance(AccountId account, String currencyId,
                                                                BigDecimal target, String key,
                                                                Map<String, String> metadata) {
        LedgerEconomyService service = required(currencyId);
        return service.setPlayerBalance(account, target, key, metadata);
    }

    public CompletionStage<TransactionResult> setPlayerBalance(AccountId account, BigDecimal target,
                                                                String key, Map<String, String> metadata) {
        return primaryService().setPlayerBalance(account, target, key, metadata);
    }

    public void seedBalances(String currencyId, Map<AccountId, BigDecimal> balances) {
        required(currencyId).seedBalances(balances);
    }

    public Optional<BalanceSnapshot> cachedBalance(AccountId account, String currencyId) {
        LedgerEconomyService service = service(currencyId);
        return service == null ? Optional.empty() : service.cachedBalance(account);
    }

    public Optional<BalanceSnapshot> cachedBalance(AccountId account) {
        return primaryService().cachedBalance(account);
    }

    public GlobalEconomySnapshot cachedGlobalSnapshot(String currencyId) {
        return required(currencyId).cachedGlobalSnapshot();
    }

    public GlobalEconomySnapshot cachedGlobalSnapshot() { return primaryService().cachedGlobalSnapshot(); }
    public int cachedAccountCount() { return services.values().stream()
            .mapToInt(LedgerEconomyService::cachedAccountCount).sum(); }
    public void cacheZeroIfAbsent(AccountId account) {
        services.values().forEach(service -> service.cacheZeroIfAbsent(account));
    }
    public void applyCommittedBalances(Map<CurrencyAccountKey, BigDecimal> balances) {
        balances.forEach((key, value) -> required(key.currencyId())
                .applyCommittedBalance(key.account(), value));
    }
    public LedgerEconomyService primaryService() { return services.get(primary.id()); }
    public LedgerEconomyService required(String currencyId) {
        LedgerEconomyService result = service(currencyId);
        if (result == null) throw new IllegalArgumentException("Unknown currency: " + currencyId);
        return result;
    }
    private LedgerEconomyService service(String currencyId) {
        return currencyId == null ? null : services.get(currencyId.toLowerCase(Locale.ROOT));
    }
}
