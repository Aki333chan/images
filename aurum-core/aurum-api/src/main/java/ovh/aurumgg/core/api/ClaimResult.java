package ovh.aurumgg.core.api;

import java.util.Objects;
import java.util.Optional;

public record ClaimResult(Status status, Optional<ClaimSnapshot> claim, String message) {
    public ClaimResult {
        Objects.requireNonNull(status, "status");
        claim = Objects.requireNonNull(claim, "claim");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status {
        SUCCESS,
        /** The same idempotency key already promised this; the existing claim is returned. */
        DUPLICATE,
        NOT_FOUND,
        /**
         * The claim is not in a state this call can act on — someone else holds
         * the lease, or it is already settled. Never an error to retry blindly.
         */
        CONFLICT,
        UNAVAILABLE
    }

    public boolean ok() {
        return status == Status.SUCCESS || status == Status.DUPLICATE;
    }
}
