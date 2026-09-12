package ovh.aurumgg.core.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Items on a trade table, as bytes Core can keep.
 *
 * <h2>Why Bukkit's own stream and not a format of our own</h2>
 *
 * An item is not a material and a count. It is enchantments, durability,
 * custom names, lore, attribute modifiers, book pages, and whatever the next
 * game version adds. A hand-rolled encoding does not fail loudly on any of
 * that — it hands back a sword that quietly lost its enchantments, and the
 * player who paid for it has no way to prove what it was.
 *
 * <h2>The version stamp is not decoration</h2>
 *
 * A blob written by a later build may be unreadable by an earlier one, and a
 * blob written before a game update may not round-trip cleanly after it.
 * {@link #FORMAT} travels with the blob so an unreadable one is recognised as
 * unreadable — and quarantined for a person — instead of being guessed at.
 */
final class TradeItems {

    /** Bump this when the encoding changes, never when the items do. */
    static final int FORMAT = 1;

    private TradeItems() {}

    /** Empty list encodes to null: "this side offered no items" is a real answer. */
    static byte[] encode(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return null;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream stream = new BukkitObjectOutputStream(bytes)) {
            stream.writeInt(items.size());
            for (ItemStack item : items) stream.writeObject(item);
            stream.flush();
            return bytes.toByteArray();
        } catch (Exception failure) {
            // Refusing to record the offer is the right failure: the alternative
            // is a trade whose stored half is silently wrong.
            throw new IllegalStateException("Could not serialize trade items", failure);
        }
    }

    /**
     * Read a blob back.
     *
     * <p>Empty means it cannot be read at all — a truncated write, a downgrade,
     * an item type this build no longer knows. The caller quarantines rather
     * than handing over a guess.
     */
    static Optional<List<ItemStack>> decode(byte[] blob, int version) {
        if (blob == null || blob.length == 0) return Optional.of(List.of());
        if (version != FORMAT) return Optional.empty();
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(blob);
                BukkitObjectInputStream stream = new BukkitObjectInputStream(bytes)) {
            int count = stream.readInt();
            if (count < 0 || count > 1024) return Optional.empty();
            List<ItemStack> items = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                Object read = stream.readObject();
                if (!(read instanceof ItemStack item)) return Optional.empty();
                items.add(item);
            }
            return Optional.of(List.copyOf(items));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /** One line for a chat summary or the claims listing. */
    static String describe(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return "-";
        StringBuilder text = new StringBuilder();
        for (ItemStack item : items) {
            if (!text.isEmpty()) text.append(", ");
            text.append(item.getAmount()).append("x ")
                    .append(item.getType().name().toLowerCase(java.util.Locale.ROOT));
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text.toString();
    }
}
