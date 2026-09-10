package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.api.TransactionRequest;

public final class TransactionPlanner {
    private TransactionPlanner() {}

    public static TransactionPlan plan(
            TransactionRequest request,
            CurrencySpec currency,
            Optional<TaxRule> selectedRule
    ) {
        if (!currency.id().equals(request.currencyId())) {
            throw new IllegalArgumentException("Transaction currency does not match the selected currency");
        }
        TaxRule rule = selectedRule.filter(it -> it.appliesTo(request.category())).orElse(null);
        TaxBreakdown breakdown = rule == null || rule.recipient().equals(request.to())
                ? TaxCalculator.untaxed(request.amount(), currency)
                : TaxCalculator.calculate(request.amount(), currency, rule);

        Map<AccountId, BigDecimal> combined = new LinkedHashMap<>();
        add(combined, request.from(), breakdown.sourceDebit().negate());
        add(combined, request.to(), breakdown.targetCredit());
        if (breakdown.treasuryCredit().signum() != 0) {
            add(combined, rule.recipient(), breakdown.treasuryCredit());
        }

        List<LedgerPosting> postings = new ArrayList<>();
        combined.forEach((account, amount) -> {
            if (amount.signum() != 0) postings.add(new LedgerPosting(account, amount));
        });
        return new TransactionPlan(
                request,
                breakdown.sourceDebit(),
                breakdown.targetCredit(),
                breakdown.treasuryCredit(),
                rule == null ? null : rule.id(),
                postings
        );
    }

    private static void add(Map<AccountId, BigDecimal> postings, AccountId account, BigDecimal amount) {
        postings.merge(account, amount, BigDecimal::add);
    }
}
