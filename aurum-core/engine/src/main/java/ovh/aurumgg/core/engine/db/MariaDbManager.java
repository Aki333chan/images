package ovh.aurumgg.core.engine.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class MariaDbManager implements AutoCloseable {
    private final HikariDataSource dataSource;

    public MariaDbManager(MariaDbSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setPoolName("AurumCore-DB");
        this.dataSource = new HikariDataSource(config);
    }

    public void migrate() throws SQLException {
        new MigrationRunner(dataSource).migrate(CoreMigrations.all());
    }

    public boolean healthy() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
