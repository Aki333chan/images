package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TradeOffer;
import ovh.aurumgg.core.api.TradeResult;
import ovh.aurumgg.core.api.TradeSession;
import ovh.aurumgg.core.api.TradeState;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

/**
 * Guaranteed trades between two players.
 *
 * <h2>Why the money moves last</h2>
 *
 * Items leave a player's inventory when they are offered — otherwise the same
 * stack could be promised in two places, or simply dropped while the other side
 * is looking at it. Money cannot be offered twice (a player is in at most one
 * trade), so it stays where it is until the moment of settlement.
 *
 * <p>The recorded design reserved money as soon as it was offered. This
 * reserves it when the trade settles instead, and the guarantee is the same:
 * nothing moves unless BOTH sides are funded. Reserving per offer would mean
 * creating and releasing a hold on every edit of the amount, for a window that
 * settlement closes in microseconds anyway.
 *
 * <h2>Why holds and not two transfers</h2>
 *
 * Both sides may be offering money. Two plain transfers can half-succeed, so
 * offers in one currency are netted into one payment and that payment is
 * reserved before capture. One ledger transaction has no half-settled side.
 */
public final class TradeService {

    private final TradeRepository repository;
    private final AurumEconomyApi economy;
    private final Executor executor;
    private final Clock clock;
    private final Duration inviteTimeout;
    private final Duration sessionTimeout;

    public TradeService(TradeRepository repository, AurumEconomyApi economy,
                        Executor executor, Clock clock, Duration inviteTimeout, Duration sessionTimeout) {
        this.repository = repository;
        this.economy = economy;
        this.executor = executor;
        this.clock = clock;
        this.inviteTimeout = inviteTimeout;
        this.sessionTimeout = sessionTimeout;
        if (inviteTimeout.isNegative() || inviteTimeout.isZero()
                || sessionTimeout.isNegative() || sessionTimeout.isZero()) {
            throw new IllegalArgumentException("Trade timeouts must be positive");
        }
    }

    // ----------------------------------------------------------- жизнь сделки

    /** Invite someone to trade. Fails if either of you is already in one. */
    public CompletionStage<TradeResult> invite(UUID from, UUID to) {
        return operation(() -> {
            if (from.equals(to)) {
                return new TradeResult(TradeResult.Status.CONFLICT, Optional.empty(),
                        "A player cannot trade with themselves");
            }
            Instant now = Instant.now(clock);
            Optional<TradeSession> opened = repository.open(UUID.randomUUID(), from, to,
                    now.plus(inviteTimeout), now);
            return opened.map(trade -> new TradeResult(TradeResult.Status.SUCCESS, Optional.of(trade),
                            "Trade invited"))
                    .orElseGet(() -> new TradeResult(TradeResult.Status.BUSY, Optional.empty(),
                            "One of you is already trading"));
        });
    }

    /**
     * Accept an invitation.
     *
     * <p>Only the invited side may accept, and the clock restarts: the invite
     * timeout answers "did they notice", the session timeout answers "are they
     * still doing something", and those are different questions.
     */
    public CompletionStage<TradeResult> accept(UUID tradeId, UUID player) {
        return operation(() -> {
            Optional<TradeSession> trade = repository.find(tradeId);
            if (trade.isEmpty()) return notFound();
            if (!trade.get().second().equals(player)) {
                return conflict(trade, "Only the invited player can accept");
            }
            Instant now = Instant.now(clock);
            Optional<TradeSession> opened =
                    repository.transition(tradeId, TradeState.INVITED, TradeState.OPEN, false, now);
            if (opened.isEmpty()) return conflict(repository.find(tradeId), "The invitation is no longer open");
            return renew(opened.get(), now.plus(sessionTimeout), "Trade open");
        });
    }

