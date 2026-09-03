package ovh.aurumgg.companion.paper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.companion.core.GameBridge;
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.GiveResult;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.InventorySelection;
import ovh.aurumgg.companion.core.model.IpRecordInfo;
import ovh.aurumgg.companion.core.model.ItemInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.KnownPlayer;
import ovh.aurumgg.companion.core.model.KnownPlayersPage;
import ovh.aurumgg.companion.core.model.PasswordReset;
import ovh.aurumgg.companion.core.model.PermissionChange;
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

    /**
     * Сколько слотов офлайн-инвентаря видит и правит панель.
     *
     * Столько же, сколько показывает {@code InvSeeIntegration.toInventoryInfo}:
     * основной инвентарь вместе с хотбаром. Дальше у спектаторского инвентаря
     * идут броня, курсор и верстак — панель их для офлайн-режима не рисует,
     * поэтому и не пишет туда.
     */
    private static final int OFFLINE_SLOTS = 36;

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

    /**
     * Базовый список всех, кого помнит сервер, — с коротким кэшем.
     *
     * <h2>Зачем кэш</h2>
     *
     * {@code Bukkit.getOfflinePlayers()} обходит весь usercache: на сервере с
     * многолетней историей это тысячи объектов, и собирается список в ГЛАВНОМ
     * потоке, потому что {@code isOp()} читать откуда-то ещё нельзя. Один
     * такой обход стоит недорого, но вкладка панели открывается, листается и
     * фильтруется — и без кэша каждое движение стоило бы полного обхода.
     *
     * Полминуты — достаточно, чтобы листание и поиск шли по одному снимку, и
     * достаточно мало, чтобы зашедший игрок появился в списке почти сразу.
     */
    private static final long KNOWN_CACHE_MS = 30_000;

    private volatile List<KnownPlayer> knownCache = List.of();
    private volatile long knownCacheAt;

    @Override
    public KnownPlayersPage knownPlayers(String query, int offset, int limit) {
        List<KnownPlayer> all = cachedKnown();

        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<KnownPlayer> matched = new ArrayList<>();
        for (KnownPlayer player : all) {
            if (needle.isEmpty() || player.name().toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(player);
            }
        }

        int from = Math.max(0, Math.min(offset, matched.size()));
        int to = Math.max(from, Math.min(from + Math.max(1, limit), matched.size()));

        // Ник и признак регистрации — ТОЛЬКО для видимой страницы. Ник читается
        // из файла игрока, то есть отдельным обращением к диску на каждого;
        // делать это для тысячи записей ради полусотни на экране незачем.
        boolean essentials = EssentialsIntegration.isAvailable();
        Set<String> registered = AuthIntegration.registeredUsernames();
        boolean authAvailable = registered != null;

        List<KnownPlayer> page = new ArrayList<>(to - from);
        for (KnownPlayer base : matched.subList(from, to)) {
            String alias = essentials ? EssentialsIntegration.nicknameOf(base.uuid()) : null;
            Boolean isRegistered = authAvailable
                    ? registered.contains(base.name().toLowerCase(Locale.ROOT))
                    : null;
            page.add(new KnownPlayer(base.uuid(), base.name(), alias, base.op(), base.online(),
                    isRegistered, base.lastSeen()));
        }
        return new KnownPlayersPage(List.copyOf(page), matched.size(), authAvailable);
    }

    /** Снимок из usercache, не старше {@link #KNOWN_CACHE_MS}. */
    private List<KnownPlayer> cachedKnown() {
        long now = System.currentTimeMillis();
        List<KnownPlayer> cached = knownCache;
        if (!cached.isEmpty() && now - knownCacheAt < KNOWN_CACHE_MS) return cached;

        List<KnownPlayer> fresh = callSync(
                () -> {
                    List<KnownPlayer> result = new ArrayList<>();
                    for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                        String name = player.getName();
                        // Без имени запись бесполезна: показывать нечего, и
                        // искать по ней тоже.
                        if (name == null || name.isBlank()) continue;
                        result.add(new KnownPlayer(
                                player.getUniqueId(),
                                name,
                                // Ник и регистрация добираются позже, для
                                // страницы: здесь они стоили бы обхода диска
                                // по всему списку.
                                null,
                                player.isOp(),
                                player.isOnline(),
                                null,
                                player.getLastSeen()));
                    }
                    // Сначала те, кто в сети, потом свежие: администратор
                    // чаще ищет того, кто на сервере прямо сейчас, а следом —
                    // того, кто заходил на днях, а не три года назад.
                    //
                    // Онлайн первыми — ещё и потому, что панель берёт из
                    // первой страницы этого списка звёздочку оператора и ник
                    // EssentialsX для таблицы онлайна. Хвост списка она не
                    // грузит, и без этого правила у игрока в сети не было бы
                    // звёздочки просто потому, что он давно не выходил.
                    result.sort(Comparator.comparing(KnownPlayer::online)
                            .thenComparing(KnownPlayer::lastSeen)
                            .reversed());
                    return result;
                },
                List.of(),
                KNOWN_TIMEOUT_SECONDS);

        if (!fresh.isEmpty()) {
            knownCache = List.copyOf(fresh);
            knownCacheAt = now;
        }
        return fresh;
    }

    /**
     * Обход usercache идёт в главном потоке и на большом сервере не мгновенен,
     * поэтому запас по времени больше обычного.
     */
    private static final long KNOWN_TIMEOUT_SECONDS = 15;

    @Override
    public List<IpRecordInfo> ipHistory(UUID playerUuid) {
        return AuthIntegration.ipHistory(playerUuid);
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
    public Optional<List<GiveResult>> giveItems(UUID playerUuid, List<ItemSpec> items) {
        return callSync(
                () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player == null) return Optional.<List<GiveResult>>empty();

                    List<GiveResult> results = new ArrayList<>(items.size());
                    for (ItemSpec spec : items) {
                        results.add(giveOne(player.getInventory(), spec));
                    }
                    return Optional.of(results);
                },
                Optional.empty());
    }

    /**
     * Одна строка списка выдачи.
     *
     * Стак режем сами, а не полагаемся на addItem: у разных предметов разный
     * предельный размер стака (яйца — 16, зелья — 1), и передавать в API
     * заведомо переполненный ItemStack значит зависеть от того, как именно он
     * решит его разложить. Здесь же видно, сколько кусков ушло и сколько
     * вернулось.
     */
    private static GiveResult giveOne(Inventory inventory, ItemSpec spec) {
        Material material = Material.matchMaterial(spec.id());
        if (material == null || material == Material.AIR || !material.isItem()) {
            return GiveResult.failed(spec.id(), spec.count(), "Неизвестный предмет");
        }
        int perStack = Math.max(1, material.getMaxStackSize());

        List<ItemStack> stacks = new ArrayList<>();
        for (int left = spec.count(); left > 0; left -= perStack) {
            stacks.add(new ItemStack(material, Math.min(perStack, left)));
        }

        Map<Integer, ItemStack> leftovers =
                inventory.addItem(stacks.toArray(new ItemStack[0]));
        int notPlaced = 0;
        for (ItemStack leftover : leftovers.values()) notPlaced += leftover.getAmount();

        int given = spec.count() - notPlaced;
        if (notPlaced == 0) return GiveResult.ok(spec.id(), spec.count());
        return new GiveResult(
                spec.id(),
                spec.count(),
                given,
                given == 0 ? "Инвентарь заполнен" : "Инвентарь заполнен: не поместилось " + notPlaced);
    }

    @Override
    public boolean clearInventory(UUID playerUuid, InventorySelection selection) {
        return callSync(
                () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player == null) return false;
                    PlayerInventory inv = player.getInventory();

                    if (selection.all()) {
                        // Явно, а не одним clear(): у PlayerInventory он трогает
                        // и слоты, которых панель не показывает вовсе, — а стирать
                        // то, чего человек не видел, нельзя.
                        for (int slot = 0; slot < 36; slot++) inv.setItem(slot, null);
                        inv.setArmorContents(new ItemStack[4]);
                        inv.setItemInOffHand(null);
                        return true;
                    }

                    for (int slot : selection.slots()) {
                        if (slot >= 0 && slot < 36) inv.setItem(slot, null);
                    }
                    if (!selection.armor().isEmpty()) {
                        // Порядок массива Bukkit: ботинки, поножи, нагрудник, шлем.
                        ItemStack[] armor = inv.getArmorContents();
                        for (int index : selection.armor()) {
                            if (index >= 0 && index < armor.length) armor[index] = null;
                        }
                        inv.setArmorContents(armor);
                    }
                    if (selection.offhand()) inv.setItemInOffHand(null);
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

    /**
     * Общий каркас правки офлайн-инвентаря: достать, изменить, сохранить.
     *
     * Достаём на этом потоке (HTTP), а правим и сохраняем через callSync.
     * Разделение обязательное, а не стилистическое: InvSee++ завершает свой
     * future в основном потоке, и ожидание его оттуда же — взаимоблок;
     * править же Bukkit-инвентарь с постороннего потока нельзя, потому что
     * он может быть прямо сейчас открыт у кого-то в /invsee.
     *
     * @param change что сделать с инвентарём; возвращает результат для панели
     * @param failure что вернуть, если инвентаря нет или сохранить не вышло
     */
    private <T> T editOffline(
            UUID playerUuid, String playerName, java.util.function.Function<Inventory, T> change, T failure) {
        if (!InvSeeIntegration.isAvailable()) return failure;

        Optional<Inventory> fetched = InvSeeIntegration.fetch(playerUuid, playerName);
        if (fetched.isEmpty()) return failure;
        Inventory inventory = fetched.get();

        return callSync(
                () -> {
                    T result = change.apply(inventory);
                    // Сохраняем в любом случае: даже отказ по одной строке
                    // выдачи мог оставить в инвентаре то, что поместилось.
                    return InvSeeIntegration.save(inventory) ? result : failure;
                },
                failure);
    }

    @Override
    public boolean setOfflineInventorySlot(UUID playerUuid, String playerName, int slot, ItemSpec spec) {
        return editOffline(
                playerUuid,
                playerName,
                inventory -> {
                    if (slot < 0 || slot >= Math.min(inventory.getSize(), OFFLINE_SLOTS)) return false;
                    if (spec.isClear()) {
                        inventory.setItem(slot, null);
                        return true;
                    }
                    Material material = Material.matchMaterial(spec.id());
                    if (material == null || material == Material.AIR || !material.isItem()) return false;
                    inventory.setItem(slot, new ItemStack(material, spec.count()));
                    return true;
                },
                false);
    }

    @Override
    public Optional<List<GiveResult>> giveOfflineItems(
            UUID playerUuid, String playerName, List<ItemSpec> items) {
        return editOffline(
                playerUuid,
                playerName,
                inventory -> {
                    List<GiveResult> results = new ArrayList<>(items.size());
                    for (ItemSpec spec : items) results.add(giveOne(inventory, spec));
                    return Optional.of(results);
                },
                Optional.empty());
    }

    @Override
    public boolean clearOfflineInventory(
            UUID playerUuid, String playerName, InventorySelection selection) {
        return editOffline(
                playerUuid,
                playerName,
                inventory -> {
                    int limit = Math.min(inventory.getSize(), OFFLINE_SLOTS);
                    if (selection.all()) {
                        // Ровно те же 36 слотов, что панель показала. У
                        // спектаторского инвентаря InvSee++ дальше идут броня,
                        // курсор и верстак — их офлайн-режим не показывает, и
                        // стирать их «заодно» было бы сюрпризом.
                        for (int slot = 0; slot < limit; slot++) inventory.setItem(slot, null);
                        return true;
                    }
                    for (int slot : selection.slots()) {
                        if (slot >= 0 && slot < limit) inventory.setItem(slot, null);
                    }
                    // selection.armor() и offhand() здесь намеренно не трогаем.
                    return true;
                },
                false);
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

    // ---------------------------------------------------- гильдии и пати
    //
    // Всё делегируется мосту: он один знает про классы AurumGuilds, и на
    // сервере без этого плагина они не загружаются вовсе.

    @Override
    public boolean guildsAvailable() {
        return GuildsIntegration.installed();
    }

    @Override
    public java.util.List<ovh.aurumgg.companion.core.model.GuildInfo> guilds(String query, int limit) {
        return GuildsIntegration.guilds(query, limit);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildInfo> guild(long guildId) {
        return GuildsIntegration.guild(guildId);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildMembershipInfo> guildOf(UUID playerUuid) {
        return GuildsIntegration.membership(playerUuid);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildActionOutcome> guildDisband(
            long guildId, String actor) {
        return GuildsIntegration.disband(guildId, actor);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildActionOutcome> guildTransfer(
            long guildId, String targetName, String actor) {
        return GuildsIntegration.transfer(guildId, targetName, actor);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildActionOutcome> guildRemoveMember(
            String targetName, String actor) {
        return GuildsIntegration.removeMember(targetName, actor);
    }

    @Override
    public List<ovh.aurumgg.companion.core.model.GuildBonusInfo> guildBonuses(long guildId) {
        return GuildsIntegration.bonuses(guildId);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildActionOutcome> guildGrantBonus(
            long guildId, String type, double magnitude, long seconds, String actor) {
        return GuildsIntegration.grantBonus(guildId, type, magnitude, seconds, actor);
    }

    @Override
    public Optional<ovh.aurumgg.companion.core.model.GuildActionOutcome> guildRevokeBonus(
            long guildId, String type, String actor) {
        return GuildsIntegration.revokeBonus(guildId, type, actor);
    }
}
