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
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PasswordReset;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;
import ovh.aurumgg.companion.core.model.PluginToggle;

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

    /** Имя самого companion-плагина — совпадает с name в plugin.yml. */
    private static final String SELF_NAME = "AurumCompanion";

    /**
     * Отдельный таймаут для подсчёта экономики сервера. Обход всех, кто
     * когда-либо заходил, у некоторых провайдеров означает поход в базу на
     * каждого игрока, и на сервере с тысячами записей трёх секунд не хватит.
     * Считается это редко (панель кэширует результат), так что запас уместен.
     */
    private static final long ECONOMY_TIMEOUT_SECONDS = 30;

    private final Plugin plugin;

    public BukkitGameBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    private <T> T callSync(Callable<T> callable, T fallback) {
        return callSync(callable, fallback, SYNC_TIMEOUT_SECONDS);
    }

    private <T> T callSync(Callable<T> callable, T fallback, long timeoutSeconds) {
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
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
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

    @Override
    public PluginToggle setPluginEnabled(String pluginName, boolean enabled) {
        // Обязательно в основном потоке: enablePlugin/disablePlugin трогают
        // реестры команд и слушателей, а они не потокобезопасны.
        return callSync(
                () -> {
                    Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
                    if (target == null) {
                        return PluginToggle.failed("Плагин «" + pluginName + "» на сервере не найден");
                    }
                    // Себя выключать нельзя: вместе с плагином остановится и
                    // HTTP-сервер, через который пришёл этот самый запрос, —
                    // включить обратно будет уже нечем.
                    if (target.getName().equals(SELF_NAME)) {
                        return PluginToggle.failed("Нельзя выключить companion-плагин: панель потеряет связь с сервером");
                    }
                    if (target.isEnabled() == enabled) {
                        return PluginToggle.ok(enabled);
                    }

                    try {
                        if (enabled) {
                            Bukkit.getPluginManager().enablePlugin(target);
                        } else {
                            Bukkit.getPluginManager().disablePlugin(target);
                        }
                    } catch (Throwable t) {
                        // Ловим Throwable, а не Exception: плагин при старте
                        // вполне может уронить NoClassDefFoundError, и уронить
                        // вместе с собой основной поток сервера мы не имеем права.
                        return PluginToggle.failed(
                                "Плагин отказался переключиться: " + t.getClass().getSimpleName()
                                        + (t.getMessage() == null ? "" : " — " + t.getMessage()));
                    }

                    return PluginToggle.ok(target.isEnabled());
                },
                PluginToggle.failed("Сервер не ответил вовремя"));
    }

    // ---------- Экономика (Vault) ----------
    //
    // В основной поток прыгаем сознательно: Vault — только интерфейс, а за
    // ним стоит произвольный плагин экономики. Часть провайдеров (тот же
    // EssentialsX) держит балансы в структурах, рассчитанных на обращение из
    // основного потока, и потокобезопасность здесь никем не обещана.

    @Override
    public Optional<BalanceInfo> balance(UUID playerUuid) {
        return callSync(() -> VaultEconomyIntegration.balance(playerUuid), Optional.empty());
    }

    @Override
    public Optional<BalanceChange> deposit(UUID playerUuid, double amount) {
        return callSync(() -> VaultEconomyIntegration.change(playerUuid, amount, true), Optional.empty());
    }

    @Override
    public Optional<BalanceChange> withdraw(UUID playerUuid, double amount) {
        return callSync(() -> VaultEconomyIntegration.change(playerUuid, amount, false), Optional.empty());
    }

    @Override
    public Optional<EconomySummary> economySummary(int topLimit) {
        return callSync(
                () -> VaultEconomyIntegration.summary(topLimit), Optional.empty(), ECONOMY_TIMEOUT_SECONDS);
    }

    /**
     * Токен сброса пароля — через публичный API AurumAuth.
     *
     * join() здесь допустим и безопасен: метод вызывается из потока
     * HTTP-сервера companion, а не из главного, и сам поход в базу происходит
     * внутри пула плагина авторизации. На всякий случай стоит таймаут: висеть
     * в ожидании ответа от чужого плагина дольше нескольких секунд нам незачем.
     */
    @Override
    public Optional<PasswordReset> issuePasswordReset(String username) {
        try {
            return AuthIntegration.issueResetToken(username)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
                    .map(token -> new PasswordReset(
                            token.username(), token.token(), token.expiresAt().toEpochMilli()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            // Ни текста ошибки, ни тем более токена в лог: панель получит
            // общий отказ, а подробности здесь ничего не добавляют.
            return Optional.empty();
        }
    }
}
