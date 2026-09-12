package ovh.aurumgg.companion.core.model;

import java.util.List;

/** Preview response; only READY carries a one-use confirmation token. */
public record EconomyRulePreview(
        String status,
        String token,
        EconomyRuleInfo current,
        EconomyRuleInfo proposed,
        List<String> warnings,
        String message,
        long expiresAt
) {
    public EconomyRulePreview {
        warnings = List.copyOf(warnings);
    }
}
