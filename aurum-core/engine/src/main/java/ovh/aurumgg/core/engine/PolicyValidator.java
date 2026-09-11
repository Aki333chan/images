package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionCategory;

public final class PolicyValidator {
    private static final Set<PolicyKind> PERCENTAGE = EnumSet.of(
            PolicyKind.TAX, PolicyKind.FEE, PolicyKind.COMMISSION,
            PolicyKind.CASHBACK, PolicyKind.SUBSIDY);
    private static final Set<TransactionCategory> PROTECTED_CATEGORIES = EnumSet.of(
            TransactionCategory.VAULT_DEPOSIT, TransactionCategory.VAULT_WITHDRAWAL,
            TransactionCategory.TAX, TransactionCategory.FEE, TransactionCategory.COMMISSION,
            TransactionCategory.CASHBACK, TransactionCategory.SUBSIDY, TransactionCategory.TRADE_HOLD,
            TransactionCategory.REFUND, TransactionCategory.ADMIN_ADJUSTMENT, TransactionCategory.MIGRATION,
            TransactionCategory.CURRENCY_EXCHANGE);

    private PolicyValidator() {}

    public static FinancialRule validate(FinancialRule rule, CurrencySpec currency) {
        if (rule.categories().stream().anyMatch(PROTECTED_CATEGORIES::contains)) {
            throw new IllegalArgumentException("Policies cannot target protected transaction categories");
        }
        if (rule.handlerVersion() != 1) {
            throw new IllegalArgumentException("Only handler version 1 is supported");
        }
        if (rule.kind() == PolicyKind.CUSTOM && rule.enabled()) {
            throw new IllegalArgumentException("CUSTOM rules cannot be enabled without a registered handler");
        }
        if (PERCENTAGE.contains(rule.kind())) validatePercentage(rule);
        if (rule.kind() == PolicyKind.LIMIT) validateLimit(rule, currency);
        if (rule.kind() == PolicyKind.EXEMPTION) validateExemption(rule);
        validateConditions(rule);
        return rule;
    }

    public static boolean policyEligible(TransactionCategory category) {
        return !PROTECTED_CATEGORIES.contains(category);
    }

    private static void validatePercentage(FinancialRule rule) {
        BigDecimal rate = decimal(rule, "rate");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("rate must be between 0 and 1");
        }
        if (rule.kind() == PolicyKind.TAX || rule.kind() == PolicyKind.FEE
                || rule.kind() == PolicyKind.COMMISSION) {
            TaxMode.valueOf(rule.definition().getOrDefault("mode", "INCLUDED").toUpperCase(Locale.ROOT));
            validateAccount(rule, "recipient");
        } else {
            validateAccount(rule, "funding");
        }
    }

    private static void validateLimit(FinancialRule rule, CurrencySpec currency) {
        String minimum = rule.definition().get("minimum");
        String maximum = rule.definition().get("maximum");
        if (minimum == null && maximum == null) {
            throw new IllegalArgumentException("LIMIT needs minimum and/or maximum");
        }
        BigDecimal min = minimum == null ? null : currency.requireAmount(new BigDecimal(minimum));
        BigDecimal max = maximum == null ? null : currency.requireAmount(new BigDecimal(maximum));
        if ((min != null && min.signum() < 0) || (max != null && max.signum() <= 0)
                || (min != null && max != null && max.compareTo(min) < 0)) {
            throw new IllegalArgumentException("Invalid LIMIT range");
        }
    }

    private static void validateExemption(FinancialRule rule) {
        String kinds = rule.definition().getOrDefault("kinds", "");
        if (kinds.isBlank()) throw new IllegalArgumentException("EXEMPTION needs kinds");
        for (String value : kinds.split(",")) {
            PolicyKind kind = PolicyKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (kind == PolicyKind.LIMIT || kind == PolicyKind.EXEMPTION || kind == PolicyKind.CUSTOM) {
                throw new IllegalArgumentException("That policy kind cannot be exempted: " + kind);
            }
        }
    }

    private static BigDecimal decimal(FinancialRule rule, String key) {
        String value = rule.definition().get(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return new BigDecimal(value);
    }

    private static void validateAccount(FinancialRule rule, String prefix) {
        String type = rule.definition().get(prefix + "-type");
        String id = rule.definition().get(prefix + "-id");
        if (type == null && id == null) return;
        if (type == null || id == null) throw new IllegalArgumentException("Incomplete " + prefix + " account");
        AccountType accountType = AccountType.valueOf(type.toUpperCase(Locale.ROOT));
        if (accountType == AccountType.SYSTEM_SOURCE || accountType == AccountType.SYSTEM_SINK) {
            throw new IllegalArgumentException("Policy accounts cannot be system source/sink");
        }
        if (accountType == AccountType.PLAYER) java.util.UUID.fromString(id);
        new AccountId(accountType, id);
    }

    private static void validateConditions(FinancialRule rule) {
        validateConditionAccount(rule, "source");
        validateConditionAccount(rule, "target");
        String key = rule.definition().get("condition-metadata-key");
        String value = rule.definition().get("condition-metadata-value");
        if ((key == null) != (value == null) || (key != null && (key.isBlank() || value.isBlank()))) {
            throw new IllegalArgumentException("Metadata condition needs both key and value");
        }
    }

    private static void validateConditionAccount(FinancialRule rule, String side) {
        String type = rule.definition().get("condition-" + side + "-type");
        String id = rule.definition().get("condition-" + side + "-id");
        if (type != null) AccountType.valueOf(type.toUpperCase(Locale.ROOT));
        if (id != null && id.isBlank()) throw new IllegalArgumentException("Blank " + side + " condition");
    }
}
