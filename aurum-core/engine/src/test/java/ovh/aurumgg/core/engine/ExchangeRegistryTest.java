package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;

class ExchangeRegistryTest {
    @Test
    void selectsHighestPriorityMatchingScheduledAndMetadataRule() {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        AccountId player = AccountId.player(UUID.randomUUID());
        ExchangeRegistry registry = new ExchangeRegistry();
        registry.replace(List.of(
                rule("generic", 10, Map.of(), null, null),
                rule("npc-special", 50, Map.of("account-type", "PLAYER",
                                "metadata-key", "npc", "metadata-value", "banker"),
                        now.minusSeconds(60), now.plusSeconds(60)),
                rule("expired", 100, Map.of(), now.minusSeconds(120), now.minusSeconds(1))));
        assertEquals("npc-special", registry.select(player, "coins", "tokens",
                Map.of("npc", "banker"), now).orElseThrow().id());
        assertEquals("generic", registry.select(player, "coins", "tokens",
                Map.of("npc", "other"), now).orElseThrow().id());
        assertTrue(registry.select(player, "tokens", "coins", Map.of(), now).isEmpty());
    }

    private static ExchangeRule rule(String id, int priority, Map<String, String> conditions,
                                     Instant from, Instant until) {
        return new ExchangeRule(id, 1, "coins", "tokens", new BigDecimal("0.01"),
                BigDecimal.ZERO, null, null, ExchangeSettlement.MINT_BURN, conditions,
                priority, true, from, until);
    }
}
