package ovh.aurumgg.core.engine;

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
 * Both sides may be offering money. Two plain transfers can half-succeed, and
 * unwinding the first one is a refund posting nobody asked for. Reserving both
 * first turns "can this trade pay for itself" into a question answered before
 * anything moves — which is exactly what a hold is.
 */
public final class TradeService {

    private final TradeRepository repository;
    private final MultiCurrencyEconomyService economy;
    private final Executor executor;
    private final Clock clock;
    private final Duration inviteTimeout;
    private final Duration sessionTimeout;

    public TradeService(TradeRepository repository, MultiCurrencyEconomyService economy,
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
            Optional<TradeSession> settling =
                    repository.transition(tradeId, TradeState.CONFIRMED, TradeState.SETTLING, true, now);
            if (settling.isEmpty()) {
                Optional<TradeSession> current = repository.find(tradeId);
                return current.isEmpty() ? notFound() : conflict(current, "The trade is not ready to settle");
            }
            TradeSession trade = settling.get();
            List<TradeOffer> offers = repository.offers(tradeId);

            List<HoldSnapshot> reserved = new java.util.ArrayList<>();
            for (TradeOffer offer : offers) {
                if (!offer.hasMoney()) continue;
                HoldResult held = reserve(trade, offer, now);
                if (held.status() != HoldResult.Status.SUCCESS || held.hold().isEmpty()) {
                    // Nothing has moved yet. Let the other reservation go and put
                    // the trade back on the table.
                    reserved.forEach(hold -> economy.releaseHold(hold.id()));
                    repository.transition(tradeId, TradeState.SETTLING, TradeState.OPEN, false, now);
                    return new TradeResult(TradeResult.Status.INSUFFICIENT_FUNDS, repository.find(tradeId),
                            "A side could not cover its offer");
                }
                reserved.add(held.hold().orElseThrow());
            }

            for (HoldSnapshot hold : reserved) {
                // Capture cannot fail for lack of money: that is what the
                // reservation just settled. Anything else is a storage problem,
                // and the hold's own recovery finishes it.
                economy.captureHold(hold.id(), new TransactionRequest(
                        "trade-capture:" + hold.idempotencyKey(), hold.from(), hold.to(),
                        hold.currency().id(), hold.amount(), hold.category(), hold.metadata()));
            }
            return new TradeResult(TradeResult.Status.SUCCESS, Optional.of(trade), "Money settled");
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

    private HoldResult reserve(TradeSession trade, TradeOffer offer, Instant now) {
        String currency = offer.currencyId().orElseThrow();
        HoldRequest request = new HoldRequest(
                "trade:" + trade.id() + ":" + offer.owner(),
                AccountId.player(offer.owner()),
                AccountId.player(trade.other(offer.owner())),
                currency, offer.money(), TransactionCategory.TRADE_SETTLEMENT, "trade",
                trade.id().toString(),
                // Short: this reservation exists only for the moment between
                // "can both sides pay" and "both have paid".
                now.plusSeconds(60),
                Map.of("trade", trade.id().toString(), "revision", Long.toString(trade.revision())));
        return economy.createHold(request).toCompletableFuture().join();
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
