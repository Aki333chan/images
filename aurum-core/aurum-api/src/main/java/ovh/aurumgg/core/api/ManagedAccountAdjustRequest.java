package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Administrative credit or debit of one managed-account member.
 *
 * <p>Unlike a transfer, this has only one side: the money is created from the
 * system source or destroyed into the system sink, so the server's money supply
 * changes. That is exactly why it is a separate request with its own reason —
 * every such adjustment must be attributable in the audit to a person and a
 * stated purpose.</p>
 *
 * @param credit true adds money to the member, false takes it away
 */
public record ManagedAccountAdjustRequest(
        String idempotencyKey,
        String profileKey,
        String role,
        String currencyId,
        BigDecimal amount,
        boolean credit,
        String actor,
        String reason
) {
    public ManagedAccountAdjustRequest {
        idempotencyKey = required(idempotencyKey, 191, "idempotency key");
        profileKey = required(profileKey, 191, "profile").toLowerCase(java.util.Locale.ROOT);
        role = role == null || role.isBlank() ? "primary"
                : required(role, 32, "member role").toLowerCase(java.util.Locale.ROOT);
        currencyId = required(currencyId, 32, "currency").toLowerCase(java.util.Locale.ROOT);
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        actor = required(actor, 128, "actor");
        reason = required(reason, 255, "reason");
    }

    private static String required(String value, int maximum, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must contain 1.." + maximum + " safe characters");
        }
        return value;
    }
}
