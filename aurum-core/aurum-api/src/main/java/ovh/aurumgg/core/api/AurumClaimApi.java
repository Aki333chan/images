package ovh.aurumgg.core.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Durable delivery of what a player has already paid for.
 *
 * <h2>Why this is separate from holds</h2>
 *
 * A hold protects the money half of a cross-system operation: reserve, change
 * Minecraft state, capture. That leaves one window open, and it is the
 * expensive one. Between "items handed over" and "journal says so" the server
 * can die, and on restart the plugin sees a reservation it believes was never
 * applied — so it releases the money and the player keeps the goods for free.
 * Console commands are worse still: they run after capture and vanish entirely
 * if the process dies first, and re-running them blindly is not safe either.
 *
 * A claim closes that window by reversing the order. Money moves first, then a
 * claim records what is owed, and only then is anything handed over. Nothing is
 * ever owed without a durable record of it, and the record remembers how far
 * delivery got.
 *
 * <h2>The delivery loop</h2>
 *
 * <ol>
 *   <li>{@link #promise} — after the money is captured, write down the debt;</li>
 *   <li>{@link #take} — lease it, so two servers or two logins cannot deliver
 *       the same claim twice;</li>
 *   <li>perform step {@code stepCursor}, then {@link #advance} — repeat until
 *       {@link ClaimSnapshot#complete()};</li>
 *   <li>{@link #settle} — done, forever;</li>
 *   <li>{@link #defer} if it may work later (inventory full, player left), or
 *       {@link #quarantine} if it cannot.</li>
 * </ol>
 *
 * Crash anywhere and the lease simply expires: the claim returns to PENDING
 * with its cursor intact, and delivery resumes at the step that never ran.
 *
 * <h2>What Core does not know</h2>
 *
 * The payload is opaque. Core stores it, hands it back and never parses it —
 * items, commands and their encoding belong to the plugin. Core owns only the
 * three things a plugin cannot get right alone: the record survives a crash,
 * exactly one worker holds it at a time, and progress inside it is remembered.
 */
public interface AurumClaimApi {

    /** How long a lease lasts when a caller does not say. */
    Duration DEFAULT_LEASE = Duration.ofMinutes(2);

    /**
     * Record that something is owed. Idempotent: promising the same key twice
     * returns the first claim with status DUPLICATE and creates nothing.
     */
    CompletionStage<ClaimResult> promise(ClaimRequest request);

    /**
     * Everything this plugin still owes the player — PENDING claims, plus
     * CLAIMED ones whose lease has run out.
     */
    CompletionStage<List<ClaimSnapshot>> owed(UUID owner, String plugin);

    /**
     * Lease the claim for delivery. Exactly one caller wins; everyone else gets
     * CONFLICT, including a second server racing for the same claim.
     */
    CompletionStage<ClaimResult> take(UUID claimId, String worker, Duration lease);

    /**
     * Remember that delivery has completed this many steps.
     *
     * <p>Call it after each step takes effect, not after the batch: the number
     * recorded here is exactly what a restarted server will trust.
     *
     * <p>The worker name is not decoration. Without it, a server that woke up
     * after its lease had expired could report progress on a claim someone else
     * is delivering right now.
     */
    CompletionStage<ClaimResult> advance(UUID claimId, String worker, int completedSteps);

    /** Delivered in full. Terminal. */
    CompletionStage<ClaimResult> settle(UUID claimId, String worker);

    /**
     * Give the claim back unfinished — it may work later. Counts an attempt,
     * and enough attempts send it to quarantine on their own.
     */
    CompletionStage<ClaimResult> defer(UUID claimId, String worker, String reason);

    /** Delivery cannot work. Hand it to an administrator. */
    CompletionStage<ClaimResult> quarantine(UUID claimId, String worker, String reason);

    /** What is waiting for an administrator, newest first. */
    CompletionStage<List<ClaimSnapshot>> quarantined(String plugin, int limit);

    /** Administrator: put a quarantined claim back in the queue, attempts reset. */
    CompletionStage<ClaimResult> requeue(UUID claimId);

    /**
     * Administrator: this will never be delivered.
     *
     * <p>Terminal and deliberate. The money for it has already moved, so the
     * record stays readable — dropping the delivery is not the same as
     * pretending the debt never existed.
     */
    CompletionStage<ClaimResult> drop(UUID claimId, String reason);

    default CompletionStage<ClaimResult> take(UUID claimId, String worker) {
        return take(claimId, worker, DEFAULT_LEASE);
    }
}
