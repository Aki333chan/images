package ovh.aurumgg.companion.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PermissionChange;
import ovh.aurumgg.companion.core.model.PermissionsInfo;
import ovh.aurumgg.companion.core.model.PlayerInfo;
import ovh.aurumgg.companion.core.model.PluginInfo;

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

    // ---------- Интеграции со сторонними плагинами ----------
    //
    // Все три метода ниже устроены одинаково: если нужного плагина на сервере
    // нет, они возвращают «пусто», а не бросают исключение. Ответственность за
    // формулировку «нужен такой-то плагин» лежит на HTTP-слое — так текст
    // ошибки один на все реализации.

    /** Все установленные плагины сервера: имя, версия, включён ли. */
    List<PluginInfo> installedPlugins();

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
}
