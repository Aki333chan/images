package ovh.aurumgg.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Exact proposal that may be confirmed once with {@link AurumRulesAdminApi#apply}. */
public record RuleChangePreview(
        Status status,
        String token,
        RuleResource current,
        RuleResource proposed,
        List<String> warnings,
        String message,
        Instant expiresAt
) {
    public enum Status { READY, CONFLICT, INVALID, UNAVAILABLE }

    public RuleChangePreview {
        Objects.requireNonNull(status, "status");
        token = token == null ? "" : token;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        message = message == null ? "" : message;
        if (status == Status.READY && (token.isBlank() || proposed == null || expiresAt == null)) {
            throw new IllegalArgumentException("Ready preview needs a proposal token");
        }
    }
}
