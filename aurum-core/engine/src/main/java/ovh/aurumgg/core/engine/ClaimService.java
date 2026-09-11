package ovh.aurumgg.core.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import ovh.aurumgg.core.api.AurumClaimApi;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimResult;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.api.ClaimStatus;

/**
 * Delivery claims, off the Paper thread.
 *
 * <p>The repository owns the races; this owns the two judgement calls that are
 * policy rather than storage: how long a lease may run, and how many failed
 * attempts mean "stop trying and fetch a human".
 */
public final class ClaimService implements AurumClaimApi {

    private final ClaimRepository repository;
    private final Executor executor;
    private final Clock clock;
    private final Duration maxLease;
    private final int maxAttempts;

    public ClaimService(ClaimRepository repository, Executor executor, Clock clock,
                        Duration maxLease, int maxAttempts) {
        this.repository = repository;
        this.executor = executor;
        this.clock = clock;
        this.maxLease = maxLease;
        this.maxAttempts = maxAttempts;
        if (maxLease.isZero() || maxLease.isNegative()) {
            throw new IllegalArgumentException("maxLease must be positive");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
    }

    @Override
    public CompletionStage<ClaimResult> promise(ClaimRequest request) {
        return operation(() -> {
            Instant now = Instant.now(clock);
            UUID id = UUID.randomUUID();
            ClaimSnapshot stored = repository.promise(request, id, now);
            // The id coming back differs from the one we generated exactly when
            // the key was already promised — that is the duplicate, and it is a
            // success for the caller, not an error.
            boolean fresh = stored.id().equals(id);
            return new ClaimResult(fresh ? ClaimResult.Status.SUCCESS : ClaimResult.Status.DUPLICATE,
                    Optional.of(stored), fresh ? "Claim recorded" : "Existing claim");
        });
    }

    @Override
    public CompletionStage<List<ClaimSnapshot>> owed(UUID owner, String plugin) {
        return listing(() -> repository.owed(owner, plugin, Instant.now(clock)));
    }

    @Override
    public CompletionStage<ClaimResult> take(UUID claimId, String worker, Duration lease) {
        return operation(() -> {
            // A lease longer than the maximum would mean a crashed worker locks
            // the player's goods away for as long as it asked for.
            Duration capped = lease == null || lease.isZero() || lease.isNegative()
                    || lease.compareTo(maxLease) > 0 ? maxLease : lease;
            return resolve(repository.take(claimId, worker, capped, Instant.now(clock)), claimId,
                    "Claim leased", "Claim is held by someone else or already finished");
        });
    }

    @Override
    public CompletionStage<ClaimResult> advance(UUID claimId, String worker, int completedSteps) {
        return operation(() -> resolve(
                repository.advance(claimId, worker, completedSteps, Instant.now(clock)), claimId,
                "Progress recorded", "Lease expired or belongs to another worker"));
    }

    @Override
    public CompletionStage<ClaimResult> settle(UUID claimId, String worker) {
        return operation(() -> resolve(
                repository.finish(claimId, worker, ClaimStatus.SETTLED, "", false, Instant.now(clock)),
                claimId, "Claim settled", "Claim is not open"));
    }

    /**
     * Give a claim back unfinished.
     *
     * <p>Deferring counts an attempt, and at the limit the claim quarantines
     * itself. Retrying for ever would be the wrong kindness: a delivery that
     * keeps failing is a delivery that needs a person to look at it, and in the
     * meantime it would spam the log on every login.
     */
    @Override
    public CompletionStage<ClaimResult> defer(UUID claimId, String worker, String reason) {
        return operation(() -> {
            Instant now = Instant.now(clock);
            Optional<ClaimSnapshot> current = repository.find(claimId);
            boolean exhausted = current.isPresent() && current.get().attempts() + 1 >= maxAttempts;
            ClaimStatus next = exhausted ? ClaimStatus.QUARANTINED : ClaimStatus.PENDING;
            String note = exhausted
                    ? reason + " (quarantined after " + maxAttempts + " attempts)"
                    : reason;
            return resolve(repository.finish(claimId, worker, next, note, true, now), claimId,
                    exhausted ? "Claim quarantined" : "Claim returned to the queue",
                    "Claim is not open");
        });
    }

    @Override
    public CompletionStage<ClaimResult> quarantine(UUID claimId, String worker, String reason) {
        return operation(() -> resolve(
                repository.finish(claimId, worker, ClaimStatus.QUARANTINED, reason, true, Instant.now(clock)),
                claimId, "Claim quarantined", "Claim is not open"));
    }

    /**
     * One claim by id, whatever state it is in.
     *
     * <p>Engine-side only: a plugin has no business reading a claim it does not
     * own, while an administrator inspecting quarantine needs to see any of them.
     */
    public CompletionStage<Optional<ClaimSnapshot>> inspect(UUID claimId) {
        return CompletableFuture.supplyAsync(() -> {
            try { return repository.find(claimId); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> Optional.empty());
    }

    @Override
    public CompletionStage<List<ClaimSnapshot>> quarantined(String plugin, int limit) {
        return listing(() -> repository.byStatus(ClaimStatus.QUARANTINED, plugin, limit));
    }

    @Override
    public CompletionStage<ClaimResult> requeue(UUID claimId) {
        return operation(() -> resolve(repository.requeue(claimId, Instant.now(clock)), claimId,
                "Claim back in the queue", "Claim is not quarantined"));
    }

    @Override
    public CompletionStage<ClaimResult> drop(UUID claimId, String reason) {
        return operation(() -> resolve(
                repository.finish(claimId, null, ClaimStatus.DROPPED, reason, false, Instant.now(clock)),
                claimId, "Claim dropped", "Claim is not open"));
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Turn "the conditional UPDATE changed nothing" into an answer the caller
     * can act on: a claim that is simply gone is NOT_FOUND, one that exists but
     * refused the change is CONFLICT. Collapsing both into one status would
     * leave a retry loop unable to tell "never existed" from "someone else has it".
     */
    private ClaimResult resolve(Optional<ClaimSnapshot> updated, UUID claimId, String success,
                                String conflict) throws Exception {
        if (updated.isPresent()) {
            return new ClaimResult(ClaimResult.Status.SUCCESS, updated, success);
        }
        Optional<ClaimSnapshot> current = repository.find(claimId);
        return current.isPresent()
                ? new ClaimResult(ClaimResult.Status.CONFLICT, current, conflict)
                : new ClaimResult(ClaimResult.Status.NOT_FOUND, Optional.empty(), "No such claim");
    }

    private CompletableFuture<ClaimResult> operation(CheckedSupplier<ClaimResult> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> new ClaimResult(ClaimResult.Status.UNAVAILABLE,
                Optional.empty(), "Claim storage unavailable"));
    }

    private CompletableFuture<List<ClaimSnapshot>> listing(CheckedSupplier<List<ClaimSnapshot>> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, executor).exceptionally(exception -> List.of());
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
