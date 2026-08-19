package ovh.aurumgg.companion.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.companion.core.model.BalanceChange;
import ovh.aurumgg.companion.core.model.BalanceInfo;
import ovh.aurumgg.companion.core.model.EconomySummary;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;
import ovh.aurumgg.companion.core.model.PluginToggle;

/**
 * Единственная точка соприкосновения с игровым сервером.
 *
 * Реализация для Paper обязана переносить вызовы в основной поток: Bukkit API
 * не потокобезопасен, а HTTP-сервер работает на своих потоках. Благодаря этому
 * интерфейсу весь остальной код модуля core тестируется без запуска Minecraft.
 */
public interface GameBridge {

    List<PlayerInfo> onlinePlayers();

    /** Пусто, если игрок не в сети. */
    Optional<InventoryInfo> inventory(UUID playerUuid);

    /**
     * Кладёт предмет в слот основного инвентаря (0-35) или очищает его.
     *
     * @return false, если игрок не в сети или материал неизвестен
     */
    boolean setInventorySlot(UUID playerUuid, int slot, ItemSpec spec);

    /** Отправляет игроку сообщение в чат. Тихо игнорируется, если он оффлайн. */
    void sendMessage(UUID playerUuid, String message);

    /**
     * Автодополнение команды так, как его делает сам сервер.
     *
     * Строка — как её набирают в консоли, БЕЗ ведущего слэша. Возвращаются
     * варианты целиком для последнего (незавершённого) слова: если слово
     * одно — имена команд, дальше — аргументы конкретной команды.
     *
     * Смысл этого метода в том, что он знает то, чего панель знать не может:
     * команды установленных плагинов и их аргументы — имена миров, китов,
     * зачарований. Пустой список — не ошибка, а «нечего предложить».
     */
    List<String> completeCommand(String line);

    // ---------- Интеграции со сторонними плагинами ----------
    //
    // Все три метода ниже устроены одинаково: если нужного плагина на сервере
    // нет, они возвращают «пусто», а не бросают исключение. Ответственность за
    // формулировку «нужен такой-то плагин» лежит на HTTP-слое — так текст
    // ошибки один на все реализации.

    /** Все установленные плагины сервера: имя, версия, включён ли. */
    List<PluginInfo> installedPlugins();

    /**
     * Горячее включение или выключение плагина без перезапуска сервера.
     *
     * ЭТО BEST-EFFORT ПО ПРИРОДЕ BUKKIT. Плагины регистрируют слушателей,
     * задачи планировщика и команды, и далеко не все аккуратно снимают их за
     * собой при выключении — по той же причине /reload считается рискованной
     * командой. Панель обязана предупреждать об этом человека и держать рядом
     * кнопку перезапуска, а не делать вид, что операция безобидна.
     *
     * @return результат с причиной отказа, если не вышло
     */
    PluginToggle setPluginEnabled(String pluginName, boolean enabled);

    /**
     * Права игрока через LuckPerms.
     *
     * @return пусто, если LuckPerms на сервере нет
     */
    Optional<PermissionsInfo> permissions(UUID playerUuid);

    /**
     * Применяет одно изменение прав через LuckPerms.
     *
     * @return пусто, если LuckPerms нет; иначе результат с причиной отказа
     */
    Optional<PermissionChange.Result> applyPermission(UUID playerUuid, PermissionChange change);

    /**
     * Инвентарь игрока, которого нет в сети, — через InvSee++.
     *
     * @return пусто, если InvSee++ не установлен либо данных по игроку нет
     */
    Optional<InventoryInfo> offlineInventory(UUID playerUuid, String playerName);

    // ---------- Экономика (Vault) ----------
    //
    // Vault сам денег не хранит: это прослойка, за которой стоит настоящий
    // плагин экономики. Поэтому «пусто» здесь означает одно из двух — нет
    // самого Vault или у него не зарегистрирован Economy-провайдер. Различать
    // эти случаи для панели не нужно: и там, и там блок «Валюта» не работает,
    // а формулировку даёт HTTP-слой.

    /**
     * Баланс игрока. Работает и для тех, кого нет в сети: Vault оперирует
     * OfflinePlayer, а не живым игроком.
     *
     * @return пусто, если экономики на сервере нет
     */
    Optional<BalanceInfo> balance(UUID playerUuid);

    /**
     * Начисление (amount &gt; 0) или списание (amount &lt; 0 не передаётся —
     * для списания есть отдельный метод, чтобы знак не терялся по дороге).
     *
     * @return пусто, если экономики нет; иначе результат с балансом до и после
     */
    Optional<BalanceChange> deposit(UUID playerUuid, double amount);

    /** Списание. Симметрично deposit. */
    Optional<BalanceChange> withdraw(UUID playerUuid, double amount);

    /**
     * Экономика сервера целиком: сумма по всем, кто когда-либо заходил, и
     * доска богатства.
     *
     * @param topLimit сколько строк вернуть в доске богатства
     * @return пусто, если экономики нет
     */
    Optional<EconomySummary> economySummary(int topLimit);
}
