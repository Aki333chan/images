package ovh.aurumgg.core.api;

/** Independent audit views; one request never loads all tables at once. */
public enum EconomyAuditSection {
    OVERVIEW,
    LEDGER,
    POLICIES,
    EXCHANGES,
    HOLDS,
    CLAIMS
}
