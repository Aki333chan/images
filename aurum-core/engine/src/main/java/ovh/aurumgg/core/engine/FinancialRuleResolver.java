package ovh.aurumgg.core.engine;

import java.time.Instant;
import java.util.List;
import ovh.aurumgg.core.api.TransactionRequest;

@FunctionalInterface
public interface FinancialRuleResolver {
    List<FinancialRule> select(TransactionRequest request, Instant now);

    static FinancialRuleResolver none() {
        return (request, now) -> List.of();
    }
}
