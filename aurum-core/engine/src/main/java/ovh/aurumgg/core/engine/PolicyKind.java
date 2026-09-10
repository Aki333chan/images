package ovh.aurumgg.core.engine;

/** Built-in policy families. CUSTOM is reserved for a versioned future handler. */
public enum PolicyKind {
    TAX,
    FEE,
    COMMISSION,
    CASHBACK,
    SUBSIDY,
    LIMIT,
    EXEMPTION,
    CUSTOM
}
