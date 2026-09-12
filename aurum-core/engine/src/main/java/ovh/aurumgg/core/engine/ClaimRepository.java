package ovh.aurumgg.core.engine;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.api.ClaimStatus;

/**
 * Durable store of promised deliveries.
 *
 * <p>Every mutation here is conditional on the state the caller believes the
 * claim is in. That is the whole contract: two servers, or one server twice,
 * must not be able to deliver the same claim, and the loser must be told so
 * rather than silently succeeding.
 */
public interface ClaimRepository {

    /** Insert, or return the existing claim when the idempotency key is taken. */
    ClaimSnapshot promise(ClaimRequest request, UUID id, Instant now) throws SQLException;

    Optional<ClaimSnapshot> find(UUID id) throws SQLException;

    /** PENDING claims plus CLAIMED ones whose lease has expired, oldest first. */
    List<ClaimSnapshot> owed(UUID owner, String plugin, Instant now) throws SQLException;

    List<ClaimSnapshot> byStatus(ClaimStatus status, String plugin, int limit) throws SQLException;

    /**
     * Lease the claim. Succeeds only from PENDING, or from CLAIMED once the
     * previous lease has run out — that second case is exactly how a crashed
     * worker's claim comes back into circulation.
     *
     * @return empty when another worker holds it or the claim is already finished
     */
    Optional<ClaimSnapshot> take(UUID id, String worker, Duration lease, Instant now) throws SQLException;

    /**
     * Record progress. Only moves the cursor forward and only while this worker
     * still holds the lease.
     */
    Optional<ClaimSnapshot> advance(UUID id, String worker, int completedSteps, Duration lease, Instant now)
            throws SQLException;

    /**
     * Leave the lease for a terminal or waiting state.
     *
     * @param worker      lease holder; null lets an administrator act on a claim
     *                    nobody holds
     * @param countAttempt true when giving the claim back unfinished
     */
    Optional<ClaimSnapshot> finish(UUID id, String worker, ClaimStatus status, String reason,
                                   boolean countAttempt, Instant now) throws SQLException;

    /** Administrator path: back into the queue with attempts reset. */
    Optional<ClaimSnapshot> requeue(UUID id, Instant now) throws SQLException;
}
