package ovh.aurumgg.core.api;

/**
 * Where a guaranteed trade stands.
 *
 * <p>The states exist because a trade is the one operation where both sides can
 * change their mind, and both sides' goods are already out of their hands by the
 * time anyone confirms.
 */
public enum TradeState {
    /** Offered to the other player, waiting for them to accept. */
    INVITED,
    /** Both are in the trade and may change their offers. */
    OPEN,
    /** Both confirmed the same revision. Nothing may change any more. */
    CONFIRMED,
    /** Money and goods are moving. */
    SETTLING,
    /** Done. Terminal. */
    SETTLED,
    /** Called off, by either side or by an administrator. Both offers go back. */
    CANCELLED,
    /** Nobody finished it in time. Both offers go back. */
    EXPIRED;

    /** Can offers still be edited? */
    public boolean editable() {
        return this == OPEN;
    }

    public boolean finished() {
        return this == SETTLED || this == CANCELLED || this == EXPIRED;
    }
}
