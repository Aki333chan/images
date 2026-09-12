package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

/** Reserves available balance and resolves cross-system operations without blocking the Paper thread. */
public final class HoldService {
    private final Map<String, CurrencySpec> currencies;
    private final HoldRepository repository;
    private final MultiCurrencyEconomyService economy;
    private final Executor executor;
    private final Clock clock;
    private final Object mutationLock;
    private final Duration maxTtl;

    public HoldService(Map<String, CurrencySpec> currencies, HoldRepository repository,
                       MultiCurrencyEconomyService economy, Executor executor, Clock clock,
                       Object mutationLock, Duration maxTtl) {
        this.currencies = Map.copyOf(currencies);
        this.repository = repository;
        this.economy = economy;
        this.executor = executor;
        this.clock = clock;
        this.mutationLock = mutationLock;
        this.maxTtl = java.util.Objects.requireNonNull(maxTtl, "maxTtl");
        if (maxTtl.isZero() || maxTtl.isNegative()) throw new IllegalArgumentException("maxTtl must be positive");
    }

    public CompletionStage<HoldResult> create(HoldRequest request) {
        return operation(() -> {
            synchronized (mutationLock) {
                CurrencySpec currency = currencies.get(request.currencyId());
                Instant now = Instant.now(clock);
                if (currency == null) return rejected("Unknown hold currency");
                Optional<HoldSnapshot> existing = repository.find(request.idempotencyKey(), currency);
                if (existing.isPresent()) {
                    if (!sameReservation(existing.get(), request)) {
                        return new HoldResult(HoldResult.Status.REJECTED, existing,
                                "Idempotency key belongs to a different hold intent");
                    }
                    return new HoldResult(HoldResult.Status.DUPLICATE, existing, "Existing hold");
                }
                if (!request.expiresAt().isAfter(now)
                        || request.expiresAt().isAfter(now.plus(maxTtl))) {
                    return rejected("Hold expiry exceeds the configured maximum");
                }
                UUID holdId = UUID.randomUUID();
                Map<String, String> previewMetadata = new HashMap<>(request.metadata());
                previewMetadata.put("aurum-hold-id", holdId.toString());
                TransactionRequest transaction = request.transaction("hold:preview:" + holdId, previewMetadata);
                TransactionPlan plan = economy.required(currency.id()).planFor(transaction);
                HoldSnapshot hold = new HoldSnapshot(holdId, request.idempotencyKey(), request.from(),
                        request.to(), currency, currency.requireAmount(request.amount()), plan.sourceDebit(),
                        request.category(), request.purpose(), request.referenceId(), HoldSnapshot.Status.HELD,
                        now, request.expiresAt(), request.metadata());
                return repository.reserve(hold);
            }
        });
    }

