package ovh.aurumgg.core.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One side's half of a trade.
 *
 * <h2>Items are bytes to Core</h2>
 *
 * The items a player puts up are serialized by the game side and stored here as
 * an opaque blob with a format version. Core never looks inside: what an item
 * <em>is</em> belongs to Minecraft, and a version stamp is what lets a later
 * build recognise a blob it can no longer read instead of handing back
 * something subtly wrong.
 *
 * <h2>Money is a number here, not a reservation</h2>
 *
 * The offered amount is recorded as soon as it is offered, but the money is
 * only reserved when that side confirms. Reserving on every keystroke would
 * mean creating and releasing a hold for each edit; reserving on confirm gives
 * the property that actually matters — a confirmed offer is a funded one — at a
 * fraction of the churn.
 *
 * @param items null when this side offers no items at all
 */
public record TradeOffer(UUID tradeId, UUID owner, Optional<String> currencyId, BigDecimal money,
                         byte[] items, int itemsFormatVersion) {

    /** Blobs are a trade window's worth of items, not bulk storage. */
    public static final int MAX_ITEMS_BYTES = 1_000_000;

    public TradeOffer {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(owner, "owner");
        currencyId = Objects.requireNonNull(currencyId, "currencyId");
        money = Objects.requireNonNullElse(money, BigDecimal.ZERO);
        if (money.signum() < 0) throw new IllegalArgumentException("A trade offer cannot be negative");
        if (money.signum() > 0 && currencyId.isEmpty()) {
            throw new IllegalArgumentException("Offered money needs a currency");
        }
        if (items != null && items.length > MAX_ITEMS_BYTES) {
            throw new IllegalArgumentException("Trade item blob is too large");
        }
        if (itemsFormatVersion < 1) throw new IllegalArgumentException("Invalid item format version");
    }

    /** An empty offer is legal: one side may be giving something away. */
    public boolean empty() {
        return money.signum() == 0 && (items == null || items.length == 0);
    }

    public boolean hasMoney() {
        return money.signum() > 0;
    }
}
