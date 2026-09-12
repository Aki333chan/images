package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.BalanceSnapshot;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.EconomyAuditPage;
import ovh.aurumgg.core.api.EconomyAuditSection;
import ovh.aurumgg.core.api.GlobalEconomySnapshot;

class EconomyAuditServiceTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);

    @Test
    void ledgerAccountSelectorIsParsedAndNeverSilentlyWidened() throws Exception {
        RecordingLedger ledger = new RecordingLedger();
        EconomyAuditService service = new EconomyAuditService(COINS, Map.of(COINS.id(), COINS), ledger,
                null, null, new PolicyRegistry(true, COINS.id()), new ExchangeRegistry(), Runnable::run,
                Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC));

        Optional<EconomyAuditPage> page = service.read(EconomyAuditSection.LEDGER, "coins",
                "guild:42", 500).toCompletableFuture().get();

        assertTrue(page.isPresent());
        assertEquals("guild:42", page.orElseThrow().summary().get("account"));
        assertEquals("guild:42", ledger.account.orElseThrow().stableKey());
        assertEquals(200, ledger.limit);

        ledger.called = false;
        assertTrue(service.read(EconomyAuditSection.LEDGER, "coins", "not-an-account", 50)
                .toCompletableFuture().get().isEmpty());
        assertTrue(!ledger.called, "malformed account must not fall back to an all-account query");
    }

    private static final class RecordingLedger implements LedgerRepository {
        private Optional<AccountId> account = Optional.empty();
        private int limit;
        private boolean called;

        @Override public void initialize(CurrencySpec currency) {}
        @Override public Optional<BigDecimal> balance(AccountId account, CurrencySpec currency) {
            return Optional.empty();
        }
        @Override public GlobalEconomySnapshot globalSnapshot(CurrencySpec currency) { return null; }
        @Override public List<BalanceSnapshot> richest(CurrencySpec currency, int limit) { return List.of(); }
        @Override public List<LedgerTransactionAudit> history(
                CurrencySpec currency, Optional<AccountId> account, int limit) {
            this.account = account;
            this.limit = limit;
            this.called = true;
            return List.of();
        }
        @Override public LedgerCommit commit(TransactionPlan plan, CurrencySpec currency) { return null; }
    }
}