    public CompletionStage<HoldResult> capture(UUID id, TransactionRequest request) {
        return operation(() -> {
            synchronized (mutationLock) {
                CurrencySpec currency = currencies.get(request.currencyId());
                if (currency == null) return rejected("Unknown hold currency");
                Optional<HoldSnapshot> selected = repository.find(id, currency);
                if (selected.isEmpty()) return new HoldResult(HoldResult.Status.NOT_FOUND,
                        Optional.empty(), "Hold was not found");
                HoldSnapshot hold = selected.get();
                if (hold.status() == HoldSnapshot.Status.CAPTURED) {
                    return new HoldResult(HoldResult.Status.DUPLICATE, selected, "Hold already captured");
                }
                if (hold.status() != HoldSnapshot.Status.HELD) {
                    return new HoldResult(HoldResult.Status.REJECTED, selected,
                            "Hold is already " + hold.status().name().toLowerCase());
                }
                if (!sameIntent(hold, request)) return new HoldResult(HoldResult.Status.REJECTED, selected,
                        "Capture request differs from the reserved operation");
                LedgerEconomyService currencyEconomy = economy.required(currency.id());
                String transactionKey = "hold:capture:" + id;
                if (currencyEconomy.transactionCommitted(transactionKey)) {
                    return repository.resolve(id, HoldSnapshot.Status.CAPTURED, currency);
                }
                if (!Instant.now(clock).isBefore(hold.expiresAt())) {
                    return repository.resolve(id, HoldSnapshot.Status.EXPIRED, currency);
                }
                Map<String, String> metadata = new HashMap<>(request.metadata());
                metadata.put("aurum-hold-id", id.toString());
                TransactionRequest capture = new TransactionRequest(transactionKey, request.from(),
                        request.to(), request.currencyId(), request.amount(), request.category(), metadata);
                TransactionPlan plan = currencyEconomy.planFor(capture);
                if (plan.sourceDebit().compareTo(hold.reservedAmount()) != 0) {
                    return new HoldResult(HoldResult.Status.REJECTED, selected,
                            "Financial rules changed; create a new hold");
                }
                TransactionResult result = currencyEconomy.commitPlanned(plan, id);
                if (result.status() == TransactionResult.Status.SUCCESS
                        || result.status() == TransactionResult.Status.DUPLICATE) {
                    return repository.resolve(id, HoldSnapshot.Status.CAPTURED, currency);
                }
                return new HoldResult(result.status() == TransactionResult.Status.UNAVAILABLE
                        ? HoldResult.Status.UNAVAILABLE : HoldResult.Status.REJECTED, selected, result.message());
            }
        });
    }

    public CompletionStage<HoldResult> release(UUID id) {
        return operation(() -> {
            synchronized (mutationLock) {
                for (CurrencySpec currency : currencies.values()) {
                    Optional<HoldSnapshot> hold = repository.find(id, currency);
                    if (hold.isEmpty()) continue;
                    if (hold.get().status() == HoldSnapshot.Status.RELEASED
                            || hold.get().status() == HoldSnapshot.Status.EXPIRED) {
                        return new HoldResult(HoldResult.Status.DUPLICATE, hold,
                                "Hold is already released");
                    }
                    if (hold.get().status() != HoldSnapshot.Status.HELD) {
                        return new HoldResult(HoldResult.Status.REJECTED, hold,
                                "Captured holds cannot be released; use an explicit refund");
                    }
                    return repository.resolve(id, HoldSnapshot.Status.RELEASED, currency);
                }
                return new HoldResult(HoldResult.Status.NOT_FOUND, Optional.empty(), "Hold was not found");
            }
        });
    }

    public CompletionStage<Optional<HoldSnapshot>> find(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                for (CurrencySpec currency : currencies.values()) {
                    Optional<HoldSnapshot> hold = repository.find(key, currency);
                    if (hold.isPresent()) return hold;
                }
                return Optional.<HoldSnapshot>empty();
            } catch (Exception exception) { throw new CompletionException(exception); }
        }, executor);
    }

    private static boolean sameIntent(HoldSnapshot hold, TransactionRequest request) {
        return hold.from().equals(request.from()) && hold.to().equals(request.to())
                && hold.currency().id().equals(request.currencyId()) && hold.category() == request.category()
                && hold.amount().compareTo(request.amount()) == 0 && hold.metadata().equals(request.metadata());
    }
    private static boolean sameReservation(HoldSnapshot hold, HoldRequest request) {
        return hold.from().equals(request.from()) && hold.to().equals(request.to())
                && hold.currency().id().equals(request.currencyId()) && hold.category() == request.category()
                && hold.amount().compareTo(request.amount()) == 0
                && hold.purpose().equals(request.purpose())
                && hold.referenceId().equals(request.referenceId())
                && hold.expiresAt().equals(request.expiresAt())
                && hold.metadata().equals(request.metadata());
    }
    private static HoldResult rejected(String message) {
        return new HoldResult(HoldResult.Status.REJECTED, Optional.empty(), message);
    }
    private CompletableFuture<HoldResult> operation(CheckedSupplier<HoldResult> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (PolicyRejectedException exception) { return rejected("POLICY:" + exception.getMessage()); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> new HoldResult(HoldResult.Status.UNAVAILABLE,
                Optional.empty(), "Hold storage unavailable"));
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
