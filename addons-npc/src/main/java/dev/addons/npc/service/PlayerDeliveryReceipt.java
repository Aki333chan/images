package dev.addons.npc.service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * A tiny crash receipt stored beside a player's inventory in player data.
 *
 * <p>Inventory and this marker are changed in one main-thread turn, then the
 * caller saves that one player's data before advancing the MariaDB cursor.
 * This is intentionally one small write per real item transaction: relying on
 * the normal autosave would let the database commit first and lose the item
 * state in a crash.
 */
final class PlayerDeliveryReceipt {

    private static final String PREFIX = "delivery_receipt_";

    private final Plugin plugin;

    PlayerDeliveryReceipt(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean has(Player player, UUID claimId, int step) {
        return player.getPersistentDataContainer().has(key(claimId, step), PersistentDataType.BYTE);
    }

    void mark(Player player, UUID claimId, int step) {
        player.getPersistentDataContainer().set(key(claimId, step), PersistentDataType.BYTE, (byte) 1);
    }

    void clear(Player player, UUID claimId, int step) {
        player.getPersistentDataContainer().remove(key(claimId, step));
    }

    /** Remove receipts whose claims are no longer owed; normally there are none. */
    void clean(Player player, Set<UUID> activeClaims) {
        Set<String> active = new HashSet<>(activeClaims.size());
        for (UUID id : activeClaims) active.add(compact(id));
        PersistentDataContainer data = player.getPersistentDataContainer();
        for (NamespacedKey candidate : Set.copyOf(data.getKeys())) {
            if (!candidate.getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT))) continue;
            String path = candidate.getKey();
            if (!path.startsWith(PREFIX)) continue;
            int idStart = PREFIX.length();
            int idEnd = idStart + 32;
            if (path.length() <= idEnd || path.charAt(idEnd) != '_'
                    || !active.contains(path.substring(idStart, idEnd))) {
                data.remove(candidate);
            }
        }
    }

    private NamespacedKey key(UUID claimId, int step) {
        return new NamespacedKey(plugin, PREFIX + compact(claimId) + "_" + step);
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }
}
