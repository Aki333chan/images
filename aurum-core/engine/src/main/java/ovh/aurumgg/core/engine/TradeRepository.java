package ovh.aurumgg.core.engine;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.core.api.TradeOffer;
import ovh.aurumgg.core.api.TradeSession;
import ovh.aurumgg.core.api.TradeState;

/**
 * Durable store of trades in progress.
 *
 * <p>Like claims, every mutation is conditional on the state the caller
 * believed it was acting on, and the row count decides the outcome. A trade is
 * the one place where two people are clicking at once by design, so reading and
 * then writing would be a race dressed up as an API.
 */
public interface TradeRepository {

    /**
     * Start a trade, unless either player is already in one.
     *
     * <p>One trade per player at a time is not a simplification: without it the
     * same items could be offered in two windows, and whichever settled second
     * would be settling goods that are no longer there.
     */
    Optional<TradeSession> open(UUID id, UUID first, UUID second, Instant expiresAt, Instant now)
            throws SQLException;

    Optional<TradeSession> find(UUID tradeId) throws SQLException;

    /** The unfinished trade this player is in, if any. */
    Optional<TradeSession> activeFor(UUID player) throws SQLException;

    List<TradeOffer> offers(UUID tradeId) throws SQLException;

    /**
     * Replace one side's offer.
     *
     * <p>Bumps the revision and clears BOTH confirmations in the same statement
     * as the write. Anything less leaves a window in which the other side's
     * confirmation still stands against an offer that has already changed —
     * which is the exact scam the revision exists to prevent.
     *
     * @return empty when the trade is not editable any more
     */
    Optional<TradeSession> offer(UUID tradeId, TradeOffer offer, Instant now) throws SQLException;

    /**
     * Record that this side confirms the given revision.
     *
     * @return empty when the revision is no longer current, or the trade is not
     *         open — both mean the player confirmed something they can no
     *         longer be held to
     */
    Optional<TradeSession> confirm(UUID tradeId, UUID owner, long revision, Instant now)
            throws SQLException;

    /**
     * Move between states, only from the state the caller expects.
     *
     * <p>{@code requireReady} additionally demands that both sides have
     * confirmed the current revision, so the step into settlement cannot be
     * taken by a caller working from a stale read.
     */
    Optional<TradeSession> transition(UUID tradeId, TradeState from, TradeState to,
                                      boolean requireReady, Instant now) throws SQLException;

    /**
     * Put a failed, still-unpaid settlement back on the table.
     *
     * <p>This is deliberately not a plain state transition: both confirmations
     * must be cleared and the revision must change atomically. Otherwise the
     * same already-confirmed offer could immediately enter settlement again.
     */
    Optional<TradeSession> reopen(UUID tradeId, Instant now) throws SQLException;

    /**
     * Push the deadline out after real activity.
     *
     * <p>Only for trades that are still running. A session timeout is meant to
     * clear abandoned windows, not to cut off two people who are still
     * arranging things.
     */
    Optional<TradeSession> touch(UUID tradeId, Instant expiresAt, Instant now) throws SQLException;

    /** Trades whose time ran out and that nobody has cleaned up yet. */
    List<TradeSession> timedOut(Instant now, int limit) throws SQLException;

    /** Settlements left in progress by a restart or a transient storage failure. */
    List<TradeSession> settling(int limit) throws SQLException;
}
