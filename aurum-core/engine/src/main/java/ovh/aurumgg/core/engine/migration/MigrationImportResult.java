package ovh.aurumgg.core.engine.migration;

import java.util.Objects;

public record MigrationImportResult(Status status, MigrationRunSummary summary, int imported, String message) {
    public MigrationImportResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status { VERIFIED, BLOCKED, FAILED }
}
