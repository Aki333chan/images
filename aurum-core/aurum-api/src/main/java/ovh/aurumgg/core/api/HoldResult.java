package ovh.aurumgg.core.api;

import java.util.Objects;
import java.util.Optional;

public record HoldResult(Status status, Optional<HoldSnapshot> hold, String message) {
    public HoldResult {
        Objects.requireNonNull(status, "status");
        hold = Objects.requireNonNull(hold, "hold");
        message = Objects.requireNonNullElse(message, "");
    }
    public enum Status { SUCCESS, DUPLICATE, INSUFFICIENT_FUNDS, REJECTED, NOT_FOUND, UNAVAILABLE }
}
