package ovh.aurumgg.core.engine.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.TradeOffer;
import ovh.aurumgg.core.api.TradeSession;
import ovh.aurumgg.core.api.TradeState;
import ovh.aurumgg.core.engine.TradeRepository;

/** Trades in MariaDB. Every transition is one conditional UPDATE; the row count decides. */
public final class MariaDbTradeRepository implements TradeRepository {

    private static final String COLUMNS = "id, first_player, second_player, state, revision, "
            + "first_confirmed_revision, second_confirmed_revision, expires_at, created_at, updated_at";

    /** States a player can still be busy in. Finished trades never block a new one. */
    private static final String LIVE = "'INVITED','OPEN','CONFIRMED','SETTLING'";

    private final DataSource dataSource;

    public MariaDbTradeRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<TradeSession> open(UUID id, UUID first, UUID second, Instant expiresAt, Instant now)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Both checks and the insert under one transaction: two players
                // inviting each other at the same instant must not end up in two
                // trades holding the same items.
                if (busy(connection, first) || busy(connection, second)) {
                    connection.rollback();
                    return Optional.empty();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO aurum_trades (id, first_player, second_player, state, revision, "
                                + "expires_at, created_at, updated_at) VALUES (?,?,?,?,0,?,?,?)")) {
                    statement.setString(1, id.toString());
                    statement.setString(2, first.toString());
                    statement.setString(3, second.toString());
                    statement.setString(4, TradeState.INVITED.name());
                    statement.setTimestamp(5, Timestamp.from(expiresAt));
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.executeUpdate();
                }
                Optional<TradeSession> stored = one(connection, id);
                connection.commit();
                return stored;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @Override
    public Optional<TradeSession> find(UUID tradeId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return one(connection, tradeId);
        }
    }

    @Override
    public Optional<TradeSession> activeFor(UUID player) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM aurum_trades WHERE state IN (" + LIVE + ") "
                + "AND (first_player = ? OR second_player = ?) ORDER BY created_at DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            List<TradeSession> found = list(statement);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        }
    }

    @Override
    public List<TradeOffer> offers(UUID tradeId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT trade_id, owner_uuid, currency_id, money_amount, items_blob, "
                                + "items_format_version FROM aurum_trade_offers WHERE trade_id = ?")) {
            statement.setString(1, tradeId.toString());
            List<TradeOffer> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BigDecimal money = rs.getBigDecimal("money_amount");
                    result.add(new TradeOffer(
                            UUID.fromString(rs.getString("trade_id")),
                            UUID.fromString(rs.getString("owner_uuid")),
                            Optional.ofNullable(rs.getString("currency_id")),
                            money == null ? BigDecimal.ZERO : money,
                            rs.getBytes("items_blob"),
                            rs.getInt("items_format_version")));
                }
            }
            return result;
        }
    }

    @Override
    public Optional<TradeSession> offer(UUID tradeId, TradeOffer offer, Instant now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // The revision bump and the cleared confirmations go in the same
                // transaction as the offer itself. Split them and the other side's
                // confirmation briefly stands against an offer that already changed.
                int bumped;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE aurum_trades SET revision = revision + 1, "
                                + "first_confirmed_revision = NULL, second_confirmed_revision = NULL, "
                                + "updated_at = ? WHERE id = ? AND state = 'OPEN' "
                                + "AND (first_player = ? OR second_player = ?)")) {
                    statement.setTimestamp(1, Timestamp.from(now));
                    statement.setString(2, tradeId.toString());
                    statement.setString(3, offer.owner().toString());
                    statement.setString(4, offer.owner().toString());
                    bumped = statement.executeUpdate();
                }
                if (bumped == 0) {
                    connection.rollback();
                    return Optional.empty();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO aurum_trade_offers (trade_id, owner_uuid, currency_id, money_amount, "
                                + "items_blob, items_format_version) VALUES (?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE currency_id = VALUES(currency_id), "
                                + "money_amount = VALUES(money_amount), items_blob = VALUES(items_blob), "
                                + "items_format_version = VALUES(items_format_version)")) {
                    statement.setString(1, tradeId.toString());
                    statement.setString(2, offer.owner().toString());
                    if (offer.currencyId().isPresent()) statement.setString(3, offer.currencyId().get());
                    else statement.setNull(3, Types.VARCHAR);
                    statement.setBigDecimal(4, offer.money());
                    if (offer.items() == null) statement.setNull(5, Types.BLOB);
                    else statement.setBytes(5, offer.items());
                    statement.setInt(6, offer.itemsFormatVersion());
                    statement.executeUpdate();
                }
                Optional<TradeSession> updated = one(connection, tradeId);
                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @Override
    public Optional<TradeSession> confirm(UUID tradeId, UUID owner, long revision, Instant now)
            throws SQLException {
        // "AND revision = ?" is the whole guarantee: a confirmation that arrives
        // after the table changed is refused rather than applied to something
        // the player never saw.
        String sql = "UPDATE aurum_trades SET %s = ?, updated_at = ? "
                + "WHERE id = ? AND state = 'OPEN' AND revision = ? AND %s = ?";
        try (Connection connection = dataSource.getConnection()) {
            String side = side(connection, tradeId, owner);
            if (side == null) return Optional.empty();
            String column = side.equals("first") ? "first_confirmed_revision" : "second_confirmed_revision";
            String player = side.equals("first") ? "first_player" : "second_player";
            try (PreparedStatement statement = connection.prepareStatement(
                    sql.formatted(column, player))) {
                statement.setLong(1, revision);
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setString(3, tradeId.toString());
                statement.setLong(4, revision);
                statement.setString(5, owner.toString());
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, tradeId);
        }
    }

    @Override
    public Optional<TradeSession> transition(UUID tradeId, TradeState from, TradeState to,
                                             boolean requireReady, Instant now) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "UPDATE aurum_trades SET state = ?, updated_at = ? WHERE id = ? AND state = ?");
        if (requireReady) {
            // Both confirmations must still match the CURRENT revision. Checking
            // this in the statement, not in the caller, is what stops a settle
            // that was decided on a read taken one edit ago.
            sql.append(" AND first_confirmed_revision = revision AND second_confirmed_revision = revision");
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setString(1, to.name());
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setString(3, tradeId.toString());
                statement.setString(4, from.name());
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, tradeId);
        }
    }

    @Override
    public Optional<TradeSession> touch(UUID tradeId, Instant expiresAt, Instant now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE aurum_trades SET expires_at = ?, updated_at = ? "
                            + "WHERE id = ? AND state IN (" + LIVE + ")")) {
                statement.setTimestamp(1, Timestamp.from(expiresAt));
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setString(3, tradeId.toString());
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, tradeId);
        }
    }

    @Override
    public List<TradeSession> timedOut(Instant now, int limit) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM aurum_trades WHERE state IN (" + LIVE + ") "
                + "AND expires_at < ? ORDER BY expires_at ASC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, Math.clamp(limit, 1, 500));
            return list(statement);
        }
    }

    // --------------------------------------------------------------- reading

    private boolean busy(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM aurum_trades WHERE state IN (" + LIVE + ") "
                        + "AND (first_player = ? OR second_player = ?) LIMIT 1")) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** "first", "second" or null when the player is not in this trade. */
    private String side(Connection connection, UUID tradeId, UUID player) throws SQLException {
        Optional<TradeSession> trade = one(connection, tradeId);
        if (trade.isEmpty()) return null;
        if (trade.get().first().equals(player)) return "first";
        return trade.get().second().equals(player) ? "second" : null;
    }

    private Optional<TradeSession> one(Connection connection, UUID tradeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM aurum_trades WHERE id = ?")) {
            statement.setString(1, tradeId.toString());
            List<TradeSession> found = list(statement);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        }
    }

    private static List<TradeSession> list(PreparedStatement statement) throws SQLException {
        List<TradeSession> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) result.add(read(rs));
        }
        return result;
    }

    private static TradeSession read(ResultSet rs) throws SQLException {
        return new TradeSession(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("first_player")),
                UUID.fromString(rs.getString("second_player")),
                TradeState.valueOf(rs.getString("state")),
                rs.getLong("revision"),
                revision(rs, "first_confirmed_revision"),
                revision(rs, "second_confirmed_revision"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static OptionalLong revision(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
