package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.api.ClaimStatus;

/**
 * The delivery half of a cross-system operation.
 *
 * <p>The repository here is in memory and mirrors the conditions the SQL spells
 * out; what these tests pin down is the behaviour the conditions are FOR —
 * one winner per claim, a cursor that only moves forward, a lease that returns
 * a crashed worker's claim to the queue, and failures that eventually stop
 * retrying and fetch a person.
 */
class ClaimServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void promisingTheSameKeyTwiceOwesItOnce() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);

        ClaimResult first = service.promise(request("npc-shop:abc")).toCompletableFuture().join();
        ClaimResult second = service.promise(request("npc-shop:abc")).toCompletableFuture().join();

        assertEquals(ClaimResult.Status.SUCCESS, first.status());
        assertEquals(ClaimResult.Status.DUPLICATE, second.status());
        assertEquals(first.claim().orElseThrow().id(), second.claim().orElseThrow().id());
        assertEquals(1, claims.rows.size());
    }

    @Test
    void claimKeyCannotPromiseDifferentPayload() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);
        ClaimRequest original = request("npc-shop:fixed");
        ClaimRequest changed = new ClaimRequest(original.idempotencyKey(), original.plugin(),
                original.owner(), original.kind(), original.stepCount(), original.summary(),
                "{\"items\":[\"different\"]}");

        assertEquals(ClaimResult.Status.SUCCESS,
                service.promise(original).toCompletableFuture().join().status());
        assertEquals(ClaimResult.Status.CONFLICT,
                service.promise(changed).toCompletableFuture().join().status());
        assertEquals(1, claims.rows.size());
    }

    @Test
    void onlyOneWorkerCanHoldAClaim() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);
        UUID id = promised(service);

        assertEquals(ClaimResult.Status.SUCCESS,
                service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join().status());
        // The loser is told so. Silently succeeding here would hand the same
        // goods out twice.
        assertEquals(ClaimResult.Status.CONFLICT,
                service.take(id, "server-b", Duration.ofMinutes(1)).toCompletableFuture().join().status());
    }

    @Test
    void anExpiredLeaseReturnsTheClaimToTheQueue() {
        MemoryClaims claims = new MemoryClaims();
        MutableClock clock = new MutableClock(NOW);
        ClaimService service = new ClaimService(claims, Runnable::run, clock, Duration.ofMinutes(5), 3);
        UUID id = promised(service);

        service.take(id, "crashed-server", Duration.ofMinutes(1)).toCompletableFuture().join();
        service.advance(id, "crashed-server", 1).toCompletableFuture().join();

        // The worker never came back. Without the lease running out, the
        // player's goods would be locked away for good.
        clock.advance(Duration.ofMinutes(10));
        List<ClaimSnapshot> owed = service.owed(PLAYER, "AddonsNPC").toCompletableFuture().join();
        assertEquals(1, owed.size());

        assertEquals(ClaimResult.Status.SUCCESS,
                service.take(id, "fresh-server", Duration.ofMinutes(1)).toCompletableFuture().join().status());
        // And the steps that already took effect are not repeated.
        assertEquals(1, claims.rows.get(id).stepCursor());
    }

    @Test
    void progressOnlyMovesForwardAndOnlyFromTheLeaseHolder() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);
        UUID id = promised(service);
        service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();

        service.advance(id, "server-a", 2).toCompletableFuture().join();
        // A late reply from a worker that lost the lease changes nothing.
        assertEquals(ClaimResult.Status.CONFLICT,
                service.advance(id, "server-b", 3).toCompletableFuture().join().status());
        // Nor does a stale report from the holder itself.
        service.advance(id, "server-a", 1).toCompletableFuture().join();
        assertEquals(2, claims.rows.get(id).stepCursor());

        assertEquals(ClaimResult.Status.CONFLICT,
                service.settle(id, "server-a").toCompletableFuture().join().status(),
                "a partial delivery cannot be declared settled");
        service.advance(id, "server-a", 3).toCompletableFuture().join();
        service.settle(id, "server-a").toCompletableFuture().join();
        assertEquals(ClaimStatus.SETTLED, claims.rows.get(id).status());
        assertTrue(claims.rows.get(id).complete());
    }

    @Test
    void enoughFailedAttemptsQuarantineTheClaim() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = new ClaimService(claims, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), 3);
        UUID id = promised(service);

        for (int attempt = 1; attempt <= 2; attempt++) {
            service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();
            service.defer(id, "server-a", "inventory full").toCompletableFuture().join();
            assertEquals(ClaimStatus.PENDING, claims.rows.get(id).status());
        }
        service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();
        service.defer(id, "server-a", "inventory full").toCompletableFuture().join();

        // Retrying for ever would spam every login instead of getting a person
        // to look at it.
        assertEquals(ClaimStatus.QUARANTINED, claims.rows.get(id).status());
        assertTrue(claims.rows.get(id).lastError().contains("quarantined after 3 attempts"));
        assertTrue(service.owed(PLAYER, "AddonsNPC").toCompletableFuture().join().isEmpty());
        assertEquals(1, service.quarantined("AddonsNPC", 10).toCompletableFuture().join().size());
    }

    @Test
    void neutralPauseDoesNotSpendAnAttempt() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);
        UUID id = promised(service);
        service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();

        assertEquals(ClaimResult.Status.SUCCESS,
                service.pause(id, "server-a", "player left").toCompletableFuture().join().status());
        assertEquals(ClaimStatus.PENDING, claims.rows.get(id).status());
        assertEquals(0, claims.rows.get(id).attempts());
    }

    @Test
    void expiredWorkerAndAdministratorCannotStealActiveLease() {
        MemoryClaims claims = new MemoryClaims();
        MutableClock clock = new MutableClock(NOW);
        ClaimService service = new ClaimService(claims, Runnable::run, clock, Duration.ofMinutes(5), 3);
        UUID id = promised(service);
        service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();

        assertEquals(ClaimResult.Status.CONFLICT,
                service.drop(id, "admin raced delivery").toCompletableFuture().join().status());
        clock.advance(Duration.ofMinutes(2));
        assertEquals(ClaimResult.Status.CONFLICT,
                service.defer(id, "server-a", "late result").toCompletableFuture().join().status());
        assertEquals(ClaimStatus.CLAIMED, claims.rows.get(id).status());
    }

    @Test
    void anAdministratorCanPutAQuarantinedClaimBack() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);
        UUID id = promised(service);
        service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();
        service.quarantine(id, "server-a", "unknown item").toCompletableFuture().join();

        assertEquals(ClaimResult.Status.SUCCESS, service.requeue(id).toCompletableFuture().join().status());
        assertEquals(ClaimStatus.PENDING, claims.rows.get(id).status());
        assertEquals(0, claims.rows.get(id).attempts(), "счётчик попыток сбрасывается вместе с разбором");
        assertEquals(1, service.owed(PLAYER, "AddonsNPC").toCompletableFuture().join().size());
    }

    @Test
    void aSettledClaimIsNeverReopened() {
        MemoryClaims claims = new MemoryClaims();
        ClaimService service = service(claims, NOW);
        UUID id = promised(service);
        service.take(id, "server-a", Duration.ofMinutes(1)).toCompletableFuture().join();
        service.advance(id, "server-a", 3).toCompletableFuture().join();
        service.settle(id, "server-a").toCompletableFuture().join();

        assertEquals(ClaimResult.Status.CONFLICT,
                service.take(id, "server-b", Duration.ofMinutes(1)).toCompletableFuture().join().status());
        assertEquals(ClaimResult.Status.CONFLICT,
                service.defer(id, null, "late").toCompletableFuture().join().status());
        assertEquals(ClaimResult.Status.NOT_FOUND,
                service.settle(UUID.randomUUID(), null).toCompletableFuture().join().status());
        assertFalse(claims.rows.get(id).status() != ClaimStatus.SETTLED);
    }

    @Test
    void aLeaseCannotOutlastTheConfiguredMaximum() {
        MemoryClaims claims = new MemoryClaims();
        MutableClock clock = new MutableClock(NOW);
        ClaimService service = new ClaimService(claims, Runnable::run, clock, Duration.ofMinutes(2), 3);
        UUID id = promised(service);

        // A worker asking for an hour would otherwise lock the claim away for an
        // hour after it died.
        service.take(id, "greedy", Duration.ofHours(1)).toCompletableFuture().join();
        clock.advance(Duration.ofMinutes(3));
        assertEquals(ClaimResult.Status.SUCCESS,
                service.take(id, "next", Duration.ofMinutes(1)).toCompletableFuture().join().status());
    }

    // ------------------------------------------------------------- fixtures

    private static ClaimService service(MemoryClaims claims, Instant now) {
        return new ClaimService(claims, Runnable::run, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(5), 3);
    }

    private static UUID promised(ClaimService service) {
        return service.promise(request("npc-shop:" + UUID.randomUUID())).toCompletableFuture().join()
                .claim().orElseThrow().id();
    }

    private static ClaimRequest request(String key) {
        return new ClaimRequest(key, "AddonsNPC", PLAYER, "SHOP_PURCHASE", 3,
                "3x diamond and 2 commands", "{\"items\":[]}");
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Mirrors the conditions of {@code MariaDbClaimRepository}, without a database. */
    private static final class MemoryClaims implements ClaimRepository {
        private final Map<UUID, ClaimSnapshot> rows = new LinkedHashMap<>();
        private final Map<String, UUID> byKey = new LinkedHashMap<>();

        @Override
        public ClaimSnapshot promise(ClaimRequest request, UUID id, Instant now) {
            UUID existing = byKey.get(request.idempotencyKey());
            if (existing != null) return rows.get(existing);
            ClaimSnapshot claim = new ClaimSnapshot(id, request.idempotencyKey(), request.plugin(),
                    request.owner(), request.kind(), ClaimStatus.PENDING, 0, request.stepCount(), 0,
                    request.summary(), request.payload(), "", Optional.empty(), Optional.empty(), now, now);
            rows.put(id, claim);
            byKey.put(request.idempotencyKey(), id);
            return claim;
        }

        @Override public Optional<ClaimSnapshot> find(UUID id) { return Optional.ofNullable(rows.get(id)); }

        @Override
        public List<ClaimSnapshot> owed(UUID owner, String plugin, Instant now) {
            List<ClaimSnapshot> result = new ArrayList<>();
            for (ClaimSnapshot claim : rows.values()) {
                if (!claim.plugin().equals(plugin) || !claim.owner().equals(owner)) continue;
                if (claim.status() == ClaimStatus.PENDING || (claim.status() == ClaimStatus.CLAIMED
                        && claim.leaseUntil().map(until -> until.isBefore(now)).orElse(true))) {
                    result.add(claim);
                }
            }
            result.sort(Comparator.comparing(ClaimSnapshot::createdAt));
            return result;
        }

        @Override
        public List<ClaimSnapshot> byStatus(ClaimStatus status, String plugin, int limit) {
            return rows.values().stream()
                    .filter(claim -> claim.status() == status)
                    .filter(claim -> plugin == null || plugin.isBlank() || claim.plugin().equals(plugin))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<ClaimSnapshot> take(UUID id, String worker, Duration lease, Instant now) {
            ClaimSnapshot claim = rows.get(id);
            if (claim == null) return Optional.empty();
            boolean free = claim.status() == ClaimStatus.PENDING
                    || (claim.status() == ClaimStatus.CLAIMED
                        && claim.leaseUntil().map(until -> until.isBefore(now)).orElse(true));
            if (!free) return Optional.empty();
            return Optional.of(put(with(claim, ClaimStatus.CLAIMED, claim.stepCursor(), claim.attempts(),
                    claim.lastError(), Optional.of(worker), Optional.of(now.plus(lease)), now)));
        }

        @Override
        public Optional<ClaimSnapshot> advance(UUID id, String worker, int completedSteps,
                                               Duration lease, Instant now) {
            ClaimSnapshot claim = rows.get(id);
            if (claim == null || claim.status() != ClaimStatus.CLAIMED
                    || !claim.claimedBy().map(worker::equals).orElse(false)
                    || claim.leaseUntil().map(until -> until.isBefore(now)).orElse(true)) {
                return Optional.empty();
            }
            int cursor = Math.min(claim.stepCount(), Math.max(claim.stepCursor(), completedSteps));
            return Optional.of(put(with(claim, claim.status(), cursor, claim.attempts(), claim.lastError(),
                    claim.claimedBy(), Optional.of(now.plus(lease)), now)));
        }

        @Override
        public Optional<ClaimSnapshot> finish(UUID id, String worker, ClaimStatus status, String reason,
                                              boolean countAttempt, Instant now) {
            ClaimSnapshot claim = rows.get(id);
            if (claim == null) return Optional.empty();
            if (worker == null) {
                if (claim.status() != ClaimStatus.PENDING && claim.status() != ClaimStatus.QUARANTINED) {
                    return Optional.empty();
                }
            } else if (claim.status() != ClaimStatus.CLAIMED
                    || !claim.claimedBy().map(worker::equals).orElse(false)
                    || claim.leaseUntil().map(until -> until.isBefore(now)).orElse(true)) {
                return Optional.empty();
            }
            if (status == ClaimStatus.SETTLED && !claim.complete()) return Optional.empty();
            int cursor = status == ClaimStatus.SETTLED ? claim.stepCount() : claim.stepCursor();
            return Optional.of(put(with(claim, status, cursor,
                    claim.attempts() + (countAttempt ? 1 : 0), reason == null ? "" : reason,
                    Optional.empty(), Optional.empty(), now)));
        }

        @Override
        public Optional<ClaimSnapshot> requeue(UUID id, Instant now) {
            ClaimSnapshot claim = rows.get(id);
            if (claim == null || claim.status() != ClaimStatus.QUARANTINED) return Optional.empty();
            return Optional.of(put(with(claim, ClaimStatus.PENDING, claim.stepCursor(), 0, "",
                    Optional.empty(), Optional.empty(), now)));
        }

        private ClaimSnapshot put(ClaimSnapshot claim) {
            rows.put(claim.id(), claim);
            return claim;
        }

        private static ClaimSnapshot with(ClaimSnapshot claim, ClaimStatus status, int cursor, int attempts,
                                          String error, Optional<String> worker,
                                          Optional<Instant> lease, Instant now) {
            return new ClaimSnapshot(claim.id(), claim.idempotencyKey(), claim.plugin(), claim.owner(),
                    claim.kind(), status, cursor, claim.stepCount(), attempts, claim.summary(),
                    claim.payload(), error, worker, lease, claim.createdAt(), now);
        }
    }
}
