package ovh.aurumgg.core.api;

/** Lifecycle of a managed account profile. Ledger rows are never deleted. */
public enum ManagedAccountStatus {
    ACTIVE,
    FROZEN,
    CLOSING,
    CLOSED
}
