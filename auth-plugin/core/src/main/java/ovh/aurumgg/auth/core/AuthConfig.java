package ovh.aurumgg.auth.core;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import ovh.aurumgg.auth.core.premium.PremiumChecker;

/**
 * Настройки плагина.
 *
 * Читаются из плоской карты «ключ через точку → значение» — ровно такую отдаёт
 * Bukkit по getValues(true). Благодаря этому разбор конфига не зависит от
 * Bukkit и проверяется тестами: значения вне разумного зажимаются, а не
 * принимаются молча. Настройка «таймаут входа 0 секунд» или «пароль от одного
 * символа» ломает сервер тише и обиднее, чем опечатка в коде.
 */
public record AuthConfig(
        String jdbcUrl,
        String dbUsername,
        String dbPassword,
        String tableName,
        int poolSize,
        Duration loginTimeout,
        Duration sessionWindow,
        int minPasswordLength,
        int maxPasswordLength,
        int maxAttempts,
        Duration lockout,
        Duration attemptDelay,
        int bcryptCost,
        Duration resetTokenTtl,
        boolean premiumEnabled,
        boolean premiumSkipPassword,
        String premiumEndpoint,
        Duration premiumTimeout,
        Duration premiumCacheTtl,
        JoinMessageMode joinMessageMode,
        /** Разрешить право aurumauth.bypass пропускать вход. См. предупреждение в config.yml. */
        boolean permissionBypass,
        /** Допуск на расхождение часов в интервалах по 30 секунд. */
        int totpWindow,
        /** Как называться в приложении-аутентификаторе. */
        String totpIssuer,
        /** Сколько хранить историю входов. */
        Duration historyRetention,
        /** Имя этого сервера в истории входов: база одна на всю сеть. */
        String serverId,
        MessageSettings messages) {

    /** Что делать с сообщениями о входе и выходе, пока игрок не авторизован. */
    public enum JoinMessageMode {
        /** Перехватить и показать после входа — поведение по умолчанию. */
        DEFER,
        /** Погасить совсем: игрок, который так и не вошёл, никак не отмечается. */
        SUPPRESS,
        /** Не трогать вовсе — на случай, если этим занимается другой плагин. */
        IGNORE;

        static JoinMessageMode parse(Object raw) {
            if (raw == null) return DEFER;
            return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
                case "suppress" -> SUPPRESS;
                case "ignore" -> IGNORE;
                default -> DEFER;
            };
        }
    }

    public static AuthConfig fromMap(Map<String, Object> raw) {
        return new AuthConfig(
                string(raw, "database.jdbc-url", "jdbc:mariadb://127.0.0.1:3306/aurum_auth"),
                string(raw, "database.username", "aurum"),
                string(raw, "database.password", ""),
                tableName(string(raw, "database.table", "auth_accounts")),
                clamp(integer(raw, "database.pool-size", 6), 1, 32),

                // Таймаут входа. Меньше десяти секунд не оставляет времени
                // набрать пароль на телефоне, больше пяти минут превращает
                // невошедших в бесконечно висящие подключения.
                seconds(clamp(integer(raw, "login.timeout-seconds", 60), 10, 300)),
                // Окно сессии. Ноль — осмысленное значение: «сессии выключены,
                // пароль всегда», поэтому нижняя граница именно 0.
                minutes(clamp(integer(raw, "login.session-minutes", 15), 0, 24 * 60)),
                clamp(integer(raw, "login.min-password-length", 8), 4, 64),
                clamp(integer(raw, "login.max-password-length", 64), 16, 256),
                clamp(integer(raw, "login.max-attempts", 5), 1, 50),
                minutes(clamp(integer(raw, "login.lockout-minutes", 5), 1, 24 * 60)),
                millis(clamp(integer(raw, "login.attempt-delay-ms", 250), 0, 5000)),
                // Стоимость bcrypt. 12 — примерно четверть секунды на обычном
                // ядре; выше 14 вход начинает ощущаться даже асинхронно, ниже
                // 10 нет смысла брать bcrypt вообще.
                clamp(integer(raw, "login.bcrypt-cost", 12), 10, 14),
                // Срок жизни токена сброса. Двадцать минут — столько, чтобы
                // человек успел зайти на сервер, и не столько, чтобы забытый
                // в переписке токен работал завтра.
                minutes(clamp(integer(raw, "login.reset-token-minutes", 20), 1, 24 * 60)),

                bool(raw, "premium.enabled", true),
                bool(raw, "premium.skip-password", true),
                string(raw, "premium.endpoint", PremiumChecker.DEFAULT_ENDPOINT),
                seconds(clamp(integer(raw, "premium.timeout-seconds", 5), 1, 20)),
                minutes(clamp(integer(raw, "premium.cache-minutes", 60), 1, 24 * 60)),
                JoinMessageMode.parse(raw.get("join-messages.mode")),
                bool(raw, "login.permission-bypass", false),
                // Допуск на расхождение часов. Один интервал в каждую сторону
                // — общепринятый компромисс: телефоны расходятся на секунды,
                // а каждый лишний интервал втрое увеличивает окно, в котором
                // годен подсмотренный код.
                clamp(integer(raw, "totp.window", 1), 0, 5),
                string(raw, "totp.issuer", "Aurum"),
                // Срок хранения истории. Девяносто дней хватает на любой
                // разбор «меня взломали», а бесконечная история — это таблица,
                // которая растёт весь срок жизни сервера.
                Duration.ofDays(clamp(integer(raw, "history.keep-days", 90), 1, 3650)),
                string(raw, "server-id", "server"),
                MessageSettings.fromMap(raw));
    }

    /** Имя таблицы по умолчанию — оно же запасное при негодном значении. */
    public static final String DEFAULT_TABLE = "auth_accounts";

    /**
     * Имя таблицы подставляется в SQL как есть — параметром его сделать нельзя.
     * Поэтому негодное значение ЦЕЛИКОМ заменяется на имя по умолчанию.
     *
     * Именно заменяется, а не вычищается посимвольно: из «auth; DROP TABLE
     * users--» вычистка сделала бы «authDROPTABLEusers» — формально безопасно,
     * но плагин молча начал бы работать с таблицей, которую никто не заводил,
     * и найти это было бы нечем. Замена на дефолт заметна сразу, и о ней
     * пишется предупреждение при старте.
     */
    public static String tableName(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_]{1,64}") ? raw : DEFAULT_TABLE;
    }

    /** Проверка пароля на длину. Возвращает null, если всё в порядке. */
    public String validatePassword(String password) {
        if (password.length() < minPasswordLength) {
            return "Пароль короче " + minPasswordLength + " символов";
        }
        if (password.length() > maxPasswordLength) {
            return "Пароль длиннее " + maxPasswordLength + " символов";
        }
        return null;
    }

    private static String string(Map<String, Object> raw, String key, String fallback) {
        Object value = raw.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int integer(Map<String, Object> raw, String key, int fallback) {
        Object value = raw.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Map<String, Object> raw, String key, boolean fallback) {
        Object value = raw.get(key);
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Duration seconds(int value) {
        return Duration.ofSeconds(value);
    }

    private static Duration minutes(int value) {
        return Duration.ofMinutes(value);
    }

    private static Duration millis(int value) {
        return Duration.ofMillis(value);
    }
}
