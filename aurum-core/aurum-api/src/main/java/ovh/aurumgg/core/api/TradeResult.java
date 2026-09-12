package ovh.aurumgg.core.api;

import java.util.Objects;
import java.util.Optional;

public record TradeResult(Status status, Optional<TradeSession> trade, String message) {
    public TradeResult {
        Objects.requireNonNull(status, "status");
        trade = Objects.requireNonNull(trade, "trade");
        message = Objects.requireNonNullElse(message, "");
    }

    public enum Status {
        SUCCESS,
        /** One of the players is already in a trade. */
        BUSY,
        /**
         * The trade is not in a state this call can act on — most often because
         * the offer changed between the player looking and clicking, which is
         * the case the revision exists to catch.
         */
        CONFLICT,
        NOT_FOUND,
        /** A side could not cover what it offered. Nothing moved. */
        INSUFFICIENT_FUNDS,
        UNAVAILABLE
    }

    public boolean ok() {
        return status == Status.SUCCESS;
    }
}
