package ovh.aurumgg.guilds.core;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Настройки плагина.
 *
 * Читаются из плоской карты «ключ через точку → значение» — ровно такую отдаёт
 * Bukkit по getValues(true). Благодаря этому разбор конфига не зависит от
 * Bukkit и проверяется тестами: значения вне разумного зажимаются, а не
 * принимаются молча. «Тег длиной ноль» или «пати на одного» ломают плагин
 * тише и обиднее, чем опечатка в коде.
 *
 * @param jdbcUrl                адрес базы
 * @param dbUsername             пользователь базы
 * @param dbPassword             пароль базы
 * @param tablePrefix            префикс имён таблиц
 * @param poolSize               размер пула соединений
 * @param requireCreatePermission нужно ли право, чтобы создать гильдию
 * @param maxNameLength          предел длины имени гильдии
 * @param maxTagLength           предел длины тега
 * @param maxGuildMembers        сколько человек помещается в гильдию
 * @param partyFriendlyFire      разрешён ли урон по своим внутри пати (общесерверно)
 * @param maxPartyMembers        сколько человек помещается в пати
 * @param partyInviteTtl         сколько живёт приглашение в пати
 * @param guildInviteTtl         сколько живёт приглашение в гильдию
 * @param hudEnabled             показывать сайдбар
 * @param hudRefresh             как часто обновлять сайдбар
 * @param hudTitle               заголовок сайдбара
 * @param luckPermsGroupPrefix   префикс технических имён групп LuckPerms
 * @param suffixFormat           во что оборачивается тег в суффиксе
 * @param bankEnabled            разрешены ли команды банка (при наличии Vault)
 */
public record GuildsConfig(
        String jdbcUrl,
        String dbUsername,
        String dbPassword,
        String tablePrefix,
        int poolSize,
        boolean requireCreatePermission,
        int maxNameLength,
        int maxTagLength,
        int maxGuildMembers,
        int maxPartyMembers,
        boolean partyFriendlyFire,
        Duration partyInviteTtl,
        Duration guildInviteTtl,
        boolean hudEnabled,
        Duration hudRefresh,
        String hudTitle,
        String luckPermsGroupPrefix,
        String suffixFormat,
        boolean bankEnabled) {

    /** Префикс таблиц по умолчанию — он же запасной при негодном значении. */
    public static final String DEFAULT_PREFIX = "aurum_guilds";

    public static GuildsConfig fromMap(Map<String, Object> raw) {
        return new GuildsConfig(
                string(raw, "database.jdbc-url", "jdbc:mariadb://127.0.0.1:3306/aurum_guilds"),
                string(raw, "database.username", "aurum"),
                string(raw, "database.password", ""),
                tablePrefix(string(raw, "database.table-prefix", DEFAULT_PREFIX)),
                clamp(integer(raw, "database.pool-size", 4), 1, 32),

                bool(raw, "guild.require-create-permission", false),
                // Имя видно в списке гильдий и в панели. Длиннее двадцати
                // четырёх символов оно перестаёт помещаться в строку списка, а
                // короче трёх — это уже тег, а не имя.
                clamp(integer(raw, "guild.max-name-length", 24), 3, 32),
                // Тег висит рядом с ником в каждом сообщении чата. Четыре
                // символа — общепринятый предел; шесть уже заметно отодвигают
                // сам текст сообщения.
                clamp(integer(raw, "guild.max-tag-length", 4), 1, 6),
                clamp(integer(raw, "guild.max-members", 50), 2, 500),

                clamp(integer(raw, "party.max-members", 8), 2, 50),
                // Свой огонь в пати выключен по умолчанию и намеренно. Пати
                // собирают, чтобы идти вместе, и первое же случайное попадание
                // по своему — это ссора на ровном месте. Кому нужен обратный
                // порядок, включает его сам.
                bool(raw, "party.friendly-fire", false),
                // Приглашение живёт минуту-две: дольше — и человек уже забыл,
                // куда его звали, а принятое через час приглашение выглядит
                // как чужая ошибка.
                seconds(clamp(integer(raw, "party.invite-seconds", 90), 15, 600)),
                // В гильдию зовут вдумчивее, чем в пати на один бой, поэтому
                // срок больше.
                seconds(clamp(integer(raw, "guild.invite-seconds", 300), 30, 3600)),

                bool(raw, "hud.enabled", true),
                // Чаще раза в полсекунды обновлять нечего: HP и состав так
                // быстро не меняются, а каждое обновление — это пакеты всем,
                // кому сайдбар показан.
                millis(clamp(integer(raw, "hud.refresh-ms", 1000), 500, 10_000)),
                string(raw, "hud.title", "&6&lAurum"),

                // Техническое имя группы собирается из этого префикса и
                // внутреннего id гильдии — не из тега. Тег меняется и может
                // содержать что угодно, а имя группы должно быть постоянным и
                // безопасным.
                groupPrefix(string(raw, "luckperms.group-prefix", "guild_")),
                string(raw, "luckperms.suffix-format", "&7[&b{tag}&7]"),
                bool(raw, "bank.enabled", true));
    }

    /**
     * Префикс таблиц подставляется в SQL как есть — параметром его сделать
     * нельзя. Поэтому негодное значение ЦЕЛИКОМ заменяется на значение по
     * умолчанию, а не вычищается посимвольно: из «guilds; DROP TABLE users--»
     * вычистка сделала бы «guildsDROPTABLEusers», и плагин молча начал бы
     * работать с таблицами, которых никто не заводил.
     */
    public static String tablePrefix(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_]{1,48}") ? raw : DEFAULT_PREFIX;
    }

    /**
     * Префикс имён групп LuckPerms.
     *
     * Имена групп там ограничены набором символов, и кириллица или пробел
     * привели бы к тому, что группа не создаётся, а гильдия при этом уже есть.
     */
    public static String groupPrefix(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9_.-]{1,24}") ? value : "guild_";
    }

    public String guildsTable() {
        return tablePrefix;
    }

    public String membersTable() {
        return tablePrefix + "_members";
    }

    /** Таблица бонусов гильдий. */
    public String bonusesTable() {
        return tablePrefix + "_bonuses";
    }

    /** Таблица привязок регионов WorldGuard к гильдиям. */
    public String regionsTable() {
        return tablePrefix + "_regions";
    }

    public String bankLogTable() {
        return tablePrefix + "_bank_log";
    }

    // ------------------------------------------------------------ разбор

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

    private static Duration millis(int value) {
        return Duration.ofMillis(value);
    }
}
