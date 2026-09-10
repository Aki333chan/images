package ovh.aurumgg.core.engine;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ovh.aurumgg.core.api.TransactionCategory;

/** Persisted, versioned rule envelope interpreted by a PolicyKind handler. */
public record FinancialRule(
        String id,
        PolicyKind kind,
        int handlerVersion,
        Set<TransactionCategory> categories,
        Map<String, String> definition,
        int priority,
        boolean enabled,
        Instant effectiveFrom,
        Instant effectiveUntil
) {
    public FinancialRule {
        id = Objects.requireNonNull(id, "id").trim();
        Objects.requireNonNull(kind, "kind");
        categories = Set.copyOf(Objects.requireNonNull(categories, "categories"));
        definition = Map.copyOf(Objects.requireNonNull(definition, "definition"));
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")
                || handlerVersion < 1 || categories.isEmpty() || definition.size() > 16
                || definition.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                || entry.getKey().length() > 64 || entry.getValue().length() > 256)) {
            throw new IllegalArgumentException("Invalid financial rule");
        }
        if (effectiveFrom != null && effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("Rule end must be after its start");
        }
    }

    public boolean activeFor(TransactionCategory category, Instant now) {
        return enabled
                && categories.contains(category)
                && (effectiveFrom == null || !now.isBefore(effectiveFrom))
                && (effectiveUntil == null || now.isBefore(effectiveUntil));
    }
}
