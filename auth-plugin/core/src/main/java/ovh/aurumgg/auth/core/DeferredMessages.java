package ovh.aurumgg.auth.core;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отложенные сообщения о входе.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС ПОД ОДНУ КАРТУ. Здесь легко получить утечку: сообщение
 * кладётся на входе, а забирается на успешном логине — то есть у игрока,
 * который так и не вошёл и был выкинут по таймауту, запись останется навсегда.
 * Забирать её надо и при выходе тоже, и в отдельном классе про это видно, а
 * размазанная по слушателям карта живёт своей жизнью.
 *
 * Тип сообщения параметром: ядро не знает ни про Bukkit, ни про Adventure.
 */
public final class DeferredMessages<T> {

    private final Map<UUID, T> pending = new ConcurrentHashMap<>();

    /** Придержать сообщение. null не хранится: «сообщения не было» — не сообщение. */
    public void hold(UUID uuid, T message) {
        if (message == null) {
            pending.remove(uuid);
            return;
        }
        pending.put(uuid, message);
    }

    /** Забрать и забыть — ровно один раз. */
    public Optional<T> take(UUID uuid) {
        return Optional.ofNullable(pending.remove(uuid));
    }

    /** Выбросить, не показывая: игрок ушёл, не войдя. */
    public void drop(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean isHolding(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public int size() {
        return pending.size();
    }
}
