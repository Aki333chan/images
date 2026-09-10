package ovh.aurumgg.core.engine;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import ovh.aurumgg.core.api.AccountId;
import ovh.aurumgg.core.api.TransactionCategory;

public record TaxRule(
        String id,
        Set<TransactionCategory> categories,
        BigDecimal rate,
        TaxMode mode,
        AccountId recipient,
        int priority,
        boolean enabled
) {
    public TaxRule {
        id = Objects.requireNonNull(id, "id").trim();
        categories = Set.copyOf(Objects.requireNonNull(categories, "categories"));
        rate = Objects.requireNonNull(rate, "rate").stripTrailingZeros();
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(recipient, "recipient");
        if (id.isEmpty() || id.length() > 64 || categories.isEmpty()) {
            throw new IllegalArgumentException("Tax rule needs an id and at least one category");
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Tax rate must be between 0 and 1");
        }
    }

    public boolean appliesTo(TransactionCategory category) {
        return enabled && categories.contains(category);
    }
}
