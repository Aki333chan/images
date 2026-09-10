package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.AccountType;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionRequest;

public final class TransactionPlanner {
    private TransactionPlanner() {}

    /** Compatibility entry point retained for the original single-tax tests and migration code. */
    public static TransactionPlan plan(TransactionRequest request, CurrencySpec currency,
                                       Optional<TaxRule> selectedRule) {
        List<FinancialRule> rules = selectedRule.map(rule -> List.of(new FinancialRule(
                rule.id(), PolicyKind.TAX, 1, rule.categories(), Map.of(
                        "rate", rule.rate().toPlainString(),
                        "mode", rule.mode().name(),
                        "recipient-type", rule.recipient().type().name(),
                        "recipient-id", rule.recipient().reference()),
                rule.priority(), rule.enabled(), null, null))).orElseGet(List::of);
        return plan(request, currency, rules, Instant.EPOCH);
    }

    public static TransactionPlan plan(TransactionRequest request, CurrencySpec currency,
                                       List<FinancialRule> selectedRules, Instant now) {
        if (!currency.id().equals(request.currencyId())) {
            throw new IllegalArgumentException("Transaction currency does not match the selected currency");
        }
        BigDecimal gross = currency.requireAmount(request.amount());
        if (gross.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");

        List<FinancialRule> rules = selectedRules.stream()
                .filter(rule -> PolicyMatcher.matches(rule, request, now))
                .sorted(Comparator.comparingInt(FinancialRule::priority).reversed()
                        .thenComparing(FinancialRule::id))
                .toList();
        Set<PolicyKind> exemptions = exemptions(rules);
        rules.stream().filter(rule -> rule.kind() == PolicyKind.LIMIT)
                .forEach(rule -> enforceLimit(rule, gross, currency));

        BigDecimal taxCredit = zero(currency);
        Map<AccountId, BigDecimal> combined = new LinkedHashMap<>();
        add(combined, request.from(), gross.negate());
        add(combined, request.to(), gross);
        Map<String, BigDecimal> policyAmounts = new LinkedHashMap<>();
        List<String> appliedIds = new ArrayList<>();
        String appliedTaxId = null;

        for (FinancialRule rule : rules) {
            if (rule.kind() == PolicyKind.LIMIT || rule.kind() == PolicyKind.EXEMPTION
                    || exemptions.contains(rule.kind())) continue;
            if (rule.handlerVersion() != 1 || rule.kind() == PolicyKind.CUSTOM) {
                throw new PolicyRejectedException("Unsupported policy handler: " + rule.id());
            }
            BigDecimal value = percentage(gross, rate(rule), currency);
            switch (rule.kind()) {
                case TAX, FEE, COMMISSION -> {
                    TaxMode mode = mode(rule);
                    if (mode == TaxMode.INCLUDED) add(combined, request.to(), value.negate());
                    else add(combined, request.from(), value.negate());
                    add(combined, account(rule, "recipient", AccountId.globalTreasury()), value);
                    if (rule.kind() == PolicyKind.TAX) {
                        taxCredit = taxCredit.add(value);
                        if (appliedTaxId == null) appliedTaxId = rule.id();
                    }
                }
                case CASHBACK -> {
                    add(combined, account(rule, "funding", AccountId.globalTreasury()), value.negate());
                    add(combined, request.from(), value);
                }
                case SUBSIDY -> {
                    add(combined, account(rule, "funding", AccountId.globalTreasury()), value.negate());
                    add(combined, request.to(), value);
                }
                default -> throw new PolicyRejectedException("Unsupported policy kind: " + rule.kind());
            }
            appliedIds.add(rule.id());
            policyAmounts.put(rule.id(), value);
        }

        BigDecimal sourceDebit = combined.getOrDefault(request.from(), zero(currency)).negate();
        BigDecimal targetCredit = combined.getOrDefault(request.to(), zero(currency));
        if (sourceDebit.signum() < 0 || targetCredit.signum() < 0) {
            throw new PolicyRejectedException("Combined policies invert the payer or recipient amount");
        }

        List<LedgerPosting> postings = new ArrayList<>();
        combined.forEach((account, amount) -> {
            BigDecimal normalized = currency.requireAmount(amount);
            if (normalized.signum() != 0) postings.add(new LedgerPosting(account, normalized));
        });
        return new TransactionPlan(request, sourceDebit, targetCredit, taxCredit,
                appliedTaxId, appliedIds, policyAmounts, postings);
    }

    private static Set<PolicyKind> exemptions(List<FinancialRule> rules) {
        EnumSet<PolicyKind> result = EnumSet.noneOf(PolicyKind.class);
        rules.stream().filter(rule -> rule.kind() == PolicyKind.EXEMPTION).forEach(rule -> {
            String raw = rule.definition().getOrDefault("kinds", "TAX,FEE,COMMISSION");
            for (String value : raw.split(",")) {
                try { result.add(PolicyKind.valueOf(value.trim().toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException ignored) { /* invalid entries are rejected when the rule is saved */ }
            }
        });
        return result;
    }

    private static void enforceLimit(FinancialRule rule, BigDecimal gross, CurrencySpec currency) {
        String minimum = rule.definition().get("minimum");
        String maximum = rule.definition().get("maximum");
        if (minimum != null && gross.compareTo(currency.requireAmount(new BigDecimal(minimum))) < 0) {
            throw new PolicyRejectedException("Transaction is below policy limit " + rule.id());
        }
        if (maximum != null && gross.compareTo(currency.requireAmount(new BigDecimal(maximum))) > 0) {
            throw new PolicyRejectedException("Transaction exceeds policy limit " + rule.id());
        }
    }

    private static BigDecimal rate(FinancialRule rule) {
        BigDecimal rate;
        try { rate = new BigDecimal(rule.definition().getOrDefault("rate", "")); }
        catch (NumberFormatException exception) {
            throw new PolicyRejectedException("Invalid rate in policy " + rule.id());
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new PolicyRejectedException("Policy rate must be between 0 and 1: " + rule.id());
        }
        return rate;
    }

    private static TaxMode mode(FinancialRule rule) {
        try {
            return TaxMode.valueOf(rule.definition().getOrDefault("mode", "INCLUDED")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PolicyRejectedException("Invalid policy mode: " + rule.id());
        }
    }

    private static AccountId account(FinancialRule rule, String prefix, AccountId fallback) {
        String type = rule.definition().get(prefix + "-type");
        String id = rule.definition().get(prefix + "-id");
        if (type == null && id == null) return fallback;
        try {
            return new AccountId(AccountType.valueOf(type.toUpperCase(Locale.ROOT)), id);
        } catch (RuntimeException exception) {
            throw new PolicyRejectedException("Invalid " + prefix + " account in policy " + rule.id());
        }
    }

    private static BigDecimal percentage(BigDecimal amount, BigDecimal rate, CurrencySpec currency) {
        return amount.multiply(rate).setScale(currency.scale(), RoundingMode.HALF_UP);
    }

    private static BigDecimal zero(CurrencySpec currency) {
        return BigDecimal.ZERO.setScale(currency.scale());
    }

    private static void add(Map<AccountId, BigDecimal> postings, AccountId account, BigDecimal amount) {
        postings.merge(account, amount, BigDecimal::add);
    }
}
