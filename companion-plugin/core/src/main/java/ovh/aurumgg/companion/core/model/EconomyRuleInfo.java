package ovh.aurumgg.companion.core.model;

import java.util.Map;

/** Transport-neutral current revision of a policy or exchange rule. */
public record EconomyRuleInfo(String type, String id, long revision, Map<String, String> fields) {
    public EconomyRuleInfo {
        fields = Map.copyOf(fields);
    }
}
