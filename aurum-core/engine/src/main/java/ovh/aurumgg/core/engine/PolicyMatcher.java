package ovh.aurumgg.core.engine;

import java.time.Instant;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.TransactionRequest;

public final class PolicyMatcher {
    private PolicyMatcher() {}

    public static boolean matches(FinancialRule rule, TransactionRequest request, Instant now) {
        if (!rule.activeFor(request.category(), now)) return false;
        return accountMatches(rule, "source", request.from())
                && accountMatches(rule, "target", request.to())
                && metadataMatches(rule, request);
    }

    private static boolean accountMatches(FinancialRule rule, String side, AccountId account) {
        String type = rule.definition().get("condition-" + side + "-type");
        String id = rule.definition().get("condition-" + side + "-id");
        return (type == null || type.equalsIgnoreCase(account.type().name()))
                && (id == null || id.equals("*") || id.equalsIgnoreCase(account.reference()));
    }

    private static boolean metadataMatches(FinancialRule rule, TransactionRequest request) {
        String key = rule.definition().get("condition-metadata-key");
        String value = rule.definition().get("condition-metadata-value");
        if (key == null && value == null) return true;
        return key != null && value != null && value.equalsIgnoreCase(request.metadata().get(key));
    }
}
