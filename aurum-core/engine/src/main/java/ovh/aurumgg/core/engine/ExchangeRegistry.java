package ovh.aurumgg.core.engine;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ovh.aurumgg.core.api.AccountId;

public final class ExchangeRegistry {
    private final AtomicReference<List<ExchangeRule>> rules = new AtomicReference<>(List.of());

    public void replace(List<ExchangeRule> updated) {
        rules.set(updated.stream().sorted(Comparator.comparingInt(ExchangeRule::priority).reversed()
                .thenComparing(ExchangeRule::id)).toList());
    }

    public List<ExchangeRule> snapshot() { return rules.get(); }

    public java.util.Optional<ExchangeRule> select(AccountId account, String from, String to,
                                                   Map<String, String> metadata, Instant now) {
        return rules.get().stream().filter(rule -> rule.activeAt(now)
                && rule.fromCurrencyId().equalsIgnoreCase(from)
                && rule.toCurrencyId().equalsIgnoreCase(to)
                && conditions(rule, account, metadata)).findFirst();
    }

    private static boolean conditions(ExchangeRule rule, AccountId account, Map<String, String> metadata) {
        String type = rule.conditions().get("account-type");
        String id = rule.conditions().get("account-id");
        String key = rule.conditions().get("metadata-key");
        String value = rule.conditions().get("metadata-value");
        return (type == null || type.equalsIgnoreCase(account.type().name()))
                && (id == null || id.equals("*") || id.equalsIgnoreCase(account.reference()))
                && (key == null && value == null || key != null && value != null
                && value.equalsIgnoreCase(metadata.get(key)));
    }
}
