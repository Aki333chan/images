package ovh.aurumgg.guilds.core;

import java.util.Locale;
import java.util.Map;

/**
 * Проверка имени и тега гильдии.
 *
 * <h2>Почему это отдельный класс с тестами</h2>
 *
 * Имя и тег — единственные данные, которые игрок вводит сам и которые потом
 * попадают и в чат всем подряд, и в панель, и в суффикс к нику. Ошибка здесь не
 * падает, а всплывает через месяц в виде «у гильдии тег из управляющих
 * символов, и у половины игроков поехал чат».
 *
 * <h2>Что запрещено и почему</h2>
 *
 * <ul>
 *   <li><b>Символ &amp;</b> — им задаются цвета. Без запрета первый же игрок
 *       заведёт гильдию с тегом «&amp;kAAAA» и получит мерцающий ник, а
 *       следующий — тег цвета фона, то есть невидимый;</li>
 *   <li><b>Секция §</b> — то же самое, только уже готовым кодом цвета;</li>
 *   <li><b>Управляющие символы</b> — перевод строки в имени гильдии превратил
 *       бы одну строку лога в две, а часть текста — в подделку под чужое
 *       сообщение;</li>
 *   <li><b>Пробелы по краям</b> — два имени, отличающиеся только пробелом,
 *       выглядят одинаково, а проверка уникальности считает их разными.</li>
 * </ul>
 *
 * Кириллица при этом РАЗРЕШЕНА: сервер русскоязычный, и запрещать её значило бы
 * заставить людей писать «Драконы» латиницей. Имени группы в LuckPerms это не
 * касается — оно собирается из числового id, см. {@link #groupName}.
 */
public final class GuildNames {

    /**
     * Ответ проверки: либо всё хорошо, либо ключ объяснения, что не так.
     *
     * Ключ, а не фраза, по той же причине, что и в {@link
     * ovh.aurumgg.guilds.api.GuildActionResult}: длину предела знает конфиг, а
     * язык — слой Bukkit, и встречаются они только там, где текст выводится.
     * {@code values} несёт как раз этот предел: «Имя длиннее {max} символов».
     */
    public record Verdict(boolean ok, String messageKey, Map<String, String> values) {

        public static final Verdict OK = new Verdict(true, "", Map.of());

        public static Verdict bad(String messageKey) {
            return new Verdict(false, messageKey, Map.of());
        }

        public static Verdict bad(String messageKey, int max) {
            return new Verdict(false, messageKey, Map.of("max", String.valueOf(max)));
        }
    }

    /** Короче трёх символов имя перестаёт отличаться от тега. */
    public static final int MIN_NAME_LENGTH = 3;

    private GuildNames() {}

    public static Verdict checkName(String name, int maxLength) {
        if (name == null || name.isBlank()) return Verdict.bad("guild.err.nameEmpty");
        if (!name.equals(name.trim())) {
            return Verdict.bad("guild.err.nameSpaces");
        }
        if (name.length() < MIN_NAME_LENGTH) {
            return Verdict.bad("guild.err.nameShort", MIN_NAME_LENGTH);
        }
        if (name.length() > maxLength) return Verdict.bad("guild.err.nameLong", maxLength);
        if (hasForbidden(name)) {
            return Verdict.bad("guild.err.nameColors");
        }
        return Verdict.OK;
    }

    public static Verdict checkTag(String tag, int maxLength) {
        if (tag == null || tag.isBlank()) return Verdict.bad("guild.err.tagEmpty");
        if (!tag.equals(tag.trim())) return Verdict.bad("guild.err.tagEdgeSpaces");
        if (tag.indexOf(' ') >= 0) return Verdict.bad("guild.err.tagSpaces");
        if (tag.length() > maxLength) return Verdict.bad("guild.err.tagLong", maxLength);
        if (hasForbidden(tag)) {
            return Verdict.bad("guild.err.tagColors");
        }
        return Verdict.OK;
    }

    /**
     * Ключ для проверки уникальности.
     *
     * Сравнение без учёта регистра: «Драконы» и «драконы» — это одна и та же
     * гильдия для любого, кто их читает, и разрешить обе значило бы завести
     * способ выдавать себя за чужую гильдию.
     */
    public static String uniqueKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Техническое имя группы LuckPerms для гильдии.
     *
     * СОБИРАЕТСЯ ИЗ ЧИСЛОВОГО ID, А НЕ ИЗ ТЕГА, И ЭТО ВАЖНО. Тег меняется —
     * в меню настроек есть такой пункт — и содержит что угодно, включая
     * кириллицу, а имя группы в LuckPerms должно быть и постоянным, и
     * безопасным. Гильдия «Драконы» с тегом «ДРК» получит группу вроде
     * «guild_17», и смена тега её не тронет: меняется только значение суффикса
     * на этой же группе, и приходит оно всем участникам само, через
     * наследование.
     */
    public static String groupName(String prefix, long guildId) {
        return prefix + guildId;
    }

    private static boolean hasForbidden(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' || c == '§') return true;
            if (c < ' ' || c == '\u007f') return true;
        }
        return false;
    }
}
