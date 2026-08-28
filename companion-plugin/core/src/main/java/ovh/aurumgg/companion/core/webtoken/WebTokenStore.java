package ovh.aurumgg.companion.core.webtoken;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Одноразовые коды для входа в панель из игры (/webtoken).
 *
 * Игрок в игре получает короткий код, вводит его в панели, панель обменивает
 * код на «это точно вот тот игрок». Код одноразовый и живёт минуты.
 *
 * ПОЧЕМУ ЭТО ОТДЕЛЬНЫЙ ЧИСТЫЙ КЛАСС. Всё, что здесь может пойти не так, идёт
 * не так молча: код, который не протухает; код, который срабатывает дважды;
 * код, который можно подобрать. Ни одно из этого не видно на экране, поэтому
 * логика вынесена из Bukkit и покрыта тестами.
 */
public final class WebTokenStore {

    /**
     * Алфавит без похожих друг на друга символов.
     *
     * Код диктуют голосом и набирают с телефона, а 0/O и 1/I/l в этот момент
     * неразличимы. Убрать их дешевле, чем разбирать потом, почему «код не
     * подходит».
     */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /**
     * Длина кода.
     *
     * 8 символов из алфавита в 31 знак — это больше 10^11 вариантов. При живом
     * окне в несколько минут и одноразовости подбор бессмыслен, а продиктовать
     * всё ещё можно.
     */
    private static final int LENGTH = 8;

    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Issued> codes = new ConcurrentHashMap<>();

    public WebTokenStore(Duration ttl) {
        this.ttl = ttl;
    }

    /** Кому и когда выдан код. */
    public record Issued(UUID playerUuid, String username, Instant issuedAt) {}

    /**
     * Выдать код игроку.
     *
     * Предыдущий код того же игрока перестаёт работать: два живых кода на
     * одного человека — это просто лишний шанс, что один из них подберут или
     * подсмотрят.
     */
    public String issue(UUID playerUuid, String username, Instant now) {
        codes.values().removeIf(issued -> issued.playerUuid().equals(playerUuid));
        String code = generate();
        codes.put(code, new Issued(playerUuid, username, now));
        return code;
    }

    /**
     * Обменять код на игрока. Второй раз тот же код не сработает.
     *
     * Регистр не важен: код набирают руками, и заглавные буквы в нём выбраны
     * ради читаемости, а не ради строгости ввода.
     */
    public Optional<Issued> consume(String code, Instant now) {
        if (code == null) return Optional.empty();
        String key = code.trim().toUpperCase(java.util.Locale.ROOT);
        Issued issued = codes.remove(key);
        if (issued == null) return Optional.empty();
        Duration age = Duration.between(issued.issuedAt(), now);
        // Отрицательный возраст (часы прыгнули назад) — тоже негодный код.
        if (age.isNegative() || age.compareTo(ttl) >= 0) return Optional.empty();
        return Optional.of(issued);
    }

    /** Убрать протухшие коды — иначе карта растёт весь аптайм сервера. */
    public int purgeExpired(Instant now) {
        int before = codes.size();
        codes.values().removeIf(issued -> {
            Duration age = Duration.between(issued.issuedAt(), now);
            return age.isNegative() || age.compareTo(ttl) >= 0;
        });
        return before - codes.size();
    }

    public int size() {
        return codes.size();
    }

    private String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