    /**
     * Replace one side's offer.
     *
     * <p>Both confirmations are cleared by the same statement. A player is only
     * ever bound to the table they were looking at.
     */
    public CompletionStage<TradeResult> offer(UUID tradeId, TradeOffer offer) {
        return operation(() -> {
            Instant now = Instant.now(clock);
            Optional<TradeSession> updated = repository.offer(tradeId, offer, now);
            if (updated.isEmpty()) {
                Optional<TradeSession> current = repository.find(tradeId);
                return current.isEmpty() ? notFound() : conflict(current, "The trade is no longer editable");
            }
            return renew(updated.get(), now.plus(sessionTimeout), "Offer updated");
        });
    }

    /**
     * Confirm the revision the player is looking at.
     *
     * <p>A confirmation for any other revision is refused, not adjusted. That
     * refusal IS the feature: it is what stops the oldest trade scam there is,
     * swapping the goods in the instant between the look and the click.
     */
    public CompletionStage<TradeResult> confirm(UUID tradeId, UUID player, long revision) {
        return operation(() -> {
            Instant now = Instant.now(clock);
            Optional<TradeSession> confirmed = repository.confirm(tradeId, player, revision, now);
            if (confirmed.isEmpty()) {
                Optional<TradeSession> current = repository.find(tradeId);
                return current.isEmpty() ? notFound()
                        : conflict(current, "The offer changed; look again before confirming");
            }
            return new TradeResult(TradeResult.Status.SUCCESS, confirmed, "Confirmed");
        });
    }

    /**
     * Take the trade to CONFIRMED, but only if both sides really did sign off on
     * the current revision.
     *
     * <p>The check lives in the UPDATE, not here: deciding from a read taken one
     * edit ago is precisely the mistake the revision is meant to prevent.
     */
    public CompletionStage<TradeResult> lock(UUID tradeId) {
        return operation(() -> {
            Optional<TradeSession> locked = repository.transition(tradeId, TradeState.OPEN,
                    TradeState.CONFIRMED, true, Instant.now(clock));
            if (locked.isEmpty()) {
                Optional<TradeSession> current = repository.find(tradeId);
                return current.isEmpty() ? notFound() : conflict(current, "Both sides have not confirmed");
            }
            return new TradeResult(TradeResult.Status.SUCCESS, locked, "Trade locked");
        });
    }

    /** Call the trade off. Both offers go back to their owners; the caller returns the items. */
    public CompletionStage<TradeResult> cancel(UUID tradeId, String reason) {
        return operation(() -> {
            Instant now = Instant.now(clock);
            for (TradeState from : List.of(TradeState.INVITED, TradeState.OPEN, TradeState.CONFIRMED)) {
                Optional<TradeSession> cancelled =
                        repository.transition(tradeId, from, TradeState.CANCELLED, false, now);
                if (cancelled.isPresent()) {
                    return new TradeResult(TradeResult.Status.SUCCESS, cancelled, reason);
                }
            }
            Optional<TradeSession> current = repository.find(tradeId);
            // Already settling or settled: too late to call off, and saying so
            // is better than pretending it worked.
            return current.isEmpty() ? notFound() : conflict(current, "The trade can no longer be cancelled");
        });
    }

    // -------------------------------------------------------------- расчёт

