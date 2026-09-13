package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Metadata and current balances for one logical economy object. */
public record ManagedAccount(
        String profileKey,
        String profileType,
        String displayName,
        String purpose,
        String ownerKind,
        String ownerId,
        String founderUuid,
        String sourcePlugin,
        String linkedObjectType,
        String linkedObjectId,
        ManagedAccountStatus status,
        String closeDestinationProfile,
        boolean technical,
        List<ManagedAccountMember> members,
        Map<String, BigDecimal> balances,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt
) {
    public ManagedAccount {
        profileKey = required(profileKey, 191, "profile key");
        profileType = required(profileType, 32, "profile type");
        displayName = required(displayName, 128, "display name");
        purpose = optional(purpose, 255, "purpose");
        ownerKind = optional(ownerKind, 32, "owner kind");
        ownerId = optional(ownerId, 128, "owner id");
        founderUuid = optional(founderUuid, 36, "founder UUID");
        sourcePlugin = required(sourcePlugin, 64, "source plugin");
        linkedObjectType = optional(linkedObjectType, 32, "linked object type");
        linkedObjectId = optional(linkedObjectId, 128, "linked object id");
        Objects.requireNonNull(status, "status");
        closeDestinationProfile = optional(closeDestinationProfile, 191, "close destination");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        balances = Map.copyOf(Objects.requireNonNull(balances, "balances"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String required(String value, int maximum, String label) {
        value = optional(value, maximum, label);
        if (value.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private static String optional(String value, int maximum, String label) {
        value = value == null ? "" : value.trim();
        if (value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " exceeds its safe limit");
        }
        return value;
    }
}
