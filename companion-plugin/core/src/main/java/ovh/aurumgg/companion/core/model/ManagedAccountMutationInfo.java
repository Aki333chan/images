package ovh.aurumgg.companion.core.model;

public record ManagedAccountMutationInfo(
        boolean ok, String status, String message, ManagedAccountInfo account
) {}
