package ovh.aurumgg.core.engine;

import java.util.Optional;
import ovh.aurumgg.core.api.TransactionRequest;

@FunctionalInterface
public interface TaxRuleResolver {
    Optional<TaxRule> select(TransactionRequest request);

    static TaxRuleResolver none() {
        return ignored -> Optional.empty();
    }
}
