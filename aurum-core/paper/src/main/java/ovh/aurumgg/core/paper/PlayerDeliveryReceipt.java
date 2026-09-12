package ovh.aurumgg.core.paper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** A crash receipt persisted in the same player data as the inventory it protects. */
final class PlayerDeliveryReceipt {

    private static final String PREFIX = "trade_receipt_";

    private final Plugin plugin;

    PlayerDeliveryReceipt(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean has(Player player, UUID claimId) {
        return player.getPersistentDataContainer().has(key(claimId), PersistentDataType.BYTE);
    }

    void mark(Player player, UUID claimId) {
        player.getPersistentDataContainer().set(key(claimId), PersistentDataType.BYTE, (byte) 1);
    }

    void clear(Player player, UUID claimId) {
        player.getPersistentDataContainer().remove(key(claimId));
    }

    void clean(Player player, Set<UUID> activeClaims) {
        Set<String> active = new HashSet<>(activeClaims.size());
        for (UUID id : activeClaims) active.add(compact(id));
        PersistentDataContainer data = player.getPersistentDataContainer();
        for (NamespacedKey candidate : Set.copyOf(data.getKeys())) {
            if (!candidate.getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT))) continue;
            String path = candidate.getKey();
            if (path.startsWith(PREFIX) && (path.length() != PREFIX.length() + 32
                    || !active.contains(path.substring(PREFIX.length())))) {
                data.remove(candidate);
            }
        }
    }

    private NamespacedKey key(UUID claimId) {
        return new NamespacedKey(plugin, PREFIX + compact(claimId));
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }
}
