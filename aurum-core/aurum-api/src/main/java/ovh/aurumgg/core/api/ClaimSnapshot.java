package ovh.aurumgg.core.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A claim as Core currently holds it.
 *
 * @param stepCursor how many of {@link #stepCount()} steps are already done.
 *                   Delivery resumes at this index, which is the whole point of
 *                   the record: a crash halfway through must not repeat the
 *                   steps that already took effect
 * @param attempts   how many times delivery has been handed out and given back
 * @param leaseUntil set while CLAIMED; once it passes, another worker may take
 *                   the claim even though the previous one never gave it back
 */
public record ClaimSnapshot(UUID id, String idempotencyKey, String plugin, UUID owner, String kind,
                            ClaimStatus status, int stepCursor, int stepCount, int attempts,
                            String summary, String payload, String lastError,
                            Optional<String> claimedBy, Optional<Instant> leaseUntil,
                            Instant createdAt, Instant updatedAt) {
    public ClaimSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        summary = Objects.requireNonNullElse(summary, "");
        payload = Objects.requireNonNullElse(payload, "");
        lastError = Objects.requireNonNullElse(lastError, "");
        claimedBy = Objects.requireNonNull(claimedBy, "claimedBy");
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (stepCursor < 0 || stepCount < 1 || stepCursor > stepCount || attempts < 0) {
            throw new IllegalArgumentException("Invalid claim progress");
        }
    }

    /** True once every step has been reported done. */
    public boolean complete() {
        return stepCursor >= stepCount;
    }
}
