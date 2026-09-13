package ovh.aurumgg.core.api;

import java.util.Objects;

/** One ledger account exposed inside a managed profile. */
public record ManagedAccountMember(AccountId account, String role, int displayOrder) {
    public ManagedAccountMember {
        Objects.requireNonNull(account, "account");
        role = text(role, 32, "member role").toLowerCase(java.util.Locale.ROOT);
        if (displayOrder < 0 || displayOrder > 10_000) {
            throw new IllegalArgumentException("Display order must be between 0 and 10000");
        }
    }

    private static String text(String value, int maximum, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must contain 1.." + maximum + " safe characters");
        }
        return value;
    }
}
