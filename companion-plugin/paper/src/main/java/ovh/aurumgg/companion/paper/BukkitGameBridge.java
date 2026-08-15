package ovh.aurumgg.companion.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.companion.core.GameBridge;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PlayerInfo;

/**
 * Реализация моста поверх Bukkit API.
 *
 * Ключевой момент: Bukkit не потокобезопасен, а HTTP-обработчики выполняются
 * на своих потоках. Поэтому каждый вызов уезжает в основной поток через
 * callSyncMethod и ждёт результата с таймаутом — если сервер завис (например,
 * генерирует чанки), запрос панели завершится ошибкой, а не повиснет навсегда.
 */
public final class BukkitGameBridge implements GameBridge {

    private static final long SYNC_TIMEOUT_SECONDS = 3;

    private final Plugin plugin;

    public BukkitGameBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    private <T> T callSync(Callable<T> callable, T fallback) {
        // Если мы уже в основном потоке, лишний прыжок не нужен.
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка обращения к игре: " + e);
                return fallback;
            }
        }
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, callable);
        try {
            return future.get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            plugin.getLogger().warning("Основной поток не ответил вовремя: " + e);
            return fallback;
        }
    }

    @Override
    public List<PlayerInfo> onlinePlayers() {
        return callSync(
                () -> {
                    List<PlayerInfo> result = new ArrayList<>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        result.add(new PlayerInfo(
                                player.getUniqueId(),
                                player.getName(),
                                player.getHealth(),
                                maxHealthOf(player),
                                player.getWorld().getName(),
                                player.getLocation().getX(),
                                player.getLocation().getY(),
                                player.getLocation().getZ(),
                                player.getPing()));
                    }
                    return result;
                },
                List.of());
    }

    private static double maxHealthOf(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    @Override
    public Optional<InventoryInfo> inventory(UUID playerUuid) {
        return callSync(
                () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player == null) return Optional.<InventoryInfo>empty();
                    PlayerInventory inv = player.getInventory();

                    List<ItemInfo> items = new ArrayList<>();
                    for (int slot = 0; slot < 36; slot++) {
                        ItemInfo info = describe(inv.getItem(slot), slot);
                        if (info != null) items.add(info);
                    }

                    List<ItemInfo> armor = new ArrayList<>();
                    ItemStack[] armorContents = inv.getArmorContents();
                    for (int slot = 0; slot < armorContents.length; slot++) {
                        ItemInfo info = describe(armorContents[slot], slot);
                        if (info != null) armor.add(info);
                    }

                    return Optional.of(new InventoryInfo(items, armor, describe(inv.getItemInOffHand(), 0)));
                },
                Optional.empty());
    }

    /** null для пустого слота — пустые слоты в JSON не передаются. */
    private static ItemInfo describe(ItemStack stack, int slot) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
            return null;
        }
        String displayName = null;
        List<String> lore = List.of();
        Map<String, Integer> enchantments = new LinkedHashMap<>();

        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    displayName = org.bukkit.ChatColor.stripColor(meta.getDisplayName());
                }
                if (meta.hasLore() && meta.getLore() != null) {
                    List<String> plain = new ArrayList<>();
                    for (String line : meta.getLore()) {
                        plain.add(org.bukkit.ChatColor.stripColor(line));
                    }
                    lore = plain;
                }
            }
        }
        stack.getEnchantments()
                .forEach((enchantment, level) -> enchantments.put(enchantment.getKey().toString(), level));

        return new ItemInfo(
                slot,
                stack.getType().getKey().toString(),
                stack.getAmount(),
                displayName,
                enchantments,
                lore);
    }

    @Override
    public boolean setInventorySlot(UUID playerUuid, int slot, ItemSpec spec) {
        return callSync(
                () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player == null) return false;
                    if (spec.isClear()) {
                        player.getInventory().setItem(slot, null);
                        return true;
                    }
                    Material material = Material.matchMaterial(spec.id());
                    if (material == null || material == Material.AIR) return false;
                    player.getInventory().setItem(slot, new ItemStack(material, spec.count()));
                    return true;
                },
                false);
    }

    @Override
    public void sendMessage(UUID playerUuid, String message) {
        callSync(
                () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player != null) player.sendMessage(message);
                    return true;
                },
                false);
    }
}
