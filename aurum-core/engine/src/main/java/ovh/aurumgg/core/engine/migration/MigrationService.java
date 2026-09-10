package ovh.aurumgg.core.engine.migration;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.engine.LedgerCommit;
import ovh.aurumgg.core.engine.LedgerRepository;
import ovh.aurumgg.core.engine.TransactionPlanner;

/** Imports immutable Vault snapshots into the dormant ledger without touching the source provider. */
public final class MigrationService {
    private static final AccountId SOURCE = new AccountId(AccountType.SYSTEM_SOURCE, "global");
    private final CurrencySpec currency;
    private final MigrationRepository migrations;
    private final LedgerRepository ledger;

    public MigrationService(CurrencySpec currency, MigrationRepository migrations, LedgerRepository ledger) {
        this.currency = currency;
        this.migrations = migrations;
        this.ledger = ledger;
    }

    public MigrationImportResult importRun(UUID runId) throws SQLException {
        MigrationRunSummary before = migrations.summary(runId, currency)
                .orElseThrow(() -> new IllegalArgumentException("Migration run not found"));
        if (!before.status().equals("READY") && !before.status().equals("IMPORT_FAILED")
                && !before.status().equals("IMPORTED")) {
            return blocked(before, "Run must be READY or IMPORT_FAILED");
        }
        if (before.readFailures() != 0) return blocked(before, "Snapshot contains Vault read failures");
        if (before.negativeBalances() != 0) return blocked(before, "Snapshot contains negative balances");

        boolean retry = before.status().equals("IMPORT_FAILED") || before.status().equals("IMPORTED");
        if (!retry && migrations.allPlayerBalances(currency).stream()
                .map(PlayerLedgerBalance::balance).anyMatch(amount -> amount.signum() != 0)) {
            return blocked(before, "Player ledger is not empty");
        }

        migrations.markStatus(runId, "IMPORTING");
        int imported = 0;
        try {
            for (StoredMigrationBalance row : migrations.balances(runId, currency)) {
                BigDecimal amount = currency.requireAmount(row.externalBalance());
                if (amount.signum() == 0) continue;
                TransactionRequest request = new TransactionRequest(
                        "migration:" + runId + ":" + row.playerId(),
                        SOURCE,
                        AccountId.player(row.playerId()),
                        currency.id(),
                        amount,
                        TransactionCategory.MIGRATION,
                        Map.of("migrationRun", runId.toString(), "sourceProvider", before.providerName())
                );
                LedgerCommit commit = ledger.commit(TransactionPlanner.plan(request, currency, Optional.empty()), currency);
                if (commit.status() != LedgerCommit.Status.COMMITTED
                        && commit.status() != LedgerCommit.Status.DUPLICATE) {
                    throw new SQLException("Ledger rejected player " + row.playerId() + ": " + commit.message());
                }
                imported++;
            }
            migrations.markStatus(runId, "IMPORTED");
            MigrationRunSummary after = migrations.refreshComparison(runId, currency);
            if (!after.status().equals("VERIFIED")) {
                return new MigrationImportResult(MigrationImportResult.Status.FAILED, after, imported,
                        "Import finished with balance mismatches");
            }
            return new MigrationImportResult(MigrationImportResult.Status.VERIFIED, after, imported, "Verified");
        } catch (SQLException | RuntimeException exception) {
            try { migrations.markStatus(runId, "IMPORT_FAILED"); }
            catch (SQLException statusFailure) { exception.addSuppressed(statusFailure); }
            throw exception;
        }
    }

    private static MigrationImportResult blocked(MigrationRunSummary summary, String message) {
        return new MigrationImportResult(MigrationImportResult.Status.BLOCKED, summary, 0, message);
    }
}
