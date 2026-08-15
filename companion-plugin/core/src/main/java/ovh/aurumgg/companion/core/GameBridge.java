package ovh.aurumgg.companion.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ovh.aurumgg.companion.core.model.InventoryInfo;
import ovh.aurumgg.companion.core.model.ItemSpec;
import ovh.aurumgg.companion.core.model.PlayerInfo;

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
}
