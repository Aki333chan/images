package ovh.aurumgg.core.engine.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;
import ovh.aurumgg.core.engine.LedgerCommit;
import ovh.aurumgg.core.engine.LedgerRepository;
import ovh.aurumgg.core.engine.TransactionPlan;

class MigrationServiceTest {
    private static final CurrencySpec CURRENCY = new CurrencySpec("coins", "Coins", "$", 2);

    @Test
    void importsPositiveBalancesAndVerifiesSnapshot() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        FakeMigrations migrations = new FakeMigrations(summary(runId, "READY", 0, 0));
        migrations.rows.add(new StoredMigrationBalance(player, "Alex", amount("12.50"), amount("0"), amount("-12.50")));
        migrations.rows.add(new StoredMigrationBalance(UUID.randomUUID(), "Zero", amount("0"), amount("0"), amount("0")));
        FakeLedger ledger = new FakeLedger();

        MigrationImportResult result = new MigrationService(CURRENCY, migrations, ledger).importRun(runId);

        assertEquals(MigrationImportResult.Status.VERIFIED, result.status());
        assertEquals(1, result.imported());
        assertEquals(1, ledger.plans.size());
        assertEquals("migration:" + runId + ":" + player,
                ledger.plans.getFirst().request().idempotencyKey());
        assertEquals(List.of("IMPORTING", "IMPORTED"), migrations.statuses);
    }

    @Test
    void refusesFreshImportWhenPlayerLedgerAlreadyContainsMoney() throws Exception {
        UUID runId = UUID.randomUUID();
        FakeMigrations migrations = new FakeMigrations(summary(runId, "READY", 0, 0));
        migrations.ledgerBalances.add(new PlayerLedgerBalance(UUID.randomUUID(), amount("1")));

        MigrationImportResult result = new MigrationService(CURRENCY, migrations, new FakeLedger()).importRun(runId);

        assertEquals(MigrationImportResult.Status.BLOCKED, result.status());
        assertEquals("Player ledger is not empty", result.message());
        assertEquals(List.of(), migrations.statuses);
    }

    @Test
    void refusesIncompleteVaultSnapshot() throws Exception {
        UUID runId = UUID.randomUUID();
        FakeMigrations migrations = new FakeMigrations(summary(runId, "READY", 2, 0));

        MigrationImportResult result = new MigrationService(CURRENCY, migrations, new FakeLedger()).importRun(runId);

        assertEquals(MigrationImportResult.Status.BLOCKED, result.status());
        assertEquals("Snapshot contains Vault read failures", result.message());
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(CURRENCY.scale());
    }

    private static MigrationRunSummary summary(UUID runId, String status, int failures, int negatives) {
        return new MigrationRunSummary(runId, "EssentialsX Economy", CURRENCY.id(), status,
                1, failures, status.equals("VERIFIED") ? 0 : 1, negatives,
                amount("12.50"), status.equals("VERIFIED") ? amount("12.50") : amount("0"), Instant.EPOCH);
    }

    private static final class FakeMigrations implements MigrationRepository {
        private MigrationRunSummary value;
        private final List<StoredMigrationBalance> rows = new ArrayList<>();
        private final List<PlayerLedgerBalance> ledgerBalances = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();

        FakeMigrations(MigrationRunSummary value) { this.value = value; }
        @Override public MigrationRunSummary saveSnapshot(UUID id, String provider, CurrencySpec currency,
                List<ObservedPlayerBalance> balances, int failures) { throw new UnsupportedOperationException(); }
        @Override public Optional<MigrationRunSummary> summary(UUID id, CurrencySpec currency) { return Optional.of(value); }
        @Override public Optional<MigrationRunSummary> latest(CurrencySpec currency) { return Optional.of(value); }
        @Override public Optional<MigrationRunSummary> latestVerified(CurrencySpec currency) {
            return value.status().equals("VERIFIED") ? Optional.of(value) : Optional.empty();
        }
        @Override public MigrationRunSummary refreshComparison(UUID id, CurrencySpec currency) {
            value = MigrationServiceTest.summary(id, "VERIFIED", 0, 0);
            return value;
        }
        @Override public List<StoredMigrationBalance> balances(UUID id, CurrencySpec currency) { return rows; }
        @Override public List<PlayerLedgerBalance> allPlayerBalances(CurrencySpec currency) { return ledgerBalances; }
        @Override public void markStatus(UUID id, String status) { statuses.add(status); }
    }

    private static final class FakeLedger implements LedgerRepository {
        private final List<TransactionPlan> plans = new ArrayList<>();
        @Override public void initialize(CurrencySpec currency) {}
        @Override public Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) { return Optional.empty(); }
        @Override public GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) { throw new UnsupportedOperationException(); }
        @Override public LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) {
            plans.add(plan);
            BigDecimal zero = amount("0");
            return new LedgerCommit(LedgerCommit.Status.COMMITTED, UUID.randomUUID(), plan.request().amount(),
                    plan.targetCredit(), zero, zero, plan.targetCredit(), "Committed");
        }
    }
}
