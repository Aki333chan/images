package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AurumEconomyApi;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyMode;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.api.HoldRequest;
import ovh.aurumgg.core.api.HoldResult;
import ovh.aurumgg.core.api.HoldSnapshot;
import ovh.aurumgg.core.api.TradeOffer;
import ovh.aurumgg.core.api.TradeResult;
import ovh.aurumgg.core.api.TradeSession;
import ovh.aurumgg.core.api.TradeState;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

/**
 * The one property a trade window has to have: a player is bound to the table
 * they were looking at, and to nothing else.
 *
 * <p>The repository here is in memory and mirrors the conditions the SQL spells
 * out. What these tests pin down is the behaviour those conditions are FOR —
 * one trade per player, every edit invalidating both confirmations, and a
 * settle that cannot be reached from a stale read.
 */
class TradeServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BORIS = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CLARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void одинИгрокНеМожетВестиДвеСделкиСразу() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);

        assertTrue(service.invite(ANNA, BORIS).toCompletableFuture().join().ok());
        // Иначе один и тот же набор предметов можно предложить в двух окнах, и
        // вторая сделка расплачивалась бы тем, чего уже нет.
        assertEquals(TradeResult.Status.BUSY,
                service.invite(ANNA, CLARA).toCompletableFuture().join().status());
        assertEquals(TradeResult.Status.BUSY,
                service.invite(CLARA, BORIS).toCompletableFuture().join().status());
    }

    @Test
    void приниматьПриглашениеМожетТолькоПриглашённый() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = invited(service);

        assertEquals(TradeResult.Status.CONFLICT,
                service.accept(id, ANNA).toCompletableFuture().join().status());
        assertTrue(service.accept(id, BORIS).toCompletableFuture().join().ok());
        assertEquals(TradeState.OPEN, trades.rows.get(id).state());
    }

    @Test
    void любаяПравкаОфертыСбрасываетОбаПодтверждения() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = open(service);

        long revision = trades.rows.get(id).revision();
        assertTrue(service.confirm(id, ANNA, revision).toCompletableFuture().join().ok());
        assertTrue(service.confirm(id, BORIS, revision).toCompletableFuture().join().ok());
        assertTrue(trades.rows.get(id).ready());

        // Борис подменяет золото на землю уже после того, как Анна посмотрела.
        service.offer(id, offer(id, BORIS, "0")).toCompletableFuture().join();

        assertFalse(trades.rows.get(id).ready(), "оба подтверждения обнулены");
        assertFalse(trades.rows.get(id).confirmed(ANNA));
        assertFalse(trades.rows.get(id).confirmed(BORIS));
    }

    @Test
    void подтверждениеСтаройРевизииОтклоняется() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = open(service);
        long seen = trades.rows.get(id).revision();

        service.offer(id, offer(id, BORIS, "5")).toCompletableFuture().join();

        // Клик Анны пришёл уже после подмены. Отказ — и есть защита.
        TradeResult stale = service.confirm(id, ANNA, seen).toCompletableFuture().join();
        assertEquals(TradeResult.Status.CONFLICT, stale.status());
        assertFalse(trades.rows.get(id).confirmed(ANNA));
    }

    @Test
    void повторКвитанцииНеДобавляетПредметИНеМеняетРевизиюДважды() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = open(service);
        long before = trades.rows.get(id).revision();
        TradeOffer item = new TradeOffer(id, ANNA, Optional.of("coins"), BigDecimal.TEN,
                new byte[] {1, 2, 3}, 1);

        assertTrue(service.offerIdempotent("receipt-1", id, item).toCompletableFuture().join().ok());
        assertTrue(service.offerIdempotent("receipt-1", id, item).toCompletableFuture().join().ok());

        assertEquals(before + 1, trades.rows.get(id).revision());
        assertEquals(3, trades.offers.get(id).get(ANNA).items().length);
    }

    @Test
    void однуКвитанциюНельзяПереиспользоватьДляДругойОферты() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = open(service);

        assertTrue(service.offerIdempotent("receipt-1", id, offer(id, ANNA, "11"))
                .toCompletableFuture().join().ok());
        TradeResult collision = service.offerIdempotent("receipt-1", id, offer(id, ANNA, "12"))
                .toCompletableFuture().join();

        assertEquals(TradeResult.Status.UNAVAILABLE, collision.status());
        assertEquals(new BigDecimal("11"), trades.offers.get(id).get(ANNA).money());
    }

    @Test
    void сделкаНеЗакрываетсяПокаНеПодтвердилиОба() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = open(service);
        long revision = trades.rows.get(id).revision();

        service.confirm(id, ANNA, revision).toCompletableFuture().join();
        assertEquals(TradeResult.Status.CONFLICT, service.lock(id).toCompletableFuture().join().status());

        service.confirm(id, BORIS, revision).toCompletableFuture().join();
        assertTrue(service.lock(id).toCompletableFuture().join().ok());
        assertEquals(TradeState.CONFIRMED, trades.rows.get(id).state());
    }

    @Test
    void закрытуюСделкуНельзяПравить() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = locked(service, trades);

        assertEquals(TradeResult.Status.CONFLICT,
                service.offer(id, offer(id, ANNA, "99")).toCompletableFuture().join().status());
        assertEquals(TradeResult.Status.CONFLICT,
                service.confirm(id, ANNA, trades.rows.get(id).revision()).toCompletableFuture().join().status());
    }

    @Test
    void отменаВозможнаДоРасчётаИНеПослеНего() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = locked(service, trades);

        assertTrue(service.cancel(id, "передумал").toCompletableFuture().join().ok());
        assertEquals(TradeState.CANCELLED, trades.rows.get(id).state());
        // Повтор честно отвечает «поздно», а не делает вид, что сработал.
        assertEquals(TradeResult.Status.CONFLICT,
                service.cancel(id, "ещё раз").toCompletableFuture().join().status());
    }

    @Test
    void отменённаяСделкаНеМешаетНачатьНовую() {
        MemoryTrades trades = new MemoryTrades();
        TradeService service = service(trades);
        UUID id = invited(service);
        service.cancel(id, "передумал").toCompletableFuture().join();

        assertTrue(service.invite(ANNA, CLARA).toCompletableFuture().join().ok());
    }

    @Test
    void просроченныеСделкиНаходятся() {
        MemoryTrades trades = new MemoryTrades();
        MutableClock clock = new MutableClock(NOW);
        TradeService service = new TradeService(trades, null, Runnable::run, clock,
                Duration.ofSeconds(30), Duration.ofMinutes(5));
        service.invite(ANNA, BORIS).toCompletableFuture().join();

        assertTrue(service.timedOut(10).toCompletableFuture().join().isEmpty());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(1, service.timedOut(10).toCompletableFuture().join().size());
    }

    @Test
    void расчётНеБлокируетОбщийОднопоточныйExecutor() throws Exception {
        MemoryTrades trades = new MemoryTrades();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AsyncEconomy economy = new AsyncEconomy(executor, false);
            TradeService service = new TradeService(trades, economy, executor,
                    Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), Duration.ofMinutes(5));
            UUID id = locked(service, trades);

            TradeResult result = service.settleMoney(id).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(result.ok());
            assertEquals(TradeState.SETTLING, trades.rows.get(id).state());
            assertEquals(1, economy.captures);

            // A restart retries the same deterministic hold and observes it as
            // already captured instead of charging the player a second time.
            assertTrue(service.settleMoney(id).toCompletableFuture().get(2, TimeUnit.SECONDS).ok());
            assertEquals(1, economy.captures);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void нехваткаСредствОткрываетНовуюРевизиюБезПодтверждений() throws Exception {
        MemoryTrades trades = new MemoryTrades();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TradeService service = new TradeService(trades, new AsyncEconomy(executor, true), executor,
                    Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), Duration.ofMinutes(5));
            UUID id = locked(service, trades);
            long confirmedRevision = trades.rows.get(id).revision();

            TradeResult result = service.settleMoney(id).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(TradeResult.Status.INSUFFICIENT_FUNDS, result.status());
            assertEquals(TradeState.OPEN, trades.rows.get(id).state());
            assertEquals(confirmedRevision + 1, trades.rows.get(id).revision());
            assertFalse(trades.rows.get(id).ready());
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------- fixtures

    private static TradeService service(MemoryTrades trades) {
        // Экономика здесь не нужна: расчёт денег проверяется отдельно, а всё
        // остальное в машине состояний до неё не доходит.
        return new TradeService(trades, null, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    private static UUID invited(TradeService service) {
        return service.invite(ANNA, BORIS).toCompletableFuture().join().trade().orElseThrow().id();
    }

    private static UUID open(TradeService service) {
        UUID id = invited(service);
        service.accept(id, BORIS).toCompletableFuture().join();
        service.offer(id, offer(id, ANNA, "10")).toCompletableFuture().join();
        return id;
    }

    private static UUID locked(TradeService service, MemoryTrades trades) {
        UUID id = open(service);
        long revision = trades.rows.get(id).revision();
        service.confirm(id, ANNA, revision).toCompletableFuture().join();
        service.confirm(id, BORIS, revision).toCompletableFuture().join();
        service.lock(id).toCompletableFuture().join();
        return id;
    }

    private static TradeOffer offer(UUID tradeId, UUID owner, String money) {
        BigDecimal amount = new BigDecimal(money);
        return new TradeOffer(tradeId, owner,
                amount.signum() > 0 ? Optional.of("coins") : Optional.empty(), amount, null, 1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Mirrors the conditions of {@code MariaDbTradeRepository}, without a database. */
    private static final class MemoryTrades implements TradeRepository {
        private final Map<UUID, TradeSession> rows = new LinkedHashMap<>();
        private final Map<UUID, Map<UUID, TradeOffer>> offers = new LinkedHashMap<>();
        private final Map<String, String> offerOperations = new LinkedHashMap<>();
        private final Map<String, UUID> offerOperationOwners = new LinkedHashMap<>();

        @Override
        public Optional<TradeSession> open(UUID id, UUID first, UUID second, Instant expiresAt, Instant now) {
            if (busy(first) || busy(second)) return Optional.empty();
            TradeSession trade = new TradeSession(id, first, second, TradeState.INVITED, 0,
                    OptionalLong.empty(), OptionalLong.empty(), expiresAt, now, now);
            rows.put(id, trade);
            return Optional.of(trade);
        }

        private boolean busy(UUID player) {
            return rows.values().stream()
                    .anyMatch(trade -> !trade.state().finished() && trade.involves(player));
        }

        @Override public Optional<TradeSession> find(UUID tradeId) {
            return Optional.ofNullable(rows.get(tradeId));
        }

        @Override public Optional<TradeSession> activeFor(UUID player) {
            return rows.values().stream()
                    .filter(trade -> !trade.state().finished() && trade.involves(player))
                    .reduce((left, right) -> right);
        }

        @Override public List<TradeOffer> offers(UUID tradeId) {
            return new ArrayList<>(offers.getOrDefault(tradeId, Map.of()).values());
        }

        @Override
        public Optional<TradeSession> offer(UUID tradeId, TradeOffer offer, Instant now) {
            TradeSession trade = rows.get(tradeId);
            if (trade == null || !trade.state().editable() || !trade.involves(offer.owner())) {
                return Optional.empty();
            }
            offers.computeIfAbsent(tradeId, key -> new LinkedHashMap<>()).put(offer.owner(), offer);
            TradeSession bumped = new TradeSession(trade.id(), trade.first(), trade.second(),
                    trade.state(), trade.revision() + 1, OptionalLong.empty(), OptionalLong.empty(),
                    trade.expiresAt(), trade.createdAt(), now);
            rows.put(tradeId, bumped);
            return Optional.of(bumped);
        }

        @Override
        public Optional<OfferWrite> offerIdempotent(
                String operationKey, UUID tradeId, TradeOffer offer, Instant now) {
            String intent = tradeId + ":" + offer.owner() + ":" + offer.currencyId()
                    + ":" + offer.money().toPlainString() + ":" + offer.itemsFormatVersion()
                    + ":" + java.util.Arrays.hashCode(offer.items());
            String existing = offerOperations.get(operationKey);
            if (existing != null) {
                if (!existing.equals(intent)) throw new IllegalStateException("operation key collision");
                TradeSession current = rows.get(tradeId);
                if (current == null) throw new IllegalStateException("trade disappeared");
                return Optional.of(new OfferWrite(current, true));
            }
            Optional<TradeSession> written = offer(tradeId, offer, now);
            written.ifPresent(ignored -> {
                offerOperationOwners.entrySet().removeIf(entry -> entry.getValue().equals(offer.owner())
                        && !entry.getKey().equals(operationKey));
                offerOperations.keySet().retainAll(offerOperationOwners.keySet());
                offerOperations.put(operationKey, intent);
                offerOperationOwners.put(operationKey, offer.owner());
            });
            return written.map(trade -> new OfferWrite(trade, false));
        }

        @Override
        public Optional<TradeSession> confirm(UUID tradeId, UUID owner, long revision, Instant now) {
            TradeSession trade = rows.get(tradeId);
            if (trade == null || trade.state() != TradeState.OPEN || trade.revision() != revision
                    || !trade.involves(owner)) {
                return Optional.empty();
            }
            boolean isFirst = trade.first().equals(owner);
            TradeSession confirmed = new TradeSession(trade.id(), trade.first(), trade.second(),
                    trade.state(), trade.revision(),
                    isFirst ? OptionalLong.of(revision) : trade.firstConfirmedRevision(),
                    isFirst ? trade.secondConfirmedRevision() : OptionalLong.of(revision),
                    trade.expiresAt(), trade.createdAt(), now);
            rows.put(tradeId, confirmed);
            return Optional.of(confirmed);
        }

        @Override
        public Optional<TradeSession> transition(UUID tradeId, TradeState from, TradeState to,
                                                 boolean requireReady, Instant now) {
            TradeSession trade = rows.get(tradeId);
            if (trade == null || trade.state() != from) return Optional.empty();
            if (requireReady && !trade.ready()) return Optional.empty();
            TradeSession moved = new TradeSession(trade.id(), trade.first(), trade.second(), to,
                    trade.revision(), trade.firstConfirmedRevision(), trade.secondConfirmedRevision(),
                    trade.expiresAt(), trade.createdAt(), now);
            rows.put(tradeId, moved);
            return Optional.of(moved);
        }

        @Override
        public Optional<TradeSession> reopen(UUID tradeId, Instant now) {
            TradeSession trade = rows.get(tradeId);
            if (trade == null || trade.state() != TradeState.SETTLING) return Optional.empty();
            TradeSession reopened = new TradeSession(trade.id(), trade.first(), trade.second(),
                    TradeState.OPEN, trade.revision() + 1, OptionalLong.empty(), OptionalLong.empty(),
                    trade.expiresAt(), trade.createdAt(), now);
            rows.put(tradeId, reopened);
            return Optional.of(reopened);
        }

        @Override
        public Optional<TradeSession> touch(UUID tradeId, Instant expiresAt, Instant now) {
            TradeSession trade = rows.get(tradeId);
            if (trade == null || trade.state().finished()) return Optional.empty();
            TradeSession touched = new TradeSession(trade.id(), trade.first(), trade.second(),
                    trade.state(), trade.revision(), trade.firstConfirmedRevision(),
                    trade.secondConfirmedRevision(), expiresAt, trade.createdAt(), now);
            rows.put(tradeId, touched);
            return Optional.of(touched);
        }

        @Override
        public List<TradeSession> timedOut(Instant now, int limit) {
            return rows.values().stream()
                    .filter(trade -> !trade.state().finished() && trade.expiresAt().isBefore(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<TradeSession> settling(int limit) {
            return rows.values().stream()
                    .filter(trade -> trade.state() == TradeState.SETTLING)
                    .limit(limit)
                    .toList();
        }
    }

    /** Economy calls deliberately run on the same executor as TradeService. */
    private static final class AsyncEconomy implements AurumEconomyApi {
        private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
        private final ExecutorService executor;
        private final boolean insufficient;
        private HoldSnapshot hold;
        private int captures;

        private AsyncEconomy(ExecutorService executor, boolean insufficient) {
            this.executor = executor;
            this.insufficient = insufficient;
        }

        @Override public EconomyMode mode() { return EconomyMode.ACTIVE; }
        @Override public CurrencySpec primaryCurrency() { return COINS; }
        @Override public CompletionStage<Optional<BalanceSnapshot>> balance(AccountId account) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletionStage<GlobalEconomySnapshot> globalSnapshot() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public CompletionStage<TransactionResult> transfer(TransactionRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public CompletionStage<HoldResult> createHold(HoldRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                if (hold != null) return new HoldResult(HoldResult.Status.DUPLICATE,
                        Optional.of(hold), "Existing hold");
                HoldSnapshot.Status status = insufficient
                        ? HoldSnapshot.Status.REJECTED : HoldSnapshot.Status.HELD;
                hold = new HoldSnapshot(UUID.randomUUID(), request.idempotencyKey(), request.from(),
                        request.to(), COINS, request.amount(), request.amount(), request.category(),
                        request.purpose(), request.referenceId(), status, NOW, request.expiresAt(),
                        request.metadata());
                return new HoldResult(insufficient ? HoldResult.Status.INSUFFICIENT_FUNDS
                        : HoldResult.Status.SUCCESS, Optional.of(hold), "reserved");
            }, executor);
        }
        @Override public CompletionStage<HoldResult> captureHold(UUID holdId, TransactionRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                if (hold.status() == HoldSnapshot.Status.CAPTURED) {
                    return new HoldResult(HoldResult.Status.DUPLICATE, Optional.of(hold), "captured");
                }
                captures++;
                hold = new HoldSnapshot(hold.id(), hold.idempotencyKey(), hold.from(), hold.to(),
                        hold.currency(), hold.amount(), hold.reservedAmount(), hold.category(), hold.purpose(),
                        hold.referenceId(), HoldSnapshot.Status.CAPTURED, hold.createdAt(), hold.expiresAt(),
                        hold.metadata());
                return new HoldResult(HoldResult.Status.SUCCESS, Optional.of(hold), "captured");
            }, executor);
        }
    }
}
