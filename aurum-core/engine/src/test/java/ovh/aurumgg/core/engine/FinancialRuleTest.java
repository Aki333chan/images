package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.TransactionCategory;

class FinancialRuleTest {
    @Test
    void versionedRuleHonoursCategoryAndTimeWindow() {
        FinancialRule rule = new FinancialRule(
                "market-fee", PolicyKind.FEE, 1, Set.of(TransactionCategory.PLAYER_PAYMENT),
                Map.of("rate", "0.02"), 50, true,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));
        assertTrue(rule.activeFor(TransactionCategory.PLAYER_PAYMENT, Instant.parse("2026-09-10T00:00:00Z")));
        assertFalse(rule.activeFor(TransactionCategory.NPC_SALE, Instant.parse("2026-09-10T00:00:00Z")));
        assertFalse(rule.activeFor(TransactionCategory.PLAYER_PAYMENT, Instant.parse("2027-01-01T00:00:00Z")));
    }
}
