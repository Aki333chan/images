package ovh.aurumgg.core.api;

/** Bounded searchable account-list request. */
public record ManagedAccountQuery(
        String search,
        String profileType,
        ManagedAccountStatus status,
        boolean includeTechnical,
        int offset,
        int limit
) {
    public ManagedAccountQuery {
        search = search == null ? "" : search.trim();
        profileType = profileType == null ? "" : profileType.trim().toUpperCase(java.util.Locale.ROOT);
        if (search.length() > 128 || profileType.length() > 32) {
            throw new IllegalArgumentException("Account query exceeds its safe limits");
        }
        if (offset < 0) throw new IllegalArgumentException("Offset cannot be negative");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
    }
}
