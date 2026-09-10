package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import ovh.aurumgg.core.api.TransactionRequest;

public record TransactionPlan(
        TransactionRequest request,
        BigDecimal sourceDebit,
        BigDecimal targetCredit,
        BigDecimal taxCredit,
        String appliedRuleId,
        List<LedgerPosting> postings
) {
    public TransactionPlan {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sourceDebit, "sourceDebit");
        Objects.requireNonNull(targetCredit, "targetCredit");
        Objects.requireNonNull(taxCredit, "taxCredit");
        postings = List.copyOf(postings);
        BigDecimal sum = postings.stream().map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) throw new IllegalArgumentException("Ledger postings are not balanced: " + sum);
    }
}
