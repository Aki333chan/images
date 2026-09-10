package ovh.aurumgg.core.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreMigrationsTest {
    @Test
    void initialSchemaContainsFinancialSafetyPrimitives() {
        var migrations = CoreMigrations.all();
        assertEquals(1, migrations.size());
        String sql = String.join("\n", migrations.getFirst().statements());
        assertTrue(sql.contains("aurum_ledger_entries"));
        assertTrue(sql.contains("idempotency_key"));
        assertTrue(sql.contains("aurum_financial_rules"));
        assertTrue(sql.contains("aurum_holds"));
        assertTrue(sql.contains("aurum_trades"));
        assertTrue(sql.contains("aurum_outbox"));
        assertEquals(64, migrations.getFirst().checksum().length());
    }
}
