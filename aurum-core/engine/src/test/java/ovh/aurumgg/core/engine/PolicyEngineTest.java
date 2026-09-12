package ovh.aurumgg.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;
import ovh.aurumgg.core.api.TransactionRequest;

class PolicyEngineTest {
    private static final CurrencySpec COINS = new CurrencySpec("coins", "Coins", "$", 2);
    private static final AccountId FROM = AccountId.player(UUID.fromString(
            "00000000-0000-0000-0000-000000000001"));
    private static final AccountId TO = AccountId.player(UUID.fromString(
            "00000000-0000-0000-0000-000000000002"));

    @Test
    void combinesIncludedTaxAndAddedFeeInOneBalancedPlan() {
        TransactionPlan plan = TransactionPlanner.plan(request("100.00"), COINS, List.of(
                percentage("tax", PolicyKind.TAX, "0.10", "INCLUDED", 20),
                percentage("fee", PolicyKind.FEE, "0.05", "ADDED", 10)), Instant.now());

        assertEquals(new BigDecimal("105.00"), plan.sourceDebit());
        assertEquals(new BigDecimal("90.00"), plan.targetCredit());
        assertEquals(new BigDecimal("10.00"), plan.taxCredit());
        assertEquals(new BigDecimal("-105.00"), posting(plan, FROM));
        assertEquals(new BigDecimal("90.00"), posting(plan, TO));
        assertEquals(new BigDecimal("15.00"), posting(plan, AccountId.globalTreasury()));
        assertEquals(List.of("tax", "fee"), plan.appliedRuleIds());
    }

    @Test
    void cashbackAndSubsidyAreFundedByTreasury() {
        TransactionPlan plan = TransactionPlanner.plan(request("100.00"), COINS, List.of(
                percentage("cashback", PolicyKind.CASHBACK, "0.05", null, 10),
                percentage("subsidy", PolicyKind.SUBSIDY, "0.10", null, 5)), Instant.now());

        assertEquals(new BigDecimal("95.00"), plan.sourceDebit());
        assertEquals(new BigDecimal("110.00"), plan.targetCredit());
        assertEquals(new BigDecimal("-15.00"), posting(plan, AccountId.globalTreasury()));
    }

    @Test
    void limitRejectsBeforeLedgerMutation() {
        FinancialRule limit = new FinancialRule("cap", 1, PolicyKind.LIMIT, 1,
                Set.of(TransactionCategory.PLAYER_PAYMENT), Map.of("maximum", "50.00"),
                100, true, null, null);
        assertThrows(PolicyRejectedException.class,
                () -> TransactionPlanner.plan(request("50.01"), COINS, List.of(limit), Instant.now()));
    }

    @Test
    void exemptionSkipsNamedPolicyFamily() {
        FinancialRule exemption = new FinancialRule("no-tax", 1, PolicyKind.EXEMPTION, 1,
                Set.of(TransactionCategory.PLAYER_PAYMENT), Map.of("kinds", "TAX"),
                100, true, null, null);
        TransactionPlan plan = TransactionPlanner.plan(request("100.00"), COINS,
                List.of(exemption, percentage("tax", PolicyKind.TAX, "0.10", "INCLUDED", 1)),
                Instant.now());
        assertEquals(new BigDecimal("100.00"), plan.sourceDebit());
        assertEquals(new BigDecimal("100.00"), plan.targetCredit());
        assertEquals(List.of(), plan.appliedRuleIds());
    }

    @Test
    void accountAndMetadataConditionsTargetOnlyMatchingTransactions() {
        FinancialRule targeted = new FinancialRule("targeted", 1, PolicyKind.TAX, 1,
                Set.of(TransactionCategory.PLAYER_PAYMENT), Map.of(
                        "rate", "0.10", "mode", "INCLUDED",
                        "condition-source-id", FROM.reference(),
                        "condition-metadata-key", "tier", "condition-metadata-value", "sigmas"),
                10, true, null, null);
        TransactionRequest matching = new TransactionRequest("targeted:yes", FROM, TO, "coins",
                new BigDecimal("100.00"), TransactionCategory.PLAYER_PAYMENT, Map.of("tier", "sigmas"));
        TransactionRequest other = new TransactionRequest("targeted:no", FROM, TO, "coins",
                new BigDecimal("100.00"), TransactionCategory.PLAYER_PAYMENT, Map.of("tier", "default"));

        assertEquals(new BigDecimal("10.00"), TransactionPlanner.plan(
                matching, COINS, List.of(targeted), Instant.now()).taxCredit());
        assertEquals(new BigDecimal("0.00"), TransactionPlanner.plan(
                other, COINS, List.of(targeted), Instant.now()).taxCredit());
    }

    @Test
    void systemMintAndTechnicalVaultCategoriesCannotBeConfiguredAsPolicyBackdoors() {
        FinancialRule vaultTax = new FinancialRule("vault-tax", 1, PolicyKind.TAX, 1,
                Set.of(TransactionCategory.VAULT_DEPOSIT), Map.of("rate", "0.10"),
                1, false, null, null);
        FinancialRule mintedCashback = new FinancialRule("mint", 1, PolicyKind.CASHBACK, 1,
                Set.of(TransactionCategory.PLAYER_PAYMENT), Map.of(
                        "rate", "0.10", "funding-type", "SYSTEM_SOURCE", "funding-id", "global"),
                1, false, null, null);

        assertThrows(IllegalArgumentException.class, () -> PolicyValidator.validate(vaultTax, COINS));
        assertThrows(IllegalArgumentException.class, () -> PolicyValidator.validate(mintedCashback, COINS));
    }

    private static FinancialRule percentage(String id, PolicyKind kind, String rate,
                                            String mode, int priority) {
        Map<String, String> definition = mode == null ? Map.of("rate", rate)
                : Map.of("rate", rate, "mode", mode);
        return new FinancialRule(id, 1, kind, 1, Set.of(TransactionCategory.PLAYER_PAYMENT),
                definition, priority, true, null, null);
    }

    private static TransactionRequest request(String amount) {
        return new TransactionRequest("test:" + amount, FROM, TO, "coins", new BigDecimal(amount),
                TransactionCategory.PLAYER_PAYMENT, Map.of());
    }

    private static BigDecimal posting(TransactionPlan plan, AccountId account) {
        return plan.postings().stream().filter(value -> value.account().equals(account))
                .findFirst().orElseThrow().amount();
    }
}
