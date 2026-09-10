package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;
import ovh.aurumgg.core.api.TransactionResult;

class TaxCalculatorTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final AccountId PLAYER = AccountId.player(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AccountId SHOP = new AccountId(AccountType.NPC_SHOP, "blocks");

    @Test
    void includedTaxSplitsListedPriceWithoutCreatingMoney() {
        TaxRule rule = rule(TaxMode.INCLUDED);
        TaxBreakdown result = TaxCalculator.calculate(new BigDecimal("100.00"), COINS, rule);
        assertEquals(new BigDecimal("100.00"), result.sourceDebit());
        assertEquals(new BigDecimal("90.00"), result.targetCredit());
        assertEquals(new BigDecimal("10.00"), result.treasuryCredit());
    }

    @Test
    void addedTaxChargesOnTop() {
        TaxBreakdown result = TaxCalculator.calculate(new BigDecimal("100.00"), COINS, rule(TaxMode.ADDED));
        assertEquals(new BigDecimal("110.00"), result.sourceDebit());
        assertEquals(new BigDecimal("100.00"), result.targetCredit());
        assertEquals(new BigDecimal("10.00"), result.treasuryCredit());
    }

    @Test
    void plannerProducesBalancedEntries() {
        TransactionRequest request = new TransactionRequest(
                "npc:purchase:42", PLAYER, SHOP, "coins", new BigDecimal("100.00"),
                TransactionCategory.NPC_PURCHASE, Map.of("offer", "stone"));
        TransactionPlan plan = TransactionPlanner.plan(request, COINS, Optional.of(rule(TaxMode.INCLUDED)));
        assertEquals(3, plan.postings().size());
        assertEquals(0, plan.postings().stream().map(LedgerPosting::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).signum());
    }

    @Test
    void passiveServiceNeverWrites() {
        PassiveEconomyService service = new PassiveEconomyService(
                COINS, Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));
        service.observe(PLAYER, new BigDecimal("42.00"));
        assertEquals(new BigDecimal("42.00"), service.cachedBalance(PLAYER).orElseThrow().balance());

        TransactionRequest request = new TransactionRequest(
                "pay:test:1", PLAYER, SHOP, "coins", new BigDecimal("1.00"),
                TransactionCategory.PLAYER_PAYMENT, Map.of());
        assertEquals(TransactionResult.Status.UNAVAILABLE, service.transfer(request).toCompletableFuture().join().status());
        assertEquals(new BigDecimal("42.00"), service.cachedBalance(PLAYER).orElseThrow().balance());
    }

    @Test
    void excessPrecisionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> COINS.requireAmount(new BigDecimal("1.001")));
    }

    @Test
    void hundredPercentIncludedTaxProducesNoZeroPosting() {
        TaxRule rule = new TaxRule("all-tax", Set.of(TransactionCategory.NPC_PURCHASE), BigDecimal.ONE,
                TaxMode.INCLUDED, AccountId.globalTreasury(), 100, true);
        TransactionRequest request = new TransactionRequest(
                "npc:purchase:all-tax", PLAYER, SHOP, "coins", new BigDecimal("100.00"),
                TransactionCategory.NPC_PURCHASE, Map.of());
        TransactionPlan plan = TransactionPlanner.plan(request, COINS, Optional.of(rule));
        assertEquals(2, plan.postings().size());
        assertEquals(new BigDecimal("0.00"), plan.targetCredit());
    }

    private static TaxRule rule(TaxMode mode) {
        return new TaxRule("sales", Set.of(TransactionCategory.NPC_PURCHASE), new BigDecimal("0.10"),
                mode, AccountId.globalTreasury(), 100, true);
    }
}
