package ovh.aurumgg.companion.core.model;

import java.util.Map;

/** Exact full replacement submitted to AurumCore's preview phase. */
public record EconomyRuleMutation(
        String type, String id, long expectedRevision, Map<String, String> fields, String actor) {
    public EconomyRuleMutation {
        fields = Map.copyOf(fields);
    }
}
