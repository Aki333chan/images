package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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

public final class PassiveEconomyService implements AurumEconomyApi {
    private final CurrencySpec currency;
    private final Clock clock;
    private final ObservedBalanceStore balances;

    public PassiveEconomyService(CurrencySpec currency, Clock clock) {
        this.currency = currency;
        this.clock = clock;
        this.balances = new ObservedBalanceStore(currency, clock);
    }

    @Override
    public EconomyMode mode() {
        return EconomyMode.PASSIVE;
    }

    @Override
    public CurrencySpec primaryCurrency() {
        return currency;
    }

    @Override
    public CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account) {
        return CompletableFuture.completedFuture(balances.find(account));
    }

    @Override
    public CompletionStage<GlobalEconomySnapshot> globalSnapshot() {
        BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
        return CompletableFuture.completedFuture(
                new GlobalEconomySnapshot(currency, zero, zero, zero, Instant.now(clock), false, false));
    }

    @Override
    public CompletionStage<TransactionResult> transfer(TransactionRequest request) {
        BigDecimal amount = currency.requireAmount(request.amount());
        BigDecimal zero = BigDecimal.ZERO.setScale(currency.scale());
        return CompletableFuture.completedFuture(new TransactionResult(
                TransactionResult.Status.UNAVAILABLE,
                request.idempotencyKey(),
                amount,
                amount,
                zero,
                "AurumCore is in passive mode; no money was changed"
        ));
    }

    public void observe(AccountId account, BigDecimal balance) {
        balances.observe(account, currency.requireAmount(balance));
    }

    public int observedAccountCount() {
        return balances.size();
    }

    public Optional<BalanceSnapshot> cachedBalance(AccountId account) {
        return balances.find(account);
    }
}
