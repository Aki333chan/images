package ovh.aurumgg.core.engine.db;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import java.sql.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.engine.*;

/** Real SQL persistence/CAS tests in H2 MySQL mode, not a replacement for live MariaDB verification. */
class MariaDbStartingBalanceRepositoryTest {
    private JdbcDataSource source;
    private MariaDbStartingBalanceRepository repository;
    private static final StartingBalanceSettings DEFAULTS = new StartingBalanceSettings(1, true, "coins", new BigDecimal("100"));
    @BeforeEach void setup() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        repository = new MariaDbStartingBalanceRepository(source);
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE aurum_accounts(account_type VARCHAR(32), reference_id VARCHAR(128))");
            s.execute("CREATE TABLE aurum_account_profile_members(account_type VARCHAR(32), reference_id VARCHAR(128))");
            for (String sql : CoreMigrations.all().getLast().statements()) s.execute(sql);
        }
    }
    @Test void bootstrapSkipsExistingAccountsAndProfilesIncludingZeroBalance() throws Exception {
        UUID ledger = UUID.randomUUID(), profile = UUID.randomUUID();
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO aurum_accounts VALUES('PLAYER', '" + ledger + "')");
            s.execute("INSERT INTO aurum_account_profile_members VALUES('PLAYER', '" + profile + "')");
        }
        repository.initialize(DEFAULTS, 1000);
        assertFalse(repository.observe(ledger, true).pending());
        assertFalse(repository.observe(profile, true).pending());
        assertTrue(repository.observe(UUID.randomUUID(), true).pending());
        assertFalse(repository.observe(UUID.randomUUID(), false).pending());
    }
    @Test void restartKeepsDecisionFrozenAndDoesNotReimportConfig() throws Exception {
        repository.initialize(DEFAULTS, 1000);
        UUID id = UUID.randomUUID();
        assertTrue(repository.observe(id, true).pending());
        repository.save(new StartingBalanceSettings(1, false, "coins", BigDecimal.ZERO), 1, "panel:admin", "disable");
        repository = new MariaDbStartingBalanceRepository(source);
        repository.initialize(new StartingBalanceSettings(1, true, "tokens", BigDecimal.ONE), 9000);
        assertEquals(1000, repository.installedAt());
        assertFalse(repository.current().enabled());
        var pending = repository.observe(id, false);
        assertTrue(pending.pending());
        assertEquals(0, new BigDecimal("100").compareTo(pending.amount()));
        assertEquals("coins", pending.currency());
        repository.finish(id);
        assertEquals("PAID", repository.observe(id, true).status());
    }
    @Test void disabledFirstJoinCannotBeBackfilled() throws Exception {
        repository.initialize(new StartingBalanceSettings(1, false, "coins", BigDecimal.TEN), 1000);
        UUID id = UUID.randomUUID();
        assertEquals("SKIPPED", repository.observe(id, true).status());
        repository.save(DEFAULTS, 1, "panel:admin", "enable");
        assertEquals("SKIPPED", repository.observe(id, true).status());
        assertTrue(repository.observe(UUID.randomUUID(), true).pending());
    }
    @Test void revisionConflictLeavesCurrentAndHistoryUnchanged() throws Exception {
        repository.initialize(DEFAULTS, 1000);
        repository.save(new StartingBalanceSettings(1, true, "coins", BigDecimal.TEN), 1, "panel:admin", "adjust welcome");
        assertThrows(StaleRuleRevisionException.class, () ->
                repository.save(DEFAULTS, 1, "stale", "stale update"));
        assertEquals(2, repository.current().revision());
        try (var c = source.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT COUNT(*) FROM aurum_starting_balance_revisions")) {
            assertTrue(r.next()); assertEquals(2, r.getInt(1));
        }
        try (var c = source.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT actor, reason FROM aurum_starting_balance_revisions WHERE revision=2")) {
            assertTrue(r.next()); assertEquals("panel:admin", r.getString(1)); assertEquals("adjust welcome", r.getString(2));
        }
    }
}
