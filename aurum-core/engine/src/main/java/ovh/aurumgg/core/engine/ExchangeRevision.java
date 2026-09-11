package ovh.aurumgg.core.engine;

import java.time.Instant;
import java.util.Objects;

public record ExchangeRevision(ExchangeRule rule, String changedBy, String reason, Instant changedAt) {
    public ExchangeRevision {
        Objects.requireNonNull(rule, "rule");
        changedBy = Objects.requireNonNull(changedBy, "changedBy");
        reason = Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
