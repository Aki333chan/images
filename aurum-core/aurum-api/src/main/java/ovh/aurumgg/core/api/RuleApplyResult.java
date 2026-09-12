package ovh.aurumgg.core.api;

import java.util.Objects;

/** Definitive outcome of consuming one preview token. */
public record RuleApplyResult(Status status, RuleResource current, String message) {
    public enum Status {
        APPLIED,
        APPLIED_RELOAD_FAILED,
        CONFLICT,
        INVALID,
        EXPIRED,
        UNAVAILABLE
    }

    public RuleApplyResult {
        Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }
}
