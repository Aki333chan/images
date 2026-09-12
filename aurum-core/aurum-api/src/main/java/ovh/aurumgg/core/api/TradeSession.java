package ovh.aurumgg.core.api;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * A trade between two players, as Core currently holds it.
 *
 * <h2>What the revision is for</h2>
 *
 * Every edit to either offer bumps {@link #revision()} and clears both
 * confirmations. A confirmation records the revision it was given for, and a
 * trade may only settle when both recorded revisions equal the current one.
 *
 * <p>That is the whole safety property: a player confirms <em>what they were
 * looking at</em>. Without it the classic scam works — swap your gold for dirt
 * in the instant between their look and their click.
 *
 * @param firstConfirmedRevision  empty when that side has not confirmed the
 *                                current revision
 */
public record TradeSession(UUID id, UUID first, UUID second, TradeState state, long revision,
                           OptionalLong firstConfirmedRevision, OptionalLong secondConfirmedRevision,
                           Instant expiresAt, Instant createdAt, Instant updatedAt) {
    public TradeSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(state, "state");
        firstConfirmedRevision = Objects.requireNonNull(firstConfirmedRevision, "firstConfirmedRevision");
        secondConfirmedRevision = Objects.requireNonNull(secondConfirmedRevision, "secondConfirmedRevision");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (first.equals(second)) throw new IllegalArgumentException("A player cannot trade with themselves");
        if (revision < 0) throw new IllegalArgumentException("Invalid trade revision");
    }

    public boolean involves(UUID player) {
        return first.equals(player) || second.equals(player);
    }

    public UUID other(UUID player) {
        if (first.equals(player)) return second;
        if (second.equals(player)) return first;
        throw new IllegalArgumentException("That player is not in this trade");
    }

    /** Has this side signed off on exactly what is on the table now? */
    public boolean confirmed(UUID player) {
        OptionalLong recorded = first.equals(player) ? firstConfirmedRevision : secondConfirmedRevision;
        return recorded.isPresent() && recorded.getAsLong() == revision;
    }

    /** Both signed off on the same, current revision — the only way to settle. */
    public boolean ready() {
        return confirmed(first) && confirmed(second);
    }
}
