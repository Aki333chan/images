package ovh.aurumgg.core.api;

import java.util.List;
import java.util.Objects;

/** Idempotent registration used by Core and ecosystem plugins. */
public record ManagedAccountRegistration(
        String idempotencyKey,
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
        String closeDestinationProfile,
        boolean technical,
        List<ManagedAccountMember> members,
        String actor,
        String reason
) {
    public ManagedAccountRegistration {
        idempotencyKey = required(idempotencyKey, 191, "idempotency key");
        profileKey = required(profileKey, 191, "profile key").toLowerCase(java.util.Locale.ROOT);
        profileType = required(profileType, 32, "profile type").toUpperCase(java.util.Locale.ROOT);
        displayName = required(displayName, 128, "display name");
        purpose = optional(purpose, 255, "purpose");
        ownerKind = optional(ownerKind, 32, "owner kind").toUpperCase(java.util.Locale.ROOT);
        ownerId = optional(ownerId, 128, "owner id");
        founderUuid = optional(founderUuid, 36, "founder UUID");
        sourcePlugin = required(sourcePlugin, 64, "source plugin");
        linkedObjectType = optional(linkedObjectType, 32, "linked object type").toUpperCase(java.util.Locale.ROOT);
        linkedObjectId = optional(linkedObjectId, 128, "linked object id");
        closeDestinationProfile = optional(closeDestinationProfile, 191, "close destination")
                .toLowerCase(java.util.Locale.ROOT);
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty() || members.size() > 16) {
            throw new IllegalArgumentException("A managed account needs 1..16 ledger members");
        }
        if (members.stream().map(ManagedAccountMember::role).distinct().count() != members.size()) {
            throw new IllegalArgumentException("Managed account member roles must be unique");
        }
        actor = required(actor, 128, "actor");
        reason = required(reason, 255, "reason");
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
