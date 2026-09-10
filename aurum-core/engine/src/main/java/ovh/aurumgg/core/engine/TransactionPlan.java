package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ovh.aurumgg.core.api.TransactionRequest;

public record TransactionPlan(
        TransactionRequest request,
        BigDecimal sourceDebit,
        BigDecimal targetCredit,
        BigDecimal taxCredit,
        String appliedRuleId,
        List<String> appliedRuleIds,
        Map<String, BigDecimal> policyAmounts,
        List<LedgerPosting> postings
) {
    public TransactionPlan {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sourceDebit, "sourceDebit");
        Objects.requireNonNull(targetCredit, "targetCredit");
        Objects.requireNonNull(taxCredit, "taxCredit");
        appliedRuleIds = List.copyOf(appliedRuleIds);
        policyAmounts = Map.copyOf(policyAmounts);
        postings = List.copyOf(postings);
        BigDecimal sum = postings.stream().map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) throw new IllegalArgumentException("Ledger postings are not balanced: " + sum);
    }

    public TransactionPlan(TransactionRequest request, BigDecimal sourceDebit, BigDecimal targetCredit,
                           BigDecimal taxCredit, String appliedRuleId, List<LedgerPosting> postings) {
        this(request, sourceDebit, targetCredit, taxCredit, appliedRuleId,
                appliedRuleId == null ? List.of() : List.of(appliedRuleId),
                appliedRuleId == null ? Map.of() : Map.of(appliedRuleId, taxCredit), postings);
    }
}
