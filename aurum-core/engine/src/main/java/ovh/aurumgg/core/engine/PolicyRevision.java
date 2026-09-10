package ovh.aurumgg.core.engine;

import java.time.Instant;
import java.util.Objects;

public record PolicyRevision(FinancialRule rule, long revision, String changedBy,
                             String reason, Instant changedAt) {
    public PolicyRevision {
        Objects.requireNonNull(rule, "rule");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        changedBy = Objects.requireNonNull(changedBy, "changedBy");
        reason = Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
