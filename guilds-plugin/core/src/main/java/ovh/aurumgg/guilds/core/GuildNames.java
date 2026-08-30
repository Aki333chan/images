package ovh.aurumgg.guilds.core;

import java.util.Locale;

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

    /** Ответ проверки: либо всё хорошо, либо объяснение, что не так. */
    public record Verdict(boolean ok, String message) {

        public static final Verdict OK = new Verdict(true, "");

        public static Verdict bad(String message) {
            return new Verdict(false, message);
        }
    }

    /** Короче трёх символов имя перестаёт отличаться от тега. */
    public static final int MIN_NAME_LENGTH = 3;

    private GuildNames() {}

    public static Verdict checkName(String name, int maxLength) {
        if (name == null || name.isBlank()) return Verdict.bad("Имя гильдии не может быть пустым");
        if (!name.equals(name.trim())) {
            return Verdict.bad("Имя не должно начинаться или заканчиваться пробелом");
        }
        if (name.length() < MIN_NAME_LENGTH) {
            return Verdict.bad("Имя короче " + MIN_NAME_LENGTH + " символов");
        }
        if (name.length() > maxLength) return Verdict.bad("Имя длиннее " + maxLength + " символов");
        if (hasForbidden(name)) {
            return Verdict.bad("В имени нельзя использовать § и & — это коды цвета");
        }
        return Verdict.OK;
    }

    public static Verdict checkTag(String tag, int maxLength) {
        if (tag == null || tag.isBlank()) return Verdict.bad("Тег не может быть пустым");
        if (!tag.equals(tag.trim())) return Verdict.bad("В теге не должно быть пробелов по краям");
        if (tag.indexOf(' ') >= 0) return Verdict.bad("В теге не должно быть пробелов");
        if (tag.length() > maxLength) return Verdict.bad("Тег длиннее " + maxLength + " символов");
        if (hasForbidden(tag)) {
            return Verdict.bad("В теге нельзя использовать § и & — это коды цвета");
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