    /**
     * Move the money, once.
     *
     * <p>Reserve both sides, then capture both. A side that cannot cover its own
     * offer stops the trade before anything has moved, and the trade goes back
     * to OPEN so the players can fix it rather than losing the session.
     *
     * <p>Items are not touched here. They belong to Minecraft, and the caller
     * hands them over through durable claims once this returns SUCCESS.
     */
    public CompletionStage<TradeResult> settleMoney(UUID tradeId) {
        return operation(() -> {
            Instant now = Instant.now(clock);
            Optional<TradeSession> current = repository.find(tradeId);
            if (current.isEmpty()) return notFound();
            if (current.get().state() == TradeState.SETTLING) {
                return new TradeResult(TradeResult.Status.SUCCESS, current, "Settlement resumed");
            }
            Optional<TradeSession> settling = repository.transition(
                    tradeId, TradeState.CONFIRMED, TradeState.SETTLING, true, now);
            if (settling.isEmpty()) {
                return conflict(repository.find(tradeId), "The trade is not ready to settle");
            }
            return new TradeResult(TradeResult.Status.SUCCESS, settling, "Settlement started");
        }).thenCompose(started -> {
            if (!started.ok() || started.trade().isEmpty()) return completed(started);
            TradeSession trade = started.trade().orElseThrow();
            return settlementOffers(trade.id()).thenCompose(offers -> {
                if (offers == null) return completed(unavailable(trade, "Trade offers unavailable"));
                List<TradeOffer> payments = netPayment(trade, offers);
                if (payments == null) {
                    return operation(() -> {
                        Optional<TradeSession> reopened = repository.reopen(trade.id(), Instant.now(clock));
                        return new TradeResult(TradeResult.Status.CONFLICT,
                                reopened.isPresent() ? reopened : repository.find(trade.id()),
                                "Money in different currencies cannot be settled atomically");
                    });
                }
                return reserveAll(trade, payments, 0, new java.util.ArrayList<>())
                        .thenCompose(batch -> {
                            if (!batch.ready()) return reservationFailed(trade, batch);
                            return captureAll(trade, batch.holds(), 0);
                        });
            });
        });
    }

    /** The trade is done: items handed over, money moved. */
    public CompletionStage<TradeResult> settled(UUID tradeId) {
        return operation(() -> {
            Optional<TradeSession> done = repository.transition(tradeId, TradeState.SETTLING,
                    TradeState.SETTLED, false, Instant.now(clock));
            return done.isEmpty() ? conflict(repository.find(tradeId), "The trade is not settling")
                    : new TradeResult(TradeResult.Status.SUCCESS, done, "Trade settled");
        });
    }

