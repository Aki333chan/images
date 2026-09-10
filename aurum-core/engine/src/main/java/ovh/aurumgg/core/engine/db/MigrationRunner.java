package ovh.aurumgg.core.engine.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

public final class MigrationRunner {
    private static final String LOCK_NAME = "aurum_core_schema_migrations";

    private final DataSource dataSource;

    public MigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void migrate(List<SchemaMigration> migrations) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            acquireLock(connection);
            try {
                createHistory(connection);
                for (SchemaMigration migration : migrations) apply(connection, migration);
            } finally {
                releaseLock(connection);
            }
        }
    }

    private static void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("Could not acquire AurumCore migration lock");
                }
            }
        }
    }

    private static void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException ignored) {
            // Connection close releases a MariaDB advisory lock as a final fallback.
        }
    }

    private static void createHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS aurum_schema_history (
                        version INT PRIMARY KEY,
                        description VARCHAR(191) NOT NULL,
                        checksum CHAR(64) NOT NULL,
                        installed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ) ENGINE=InnoDB
                    """);
        }
    }

    private static void apply(Connection connection, SchemaMigration migration) throws SQLException {
        String existing = checksum(connection, migration.version());
        if (existing != null) {
            if (!existing.equals(migration.checksum())) {
                throw new SQLException("Checksum mismatch for AurumCore migration " + migration.version());
            }
            return;
        }
        for (String sql : migration.statements()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO aurum_schema_history(version, description, checksum) VALUES (?, ?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, migration.checksum());
            statement.executeUpdate();
        }
    }

    private static String checksum(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum FROM aurum_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
