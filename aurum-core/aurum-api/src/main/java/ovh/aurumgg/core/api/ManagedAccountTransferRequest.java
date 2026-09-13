package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Objects;

/** Transfer between concrete members of two registered profiles. */
public record ManagedAccountTransferRequest(
        String idempotencyKey,
        String fromProfile,
        String fromRole,
        String toProfile,
        String toRole,
        String currencyId,
        BigDecimal amount,
        String actor,
        String reason
) {
    public ManagedAccountTransferRequest {
        idempotencyKey = required(idempotencyKey, 191, "idempotency key");
        fromProfile = required(fromProfile, 191, "source profile").toLowerCase(java.util.Locale.ROOT);
        fromRole = role(fromRole);
        toProfile = required(toProfile, 191, "target profile").toLowerCase(java.util.Locale.ROOT);
        toRole = role(toRole);
        currencyId = required(currencyId, 32, "currency").toLowerCase(java.util.Locale.ROOT);
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        actor = required(actor, 128, "actor");
        reason = required(reason, 255, "reason");
    }

    private static String role(String value) {
        return value == null || value.isBlank() ? "primary"
                : required(value, 32, "member role").toLowerCase(java.util.Locale.ROOT);
    }
    private static String required(String value, int maximum, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must contain 1.." + maximum + " safe characters");
        }
        return value;
    }
}
