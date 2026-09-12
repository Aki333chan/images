package ovh.aurumgg.core.engine.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.ClaimRequest;
import ovh.aurumgg.core.api.ClaimSnapshot;
import ovh.aurumgg.core.api.ClaimStatus;
import ovh.aurumgg.core.engine.ClaimRepository;

/**
 * Claims in MariaDB.
 *
 * <p>Every state change is a single conditional UPDATE whose WHERE clause spells
 * out the state the caller believed it was acting on. The row count decides the
 * outcome: one row means this caller won, zero means someone else did. Reading
 * first and writing second would be a race with a comfortable-looking API.
 */
public final class MariaDbClaimRepository implements ClaimRepository {

    private static final String COLUMNS = "id, idempotency_key, plugin, owner_uuid, kind, status, "
            + "step_cursor, step_count, attempts, summary, payload, last_error, claimed_by, "
            + "lease_until, created_at, updated_at";

    private final DataSource dataSource;

    public MariaDbClaimRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public ClaimSnapshot promise(ClaimRequest request, UUID id, Instant now) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // INSERT IGNORE, then read back: the unique key on idempotency_key
                // is what makes this idempotent, not a preceding SELECT. Two
                // callers racing on the same key both end up reading the one row
                // that actually got in.
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT IGNORE INTO aurum_claims (id, idempotency_key, plugin, owner_uuid, kind, "
                                + "status, step_cursor, step_count, attempts, summary, payload, last_error, "
                                + "created_at, updated_at) VALUES (?,?,?,?,?,?,0,?,0,?,?,'',?,?)")) {
                    statement.setString(1, id.toString());
                    statement.setString(2, request.idempotencyKey());
                    statement.setString(3, request.plugin());
                    statement.setString(4, request.owner().toString());
                    statement.setString(5, request.kind());
                    statement.setString(6, ClaimStatus.PENDING.name());
                    statement.setInt(7, request.stepCount());
                    statement.setString(8, request.summary());
                    statement.setString(9, request.payload());
                    statement.setTimestamp(10, Timestamp.from(now));
                    statement.setTimestamp(11, Timestamp.from(now));
                    statement.executeUpdate();
                }
                ClaimSnapshot stored = one(connection, "idempotency_key = ?", request.idempotencyKey())
                        .orElseThrow(() -> new SQLException("Claim vanished right after insert"));
                connection.commit();
                return stored;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @Override
    public Optional<ClaimSnapshot> find(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return one(connection, "id = ?", id.toString());
        }
    }

    @Override
    public List<ClaimSnapshot> owed(UUID owner, String plugin, Instant now) throws SQLException {
        // A CLAIMED row whose lease has run out is owed again: its worker is
        // gone. Without this the claim would sit untouchable forever after a
        // crash, which is the exact failure the table exists to prevent.
        String sql = "SELECT " + COLUMNS + " FROM aurum_claims WHERE plugin = ? AND owner_uuid = ? "
                + "AND (status = 'PENDING' OR (status = 'CLAIMED' AND lease_until < ?)) "
                + "ORDER BY created_at ASC, id ASC LIMIT 256";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, plugin);
            statement.setString(2, owner.toString());
            statement.setTimestamp(3, Timestamp.from(now));
            return list(statement);
        }
    }

    @Override
    public List<ClaimSnapshot> byStatus(ClaimStatus status, String plugin, int limit) throws SQLException {
        boolean anyPlugin = plugin == null || plugin.isBlank();
        String sql = "SELECT " + COLUMNS + " FROM aurum_claims WHERE status = ?"
                + (anyPlugin ? "" : " AND plugin = ?") + " ORDER BY updated_at DESC, id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            int index = 2;
            if (!anyPlugin) statement.setString(index++, plugin);
            statement.setInt(index, Math.clamp(limit, 1, 500));
            return list(statement);
        }
    }

    @Override
    public Optional<ClaimSnapshot> take(UUID id, String worker, Duration lease, Instant now)
            throws SQLException {
        String sql = "UPDATE aurum_claims SET status = 'CLAIMED', claimed_by = ?, lease_until = ?, "
                + "updated_at = ? WHERE id = ? AND (status = 'PENDING' "
                + "OR (status = 'CLAIMED' AND lease_until < ?))";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, worker);
                statement.setTimestamp(2, Timestamp.from(now.plus(lease)));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setString(4, id.toString());
                statement.setTimestamp(5, Timestamp.from(now));
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, "id = ?", id.toString());
        }
    }

    @Override
    public Optional<ClaimSnapshot> advance(UUID id, String worker, int completedSteps,
                                           Duration lease, Instant now)
            throws SQLException {
        // step_cursor only ever grows, and never past step_count. A late reply
        // from a worker that already lost the lease cannot rewind delivery.
        String sql = "UPDATE aurum_claims SET step_cursor = LEAST(step_count, GREATEST(step_cursor, ?)), "
                + "lease_until = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'CLAIMED' AND claimed_by = ? AND lease_until >= ?";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, Math.max(0, completedSteps));
                // Progress renews the lease: a long delivery must not have the
                // claim stolen out from under it halfway through.
                statement.setTimestamp(2, Timestamp.from(now.plus(lease)));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setString(4, id.toString());
                statement.setString(5, worker);
                statement.setTimestamp(6, Timestamp.from(now));
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, "id = ?", id.toString());
        }
    }

    @Override
    public Optional<ClaimSnapshot> finish(UUID id, String worker, ClaimStatus status, String reason,
                                          boolean countAttempt, Instant now) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE aurum_claims SET status = ?, claimed_by = NULL, "
                + "lease_until = NULL, last_error = ?, updated_at = ?");
        if (countAttempt) sql.append(", attempts = attempts + 1");
        if (status == ClaimStatus.SETTLED) sql.append(", settled_at = ?, step_cursor = step_count");
        // A worker may finish only the live lease it owns. Administrative drop
        // deliberately cannot steal a CLAIMED row from an active delivery.
        sql.append(" WHERE id = ?");
        if (worker != null) {
            sql.append(" AND status = 'CLAIMED' AND claimed_by = ? AND lease_until >= ?");
        } else {
            sql.append(" AND status IN ('PENDING','QUARANTINED')");
        }
        if (status == ClaimStatus.SETTLED) sql.append(" AND step_cursor >= step_count");

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setString(index++, status.name());
                statement.setString(index++, trim(reason));
                statement.setTimestamp(index++, Timestamp.from(now));
                if (status == ClaimStatus.SETTLED) statement.setTimestamp(index++, Timestamp.from(now));
                statement.setString(index++, id.toString());
                if (worker != null) {
                    statement.setString(index++, worker);
                    statement.setTimestamp(index, Timestamp.from(now));
                }
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, "id = ?", id.toString());
        }
    }

    @Override
    public Optional<ClaimSnapshot> requeue(UUID id, Instant now) throws SQLException {
        String sql = "UPDATE aurum_claims SET status = 'PENDING', attempts = 0, claimed_by = NULL, "
                + "lease_until = NULL, last_error = '', updated_at = ? WHERE id = ? AND status = 'QUARANTINED'";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setString(2, id.toString());
                if (statement.executeUpdate() == 0) return Optional.empty();
            }
            return one(connection, "id = ?", id.toString());
        }
    }

    // --------------------------------------------------------------- reading

    private Optional<ClaimSnapshot> one(Connection connection, String where, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM aurum_claims WHERE " + where)) {
            statement.setString(1, value);
            List<ClaimSnapshot> found = list(statement);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        }
    }

    private static List<ClaimSnapshot> list(PreparedStatement statement) throws SQLException {
        List<ClaimSnapshot> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) result.add(read(rs));
        }
        return result;
    }

    private static ClaimSnapshot read(ResultSet rs) throws SQLException {
        Timestamp lease = rs.getTimestamp("lease_until");
        String worker = rs.getString("claimed_by");
        return new ClaimSnapshot(
                UUID.fromString(rs.getString("id")),
                rs.getString("idempotency_key"),
                rs.getString("plugin"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("kind"),
                ClaimStatus.valueOf(rs.getString("status")),
                rs.getInt("step_cursor"),
                rs.getInt("step_count"),
                rs.getInt("attempts"),
                rs.getString("summary"),
                rs.getString("payload"),
                rs.getString("last_error"),
                Optional.ofNullable(worker),
                Optional.ofNullable(lease).map(Timestamp::toInstant),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String trim(String reason) {
        if (reason == null) return "";
        String text = reason.replace('\n', ' ').trim();
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}
