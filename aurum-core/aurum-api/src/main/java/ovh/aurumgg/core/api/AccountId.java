package ovh.aurumgg.core.api;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record AccountId(AccountType type, String reference) {
    public AccountId {
        Objects.requireNonNull(type, "type");
        reference = Objects.requireNonNull(reference, "reference").trim();
        if (reference.isEmpty() || reference.length() > 128) {
            throw new IllegalArgumentException("Account reference must contain 1..128 characters");
        }
    }

    public static AccountId player(UUID playerId) {
        return new AccountId(AccountType.PLAYER, Objects.requireNonNull(playerId, "playerId").toString());
    }

    public static AccountId globalTreasury() {
        return new AccountId(AccountType.TREASURY, "global");
    }

    public String stableKey() {
        return type.name().toLowerCase(Locale.ROOT) + ":" + reference;
    }
}
