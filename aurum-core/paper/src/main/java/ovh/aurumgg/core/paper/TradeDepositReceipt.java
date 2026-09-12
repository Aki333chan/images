package ovh.aurumgg.core.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.core.api.TradeOffer;

/**
 * The half of an item deposit that lives with the player's inventory.
 *
 * <p>MariaDB and {@code player.dat} cannot share a transaction. This receipt
 * makes the safe ordering explicit: persist "item absent + receipt present",
 * then idempotently write the new offer. A crash between those writes retries
 * the same operation instead of losing or duplicating the stack.
 */
final class TradeDepositReceipt {

    private static final int MAGIC = 0x41545231; // ATR1
    private static final int MAX_BLOB = TradeOffer.MAX_ITEMS_BYTES;
    private final Plugin plugin;
    private final NamespacedKey key;

    TradeDepositReceipt(Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "trade_deposit_receipt");
    }

    boolean has(Player player) {
        return player.getPersistentDataContainer().has(key, PersistentDataType.BYTE_ARRAY);
    }

    void mark(Player player, Pending pending) {
        player.getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY, encode(pending));
    }

    Optional<Pending> read(Player player) {
        byte[] value = player.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
        if (value == null) return Optional.empty();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            if (input.readInt() != MAGIC) throw new IllegalArgumentException("unknown receipt version");
            String operationKey = input.readUTF();
            UUID tradeId = new UUID(input.readLong(), input.readLong());
            Optional<String> currency = input.readBoolean()
                    ? Optional.of(input.readUTF()) : Optional.empty();
            BigDecimal money = new BigDecimal(input.readUTF());
            int format = input.readInt();
            byte[] table = bytes(input);
            byte[] item = bytes(input);
            if (input.available() != 0) throw new IllegalArgumentException("trailing receipt data");
            return Optional.of(new Pending(operationKey, tradeId, currency, money, format, table, item));
        } catch (Exception unreadable) {
            // Never clear an unreadable receipt automatically: it is the only
            // remaining proof that an item was removed from this inventory.
            plugin.getLogger().warning("Unreadable outgoing trade receipt for " + player.getUniqueId()
                    + ": " + unreadable.getMessage());
            return Optional.empty();
        }
    }

    void clear(Player player) {
        player.getPersistentDataContainer().remove(key);
    }

    private static byte[] encode(Pending pending) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeUTF(pending.operationKey());
            output.writeLong(pending.tradeId().getMostSignificantBits());
            output.writeLong(pending.tradeId().getLeastSignificantBits());
            output.writeBoolean(pending.currencyId().isPresent());
            if (pending.currencyId().isPresent()) output.writeUTF(pending.currencyId().orElseThrow());
            output.writeUTF(pending.money().toPlainString());
            output.writeInt(pending.format());
            bytes(output, pending.tableBlob());
            bytes(output, pending.itemBlob());
            output.flush();
            return bytes.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encode outgoing trade receipt", failure);
        }
    }

    private static void bytes(DataOutputStream output, byte[] value) throws java.io.IOException {
        byte[] present = value == null ? new byte[0] : value;
        if (present.length > MAX_BLOB) throw new IllegalArgumentException("trade receipt blob is too large");
        output.writeInt(present.length);
        output.write(present);
    }

    private static byte[] bytes(DataInputStream input) throws java.io.IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BLOB || length > input.available()) {
            throw new IllegalArgumentException("invalid trade receipt blob length");
        }
        return input.readNBytes(length);
    }

    record Pending(String operationKey, UUID tradeId, Optional<String> currencyId,
                   BigDecimal money, int format, byte[] tableBlob, byte[] itemBlob) {
        Pending {
            if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("operationKey");
            if (tradeId == null || currencyId == null || money == null) throw new IllegalArgumentException("receipt");
            if (tableBlob != null && tableBlob.length > MAX_BLOB) throw new IllegalArgumentException("tableBlob");
            if (itemBlob != null && itemBlob.length > MAX_BLOB) throw new IllegalArgumentException("itemBlob");
            tableBlob = tableBlob == null ? null : tableBlob.clone();
            itemBlob = itemBlob == null ? null : itemBlob.clone();
        }

        @Override public byte[] tableBlob() { return tableBlob == null ? null : tableBlob.clone(); }
        @Override public byte[] itemBlob() { return itemBlob == null ? null : itemBlob.clone(); }

        TradeOffer offer(UUID owner) {
            return new TradeOffer(tradeId, owner, currencyId, money, tableBlob, format);
        }
    }
}
