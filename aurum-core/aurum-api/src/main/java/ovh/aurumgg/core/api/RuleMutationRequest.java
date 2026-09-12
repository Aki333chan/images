package ovh.aurumgg.core.api;

import java.util.Map;
import java.util.Objects;

/** Proposed complete replacement; expectedRevision=0 means create-only. */
public record RuleMutationRequest(
        RuleType type,
        String id,
        long expectedRevision,
        Map<String, String> fields
) {
    public RuleMutationRequest {
        Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id").trim();
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
        // RuleResource owns all transport limits and stable id validation.
        new RuleResource(type, id, expectedRevision, fields);
    }
}
