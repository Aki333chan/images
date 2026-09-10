package ovh.aurumgg.core.engine.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import ovh.aurumgg.core.engine.LedgerRepository;
import ovh.aurumgg.core.engine.PolicyRepository;
import ovh.aurumgg.core.engine.migration.MigrationRepository;

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
        config.addDataSourceProperty("connectTimeout", "5000");
        config.addDataSourceProperty("socketTimeout", "5000");
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

    public LedgerRepository ledgerRepository(Clock clock) {
        return new MariaDbLedgerRepository(dataSource, clock);
    }

    public MigrationRepository migrationRepository() {
        return new MariaDbMigrationRepository(dataSource);
    }

    public MariaDbStateRepository stateRepository() {
        return new MariaDbStateRepository(dataSource);
    }

    public PolicyRepository policyRepository() {
        return new MariaDbPolicyRepository(dataSource);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
