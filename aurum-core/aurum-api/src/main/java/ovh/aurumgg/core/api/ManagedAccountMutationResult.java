package ovh.aurumgg.core.api;

/** Definitive result of one idempotent registry mutation. */
public record ManagedAccountMutationResult(Status status, ManagedAccount account, String message) {
    public enum Status { SUCCESS, DUPLICATE, NOT_FOUND, CONFLICT, REJECTED, UNAVAILABLE }

    public ManagedAccountMutationResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        message = message == null ? "" : message;
    }
}