    /** Trades nobody finished in time. The caller returns their items and cancels them. */
    public CompletionStage<List<TradeSession>> timedOut(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.timedOut(Instant.now(clock), limit); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> List.of());
    }

    /** Settlements that must be resumed after a restart or a transient failure. */
    public CompletionStage<List<TradeSession>> settling(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.settling(limit); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> List.of());
    }

    public CompletionStage<Optional<TradeSession>> activeFor(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.activeFor(player); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> Optional.empty());
    }

    public CompletionStage<List<TradeOffer>> offers(UUID tradeId) {
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.offers(tradeId); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> List.of());
    }

    // ------------------------------------------------------------- служебное

    private HoldRequest reservation(TradeSession trade, TradeOffer offer) {
        String currency = offer.currencyId().orElseThrow();
        return new HoldRequest(
                "trade:" + trade.id() + ":" + trade.revision() + ":" + offer.owner(),
                AccountId.player(offer.owner()),
                AccountId.player(trade.other(offer.owner())),
                currency, offer.money(), TransactionCategory.TRADE_SETTLEMENT, "trade",
                trade.id().toString(),
                // Deterministic for retries. A changed expiry would make the
                // same idempotency key describe a different reservation.
                trade.updatedAt().plusSeconds(60),
                Map.of("trade", trade.id().toString(), "revision", Long.toString(trade.revision())));
    }

    /**
     * Collapse both offers in one currency into one net payment.
     *
     * <p>Two independent captures can half-succeed across a database failure.
     * A net transfer is economically identical and commits as one ledger
     * transaction, so there is no half-settled monetary state to compensate.
     */
    private static List<TradeOffer> netPayment(TradeSession trade, List<TradeOffer> offers) {
        TradeOffer first = offers.stream().filter(value -> value.owner().equals(trade.first()))
                .findFirst().orElse(null);
        TradeOffer second = offers.stream().filter(value -> value.owner().equals(trade.second()))
                .findFirst().orElse(null);
        BigDecimal firstMoney = first == null ? BigDecimal.ZERO : first.money();
        BigDecimal secondMoney = second == null ? BigDecimal.ZERO : second.money();
        String firstCurrency = first == null ? null : first.currencyId().orElse(null);
        String secondCurrency = second == null ? null : second.currencyId().orElse(null);
        if (firstMoney.signum() > 0 && secondMoney.signum() > 0
                && !java.util.Objects.equals(firstCurrency, secondCurrency)) return null;
        String currency = firstMoney.signum() > 0 ? firstCurrency : secondCurrency;
        BigDecimal net = firstMoney.subtract(secondMoney);
        if (net.signum() == 0) return List.of();
        UUID payer = net.signum() > 0 ? trade.first() : trade.second();
        return List.of(new TradeOffer(trade.id(), payer, Optional.ofNullable(currency), net.abs(), null, 1));
    }

    private CompletionStage<List<TradeOffer>> settlementOffers(UUID tradeId) {
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.offers(tradeId); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> null);
    }

    private CompletionStage<ReservationBatch> reserveAll(TradeSession trade, List<TradeOffer> offers,
                                                           int index, List<HoldSnapshot> holds) {
        if (economy == null) return completed(ReservationBatch.failed(holds, HoldResult.Status.UNAVAILABLE));
        if (index >= offers.size()) return completed(ReservationBatch.ready(holds));
        TradeOffer offer = offers.get(index);
        if (!offer.hasMoney()) return reserveAll(trade, offers, index + 1, holds);
        HoldRequest request = reservation(trade, offer);
        return economy.createHold(request).handle((result, error) -> error == null ? result : null)
                .thenCompose(result -> {
                    if (result == null || result.hold().isEmpty()) {
                        HoldResult.Status status = result == null ? HoldResult.Status.UNAVAILABLE : result.status();
                        return completed(ReservationBatch.failed(holds, status));
                    }
                    HoldSnapshot hold = result.hold().orElseThrow();
                    boolean accepted = (result.status() == HoldResult.Status.SUCCESS
                            || result.status() == HoldResult.Status.DUPLICATE)
                            && (hold.status() == HoldSnapshot.Status.HELD
                            || hold.status() == HoldSnapshot.Status.CAPTURED);
                    if (!accepted) return completed(ReservationBatch.failed(holds, result.status()));
                    holds.add(hold);
                    return reserveAll(trade, offers, index + 1, holds);
                });
    }

    private CompletionStage<TradeResult> reservationFailed(TradeSession trade, ReservationBatch batch) {
        // Once one side was captured the settlement is a durable in-progress
        // operation. Reopening would let the paid side edit the table and make
        // reconciliation impossible; leave it for the recovery sweep instead.
        if (batch.holds().stream().anyMatch(hold -> hold.status() == HoldSnapshot.Status.CAPTURED)) {
            return completed(unavailable(trade, "Settlement is waiting for hold recovery"));
        }
        return releaseAll(batch.holds(), 0).thenCompose(ignored -> operation(() -> {
            Optional<TradeSession> reopened = repository.reopen(trade.id(), Instant.now(clock));
            TradeResult.Status status = batch.failure() == HoldResult.Status.INSUFFICIENT_FUNDS
                    ? TradeResult.Status.INSUFFICIENT_FUNDS : TradeResult.Status.UNAVAILABLE;
            return new TradeResult(status, reopened.isPresent() ? reopened : repository.find(trade.id()),
                    status == TradeResult.Status.INSUFFICIENT_FUNDS
                            ? "A side could not cover its offer" : "Hold storage unavailable");
        }));
    }

    private CompletionStage<Void> releaseAll(List<HoldSnapshot> holds, int index) {
        if (index >= holds.size()) return CompletableFuture.completedFuture(null);
        HoldSnapshot hold = holds.get(index);
        if (hold.status() != HoldSnapshot.Status.HELD) return releaseAll(holds, index + 1);
        return economy.releaseHold(hold.id()).handle((ignored, error) -> null)
                .thenCompose(ignored -> releaseAll(holds, index + 1));
    }

    private CompletionStage<TradeResult> captureAll(TradeSession trade, List<HoldSnapshot> holds, int index) {
        if (index >= holds.size()) {
            return completed(new TradeResult(TradeResult.Status.SUCCESS, Optional.of(trade), "Money settled"));
        }
        HoldSnapshot hold = holds.get(index);
        if (hold.status() == HoldSnapshot.Status.CAPTURED) return captureAll(trade, holds, index + 1);
        TransactionRequest request = new TransactionRequest(
                "trade-capture:" + hold.idempotencyKey(), hold.from(), hold.to(),
                hold.currency().id(), hold.amount(), hold.category(), hold.metadata());
        return economy.captureHold(hold.id(), request).handle((result, error) -> error == null ? result : null)
                .thenCompose(result -> {
                    boolean captured = result != null && (result.status() == HoldResult.Status.SUCCESS
                            || result.status() == HoldResult.Status.DUPLICATE)
                            && result.hold().map(value -> value.status() == HoldSnapshot.Status.CAPTURED)
                                    .orElse(false);
                    if (!captured) {
                        boolean terminalWithoutCapture = result != null && result.hold()
                                .map(value -> value.status() == HoldSnapshot.Status.EXPIRED
                                        || value.status() == HoldSnapshot.Status.RELEASED
                                        || value.status() == HoldSnapshot.Status.REJECTED)
                                .orElse(false);
                        if (result != null && (result.status() == HoldResult.Status.REJECTED
                                || terminalWithoutCapture)) {
                            HoldSnapshot latest = result.hold().orElse(hold);
                            return reservationFailed(trade,
                                    ReservationBatch.failed(List.of(latest), HoldResult.Status.REJECTED));
                        }
                        return completed(unavailable(trade,
                                result == null ? "Hold capture unavailable" : result.message()));
                    }
                    return captureAll(trade, holds, index + 1);
                });
    }

    private static TradeResult unavailable(TradeSession trade, String message) {
        return new TradeResult(TradeResult.Status.UNAVAILABLE, Optional.of(trade), message);
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private record ReservationBatch(boolean ready, List<HoldSnapshot> holds, HoldResult.Status failure) {
        private ReservationBatch {
            holds = List.copyOf(holds);
        }
        static ReservationBatch ready(List<HoldSnapshot> holds) {
            return new ReservationBatch(true, holds, HoldResult.Status.SUCCESS);
        }
        static ReservationBatch failed(List<HoldSnapshot> holds, HoldResult.Status failure) {
            return new ReservationBatch(false, holds, failure);
        }
    }

    /**
     * Push the deadline out after real activity.
     *
     * <p>A session timeout is meant to clear abandoned windows, not to cut off
     * two people who are still arranging things.
     */
    private TradeResult renew(TradeSession trade, Instant until, String message) throws Exception {
        Optional<TradeSession> refreshed = repository.touch(trade.id(), until, Instant.now(clock));
        // Failing to push the deadline is not worth failing the action that just
        // succeeded: the worst case is the window closing a little sooner.
        return new TradeResult(TradeResult.Status.SUCCESS,
                refreshed.isPresent() ? refreshed : Optional.of(trade), message);
    }

    private static TradeResult notFound() {
        return new TradeResult(TradeResult.Status.NOT_FOUND, Optional.empty(), "No such trade");
    }

    private static TradeResult conflict(Optional<TradeSession> trade, String message) {
        return new TradeResult(TradeResult.Status.CONFLICT, trade, message);
    }

    private CompletableFuture<TradeResult> operation(CheckedSupplier<TradeResult> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> new TradeResult(TradeResult.Status.UNAVAILABLE,
                Optional.empty(), "Trade storage unavailable"));
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
