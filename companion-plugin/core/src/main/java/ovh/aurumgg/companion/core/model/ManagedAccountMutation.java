package ovh.aurumgg.companion.core.model;

import java.math.BigDecimal;

/** Trusted-panel request. Unused fields stay empty for the selected operation. */
public record ManagedAccountMutation(
        Operation operation,
        String idempotencyKey,
        String profileKey,
        String sourceRole,
        String secondaryProfile,
        String targetRole,
        String currency,
        BigDecimal amount,
        String displayName,
        String purpose,
        String actor,
        String reason
) {
    public enum Operation { CREATE_FUND, TRANSFER, CREDIT, DEBIT, FREEZE, UNFREEZE, CLOSE }
}
