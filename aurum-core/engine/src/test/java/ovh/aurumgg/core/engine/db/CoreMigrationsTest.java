package ovh.aurumgg.core.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreMigrationsTest {
    @Test
    void initialSchemaContainsFinancialSafetyPrimitives() {
        var migrations = CoreMigrations.all();
        assertEquals(3, migrations.size());
        String sql = migrations.stream().flatMap(it -> it.statements().stream())
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(sql.contains("aurum_ledger_entries"));
        assertTrue(sql.contains("idempotency_key"));
        assertTrue(sql.contains("aurum_financial_rules"));
        assertTrue(sql.contains("aurum_holds"));
        assertTrue(sql.contains("aurum_trades"));
        assertTrue(sql.contains("aurum_outbox"));
        assertTrue(sql.contains("aurum_shadow_balances"));
        assertTrue(sql.contains("aurum_migration_runs"));
        assertTrue(sql.contains("aurum_migration_balances"));
        assertEquals(64, migrations.getFirst().checksum().length());
        assertEquals(64, migrations.getLast().checksum().length());
    }
}
