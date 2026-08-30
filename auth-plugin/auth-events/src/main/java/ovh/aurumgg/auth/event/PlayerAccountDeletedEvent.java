package ovh.aurumgg.auth.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Регистрация игрока удалена.
 *
 * Шлётся и когда игрок удалил себя сам ({@code /unregister}), и когда это
 * сделал администратор ({@code /auth unregister}). Различает их
 * {@link #byAdmin()} — тем, кто ведёт свои записи об игроке, разница обычно
 * важна: своё удаление это уход, административное — наказание.
 *
 * <h2>Зачем это событие вообще есть</h2>
 *
 * У других плагинов заводятся собственные данные, привязанные к игроку:
 * гильдии, дома, кошельки. Когда аккаунт исчезает, эти данные остаются висеть
 * на UUID, за которым больше никого нет, — а на offline-сервере ник вместе с
 * UUID может занять уже другой человек. Событие даёт им шанс прибраться в тот
 * же момент, а не при следующем заходе.
 *
 * <h2>Поток</h2>
 *
 * ШЛЁТСЯ В ГЛАВНОМ ПОТОКЕ, хотя само удаление идёт в рабочем: обработчику
 * почти наверняка понадобится Bukkit API — найти игрока, отправить сообщение,
 * — а он не потокобезопасен. Между удалением из базы и этим событием проходит
 * один тик.
 *
 * <h2>Игрока может не быть в сети</h2>
 *
 * {@code /auth unregister <ник>} работает и по офлайн-игроку. Поэтому здесь
 * UUID и ник, а не объект Player: обработчик сам решает, искать ли игрока в
 * сети.
 */
public class PlayerAccountDeletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uuid;
    private final String username;
    private final boolean byAdmin;

    public PlayerAccountDeletedEvent(@NotNull UUID uuid, @NotNull String username, boolean byAdmin) {
        this.uuid = uuid;
        this.username = username;
        this.byAdmin = byAdmin;
    }

    /** UUID удалённого аккаунта. */
    public @NotNull UUID uuid() {
        return uuid;
    }

    /** Ник на момент удаления. */
    public @NotNull String username() {
        return username;
    }

    /** true — удалил администратор, false — игрок удалил себя сам. */
    public boolean byAdmin() {
        return byAdmin;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
