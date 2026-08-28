package ovh.aurumgg.auth.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Короткая сессия: переподключился быстро — пароль спрашивать не надо.
 *
 * ЗАЧЕМ. Игрок вылетел по сети, перезашёл через минуту — и снова вводит
 * пароль. На телефоне это особенно неприятно, а вылетают там регулярно.
 * Пятнадцатиминутное окно закрывает почти все такие случаи.
 *
 * ПОЧЕМУ СЕССИЯ ПРИВЯЗАНА К АДРЕСУ. Без проверки IP окно сессии превращается
 * в дыру: достаточно дождаться, когда игрок выйдет, и зайти под его ником —
 * пароль не спросят. С проверкой для этого нужно ещё и оказаться на том же
 * адресе. Это не непробиваемо (общий NAT, один вайфай), но разница между
 * «любой в интернете» и «кто-то с того же адреса» огромна.
 *
 * ПОЧЕМУ В ПАМЯТИ, А НЕ В БД. Перезапуск сервера сбрасывает сессии, и это
 * правильно: после рестарта разумнее спросить пароль заново, чем хранить
 * право входа переживающим перезапуск. Заодно это ещё один запрос к MariaDB,
 * которого не будет на каждом заходе.
 */
public final class SessionStore {

    private final Duration window;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SessionStore(Duration window) {
        this.window = window;
    }

    /** Запомнить успешный вход — вызывается и при входе паролем, и при продлении. */
    public void remember(UUID uuid, String ip, Instant at) {
        if (window.isZero()) return;
        sessions.put(uuid, new Session(ip, at));
    }

    /**
     * Годится ли сессия для входа без пароля прямо сейчас.
     *
     * Разные адреса — не годится, даже если по времени попадает.
     */
    public boolean isValid(UUID uuid, String ip, Instant now) {
        if (window.isZero()) return false;
        Session session = sessions.get(uuid);
        if (session == null) return false;
        if (!Objects.equals(session.ip, ip)) return false;
        // Отрицательная разница (часы прыгнули назад) — не повод пускать.
        Duration elapsed = Duration.between(session.at, now);
        return !elapsed.isNegative() && elapsed.compareTo(window) < 0;
    }

    /**
     * Забыть сессию.
     *
     * Вызывается при выходе паролем на другом устройстве, кике за подбор и
     * прочих случаях, когда «пусти без пароля» стало неуместным.
     */
    public void forget(UUID uuid) {
        sessions.remove(uuid);
    }

    /**
     * Выбросить всё, что уже протухло.
     *
     * Без этого карта растёт весь аптайм сервера: игроки заходят и уходят,
     * а записи остаются. Зовётся редким таймером, не на каждом входе.
     */
    public int purgeExpired(Instant now) {
        int before = sessions.size();
        sessions.values().removeIf(session -> {
            Duration elapsed = Duration.between(session.at, now);
            return elapsed.isNegative() || elapsed.compareTo(window) >= 0;
        });
        return before - sessions.size();
    }

    public int size() {
        return sessions.size();
    }

    private record Session(String ip, Instant at) {}
}
