package ovh.aurumgg.core.api;

/**
 * Where a promised delivery stands.
 *
 * <p>A claim exists because money and Minecraft state cannot change in one
 * transaction. A hold protects the money half; this protects the other half —
 * the items, ranks or console commands the player has already paid for.
 */
public enum ClaimStatus {
    /** Owed and nobody is delivering it right now. */
    PENDING,
    /** Leased to one worker. The lease expires, so a crash returns it to PENDING. */
    CLAIMED,
    /** Delivered in full. Terminal. */
    SETTLED,
    /** Delivery kept failing. Waits for an administrator. */
    QUARANTINED,
    /** An administrator decided it will never be delivered. Terminal. */
    DROPPED
}
