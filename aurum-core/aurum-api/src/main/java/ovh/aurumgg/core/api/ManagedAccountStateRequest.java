package ovh.aurumgg.core.api;

import java.util.Objects;

public record ManagedAccountStateRequest(
        String idempotencyKey,
        String profileKey,
        boolean frozen,
        String actor,
        String reason
) {
    public ManagedAccountStateRequest {
        idempotencyKey = text(idempotencyKey, 191, "idempotency key");
        profileKey = text(profileKey, 191, "profile key").toLowerCase(java.util.Locale.ROOT);
        actor = text(actor, 128, "actor");
        reason = text(reason, 255, "reason");
    }
    private static String text(String value, int maximum, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must contain 1.." + maximum + " safe characters");
        }
        return value;
    }
}
