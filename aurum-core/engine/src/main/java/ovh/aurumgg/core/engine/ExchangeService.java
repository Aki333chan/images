package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.ExchangeQuote;
import ovh.aurumgg.core.api.ExchangeRequest;
import ovh.aurumgg.core.api.ExchangeResult;

public final class ExchangeService {
    private final Map<String, CurrencySpec> currencies;
    private final ExchangeRegistry registry;
    private final ExchangeRepository repository;
    private final MultiCurrencyEconomyService economy;
    private final Executor executor;
    private final Clock clock;
    private final Duration quoteTtl;
    private final Object mutationLock;

    public ExchangeService(Map<String, CurrencySpec> currencies, ExchangeRegistry registry,
                           ExchangeRepository repository, MultiCurrencyEconomyService economy,
                           Executor executor, Clock clock, Duration quoteTtl, Object mutationLock) {
        this.currencies = Map.copyOf(currencies);
        this.registry = registry;
        this.repository = repository;
        this.economy = economy;
        this.executor = executor;
        this.clock = clock;
        this.quoteTtl = quoteTtl;
        this.mutationLock = mutationLock;
    }

    public CompletionStage<Optional<ExchangeQuote>> quote(AccountId account, String from, String to,
                                                           BigDecimal amount, Map<String, String> metadata) {
        try { return CompletableFuture.completedFuture(quoteNow(account, from, to, amount, metadata)); }
        catch (RuntimeException exception) { return CompletableFuture.completedFuture(Optional.empty()); }
    }

    public CompletionStage<ExchangeResult> exchange(ExchangeRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (mutationLock) {
                try {
                    if (!Instant.now(clock).isBefore(request.quoteExpiresAt())) {
                        return rejected(request, "Exchange quote expired; request a new quote");
                    }
                    Optional<ExchangeQuote> selected = quoteNow(request.account(), request.fromCurrencyId(),
                            request.toCurrencyId(), request.sourceAmount(), request.metadata());
                    if (selected.isEmpty()) return rejected(request, "No active exchange rule");
                    ExchangeQuote quote = selected.get();
                    if (!quote.ruleId().equals(request.expectedRuleId())
                            || quote.ruleRevision() != request.expectedRuleRevision()
                            || quote.targetAmount().compareTo(request.expectedTargetAmount()) != 0) {
                        return rejected(request, "Exchange rate changed; request a new quote");
                    }
                    ExchangeRule rule = registry.snapshot().stream()
                            .filter(value -> value.id().equals(quote.ruleId())
                                    && value.revision() == quote.ruleRevision()).findFirst().orElseThrow();
                    ExchangeCommit commit = repository.execute(ExchangePlanner.plan(request, quote,
                            rule.settlement()), quote.fromCurrency(), quote.toCurrency());
                    if (commit.status() == ExchangeCommit.Status.COMMITTED) {
                        economy.applyCommittedBalances(commit.balancesAfter());
                    }
                    ExchangeResult.Status status = switch (commit.status()) {
                        case COMMITTED -> ExchangeResult.Status.SUCCESS;
                        case DUPLICATE -> ExchangeResult.Status.DUPLICATE;
                        case REJECTED -> ExchangeResult.Status.REJECTED;
                    };
                    return new ExchangeResult(status, request.idempotencyKey(), Optional.of(commit.quote()),
                            commit.message());
                } catch (PolicyRejectedException exception) {
                    return rejected(request, exception.getMessage());
                } catch (Exception exception) {
                    return new ExchangeResult(ExchangeResult.Status.UNAVAILABLE, request.idempotencyKey(),
                            Optional.empty(), "Exchange storage unavailable: " + exception.getClass().getSimpleName());
                }
            }
        }, executor);
    }

    private Optional<ExchangeQuote> quoteNow(AccountId account, String fromId, String toId,
                                              BigDecimal amount, Map<String, String> metadata) {
        CurrencySpec from = currencies.get(fromId.toLowerCase(Locale.ROOT));
        CurrencySpec to = currencies.get(toId.toLowerCase(Locale.ROOT));
        if (from == null || to == null || from.id().equals(to.id())) return Optional.empty();
        Instant now = Instant.now(clock);
        return registry.select(account, from.id(), to.id(), metadata, now)
                .map(rule -> ExchangePlanner.quote(account, amount, rule, from, to, now, quoteTtl));
    }

    private static ExchangeResult rejected(ExchangeRequest request, String message) {
        return new ExchangeResult(ExchangeResult.Status.REJECTED, request.idempotencyKey(),
                Optional.empty(), message);
    }
}
