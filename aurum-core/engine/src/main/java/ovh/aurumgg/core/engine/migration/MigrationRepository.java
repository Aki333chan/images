package ovh.aurumgg.core.engine.migration;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.core.api.CurrencySpec;

public interface MigrationRepository {
    MigrationRunSummary saveSnapshot(UUID runId, String providerName, CurrencySpec currency,
                                     List<ObservedPlayerBalance> balances, int readFailures) throws SQLException;

    Optional<MigrationRunSummary> summary(UUID runId, CurrencySpec currency) throws SQLException;

    Optional<MigrationRunSummary> latest(CurrencySpec currency) throws SQLException;

    Optional<MigrationRunSummary> latestVerified(CurrencySpec currency) throws SQLException;

    MigrationRunSummary refreshComparison(UUID runId, CurrencySpec currency) throws SQLException;

    List<StoredMigrationBalance> balances(UUID runId, CurrencySpec currency) throws SQLException;

    List<PlayerLedgerBalance> allPlayerBalances(CurrencySpec currency) throws SQLException;

    void markStatus(UUID runId, String status) throws SQLException;
}
