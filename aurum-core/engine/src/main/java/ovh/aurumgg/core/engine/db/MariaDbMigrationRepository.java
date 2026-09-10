package ovh.aurumgg.core.engine.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.engine.migration.MigrationRepository;
import ovh.aurumgg.core.engine.migration.MigrationRunSummary;
import ovh.aurumgg.core.engine.migration.ObservedPlayerBalance;
import ovh.aurumgg.core.engine.migration.PlayerLedgerBalance;
import ovh.aurumgg.core.engine.migration.StoredMigrationBalance;

public final class MariaDbMigrationRepository implements MigrationRepository {
    private static final Set<String> STATUSES = Set.of(
            "READY", "IMPORTING", "IMPORTED", "IMPORT_FAILED", "VERIFIED");
    private final DataSource dataSource;

    public MariaDbMigrationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public MigrationRunSummary saveSnapshot(UUID runId, String providerName, CurrencySpec currency,
                                            List<ObservedPlayerBalance> balances, int readFailures)
            throws SQLException {
        if (providerName == null || providerName.isBlank() || providerName.length() > 64 || readFailures < 0) {
            throw new IllegalArgumentException("Invalid migration snapshot metadata");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO aurum_migration_runs(
                            run_id, provider_name, currency_id, status, read_failures)
                        VALUES (?, ?, ?, 'SCANNING', ?)
                        """)) {
                    statement.setString(1, runId.toString());
                    statement.setString(2, providerName);
                    statement.setString(3, currency.id());
                    statement.setInt(4, readFailures);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO aurum_migration_balances(
                            run_id, player_uuid, username, external_balance)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    for (ObservedPlayerBalance balance : balances) {
                        statement.setString(1, runId.toString());
                        statement.setString(2, balance.playerId().toString());
                        statement.setString(3, balance.username());
                        statement.setBigDecimal(4, currency.requireAmount(balance.externalBalance()));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                refreshRows(connection, runId, currency);
                Aggregate aggregate = aggregate(connection, runId, currency);
                updateRun(connection, runId, "READY", aggregate);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
        return summary(runId, currency).orElseThrow(() -> new SQLException("Saved migration run disappeared"));
    }

    @Override
    public Optional<MigrationRunSummary> summary(UUID runId, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM aurum_migration_runs WHERE run_id = ? AND currency_id = ?
                     """)) {
            statement.setString(1, runId.toString());
            statement.setString(2, currency.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSummary(result, currency)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<MigrationRunSummary> latest(CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM aurum_migration_runs WHERE currency_id = ?
                     ORDER BY created_at DESC LIMIT 1
                     """)) {
            statement.setString(1, currency.id());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSummary(result, currency)) : Optional.empty();
            }
        }
    }

    @Override
    public MigrationRunSummary refreshComparison(UUID runId, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = statusForUpdate(connection, runId, currency);
                refreshRows(connection, runId, currency);
                Aggregate aggregate = aggregate(connection, runId, currency);
                String status = switch (previousStatus) {
                    case "IMPORTING", "IMPORTED", "IMPORT_FAILED" ->
                            aggregate.mismatches == 0 ? "VERIFIED" : "IMPORT_FAILED";
                    default -> previousStatus;
                };
                updateRun(connection, runId, status, aggregate);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
        return summary(runId, currency).orElseThrow(() -> new SQLException("Migration run not found"));
    }

    @Override
    public List<StoredMigrationBalance> balances(UUID runId, CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_uuid, username, external_balance, internal_balance, difference_amount
                     FROM aurum_migration_balances WHERE run_id = ? ORDER BY player_uuid
                     """)) {
            statement.setString(1, runId.toString());
            List<StoredMigrationBalance> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(new StoredMigrationBalance(
                            UUID.fromString(result.getString("player_uuid")),
                            result.getString("username"),
                            result.getBigDecimal("external_balance").setScale(currency.scale()),
                            result.getBigDecimal("internal_balance").setScale(currency.scale()),
                            result.getBigDecimal("difference_amount").setScale(currency.scale())
                    ));
                }
            }
            return values;
        }
    }

    @Override
    public List<PlayerLedgerBalance> allPlayerBalances(CurrencySpec currency) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT reference_id, balance FROM aurum_accounts
                     WHERE account_type = 'PLAYER' AND currency_id = ? ORDER BY reference_id
                     """)) {
            statement.setString(1, currency.id());
            List<PlayerLedgerBalance> values = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(new PlayerLedgerBalance(
                            UUID.fromString(result.getString("reference_id")),
                            result.getBigDecimal("balance").setScale(currency.scale())
                    ));
                }
            }
            return values;
        }
    }

    @Override
    public void markStatus(UUID runId, String status) throws SQLException {
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Invalid migration status");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE aurum_migration_runs SET status = ?, completed_at =
                         CASE WHEN ? = 'IMPORTING' THEN NULL ELSE CURRENT_TIMESTAMP(6) END
                     WHERE run_id = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, status);
            statement.setString(3, runId.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Migration run not found");
        }
    }

    private static String statusForUpdate(Connection connection, UUID runId, CurrencySpec currency)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM aurum_migration_runs
                WHERE run_id = ? AND currency_id = ? FOR UPDATE
                """)) {
            statement.setString(1, runId.toString());
            statement.setString(2, currency.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Migration run not found");
                return result.getString(1);
            }
        }
    }

    private static void refreshRows(Connection connection, UUID runId, CurrencySpec currency) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE aurum_migration_balances snapshot
                LEFT JOIN aurum_accounts account
                    ON account.account_type = 'PLAYER'
                    AND account.reference_id = snapshot.player_uuid
                    AND account.currency_id = ?
                SET snapshot.internal_balance = COALESCE(account.balance, 0),
                    snapshot.difference_amount = COALESCE(account.balance, 0) - snapshot.external_balance
                WHERE snapshot.run_id = ?
                """)) {
            statement.setString(1, currency.id());
            statement.setString(2, runId.toString());
            statement.executeUpdate();
        }
    }

    private static Aggregate aggregate(Connection connection, UUID runId, CurrencySpec currency) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS players,
                    COALESCE(SUM(external_balance), 0) AS external_total,
                    COALESCE(SUM(internal_balance), 0) AS internal_total,
                    COALESCE(SUM(CASE WHEN difference_amount <> 0 THEN 1 ELSE 0 END), 0) AS mismatches,
                    COALESCE(SUM(CASE WHEN external_balance < 0 THEN 1 ELSE 0 END), 0) AS negatives
                FROM aurum_migration_balances WHERE run_id = ?
                """)) {
            statement.setString(1, runId.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new Aggregate(
                        result.getInt("players"),
                        result.getInt("mismatches"),
                        result.getInt("negatives"),
                        result.getBigDecimal("external_total").setScale(currency.scale()),
                        result.getBigDecimal("internal_total").setScale(currency.scale())
                );
            }
        }
    }

    private static void updateRun(Connection connection, UUID runId, String status, Aggregate aggregate)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE aurum_migration_runs SET status = ?, player_count = ?, mismatch_count = ?,
                    negative_count = ?, external_total = ?, internal_total = ?,
                    completed_at = CURRENT_TIMESTAMP(6) WHERE run_id = ?
                """)) {
            statement.setString(1, status);
            statement.setInt(2, aggregate.players);
            statement.setInt(3, aggregate.mismatches);
            statement.setInt(4, aggregate.negatives);
            statement.setBigDecimal(5, aggregate.externalTotal);
            statement.setBigDecimal(6, aggregate.internalTotal);
            statement.setString(7, runId.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Migration run not found");
        }
    }

    private static MigrationRunSummary readSummary(ResultSet result, CurrencySpec currency) throws SQLException {
        Timestamp created = result.getTimestamp("created_at");
        return new MigrationRunSummary(
                UUID.fromString(result.getString("run_id")),
                result.getString("provider_name"),
                result.getString("currency_id"),
                result.getString("status"),
                result.getInt("player_count"),
                result.getInt("read_failures"),
                result.getInt("mismatch_count"),
                result.getInt("negative_count"),
                result.getBigDecimal("external_total").setScale(currency.scale()),
                result.getBigDecimal("internal_total").setScale(currency.scale()),
                created.toInstant()
        );
    }

    private static void rollback(Connection connection, SQLException original) {
        try { connection.rollback(); } catch (SQLException rollback) { original.addSuppressed(rollback); }
    }

    private record Aggregate(int players, int mismatches, int negatives,
                             BigDecimal externalTotal, BigDecimal internalTotal) {}
}
