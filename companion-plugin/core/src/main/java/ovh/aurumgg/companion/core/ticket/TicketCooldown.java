package ovh.aurumgg.companion.core.ticket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Пауза между командами /ticket от одного игрока.
 *
 * Источник времени вынесен в LongSupplier, чтобы тесты не зависели от Thread.sleep.
 */
public final class TicketCooldown {

    private final Map<UUID, Long> lastUseMillis = new ConcurrentHashMap<>();
    private final long cooldownMillis;
    private final LongSupplier clock;

    public TicketCooldown(int cooldownSeconds) {
        this(cooldownSeconds, System::currentTimeMillis);
    }

    public TicketCooldown(int cooldownSeconds, LongSupplier clock) {
        this.cooldownMillis = Math.max(0, cooldownSeconds) * 1000L;
        this.clock = clock;
    }

    /**
     * Сколько секунд осталось ждать игроку; 0 — можно отправлять.
     * При успехе отметка времени обновляется, поэтому метод вызывается один раз.
     */
    public long secondsRemaining(UUID player) {
        long now = clock.getAsLong();
        Long last = lastUseMillis.get(player);
        if (last != null) {
            long elapsed = now - last;
            if (elapsed < cooldownMillis) {
                // Округляем вверх: показать «ещё 1 секунда» честнее, чем «0».
                return (cooldownMillis - elapsed + 999) / 1000;
            }
        }
        lastUseMillis.put(player, now);
        return 0;
    }

    /** Вызывается при выходе игрока, чтобы карта не росла бесконечно. */
    public void forget(UUID player) {
        lastUseMillis.remove(player);
    }
}
