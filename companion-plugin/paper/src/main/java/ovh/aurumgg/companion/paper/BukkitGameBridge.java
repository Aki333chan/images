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
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;

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
                        ItemInfo info = ItemMapper.describe(slot, inv.getItem(slot));
                        if (info != null) items.add(info);
                    }

                    List<ItemInfo> armor = new ArrayList<>();
                    ItemStack[] armorContents = inv.getArmorContents();
                    for (int slot = 0; slot < armorContents.length; slot++) {
                        ItemInfo info = ItemMapper.describe(slot, armorContents[slot]);
                        if (info != null) armor.add(info);
                    }

                    return Optional.of(new InventoryInfo(items, armor, ItemMapper.describe(0, inv.getItemInOffHand())));
                },
                Optional.empty());
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

    /**
     * Автодополнение силами самого сервера.
     *
     * Bukkit.getServer().getCommandMap() отдаёт карту команд, а её tabComplete
     * делает ровно то же, что происходит по Tab в клиенте: одно слово — имена
     * команд, дальше — аргументы конкретной команды, включая команды плагинов.
     * Строка передаётся без ведущего слэша — так требует контракт метода.
     *
     * Отправитель — консоль сервера: команды из панели выполняются от её имени,
     * и подсказки должны совпадать с тем, что консоли реально доступно. Права
     * учитываются самим tabComplete (testPermissionSilent), поэтому список
     * ничего лишнего не покажет.
     *
     * Обязательно в основном потоке: обход карты команд и вызовы
     * TabCompleter плагинов не потокобезопасны. Вернуть может null — это
     * штатный ответ «команда не найдена», а не ошибка.
     */
    @Override
    public List<String> completeCommand(String line) {
        return callSync(
                () -> {
                    List<String> completions =
                            Bukkit.getServer().getCommandMap().tabComplete(Bukkit.getConsoleSender(), line);
                    return completions == null ? List.<String>of() : List.copyOf(completions);
                },
                List.of());
    }

    // ---------- Интеграции со сторонними плагинами ----------

    @Override
    public List<PluginInfo> installedPlugins() {
        // Чтение списка плагинов основного потока не требует, но делаем это
        // через callSync для единообразия: PluginManager может меняться при
        // горячей перезагрузке, и снимок из основного потока согласован.
        return callSync(
                () -> {
                    List<PluginInfo> result = new ArrayList<>();
                    for (Plugin installed : Bukkit.getPluginManager().getPlugins()) {
                        result.add(new PluginInfo(
                                installed.getName(),
                                installed.getPluginMeta().getVersion(),
                                installed.isEnabled()));
                    }
                    result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                    return result;
                },
                List.of());
    }

    @Override
    public Optional<PermissionsInfo> permissions(UUID playerUuid) {
        // LuckPerms потокобезопасен и работает асинхронно сам, поэтому в
        // основной поток не прыгаем: иначе ожидание его future заблокировало
        // бы тик сервера.
        if (!LuckPermsIntegration.isAvailable()) return Optional.empty();
        return LuckPermsIntegration.read(playerUuid);
    }

    @Override
    public Optional<PermissionChange.Result> applyPermission(UUID playerUuid, PermissionChange change) {
        if (!LuckPermsIntegration.isAvailable()) return Optional.empty();
        return LuckPermsIntegration.apply(playerUuid, change);
    }

    @Override
    public Optional<InventoryInfo> offlineInventory(UUID playerUuid, String playerName) {
        if (!InvSeeIntegration.isAvailable()) return Optional.empty();
        return InvSeeIntegration.read(playerUuid, playerName);
    }
}
