package ovh.aurumgg.core.engine;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import ovh.aurumgg.core.api.TransactionRequest;

public final class PolicyRegistry implements FinancialRuleResolver {
    private final boolean enabled;
    private final String primaryCurrencyId;
    private final AtomicReference<List<FinancialRule>> rules = new AtomicReference<>(List.of());

    public PolicyRegistry(boolean enabled, String primaryCurrencyId) {
        this.enabled = enabled;
        this.primaryCurrencyId = primaryCurrencyId;
    }

    public void replace(List<FinancialRule> updated) {
        rules.set(updated.stream().sorted(Comparator.comparingInt(FinancialRule::priority).reversed()
                .thenComparing(FinancialRule::id)).toList());
    }

    public List<FinancialRule> snapshot() {
        return rules.get();
    }

    @Override
    public List<FinancialRule> select(TransactionRequest request, Instant now) {
        if (!enabled) return List.of();
        return rules.get().stream()
                .filter(rule -> PolicyMatcher.matches(rule, request, now, primaryCurrencyId)).toList();
    }
}
