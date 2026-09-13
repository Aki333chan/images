package ovh.aurumgg.companion.core.model;

import java.util.List;
import java.util.Map;

public record ManagedAccountInfo(
        String key, String type, String name, String purpose,
        String ownerKind, String ownerId, String founderUuid,
        String sourcePlugin, String linkedObjectType, String linkedObjectId,
        String status, String closeDestination, boolean technical,
        List<Member> members, Map<String, String> balances,
        long createdAt, long updatedAt, Long closedAt
) {
    public record Member(String role, String account, int order) {}
}
