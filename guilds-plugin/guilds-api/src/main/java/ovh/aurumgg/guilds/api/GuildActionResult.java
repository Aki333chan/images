package ovh.aurumgg.guilds.api;

import java.util.Map;

/**
 * Чем закончилось действие с гильдией.
 *
 * ЗДЕСЬ КЛЮЧ, А НЕ ГОТОВАЯ ФРАЗА. Раньше текст собирался прямо рядом с
 * условием, при котором он возникает, — и это было удобно. Но язык сервера
 * задаётся в config.yml и известен только слою Bukkit, а результат действия
 * вычисляется в core, где ни конфига, ни игрока нет. Ключ сохраняет и то, и
 * другое: условие с его формулировкой по-прежнему рядом (ключ говорящий и
 * проверяется тестом), а язык выбирается там, где он известен.
 *
 * ЧТО ЭТО ЗНАЧИТ ДЛЯ ЧУЖОГО ПЛАГИНА (например, для торговца-NPC, который
 * покупает гильдии бонусы). Показывать игроку {@link #messageKey()} нельзя —
 * это «guild.bonus.granted», а не фраза. Готовый текст на языке сервера
 * отдаёт {@code AurumGuildsApi.render(result)}: рендер живёт там, где
 * загружены файлы языка.
 *
 * Подстановки лежат отдельной картой, а не склеены с текстом: порядок слов и
 * склонение числа у языков разные, и решать это за язык в core нельзя.
 *
 * ДВЕ КАРТЫ ПОДСТАНОВОК, И ЭТО НЕ ИЗБЫТОЧНОСТЬ. В {@link #values()} лежит то,
 * что переводить нельзя и не нужно: ник, имя гильдии, сумма. В
 * {@link #keyKeys()} — подстановки, которые сами являются ключами словаря:
 * название ранга, вид бонуса, кому доступен банк. Разница видна на «{player}
 * теперь {rank}»: ник останется ником на любом языке, а «офицер» обязан стать
 * «officer». Если сложить их в одну карту, отличить одно от другого при
 * выводе будет уже нечем — и в чат уедет «Vasya теперь mc.rank.officer».
 *
 * @param ok         получилось ли
 * @param messageKey ключ сообщения в lang/messages_&lt;язык&gt;.yml
 * @param values     подстановки как есть: ники, имена, числа
 * @param keyKeys    подстановки, значения которых — ключи того же словаря
 */
public record GuildActionResult(
        boolean ok, String messageKey, Map<String, String> values, Map<String, String> keyKeys) {

    public GuildActionResult(boolean ok, String messageKey, Map<String, String> values) {
        this(ok, messageKey, values, Map.of());
    }

    public GuildActionResult(boolean ok, String messageKey) {
        this(ok, messageKey, Map.of(), Map.of());
    }

    public static GuildActionResult ok(String messageKey) {
        return new GuildActionResult(true, messageKey);
    }

    public static GuildActionResult ok(String messageKey, Map<String, String> values) {
        return new GuildActionResult(true, messageKey, values);
    }

    public static GuildActionResult ok(
            String messageKey, Map<String, String> values, Map<String, String> keyKeys) {
        return new GuildActionResult(true, messageKey, values, keyKeys);
    }

    public static GuildActionResult fail(String messageKey) {
        return new GuildActionResult(false, messageKey);
    }

    public static GuildActionResult fail(String messageKey, Map<String, String> values) {
        return new GuildActionResult(false, messageKey, values);
    }

    public static GuildActionResult fail(
            String messageKey, Map<String, String> values, Map<String, String> keyKeys) {
        return new GuildActionResult(false, messageKey, values, keyKeys);
    }
}
